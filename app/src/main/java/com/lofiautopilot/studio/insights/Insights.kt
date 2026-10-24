package com.lofiautopilot.studio.insights

import android.os.StatFs
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.core.SecretStore
import com.lofiautopilot.studio.data.JobStatus
import com.lofiautopilot.studio.data.KvEntity
import com.lofiautopilot.studio.vfx.EffectLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.Calendar

data class Tip(val text: String, val level: Int, val action: String = "") // level 0 = info, 1 = watch out, 2 = problem

data class YtStat(val id: String, val title: String, val views: Long, val likes: Long)

data class Insights(
    val daily: List<Pair<String, Int>>,
    val weekCount: Int, val weekTarget: Int, val streak: Int,
    val totalVideos: Int, val totalHours: Float, val failedWeek: Int, val successRate: Int, val avgRenderMin: Float,
    val storageUsedMb: Long, val freeMb: Long,
    val tracks: Int, val musicHours: Float, val topTracks: List<Pair<String, Int>>, val moods: List<Pair<String, Int>>,
    val topEffects: List<Pair<String, Int>>, val projects: Int,
    val published: Int, val ytIds: List<Pair<String, String>>,
    val nextRun: Long?, val tips: List<Tip>,
)

object InsightsRepo {
    private fun dayStart(offsetDays: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_YEAR, offsetDays)
        return c.timeInMillis
    }

    suspend fun compute(g: Graph): Insights = withContext(Dispatchers.IO) {
        val s = g.settings.value
        val jobs = g.db.jobs().recentNow(400)
        val done = jobs.filter { it.status == JobStatus.DONE }
        fun whenDone(j: com.lofiautopilot.studio.data.JobEntity) = if (j.finishedAt > 0) j.finishedAt else j.createdAt

        val labels = listOf("S", "M", "T", "W", "T", "F", "S")
        val daily = (13 downTo 0).map { d ->
            val a = dayStart(-d); val b = a + 86_400_000L
            val cal = Calendar.getInstance().apply { timeInMillis = a }
            labels[cal.get(Calendar.DAY_OF_WEEK) - 1] to done.count { whenDone(it) in a until b }
        }
        val week = daily.takeLast(7).sumOf { it.second }
        var streak = 0
        for (i in daily.indices.reversed()) { if (daily[i].second > 0) streak++ else if (i != daily.lastIndex) break }
        val failedWeek = jobs.count { it.status == JobStatus.FAILED && whenDone(it) >= dayStart(-6) }
        val finishedCount = done.size + jobs.count { it.status == JobStatus.FAILED }
        val success = if (finishedCount == 0) 100 else (done.size * 100 / finishedCount)
        val renders = done.filter { it.finishedAt > 0 }.take(20).map { (it.finishedAt - it.createdAt) / 60_000f }
        val used = done.sumOf { j -> j.outPath.takeIf { it.isNotBlank() }?.let { File(it).takeIf { f -> f.exists() }?.length() } ?: 0L } / 1_048_576
        val free = runCatching { StatFs(g.app.filesDir.absolutePath).availableBytes / 1_048_576 }.getOrDefault(0L)

        val tracks = g.db.tracks().usable()
        val moodCount = tracks.flatMap { it.moods.split(',').filter { m -> m.isNotBlank() } }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }.take(6)
        val topTracks = tracks.filter { it.usedCount > 0 }.sortedByDescending { it.usedCount }.take(5).map { it.title to it.usedCount }

        val projectRows = g.db.projects().allNow()
        val fxCount = HashMap<String, Int>()
        val rx = Regex("\"fx\":\"([a-z0-9_]+)\"")
        projectRows.forEach { p -> rx.findAll(p.json).forEach { m -> fxCount.merge(m.groupValues[1], 1, Int::plus) } }
        val topFx = fxCount.entries.sortedByDescending { it.value }.take(6).map { (EffectLibrary.get(it.key)?.name ?: it.key) to it.value }

        val published = done.filter { it.ytUrl.isNotBlank() || it.fbUrl.isNotBlank() }
        val ytIds = published.filter { it.ytUrl.isNotBlank() }.take(20).map { it.ytUrl.substringAfterLast('/') to it.title }

        val upcoming = jobs.filter { it.publishAt > System.currentTimeMillis() && it.status in JobStatus.active }.minOfOrNull { it.publishAt }
        val nextRun = upcoming ?: if (s.autopilot) nextSlot(s.publishTime, s.videosPerDay) else null

        val tips = ArrayList<Tip>()
        if (failedWeek > 0) tips.add(Tip("$failedWeek video${if (failedWeek > 1) "s" else ""} failed this week. Open Videos → Problems, or ask the assistant to fix it.", 2, "videos"))
        if (s.autopilot && week < s.videosPerDay * 7 / 2 && jobs.isNotEmpty()) tips.add(Tip("Only $week of ${s.videosPerDay * 7} planned videos were made this week. Check that the phone isn't stopping AIM3 in the background.", 1, "settings"))
        if (tracks.size in 1..9) tips.add(Tip("Your library has only ${tracks.size} tracks. Add more so mixes don't repeat.", 1, "library"))
        if (tracks.isEmpty()) tips.add(Tip("Add a music folder so videos can be made.", 2, "library"))
        if (free in 1..3000) tips.add(Tip("Only ${"%.1f".format(free / 1024.0)} GB free. Rendering needs space; clean up old videos.", 2, "cleanup"))
        if (renders.isNotEmpty()) { val avg = renders.average().toFloat(); if (avg > s.leadHours * 60 * 0.8f) tips.add(Tip("Renders take about ${avg.toInt()} min. Increase “Start making each video … before it's due” in Settings.", 1, "settings")) }
        if (!s.publishEnabled && done.size >= 3) tips.add(Tip("You're in practice mode. Turn on Publish for real when you're happy with the videos.", 0, "settings"))
        if (topTracks.firstOrNull()?.let { it.second >= 5 } == true) tips.add(Tip("“${topTracks.first().first}” has been used ${topTracks.first().second} times. Rotate in newer songs.", 0))
        if (streak >= 3) tips.add(Tip("$streak-day streak. Nice consistency.", 0))
        if (tips.isEmpty()) tips.add(Tip("Everything looks healthy.", 0))

        Insights(
            daily, week, s.videosPerDay * 7, streak, done.size, done.sumOf { it.durationS }.toFloat() / 3600f, failedWeek, success,
            if (renders.isEmpty()) 0f else renders.average().toFloat(), used, free,
            tracks.size, tracks.sumOf { it.durationMs } / 3_600_000f, topTracks, moodCount, topFx, projectRows.size,
            published.size, ytIds, nextRun, tips,
        )
    }

    /** Time of the next autopilot slot given "HH:mm" and videos per day. */
    fun nextSlot(time: String, perDay: Int): Long {
        val (hh, mm) = time.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 18) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
        val now = System.currentTimeMillis()
        for (d in 0..1) for (slot in 0 until perDay.coerceIn(1, 4)) {
            val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, d)
            c.set(Calendar.HOUR_OF_DAY, hh); c.set(Calendar.MINUTE, mm); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            c.add(Calendar.MINUTE, slot * 24 * 60 / perDay.coerceIn(1, 4))
            if (c.timeInMillis > now) return c.timeInMillis
        }
        return now + 86_400_000L
    }

    /** Views and likes of the videos you published to YouTube (needs the API key; cached for 3 hours). */
    suspend fun youtubeStats(g: Graph, ids: List<Pair<String, String>>, force: Boolean): List<YtStat>? = withContext(Dispatchers.IO) {
        val key = g.secrets.get(SecretStore.YOUTUBE_API)
        if (key.isBlank() || ids.isEmpty()) return@withContext null
        val ck = "ytstats_" + ids.joinToString { it.first }.hashCode()
        val cached = g.db.kv().get(ck)?.split('\n')
        if (!force && cached != null && cached.size > 1 && System.currentTimeMillis() - (cached[0].toLongOrNull() ?: 0L) < 3 * 3_600_000L) {
            return@withContext cached.drop(1).mapNotNull { l -> l.split('\t').takeIf { it.size == 4 }?.let { YtStat(it[0], it[1], it[2].toLong(), it[3].toLong()) } }
        }
        try {
            val url = "https://www.googleapis.com/youtube/v3/videos?part=statistics&id=${ids.joinToString(",") { it.first }}&key=$key"
            g.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val items = JSONObject(r.body?.string().orEmpty()).optJSONArray("items") ?: return@withContext emptyList()
                val titles = ids.toMap()
                val out = (0 until items.length()).map { i ->
                    val o = items.getJSONObject(i); val st = o.optJSONObject("statistics")
                    YtStat(o.getString("id"), titles[o.getString("id")].orEmpty(), st?.optString("viewCount")?.toLongOrNull() ?: 0L, st?.optString("likeCount")?.toLongOrNull() ?: 0L)
                }
                g.db.kv().put(KvEntity(ck, System.currentTimeMillis().toString() + "\n" + out.joinToString("\n") { "${it.id}\t${it.title.replace('\t', ' ').replace('\n', ' ')}\t${it.views}\t${it.likes}" }))
                out
            }
        } catch (e: Exception) { null }
    }
}
