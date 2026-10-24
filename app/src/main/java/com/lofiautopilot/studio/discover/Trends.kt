package com.lofiautopilot.studio.discover

import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.core.SecretStore
import com.lofiautopilot.studio.data.KvEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import kotlin.math.max

@Serializable
data class TrendVideo(
    val id: String, val title: String, val channel: String, val thumb: String,
    val publishedMs: Long, val views: Long, val likes: Long, val durationS: Int, val keyword: String,
) {
    val ageDays: Double get() = max(0.25, (System.currentTimeMillis() - publishedMs) / 86_400_000.0)
    val viewsPerDay: Double get() = views / ageDays
}

class TrendsException(msg: String, val hint: String = "") : Exception(msg)

/** Source of "what's new in the niche". Only public video metadata is read; nothing is downloaded. */
interface TrendsApi {
    suspend fun fetch(keywords: List<String>, sort: String, region: String): List<TrendVideo>
}

/** Uses the official YouTube Data API with the user's own key. */
class YouTubeTrends(private val g: Graph) : TrendsApi {
    override suspend fun fetch(keywords: List<String>, sort: String, region: String): List<TrendVideo> = withContext(Dispatchers.IO) {
        val key = g.secrets.get(SecretStore.YOUTUBE_API)
        if (key.isBlank()) throw TrendsException("Add a YouTube Data API key first.", "Settings → Accounts → YouTube Data API key.")
        val since = Instant.now().minusSeconds(86_400L * (if (sort == "LATEST") 14 else 7)).toString()
        val ids = LinkedHashMap<String, String>() // video id -> keyword
        for (kw in keywords.take(5)) {
            val url = "https://www.googleapis.com/youtube/v3/search".toHttpUrl().newBuilder()
                .addQueryParameter("part", "snippet").addQueryParameter("type", "video").addQueryParameter("q", kw)
                .addQueryParameter("maxResults", "14").addQueryParameter("order", if (sort == "LATEST") "date" else "viewCount")
                .addQueryParameter("publishedAfter", since).addQueryParameter("safeSearch", "moderate").addQueryParameter("key", key)
                .apply { if (region.isNotBlank()) addQueryParameter("regionCode", region) }.build()
            val j = call(url.toString())
            val items = j.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) items.getJSONObject(i).optJSONObject("id")?.optString("videoId")?.takeIf { it.isNotBlank() }?.let { ids.putIfAbsent(it, kw) }
        }
        if (ids.isEmpty()) return@withContext emptyList()
        val out = ArrayList<TrendVideo>()
        for (chunk in ids.keys.chunked(50)) {
            val url = "https://www.googleapis.com/youtube/v3/videos".toHttpUrl().newBuilder()
                .addQueryParameter("part", "snippet,statistics,contentDetails").addQueryParameter("id", chunk.joinToString(",")).addQueryParameter("key", key).build()
            val items = call(url.toString()).optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                val sn = o.getJSONObject("snippet"); val st = o.optJSONObject("statistics")
                val th = sn.optJSONObject("thumbnails")
                val thumb = th?.optJSONObject("high")?.optString("url") ?: th?.optJSONObject("medium")?.optString("url") ?: th?.optJSONObject("default")?.optString("url").orEmpty()
                val dur = parseDuration(o.optJSONObject("contentDetails")?.optString("duration").orEmpty())
                if (dur in 1..60) continue // skip Shorts: we want long-form ideas
                out.add(TrendVideo(o.getString("id"), sn.optString("title"), sn.optString("channelTitle"), thumb,
                    runCatching { Instant.parse(sn.optString("publishedAt")).toEpochMilli() }.getOrDefault(0L),
                    st?.optString("viewCount")?.toLongOrNull() ?: 0L, st?.optString("likeCount")?.toLongOrNull() ?: 0L, dur, ids[o.getString("id")].orEmpty()))
            }
        }
        out
    }

    private fun call(url: String): JSONObject {
        try {
            g.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val t = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val reason = runCatching { JSONObject(t).getJSONObject("error").getJSONArray("errors").getJSONObject(0).getString("reason") }.getOrDefault("")
                    throw when {
                        reason == "quotaExceeded" -> TrendsException("YouTube's daily search allowance is used up.", "It resets at midnight Pacific time. Cached results are still shown.")
                        reason == "keyInvalid" || r.code == 400 -> TrendsException("YouTube didn't accept the API key.", "Check it in Settings → Accounts.")
                        r.code == 403 -> TrendsException("YouTube refused the request.", "Enable the YouTube Data API for your key in Google Cloud.")
                        else -> TrendsException("YouTube error ${r.code}.")
                    }
                }
                return JSONObject(t)
            }
        } catch (e: java.io.IOException) {
            throw TrendsException("Couldn't reach YouTube.", "Check the internet connection.")
        }
    }

    private fun parseDuration(iso: String): Int {
        val m = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matchEntire(iso) ?: return 0
        return (m.groupValues[1].toIntOrNull() ?: 0) * 3600 + (m.groupValues[2].toIntOrNull() ?: 0) * 60 + (m.groupValues[3].toIntOrNull() ?: 0)
    }
}

