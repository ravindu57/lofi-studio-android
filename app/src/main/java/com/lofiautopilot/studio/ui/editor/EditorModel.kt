package com.lofiautopilot.studio.ui.editor

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.lofiautopilot.studio.audio.AudioAnalysis
import com.lofiautopilot.studio.audio.Cancelled
import com.lofiautopilot.studio.audio.FeatureTrack
import com.lofiautopilot.studio.audio.PcmDecoder
import com.lofiautopilot.studio.audio.TimelinePlayer
import com.lofiautopilot.studio.audio.Waveforms
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.data.ProjectEntity
import com.lofiautopilot.studio.vfx.AudioClip
import com.lofiautopilot.studio.vfx.EffectLibrary
import com.lofiautopilot.studio.vfx.Layer
import com.lofiautopilot.studio.vfx.LayerFactory
import com.lofiautopilot.studio.vfx.Project
import com.lofiautopilot.studio.vfx.MediaClip
import com.lofiautopilot.studio.vfx.Intros
import com.lofiautopilot.studio.vfx.Transitions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

sealed interface Sel {
    data class Audio(val id: String) : Sel
    data class Effect(val id: String) : Sel
    data class Media(val id: String) : Sel
}

/** All state of one editing session: the project, selection, playhead, undo history, analysis and playback. */
class EditorModel(val g: Graph, initialProject: Project) {
    private val initial = initialProject.migrate()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val ctx = g.app

