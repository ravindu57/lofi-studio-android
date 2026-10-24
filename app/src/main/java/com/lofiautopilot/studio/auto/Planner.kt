package com.lofiautopilot.studio.auto

import com.lofiautopilot.studio.ai.CloudAi
import com.lofiautopilot.studio.ai.LocalLlm
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.data.JobEntity
import com.lofiautopilot.studio.data.TrackEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.random.Random

@Serializable
data class Plan(
    val title: String,
    val description: String,
    val tags: List<String>,
    val mood: String,
    val scene: String,
    val durationS: Double,
    val trackIds: List<Long>,
    val artPrompt: String = "",
    val orderHash: String = "",
    val planner: String = "rules",
)

class PlanException(msg: String, val hint: String = "") : Exception(msg)

/** Chooses tracks and writes the title/description. Code picks the tracks (so mixes never repeat); AI only writes words. */
class Planner(private val g: Graph, private val llm: LocalLlm, private val cloud: CloudAi) {
    private val json = Json { ignoreUnknownKeys = true }

    private val scenes = mapOf(
        "rain" to listOf("rainy window study session", "rain on the attic roof", "storm outside, warm room inside"),
        "night" to listOf("late night city lights", "3am and the kettle's on", "quiet street after midnight"),
        "study" to listOf("deep focus desk session", "library corner, pages turning", "notebook open, coffee cooling"),
        "chill" to listOf("slow sunday afternoon", "nowhere to be today", "soft light, slow thoughts"),
        "sleep" to listOf("drifting off by the window", "dim lamp, heavy eyelids", "the room goes quiet"),
        "autumn" to listOf("autumn reading room", "leaves on the windowsill", "sweater weather walk"),
        "winter" to listOf("snow falling past the window", "winter cabin evening", "frost on the glass"),
        "summer" to listOf("summer evening on the balcony", "warm breeze, open window", "golden hour rooftop"),
        "coffee" to listOf("corner café morning", "steam rising from the cup", "coffee shop at closing time"),
        "drive" to listOf("late tram home", "night drive on an empty road", "train window daydream"),
        "jazz" to listOf("smoky piano bar", "old records, new rain", "jazz on a quiet evening"),
        "energy" to listOf("midnight drive, volume up", "neon city on repeat", "the dance floor after hours"),
    )
    private val styles = mapOf("rain" to "soft lofi", "night" to "chill beats", "study" to "focus beats", "sleep" to "sleepy lofi",
        "jazz" to "jazzy lofi", "coffee" to "warm lofi", "drive" to "chillhop", "autumn" to "cozy lofi", "energy" to "feel-good remixes")
    private val purpose = mapOf("study" to "to study and focus", "sleep" to "to sleep and relax", "night" to "for late nights",
        "rain" to "to relax and study", "drive" to "to unwind", "coffee" to "to start the day")

    private fun hash(ids: List<Long>) = MessageDigest.getInstance("SHA-1").digest(ids.joinToString(",").toByteArray())
        .joinToString("") { "%02x".format(it) }.take(12)

    suspend fun make(minutesOverride: Int? = null, seed: Long? = null): Plan {
        val rng = if (seed != null) Random(seed) else Random.Default
        val s = g.settings.value
        val tracks = g.db.tracks().usable()
        if (tracks.isEmpty()) throw PlanException("There's no music yet.", "Open Library and add a music folder.")
        val recent = g.db.jobs().recentNow(20).filter { it.status != "cancelled" }
        val recentMoods = recent.mapNotNull { it.mood.takeIf { m -> m.isNotBlank() } }
        val recentHashes = recent.mapNotNull { runCatching { json.decodeFromString<Plan>(it.planJson).orderHash }.getOrNull() }.toSet()

        val available = tracks.flatMap { it.moods.split(',').filter { m -> m.isNotBlank() } }.groupingBy { it }.eachCount()
        val moodPool = available.keys.filter { it in scenes }.ifEmpty { listOf("chill", "night", "study", "rain") }
        val mood = moodPool.filter { it != recentMoods.firstOrNull() }.ifEmpty { moodPool }.random(rng)

        val seconds = (minutesOverride ?: s.targetMinutes) * 60.0
        val chosen = pickTracks(tracks, mood, seconds, s.crossfadeS.toDouble(), recentHashes, rng)
        val totalMin = seconds / 60

        val hint = s.ideaHint.trim()
        var text = rulesText(mood, totalMin, rng, hint.takeIf { it.isNotBlank() })
        if (hint.isNotBlank()) g.settings.update { it.copy(ideaHint = "") }
        var planner = "rules"
        when (s.planner) {
            "cloud", "claude" -> if (cloud.configured) {
                runCatching { cloudText(mood, totalMin, text.scene) }.onSuccess { text = it; planner = "cloud AI" }
                    .onFailure { g.log("The cloud AI couldn't write the text (${it.message}); using the built-in writer.", level = "warn") }
            }
            "local" -> if (llm.available) {
                localText(mood, totalMin, text.scene)?.let { text = it; planner = "local AI" }
            }
        }
        val base = s.defaultPrompt
        return Plan(
            title = text.title, description = text.description, tags = text.tags, mood = mood, scene = text.scene,
            durationS = seconds, trackIds = chosen.map { it.id },
            artPrompt = "$base Scene: ${text.scene}. Wide ${s.aspect.let { if (it == "TALL") "9:16" else "16:9" }} background, no faces in focus, no text.",
            orderHash = hash(chosen.map { it.id }), planner = planner,
        )
    }

