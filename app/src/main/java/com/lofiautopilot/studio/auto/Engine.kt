package com.lofiautopilot.studio.auto

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.lofiautopilot.studio.ai.CloudAi
import com.lofiautopilot.studio.ai.Director
import com.lofiautopilot.studio.ai.LocalLlm
import com.lofiautopilot.studio.audio.AnalysisResult
import com.lofiautopilot.studio.audio.AudioAnalysis
import com.lofiautopilot.studio.audio.Cancelled
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.data.JobEntity
import com.lofiautopilot.studio.data.JobStatus
import com.lofiautopilot.studio.data.Stage
import com.lofiautopilot.studio.export.Aspect
import com.lofiautopilot.studio.export.Codec
import com.lofiautopilot.studio.export.ExportException
import com.lofiautopilot.studio.export.ExportRequest
import com.lofiautopilot.studio.export.VideoExporter
import com.lofiautopilot.studio.publish.Facebook
import com.lofiautopilot.studio.publish.FakeUploader
import com.lofiautopilot.studio.publish.PublishException
import com.lofiautopilot.studio.publish.RealYouTube
import com.lofiautopilot.studio.publish.YouTubeAuth
import com.lofiautopilot.studio.vfx.AudioClip
import com.lofiautopilot.studio.vfx.Fit
import com.lofiautopilot.studio.vfx.Look
import com.lofiautopilot.studio.vfx.Presets
import com.lofiautopilot.studio.vfx.Project
import com.lofiautopilot.studio.vfx.MediaClip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min
import java.util.concurrent.atomic.AtomicLong

/** What a job carries: an editor project (Studio exports) or just a requested length (automatic videos). */
@Serializable
data class JobSpec(val project: Project? = null, val minutes: Int = 0)

class JobFailure(msg: String, val hint: String = "", val retry: Boolean = true) : Exception(msg)

/** Runs jobs end to end and is the single place that changes job state (UI, Autopilot and the AI assistant all go through it). */
class Engine(private val ctx: Context, private val g: Graph) {
    val llm = LocalLlm(ctx, g)
    val cloud = CloudAi(g)
    val planner = Planner(g, llm, cloud)
    val art = Art(ctx, g)
    val arranger = com.lofiautopilot.studio.ai.AutoArranger(g, llm, cloud)
    val library = Library(ctx, g)
    val ytAuth = YouTubeAuth(g)
    val facebook = Facebook(g)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val runningId = AtomicLong(0)
    @Volatile private var cancelFlag = false
    @Volatile var onProgress: (JobEntity) -> Unit = {}

    val currentJobId: Long get() = runningId.get()

    // ---------- creating and controlling ----------

    suspend fun createJob(source: String, minutes: Int? = null, publishAt: Long = 0, project: Project? = null, title: String = ""): Long {
        val job = JobEntity(
            source = source, publishAt = publishAt, title = title,
            settingsJson = json.encodeToString(JobSpec(project, minutes ?: 0)),
            stage = if (project != null) Stage.RENDER else Stage.PLAN,
        )
        val id = g.db.jobs().insert(job)
        g.log("Video ${"%04d".format(id)} queued" + if (publishAt > 0) " for ${java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(publishAt)}" else "", id)
        return id
    }

    suspend fun setStatus(id: Long, action: String): String {
        val dao = g.db.jobs()
        val j = dao.get(id) ?: return "That video no longer exists."
        val msg: String
        when {
            action == "pause" && j.status in listOf(JobStatus.QUEUED, JobStatus.WAITING, JobStatus.RUNNING) -> {
                dao.update(j.copy(pauseRequested = true, status = if (j.status == JobStatus.RUNNING) j.status else JobStatus.PAUSED))
                if (runningId.get() == id) cancelFlag = true
                msg = "Paused."
            }
            action == "resume" && j.status == JobStatus.PAUSED -> { dao.update(j.copy(pauseRequested = false, status = JobStatus.QUEUED)); msg = "Resumed." }
            action == "cancel" && j.status !in listOf(JobStatus.DONE, JobStatus.CANCELLED) -> {
                dao.update(j.copy(status = JobStatus.CANCELLED, phase = "Cancelled"))
                if (runningId.get() == id) cancelFlag = true
                msg = "Cancelled."
            }
            action == "retry" && j.status in listOf(JobStatus.FAILED, JobStatus.PAUSED, JobStatus.CANCELLED) -> {
                dao.update(j.copy(status = JobStatus.QUEUED, attempts = 0, error = "", hint = "", pauseRequested = false, nextAttemptAt = 0))
                msg = "Retrying."
            }
            action == "publish_now" && j.status in listOf(JobStatus.WAITING, JobStatus.QUEUED) -> { dao.update(j.copy(publishAt = 0, status = JobStatus.QUEUED)); msg = "Will publish as soon as it's ready." }
            action == "delete" && j.status !in listOf(JobStatus.RUNNING) -> {
                File(j.outPath).takeIf { j.outPath.isNotBlank() }?.parentFile?.deleteRecursively()
                dao.delete(id); msg = "Deleted."
            }
            else -> return "Nothing to change."
        }
        g.log("Video ${"%04d".format(id)}: $msg", id)
        return msg
    }