    var project by mutableStateOf(initial)
        private set
    var selection by mutableStateOf<Sel?>(null)
    var posS by mutableFloatStateOf(0f)
    var playing by mutableStateOf(false)
    /** Zoom in dp per second. */
    var dpPerSec by mutableFloatStateOf(36f)
    var features by mutableStateOf<FeatureTrack?>(null)
    var analysing by mutableFloatStateOf(-1f)
    var busyMessage by mutableStateOf("")
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)

    val waveforms = Waveforms(ctx)
    val player = TimelinePlayer(ctx)
    private val undo = ArrayDeque<Project>()
    private val redo = ArrayDeque<Project>()
    private var dragBase: Project? = null
    private var saveJob: Job? = null

    init {
        g.editor = this
        scope.launch { watchAudio() }
    }

    val duration: Float get() = max(project.durationS, 0.1f)

    // ---------- editing ----------

    private fun push(p: Project) {
        undo.addLast(p); if (undo.size > 80) undo.removeFirst()
        redo.clear(); canUndo = true; canRedo = false
    }

    /** One undoable change. */
    fun edit(f: (Project) -> Project) {
        val before = project
        val after = f(before)
        if (after == before) return
        push(before)
        project = after.touch()
        afterChange(before, after)
        scheduleSave()
    }

    /** Dragging: many small updates, one undo step. */
    fun beginDrag() { dragBase = project }
    fun drag(f: (Project) -> Project) { project = f(project); scheduleSave() }
    fun endDrag() {
        val base = dragBase ?: return
        dragBase = null
        if (base != project) { push(base); project = project.touch(); afterChange(base, project) }
    }

    fun undo() {
        val p = undo.removeLastOrNull() ?: return
        redo.addLast(project); project = p; canUndo = undo.isNotEmpty(); canRedo = true
        fixSelection(); restartAudioIfPlaying(); scheduleSave()
    }

    fun redo() {
        val p = redo.removeLastOrNull() ?: return
        undo.addLast(project); project = p; canRedo = redo.isNotEmpty(); canUndo = true
        fixSelection(); restartAudioIfPlaying(); scheduleSave()
    }

    private fun fixSelection() {
        val s = selection
        if (s is Sel.Audio && project.audio.none { it.id == s.id }) selection = null
        if (s is Sel.Effect && project.effects.none { it.id == s.id }) selection = null
        if (s is Sel.Media && project.media.none { it.id == s.id }) selection = null
    }

    private fun afterChange(before: Project, after: Project) {
        if (audioSig(before) != audioSig(after)) restartAudioIfPlaying()
    }

    private fun audioSig(p: Project) = p.audio.joinToString("|") { "${it.id}${it.startS}${it.inS}${it.lenS}${it.gain}${it.fadeInS}${it.fadeOutS}${it.lufs}${it.eq}" } + p.normalize

    fun rename(name: String) = edit { it.copy(name = name.ifBlank { "Untitled" }) }

    // ---------- clips ----------

    fun audioById(id: String) = project.audio.firstOrNull { it.id == id }
    fun effectById(id: String) = project.effects.firstOrNull { it.id == id }

    fun updateAudio(id: String, drag: Boolean = false, f: (AudioClip) -> AudioClip) {
        val fn: (Project) -> Project = { p -> p.copy(audio = p.audio.map { if (it.id == id) f(it) else it }) }
        if (drag) drag(fn) else edit(fn)
    }

    fun updateEffect(id: String, drag: Boolean = false, f: (Layer) -> Layer) {
        val fn: (Project) -> Project = { p -> p.copy(effects = p.effects.map { if (it.id == id) f(it) else it }) }
        if (drag) drag(fn) else edit(fn)
    }

    /** Adds an effect clip at the playhead. */
    fun addEffect(fxId: String, lengthS: Float = 6f): String? {
        val def = EffectLibrary.get(fxId) ?: return null
        val dur = max(duration, 1f)
        val start = posS.coerceIn(0f, max(0f, dur - 0.5f))
        val len = min(lengthS, max(0.5f, dur - start)).let { if (def.kind == com.lofiautopilot.studio.vfx.FxKind.CAMERA) max(it, 4f) else it }
        val lane = freeLane(project.effects, start, start + len)
        val layer = LayerFactory.create(def).copy(from = start, to = start + len, track = lane)
        edit { it.copy(effects = it.effects + layer) }
        selection = Sel.Effect(layer.id)
        return layer.id
    }

    /** Effect lanes are identified by their `track` number; find the first lane with room. */
    fun freeLane(layers: List<Layer>, from: Float, to: Float): Int {
        val lanes = layers.map { it.track }.distinct().sorted()
        for (lane in lanes) {
            val clash = layers.any { it.track == lane && it.from < to && project.layerEnd(it) > from }
            if (!clash) return lane
        }
        return (lanes.maxOrNull() ?: -1) + 1
    }

    fun addMusic(uri: String, title: String, durS: Float, lufs: Double?): String {
        val end = project.audioEndS
        val start = if (project.audio.isEmpty()) 0f else end
        val clip = AudioClip(uri = uri, title = title, srcDurS = durS, startS = start, lenS = durS, lufs = lufs)
        edit { p0 ->
            val p = p0.copy(audio = p0.audio + clip)
            // a lone picture stretches to cover the music
            if (p.media.size == 1 && !p.media[0].isIntro && p.media[0].lenS < p.audioEndS) p.copy(media = listOf(p.media[0].copy(lenS = p.audioEndS))) else p
        }
        selection = Sel.Audio(clip.id)
        scope.launch { waveforms.peaks(uri) }
        // measure loudness in the background so levels match
        if (lufs == null) scope.launch {
            val l = AudioAnalysis.quickLufs(ctx, Uri.parse(uri))
            if (l != null) withContext(Dispatchers.Main) { project = project.copy(audio = project.audio.map { if (it.uri == uri && it.lufs == null) it.copy(lufs = l) else it }) }
        }
        return clip.id
    }

    // ---------- the picture track ----------

    fun mediaById(id: String) = project.media.firstOrNull { it.id == id }

    fun updateMedia(id: String, drag: Boolean = false, f: (MediaClip) -> MediaClip) {
        val fn: (Project) -> Project = { p -> p.copy(media = p.media.map { if (it.id == id) f(it) else it }).relayout() }
        if (drag) drag(fn) else edit(fn)
    }

    /** Inserts a clip at the playhead: splits the clip under it if needed. The first clip of a project spans the whole music. */
    private fun insertMedia(clip: MediaClip): String {
        val t = posS
        edit { p ->
            val list = p.media.toMutableList()
            if (list.isEmpty()) {
                val len = when { clip.isIntro -> clip.lenS; p.audio.isNotEmpty() -> max(p.audioEndS, clip.lenS); else -> clip.lenS }
                list.add(clip.copy(lenS = len, transition = "none"))
            } else {
                val i = list.indexOfLast { it.startS <= t + 0.01f }.coerceAtLeast(0)
                val c = list[i]
                val local = t - c.startS
                when {
                    t >= p.mediaEndS - 0.05f -> list.add(clip)
                    local < 0.3f -> list.add(i, clip)
                    local > c.lenS - 0.3f -> list.add(i + 1, clip)
                    else -> {
                        val a = c.copy(lenS = local)
                        val b = c.copy(id = UUID.randomUUID().toString().take(8), inS = c.inS + local, lenS = c.lenS - local, transition = "none")
                        list.removeAt(i); list.add(i, a); list.add(i + 1, clip); list.add(i + 2, b)
                    }
                }
            }
            p.copy(media = list).relayout()
        }
        selection = Sel.Media(clip.id)
        return clip.id
    }

    /** Adds a picture or video at the playhead. */
    fun addMedia(uri: String, video: Boolean, advance: Boolean = false): String {
        val d = if (video) runCatching { PcmDecoder.probe(ctx, Uri.parse(uri)) / 1e6f }.getOrDefault(0f) else 0f
        val len = if (video && d > 0.5f) d else 6f
        val clip = MediaClip(uri = uri, video = video, srcDurS = d, lenS = len, transition = if (project.media.isEmpty()) "none" else "fade")
        val id = insertMedia(clip)
        if (advance) mediaById(id)?.let { posS = it.endS.coerceAtMost(max(duration, it.endS)) }
        return id
    }

    /** Puts a built-in intro at the very start. It takes over the first seconds, so the video does not get longer. */
    fun addIntro(id: String) {
        val def = Intros.byId[id] ?: return
        val clip = MediaClip(uri = def.uri, lenS = def.lenS, title = project.name.takeIf { it != "Untitled" } ?: "SE")
        edit { p ->
            val rest = p.media.filter { !it.isIntro }.toMutableList()
            if (rest.isNotEmpty()) {
                val f = rest[0]
                if (f.lenS > def.lenS + 2f && !f.video) rest[0] = f.copy(lenS = f.lenS - def.lenS)
                rest[0] = rest[0].copy(transition = "dip_black", transS = 1.0f)
            }
            p.copy(media = listOf(clip) + rest).relayout()
        }
        selection = Sel.Media(clip.id)
    }

    fun replaceMedia(id: String, uri: String, video: Boolean) {
        val d = if (video) runCatching { PcmDecoder.probe(ctx, Uri.parse(uri)) / 1e6f }.getOrDefault(0f) else 0f
        updateMedia(id) { it.copy(uri = uri, video = video, srcDurS = d, inS = 0f) }
    }

    fun moveMedia(id: String, delta: Int) {
        edit { p ->
            val list = p.media.toMutableList()
            val i = list.indexOfFirst { it.id == id }
            val j = i + delta
            if (i < 0 || j !in list.indices) return@edit p
            val c = list.removeAt(i); list.add(j, c)
            p.copy(media = list).relayout()
        }
    }

    fun setTransition(id: String, transition: String, transS: Float? = null) =
        updateMedia(id) { it.copy(transition = transition, transS = transS ?: it.transS) }

    /** Applies one transition to every cut. */
    fun setAllTransitions(transition: String) =
        edit { p -> p.copy(media = p.media.mapIndexed { i, c -> if (i == 0) c else c.copy(transition = transition) }) }

    /** Re-cuts the picture track to the music (pictures take turns on section changes, transitions chosen by energy). */
    fun autoCutMedia(introId: String? = null) {
        val ft = features ?: return
        val summary = com.lofiautopilot.studio.ai.Director.summarize(ft)
        val cut = com.lofiautopilot.studio.ai.AutoTimeline.autoCut(project, summary, ft, emptyList(), introId, project.name)
        edit { it.copy(media = cut).relayout() }
    }

    fun delete(sel: Sel) {
        when (sel) {
            is Sel.Audio -> edit { it.copy(audio = it.audio.filter { c -> c.id != sel.id }) }
            is Sel.Effect -> edit { it.copy(effects = it.effects.filter { c -> c.id != sel.id }) }
            is Sel.Media -> edit { it.copy(media = it.media.filter { c -> c.id != sel.id }).relayout() }
        }
        selection = null
    }

    fun duplicate(sel: Sel) {
        when (sel) {
            is Sel.Audio -> audioById(sel.id)?.let { c ->
                val n = c.copy(id = UUID.randomUUID().toString().take(8), startS = c.endS)
                edit { it.copy(audio = it.audio + n) }; selection = Sel.Audio(n.id)
            }
            is Sel.Effect -> effectById(sel.id)?.let { c ->
                val end = project.layerEnd(c)
                val len = end - c.from
                val n = c.copy(id = UUID.randomUUID().toString().take(8), from = end, to = end + len, auto = false)
                edit { it.copy(effects = it.effects + n) }; selection = Sel.Effect(n.id)
            }
            is Sel.Media -> mediaById(sel.id)?.let { c ->
                val n = c.copy(id = UUID.randomUUID().toString().take(8), transition = "none")
                edit { p -> p.copy(media = p.media.flatMap { if (it.id == c.id) listOf(it, n) else listOf(it) }).relayout() }
                selection = Sel.Media(n.id)
            }
        }
    }

    /** Splits the selected clip at the playhead. */
    fun split(sel: Sel): Boolean {
        val t = posS
        when (sel) {
            is Sel.Audio -> {
                val c = audioById(sel.id) ?: return false
                if (t <= c.startS + 0.15f || t >= c.endS - 0.15f) return false
                val a = c.copy(lenS = t - c.startS, fadeOutS = 0f)
                val b = c.copy(id = UUID.randomUUID().toString().take(8), startS = t, inS = c.inS + (t - c.startS), lenS = c.endS - t, fadeInS = 0f)
                edit { p -> p.copy(audio = p.audio.flatMap { if (it.id == c.id) listOf(a, b) else listOf(it) }) }
                selection = Sel.Audio(b.id); return true
            }
            is Sel.Effect -> {
                val c = effectById(sel.id) ?: return false
                val end = project.layerEnd(c)
                if (t <= c.from + 0.15f || t >= end - 0.15f) return false
                val a = c.copy(to = t)
                val b = c.copy(id = UUID.randomUUID().toString().take(8), from = t, to = if (c.to < 0f) -1f else c.to)
                edit { p -> p.copy(effects = p.effects.flatMap { if (it.id == c.id) listOf(a, b) else listOf(it) }) }
                selection = Sel.Effect(b.id); return true
            }
            is Sel.Media -> {
                val c = mediaById(sel.id) ?: return false
                if (c.isIntro || t <= c.startS + 0.3f || t >= c.endS - 0.3f) return false
                val local = t - c.startS
                val a = c.copy(lenS = local)
                val b = c.copy(id = UUID.randomUUID().toString().take(8), inS = c.inS + local, lenS = c.lenS - local, transition = "fade", transS = 0.8f)
                edit { p -> p.copy(media = p.media.flatMap { if (it.id == c.id) listOf(a, b) else listOf(it) }).relayout() }
                selection = Sel.Media(b.id); return true
            }
        }
    }

    fun moveLayerOrder(id: String, delta: Int) {
        val l = effectById(id) ?: return
        edit { p -> p.copy(effects = p.effects.map { if (it.id == id) it.copy(track = it.track + delta) else it }) }
    }

    fun applyLook(layers: List<Layer>, fit: com.lofiautopilot.studio.vfx.Fit, replaceAll: Boolean) {
        val base = if (replaceAll) 0 else (project.effects.maxOfOrNull { it.track } ?: -1) + 1
        val placed = layers.mapIndexed { i, l -> l.copy(id = UUID.randomUUID().toString().take(8), from = 0f, to = -1f, track = base + i, auto = false) }
        edit { p -> p.copy(effects = (if (replaceAll) emptyList() else p.effects) + placed, fit = fit) }
        selection = null
    }

    fun clearEffects(onlyAuto: Boolean = false) = edit { p -> p.copy(effects = if (onlyAuto) p.effects.filter { !it.auto } else emptyList()) }

    // ---------- time ----------

    fun seek(t: Float) {
        posS = t.coerceIn(0f, duration)
        if (playing) player.start(project.audio, project.normalize, posS.toDouble())
    }

    fun togglePlay() {
        if (playing) pause() else play()
    }

    fun play() {
        if (duration <= 0.1f) return
        if (posS >= duration - 0.05f) posS = 0f
        playing = true
        player.start(project.audio, project.normalize, posS.toDouble())
    }

    fun pause() {
        if (!playing) return
        posS = player.halt().toFloat().coerceIn(0f, duration)
        playing = false
    }

    private fun restartAudioIfPlaying() {
        if (playing) player.start(project.audio, project.normalize, posS.toDouble())
    }

    /** Called every frame while playing. */
    fun tick() {
        if (!playing) return
        if (project.audio.isEmpty()) { posS += 1f / 60f } else posS = player.position().toFloat()
        if (posS >= duration - 0.02f) { posS = duration; playing = false; player.halt() }
    }

    // ---------- analysis ----------

    @OptIn(FlowPreview::class)
    private suspend fun watchAudio() {
        snapshotFlow { audioSig(project) }.debounce(700).collectLatest { sig ->
            val clips = project.audio
            if (clips.isEmpty()) { features = null; return@collectLatest }
            analysing = 0f
            val job = kotlinx.coroutines.currentCoroutineContext()[Job]
            try {
                val r = withContext(Dispatchers.Default) {
                    AudioAnalysis.run(ctx, clips, project.normalize, null, onProgress = { analysing = it }, cancelled = { job?.isActive == false })
                }
                features = r.features
            } catch (_: Cancelled) {
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                analysing = -1f
            }
        }
    }

    // ---------- persistence ----------

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(900)
            save()
        }
    }

    suspend fun save() {
        val p = project
        g.db.projects().upsert(ProjectEntity(p.id, p.name, json.encodeToString(p), System.currentTimeMillis(), p.durationS))
    }

    fun close() {
        pause()
        player.release()
        runBlockingSave()
        scope.cancel()
        if (g.editor === this) g.editor = null
    }

    private fun runBlockingSave() {
        runCatching { kotlinx.coroutines.runBlocking { save() } }
    }

    /** Nearest beat time to [t] (seconds), or null when there is no analysis yet. */
    fun nearestBeat(t: Float): Float? {
        val b = features?.beatTimes ?: return null
        if (b.isEmpty()) return null
        var best = b[0]
        for (x in b) if (kotlin.math.abs(x - t) < kotlin.math.abs(best - t)) best = x
        return best
    }

    @Suppress("unused")
    private fun clamp(v: Float, a: Float, b: Float) = min(max(v, a), b)
}