    private fun pickTracks(tracks: List<TrackEntity>, mood: String, seconds: Double, xf: Double, recent: Set<String>, rng: Random): List<TrackEntity> {
        var best: List<TrackEntity> = emptyList()
        repeat(12) {
            val ranked = tracks.sortedBy { t ->
                (if (mood in t.moods) -1.0 else 0.0) + rng.nextDouble() * 0.9 + minOf(t.usedCount, 20) * 0.03
            }
            val chosen = ArrayList<TrackEntity>()
            var total = 0.0
            var i = 0
            while (total < seconds) {
                var n = ranked[i % ranked.size]
                if (chosen.isNotEmpty() && n.id == chosen.last().id && ranked.size > 1) { i++; n = ranked[i % ranked.size] }
                chosen.add(n)
                total += n.durationMs / 1000.0 - (if (chosen.size > 1) xf else 0.0)
                i++
                if (chosen.size > 400) throw PlanException("The music library is too short for this video length.", "Add more music or lower the video length.")
            }
            best = chosen
            if (hash(chosen.map { it.id }) !in recent) return chosen
        }
        return best
    }

    private data class Text(val title: String, val description: String, val tags: List<String>, val scene: String)

    private fun lengthWord(minutes: Double): String {
        val h = minutes / 60
        return if (h >= 1 && h == h.toInt().toDouble()) "${h.toInt()} hour" + (if (h >= 2) "s" else "") else "${minutes.toInt()} min"
    }

    private fun rulesText(mood: String, minutes: Double, rng: Random, idea: String? = null): Text {
        val scene = idea?.take(70)?.lowercase() ?: (scenes[mood] ?: scenes.getValue("chill")).random(rng)
        val style = styles[mood] ?: "lofi beats"
        val purpose = purpose[mood] ?: "to relax"
        val length = lengthWord(minutes)
        val title = "$scene · $length of $style $purpose"
        val tags = listOf(mood, "lofi", "lofi hip hop", style, "study music", "chill beats").distinct()
        val desc = "${scene.replaceFirstChar { it.uppercase() }}. ${length.replaceFirstChar { it.uppercase() }} of $style $purpose.\n\n" +
            "Put it on in the background, get comfortable, and let the time pass a little slower.\n\n" +
            "New mixes regularly on ${g.settings.value.channelName}."
        return Text(title.take(95), desc, tags, scene)
    }

    private fun parseText(raw: String, fallback: Text): Text? {
        val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return runCatching {
            val j = JSONObject(raw.substring(a, b + 1))
            val title = j.getString("title").trim().take(95)
            if (title.length < 8) return null
            val tags = j.optJSONArray("tags")?.let { arr -> List(arr.length()) { arr.getString(it).lowercase().trim() } }?.filter { it.isNotBlank() }?.take(15)
                ?: fallback.tags
            Text(title, j.optString("description").ifBlank { fallback.description }, tags.ifEmpty { fallback.tags },
                j.optString("scene").ifBlank { fallback.scene })
        }.getOrNull()
    }

    private suspend fun cloudText(mood: String, minutes: Double, scene: String): Text {
        val fb = rulesText(mood, minutes, Random(1))
        val raw = cloud.text(
            "You write metadata for a lofi music channel on YouTube/Facebook. Original, specific, no clickbait, no claims about where the music comes from, no artist or character names. Reply with JSON only.",
            "Channel: ${g.settings.value.channelName}\nMood: $mood\nLength: ${minutes.toInt()} minutes\nScene idea: $scene\n" +
                "Return {\"title\":\"under 90 chars\",\"description\":\"2-3 short paragraphs, no links\",\"tags\":[8-12 lowercase tags],\"scene\":\"one sentence visual scene, no text in the image\"}",
        )
        return parseText(raw, fb) ?: throw PlanException("The AI's answer wasn't usable.")
    }

    private suspend fun localText(mood: String, minutes: Double, scene: String): Text? {
        val fb = rulesText(mood, minutes, Random(1))
        val raw = llm.ask(
            "You write short metadata for a lofi music channel. Reply with one JSON object only.",
            "Mood: $mood. Length: ${minutes.toInt()} minutes. Scene idea: $scene.\n" +
                "Reply {\"title\":\"under 90 chars\",\"description\":\"two short paragraphs\",\"tags\":[\"lofi\",\"...\"],\"scene\":\"one sentence\"}",
            450,
        ) ?: return null
        return parseText(raw, fb)
    }

    fun encode(p: Plan): String = json.encodeToString(p)
    fun decode(s: String): Plan? = runCatching { json.decodeFromString<Plan>(s) }.getOrNull()

    @Suppress("unused")
    fun job(j: JobEntity) = decode(j.planJson)
}