    fun allowedActions(status: String): List<String> = buildList {
        if (status in listOf("queued", "waiting", "running")) add("pause")
        if (status == "paused") add("resume")
        if (status in listOf("failed", "paused", "cancelled")) add("retry")
        if (status in listOf("waiting", "queued")) add("publish_now")
        if (status !in listOf("done", "cancelled")) add("cancel")
    }

    suspend fun updateMetadata(id: Long, title: String, description: String, tags: List<String>): String {
        val dao = g.db.jobs()
        val j = dao.get(id) ?: return "That video no longer exists."
        if (j.ytUrl.isNotBlank() && j.fbUrl.isNotBlank()) return "Already published; edit it on the platforms instead."
        dao.update(j.copy(title = title.trim().take(100), description = description.trim(), tags = tags.joinToString(",") { it.trim() }))
        return "Title and description saved."
    }

    // ---------- autopilot ----------

    /** Called every minute or so by the service: finds new pictures and queues the day's videos. */
    suspend fun tick() {
        val s = g.settings.value
        if (s.imageFolderUri.isNotBlank()) {
            val fresh = runCatching { library.scanImages() }.getOrDefault(emptyList())
            if (fresh.isNotEmpty()) {
                g.log("Found ${fresh.size} new picture(s): ${fresh.take(3).joinToString { it.name }}")
                g.notifier.newImages(fresh.size, fresh.first().name)
            }
        }
        if (!s.autopilot) return
        val per = s.videosPerDay.coerceIn(1, 4)
        val (hh, mm) = s.publishTime.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 18) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        for (dayOffset in 0..1) {
            for (slot in 0 until per) {
                val c = cal.clone() as Calendar
                c.add(Calendar.DAY_OF_YEAR, dayOffset)
                c.set(Calendar.HOUR_OF_DAY, hh); c.set(Calendar.MINUTE, mm); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                c.add(Calendar.MINUTE, (slot * 24 * 60 / per))
                val at = c.timeInMillis
                val createFrom = at - (s.leadHours * 3_600_000L).toLong()
                if (now < createFrom || at < now - 30 * 60_000L) continue
                val exists = g.db.jobs().recentNow(60).any { it.source == "autopilot" && it.publishAt == at && it.status != JobStatus.CANCELLED }
                if (!exists) createJob("autopilot", publishAt = at)
            }
        }
    }

    suspend fun hasWork(): Boolean {
        val now = System.currentTimeMillis()
        return g.db.jobs().runnable().any { (it.status != JobStatus.WAITING || it.publishAt <= now) && it.nextAttemptAt <= now }
    }

    // ---------- running ----------

    /** Runs the oldest runnable job. Returns true if one ran. */
    suspend fun runNext(): Boolean {
        val now = System.currentTimeMillis()
        val j = g.db.jobs().runnable().firstOrNull { it.nextAttemptAt <= now && (it.status != JobStatus.WAITING || it.publishAt <= now) } ?: return false
        runJob(j.id)
        return true
    }

    private suspend fun upd(id: Long, f: (JobEntity) -> JobEntity): JobEntity {
        val dao = g.db.jobs()
        var j = f(dao.get(id) ?: return JobEntity(id = id))
        if (j.finishedAt == 0L && j.status in listOf(JobStatus.DONE, JobStatus.FAILED, JobStatus.CANCELLED)) j = j.copy(finishedAt = System.currentTimeMillis())
        dao.update(j)
        onProgress(j)
        return j
    }

    private var lastProgress = 0L
    private suspend fun progress(id: Long, stageBase: Float, span: Float, frac: Float, text: String) {
        val now = System.currentTimeMillis()
        if (now - lastProgress < 700 && frac < 1f) return
        lastProgress = now
        upd(id) { it.copy(progress = (stageBase + span * frac).coerceIn(0f, 1f), phase = text) }
    }

    suspend fun runJob(id: Long) = withContext(Dispatchers.Default) {
        cancelFlag = false
        runningId.set(id)
        try {
            var job = upd(id) { it.copy(status = JobStatus.RUNNING, pauseRequested = false, error = "", hint = "") }
            while (true) {
                job = g.db.jobs().get(id) ?: return@withContext
                if (job.status == JobStatus.CANCELLED || job.status == JobStatus.PAUSED) return@withContext
                when (job.stage) {
                    Stage.PLAN -> doPlan(job)
                    Stage.ART -> doArt(job)
                    Stage.RENDER -> doRender(job)
                    Stage.THUMB -> doThumb(job)
                    Stage.PUBLISH -> {
                        if (!doPublish(job)) return@withContext
                    }
                    else -> upd(id) { it.copy(stage = Stage.PUBLISH) }
                }
                val after = g.db.jobs().get(id) ?: return@withContext
                if (after.status == JobStatus.DONE) {
                    g.notifier.videoReady(id, after.title, after.ytUrl.isNotBlank() || after.fbUrl.isNotBlank())
                    return@withContext
                }
            }
        } catch (e: Cancelled) {
            val j = g.db.jobs().get(id)
            if (j != null && j.status == JobStatus.RUNNING) upd(id) { it.copy(status = if (it.pauseRequested) JobStatus.PAUSED else JobStatus.CANCELLED, phase = "Stopped") }
        } catch (e: CancellationException) {
            upd(id) { it.copy(status = JobStatus.QUEUED, phase = "Restarting after the app stopped") }
            throw e
        } catch (e: Throwable) {
            fail(id, e)
        } finally {
            runningId.set(0)
        }
    }

    private suspend fun fail(id: Long, e: Throwable) {
        val (msg, hint, retry) = when (e) {
            is JobFailure -> Triple(e.message.orEmpty(), e.hint, e.retry)
            is PlanException -> Triple(e.message.orEmpty(), e.hint, false)
            is ArtException -> Triple(e.message.orEmpty(), e.hint, false)
            is ExportException -> Triple(e.message.orEmpty(), "Try 720p, or free some storage space.", true)
            is PublishException -> Triple(e.message.orEmpty(), e.hint, e.retry)
            else -> Triple("Something unexpected went wrong: ${e.message ?: e.javaClass.simpleName}", "", true)
        }
        android.util.Log.e("Engine", "job $id failed", e)
        val j = upd(id) {
            val attempts = it.attempts + 1
            if (retry && attempts < 3) it.copy(attempts = attempts, status = JobStatus.QUEUED, error = msg, hint = hint, phase = "Will retry", nextAttemptAt = System.currentTimeMillis() + 60_000L * attempts)
            else it.copy(attempts = attempts, status = JobStatus.FAILED, error = msg, hint = hint, phase = "Stopped")
        }
        g.log("Video ${"%04d".format(id)} problem: $msg $hint", id, "error")
        if (j.status == JobStatus.FAILED) g.notifier.videoFailed(id, j.title, msg + if (hint.isNotBlank()) " $hint" else "")
    }

    private fun spec(j: JobEntity): JobSpec = runCatching { json.decodeFromString<JobSpec>(j.settingsJson) }.getOrDefault(JobSpec())

    private suspend fun doPlan(j: JobEntity) {
        upd(j.id) { it.copy(phase = "Planning the mix", progress = 0.01f) }
        val minutes = spec(j).minutes.takeIf { it > 0 }
        val plan = planner.make(minutes)
        g.log("Plan: ${plan.title} (${plan.trackIds.size} tracks, ${plan.planner})", j.id)
        upd(j.id) {
            it.copy(
                title = it.title.ifBlank { plan.title }, description = plan.description, tags = plan.tags.joinToString(","), mood = plan.mood,
                planJson = planner.encode(plan), durationS = plan.durationS, stage = Stage.ART, progress = 0.03f,
            )
        }
    }

    private suspend fun doArt(j: JobEntity) {
        upd(j.id) { it.copy(phase = "Choosing artwork") }
        val plan = planner.decode(j.planJson) ?: throw JobFailure("The plan was lost.", "Retry the video.")
        val choice = art.choose(j.id, plan.artPrompt)
        art.markUsed(choice.usedImages)
        g.log(choice.note, j.id)
        upd(j.id) { it.copy(imageUri = Uri.fromFile(choice.file).toString(), stage = Stage.RENDER, progress = 0.05f) }
    }

    private suspend fun savedLook(name: String): Look? = g.db.looks().byName(name)?.let { runCatching { json.decodeFromString<Look>(it.json) }.getOrNull() }

    /** Builds the timeline for an automatic video: the chosen tracks back to back with crossfades, over the chosen picture. */
    private suspend fun projectFromPlan(j: JobEntity, plan: Plan, s: com.lofiautopilot.studio.core.AppSettings): Project {
        val xf = s.crossfadeS
        var t = 0f
        val clips = ArrayList<AudioClip>()
        val rows = plan.trackIds.mapNotNull { g.db.tracks().get(it) }
        rows.forEachIndexed { i, tr ->
            val len = tr.durationMs / 1000f
            val last = i == rows.lastIndex
            clips.add(AudioClip(uri = tr.uri, title = tr.title, srcDurS = len, startS = t, lenS = len, lufs = tr.lufs,
                fadeInS = if (i == 0) 0f else minOf(xf, len / 3), fadeOutS = if (last) minOf(3f, len / 3) else minOf(xf, len / 3)))
            t += len - minOf(xf, len / 3)
        }
        // Hit the requested length: trim the last song (with a fade-out) instead of ending abruptly mid-song
        val over = (clips.maxOfOrNull { it.endS } ?: 0f) - plan.durationS.toFloat()
        if (over > 5f && clips.isNotEmpty()) {
            val last = clips.last()
            val newLen = max(12f, last.lenS - over)
            clips[clips.lastIndex] = last.copy(lenS = newLen, fadeOutS = min(4f, newLen / 3))
        }
        val preset = if (s.lookName == "Auto") null else (Presets.builtin.firstOrNull { it.name == s.lookName } ?: savedLook(s.lookName))
        return Project(
            name = j.title, media = listOf(MediaClip(uri = j.imageUri, lenS = plan.durationS.toFloat().coerceAtLeast(clips.lastOrNull()?.endS ?: 0f))), audio = clips,
            effects = preset?.layers.orEmpty().mapIndexed { i, l -> l.copy(track = i) },
            fit = preset?.fit ?: Fit.COVER, aspect = s.aspect, quality = s.quality, fps = s.fps, codec = s.codec, director = s.directorOn,
        )
    }

    private suspend fun doRender(j: JobEntity) {
        val s = g.settings.value
        val spec = spec(j)
        val plan = planner.decode(j.planJson)
        var project: Project = (spec.project ?: run {
            if (plan == null) throw JobFailure("The plan was lost.", "Retry the video.", retry = false)
            projectFromPlan(j, plan, s)
        }).migrate()
        if (project.audio.isEmpty()) project.media.firstOrNull { it.video }?.let { v ->
            val d = com.lofiautopilot.studio.audio.PcmDecoder.probe(ctx, Uri.parse(v.uri)) / 1e6f
            project = project.copy(audio = listOf(AudioClip(uri = v.uri, title = "Video audio", srcDurS = d, lenS = d)))
        }
        if (project.audio.isEmpty()) throw JobFailure("There is no music for this video.", "Add music.", retry = false)
        if (project.media.isEmpty()) throw JobFailure("There is no picture for this video.", "Choose a picture.", retry = false)

        val aspect = runCatching { Aspect.valueOf(project.aspect) }.getOrDefault(Aspect.WIDE)
        val exporter = VideoExporter(ctx)
        var codec = runCatching { Codec.valueOf(project.codec) }.getOrDefault(Codec.H264)
        val quality = project.quality
        var q = quality
        var (w, h) = aspect.size(q)
        while (!exporter.supports(codec, w, h, project.fps) && q > 360) { q = if (q > 1080) 1080 else if (q > 720) 720 else if (q > 540) 540 else 360; val sz = aspect.size(q); w = sz.first; h = sz.second }
        if (!exporter.supports(codec, w, h, project.fps) && codec == Codec.HEVC) codec = Codec.H264
        if (q != quality) g.log("This phone can't encode $quality p at ${project.fps} fps, using $q p.", j.id, "warn")

        val dirOut = File(ctx.getExternalFilesDir("videos") ?: ctx.filesDir, "job-%04d".format(j.id)).apply { mkdirs() }
        val out = File(dirOut, "video.mp4")

        upd(j.id) { it.copy(phase = "Listening to the music", stage = Stage.RENDER) }
        val analysis: AnalysisResult = AudioAnalysis.run(ctx, project.audio, project.normalize, null,
            onProgress = { f -> kotlinx.coroutines.runBlocking { progress(j.id, 0.05f, 0.15f, f, "Listening to the music") } },
            cancelled = { cancelFlag })
        // remember loudness for next time
        project.audio.forEach { c -> if (c.lufs == null) analysis.lufs[c.id]?.let { l -> g.db.tracks().byUri(c.uri)?.let { t -> g.db.tracks().update(t.copy(lufs = l)) } } }

        // director + timeline arrangement
        val summary = Director.summarize(analysis.features)
        var effects = project.effects
        var plan2 = Director.rules(summary, rainyHint = plan?.mood == "rain")
        // automatic videos: several pictures and an intro, cut to the music with transitions
        if (j.source != "studio" && spec.project == null && (s.slideshowPics > 1 || s.introId.isNotBlank())) {
            val used = project.media.map { it.uri }.toSet()
            val more = if (s.slideshowPics > 1) g.db.images().candidates().map { it.uri }.filter { it !in used }.shuffled().take(s.slideshowPics - 1) else emptyList()
            val intro = s.introId.takeIf { it.isNotBlank() }?.let { if (it == "auto") com.lofiautopilot.studio.vfx.Intros.all.random().id else it }
            val cut = com.lofiautopilot.studio.ai.AutoTimeline.autoCut(project, summary, analysis.features, more, intro, s.channelName)
            project = project.copy(media = cut)
            g.log("Cut ${cut.count { !it.isIntro }} picture clip(s)${if (intro != null) " + intro" else ""} to the music with transitions.", j.id)
        }
        if (project.director) {
            upd(j.id) { it.copy(phase = "Arranging effects to the rhythm", progress = 0.2f) }
            val autoLook = j.source != "studio" && s.lookName == "Auto"
            if (autoLook) {
                val r = arranger.arrange(project, analysis.features, useAi = true)
                effects = effects.filter { !it.auto } + r.layers
                plan2 = r.plan
                g.log("Arranged ${r.layers.size} effect clips on the timeline (${r.by}) in “${r.plan.style}” style.", j.id)
            } else {
                plan2 = if (s.planner == "local") llm.direct(summary) else plan2
                Director.applyCurve(analysis.features, summary, plan2)
                effects = effects.map { l -> if (l.react == com.lofiautopilot.studio.vfx.React.OFF) l else l.copy(reactAmt = (l.reactAmt * plan2.reactivity).coerceIn(0.05f, 2f)) }
                g.log("Director: " + Director.describe(plan2), j.id)
            }
        }
        project = project.copy(effects = effects)
        val look = project.look()
        val busy = look.layers.any { it.react != com.lofiautopilot.studio.vfx.React.OFF }
        val bitrate = VideoExporter.bitrateFor(w, h, project.fps, codec, busy)
        val req = ExportRequest(
            media = project.media,
            look = look, audio = project.audio, normalize = project.normalize, width = w, height = h, fps = project.fps,
            bitrate = bitrate, codec = codec, out = out, maxSeconds = null,
        )
        upd(j.id) { it.copy(phase = "Rendering", progress = 0.22f, durationS = analysis.features.durationS.toDouble()) }
        val res = withContext(Dispatchers.Default) {
            exporter.export(req, analysis, onProgress = { f -> kotlinx.coroutines.runBlocking { progress(j.id, 0.22f, 0.63f, f, "Rendering ${(f * 100).toInt()}%") } }, cancelled = { cancelFlag })
        }
        project.audio.forEach { c -> g.db.tracks().byUri(c.uri)?.let { t -> g.db.tracks().update(t.copy(usedCount = t.usedCount + 1, lastUsedAt = System.currentTimeMillis())) } }
        g.log("Rendered ${"%.1f".format(res.seconds / 60)} min at ${w}x$h (${out.length() / 1_048_576} MB)", j.id)
        var saved = ""
        if (s.saveToGallery) saved = runCatching { saveToGallery(out, "LofiStudio-%04d.mp4".format(j.id)) }.getOrDefault("")
        upd(j.id) { it.copy(outPath = out.absolutePath, savedToGallery = saved, stage = Stage.THUMB, progress = 0.86f, durationS = res.seconds, imageUri = project.cover ?: it.imageUri) }
    }

    private fun saveToGallery(file: File, name: String): String {
        if (Build.VERSION.SDK_INT < 29) return ""
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/LofiStudio")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return ""
        ctx.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o, 1 shl 20) } }
        cv.clear(); cv.put(MediaStore.Video.Media.IS_PENDING, 0)
        ctx.contentResolver.update(uri, cv, null, null)
        return uri.toString()
    }

    private suspend fun doThumb(j: JobEntity) {
        upd(j.id) { it.copy(phase = "Making the thumbnail") }
        val artFile = j.imageUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        val out = File(File(j.outPath).parentFile ?: ctx.filesDir, "thumbnail.jpg")
        if (artFile != null) {
            runCatching {
                val src = if (artFile.scheme == "file") File(artFile.path!!) else {
                    val tmp = File(ctx.cacheDir, "thumb-src.jpg")
                    ctx.contentResolver.openInputStream(artFile)?.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    tmp
                }
                Thumbnailer.make(src, out, if (g.settings.value.thumbnailText) j.title else "")
            }.onFailure { g.log("Thumbnail skipped: ${it.message}", j.id, "warn") }
        }
        upd(j.id) { it.copy(thumbPath = if (out.exists()) out.absolutePath else "", stage = Stage.PUBLISH, progress = 0.88f) }
    }

    /** Returns true when the job moved on, false when it should wait. */
    private suspend fun doPublish(j: JobEntity): Boolean {
        val s = g.settings.value
        val now = System.currentTimeMillis()
        if (j.source == "studio") {
            upd(j.id) { it.copy(status = JobStatus.DONE, progress = 1f, phase = "Ready") }
            return true
        }
        if (j.publishAt > now + 60_000 && !(s.ytAudited && s.youtubeOn && !s.facebookOn)) {
            upd(j.id) { it.copy(status = JobStatus.WAITING, phase = "Ready. Publishing at ${java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(j.publishAt)}", progress = 0.9f) }
            return false
        }
        if (!s.publishEnabled) {
            upd(j.id) { it.copy(status = JobStatus.DONE, progress = 1f, phase = "Saved. Publishing is off (practice mode)") }
            return true
        }
        val file = File(j.outPath)
        if (!file.exists()) throw JobFailure("The video file is missing.", "Retry the video.", retry = false)
        val thumb = File(j.thumbPath).takeIf { j.thumbPath.isNotBlank() && it.exists() }
        var cur = j
        if (s.facebookOn && cur.fbUrl.isBlank() && s.fbPageId.isNotBlank()) {
            upd(j.id) { it.copy(phase = "Uploading to Facebook", progress = 0.9f) }
            val r = facebook.upload(file, cur.title, cur.description, thumb) { f -> kotlinx.coroutines.runBlocking { progress(j.id, 0.9f, 0.05f, f, "Uploading to Facebook ${(f * 100).toInt()}%") } }
            cur = upd(j.id) { it.copy(fbUrl = r.url) }
            g.log("Facebook: ${r.url}", j.id)
        }
        if (s.youtubeOn && cur.ytUrl.isBlank() && ytAuth.connected) {
            upd(j.id) { it.copy(phase = "Uploading to YouTube", progress = 0.95f) }
            val up = RealYouTube(g, ytAuth)
            val r = up.upload(file, cur.title, cur.description, cur.tags.split(",").filter { it.isNotBlank() }, thumb, cur.publishAt) { f ->
                kotlinx.coroutines.runBlocking { progress(j.id, 0.95f, 0.05f, f, "Uploading to YouTube ${(f * 100).toInt()}%") }
            }
            cur = upd(j.id) { it.copy(ytUrl = r.url, ytStatus = if (r.privacy == "public") "Public" else "Private") }
            g.log("YouTube: ${r.url} (${r.privacy})", j.id)
        }
        upd(j.id) { it.copy(status = JobStatus.DONE, progress = 1f, phase = if (cur.ytUrl.isBlank() && cur.fbUrl.isBlank()) "Saved. No account is connected yet" else "Published") }
        return true
    }

    /** Deletes old rendered files to keep storage in check. */
    suspend fun cleanup() {
        val days = g.settings.value.keepDays
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        for (j in g.db.jobs().recentNow(300)) {
            if (j.status == JobStatus.DONE && j.createdAt < cutoff && j.outPath.isNotBlank()) {
                val f = File(j.outPath)
                if (f.exists() && (j.ytUrl.isNotBlank() || j.fbUrl.isNotBlank() || !g.settings.value.publishEnabled)) {
                    f.delete()
                    g.db.jobs().update(j.copy(outPath = ""))
                }
            }
        }
    }

    @Suppress("unused")
    private val fake = FakeUploader()
}