/** Offline stand-in for tests and demos. */
class FakeTrends : TrendsApi {
    override suspend fun fetch(keywords: List<String>, sort: String, region: String): List<TrendVideo> =
        listOf(TrendVideo("fake1", "rainy window study session · 3 hours of soft lofi", "Demo channel", "", System.currentTimeMillis() - 86_400_000L, 120_000, 4_000, 10_800, keywords.firstOrNull().orEmpty()))
}

@Serializable
private data class Cached(val at: Long, val items: List<TrendVideo>)

/** Fetches with a 3-hour cache so the free quota lasts. */
class TrendsRepo(private val g: Graph, private val api: TrendsApi = YouTubeTrends(g)) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun cacheKey(k: List<String>, sort: String, region: String) = "trends_" + (k.joinToString("|") + sort + region).hashCode()

    suspend fun load(keywords: List<String>, sort: String, region: String, force: Boolean): List<TrendVideo> {
        val key = cacheKey(keywords, sort, region)
        val cached = g.db.kv().get(key)?.let { runCatching { json.decodeFromString<Cached>(it) }.getOrNull() }
        if (!force && cached != null && System.currentTimeMillis() - cached.at < 3 * 3_600_000L) return rank(cached.items, sort)
        return try {
            val fresh = api.fetch(keywords, sort, region)
            g.db.kv().put(KvEntity(key, json.encodeToString(Cached(System.currentTimeMillis(), fresh))))
            rank(fresh, sort)
        } catch (e: TrendsException) {
            if (cached != null) rank(cached.items, sort) else throw e
        }
    }

    /** Ideas from the last saved results only (no network, no quota). */
    suspend fun cachedIdeas(keywords: List<String>, sort: String, region: String): List<String> {
        val cached = g.db.kv().get(cacheKey(keywords, sort, region))?.let { runCatching { json.decodeFromString<Cached>(it) }.getOrNull() } ?: return emptyList()
        return ideas(cached.items, keywords)
    }

    private fun rank(list: List<TrendVideo>, sort: String): List<TrendVideo> = when (sort) {
        "LATEST" -> list.sortedByDescending { it.publishedMs }
        "RISING" -> list.sortedByDescending { it.viewsPerDay }
        else -> list.sortedByDescending { it.views }
    }

    companion object {
        private val stop = setOf("the", "a", "an", "of", "to", "for", "and", "in", "on", "with", "at", "by", "is", "it", "my", "your", "you", "hour", "hours", "hr", "mix", "music", "video",
            "best", "new", "full", "official", "live", "no", "ft", "feat", "vs", "from", "this", "that", "are", "be", "2024", "2025", "2026", "1", "2", "3", "4", "5", "10")

        /** Words and word pairs that show up most in what's doing well: a quick source of video ideas. */
        fun ideas(list: List<TrendVideo>, exclude: List<String>): List<String> {
            val ex = exclude.flatMap { it.lowercase().split(" ") }.toSet()
            val score = HashMap<String, Double>()
            for (v in list) {
                val words = Regex("[\\p{L}']{3,}").findAll(v.title.lowercase()).map { it.value }.filter { it !in stop && it !in ex }.toList()
                val w = Math.log10(2.0 + v.viewsPerDay)
                words.distinct().forEach { score[it] = (score[it] ?: 0.0) + w }
                words.zipWithNext().distinct().forEach { (a, b) -> score["$a $b"] = (score["$a $b"] ?: 0.0) + w * 1.6 }
            }
            return score.entries.filter { it.value > 2.5 }.sortedByDescending { it.value }.map { it.key }.take(14)
        }
    }
}
