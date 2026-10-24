package com.lofiautopilot.studio.ai

import com.lofiautopilot.studio.audio.FeatureTrack
import com.lofiautopilot.studio.vfx.Layer
import com.lofiautopilot.studio.vfx.Look
import com.lofiautopilot.studio.vfx.Presets
import com.lofiautopilot.studio.vfx.React
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Section(
    val startS: Float, val endS: Float,
    val energy: Float, val bass: Float, val onsets: Float, val bright: Float,
    var label: String,
)

data class MusicSummary(
    val durationS: Float,
    val bpm: Float,
    val avgEnergy: Float,
    val dynamics: Float,
    val bright: Float,
    val bassHeavy: Float,
    val sections: List<Section>,
) {
    /** Compact text for the language model. Small models need short, numeric, labelled input. */
    fun toPrompt(maxSections: Int = 12): String {
        val sb = StringBuilder()
        sb.append("Length: ${(durationS / 60f).roundToInt()} min. Tempo: ${bpm.roundToInt()} BPM. ")
        sb.append("Loudness ${pct(avgEnergy)}, dynamics ${pct(dynamics)}, brightness ${pct(bright)}, bass ${pct(bassHeavy)}.\n")
        sb.append("Sections (index: energy/bass/beats-per-sec/brightness):\n")
        val step = max(1, sections.size / maxSections)
        sections.filterIndexed { i, _ -> i % step == 0 }.take(maxSections).forEachIndexed { i, s ->
            sb.append("${i + 1}: ${pct(s.energy)}/${pct(s.bass)}/${"%.1f".format(s.onsets)}/${pct(s.bright)} ${s.label}\n")
        }
        return sb.toString()
    }

    private fun pct(v: Float) = "${(v * 100).roundToInt()}"
}

/** What the director decided for a mix. */
data class DirectorPlan(
    val style: String,
    val lookName: String,
    /** Overall effect strength, 0.4 .. 1.4. */
    val intensity: Float,
    /** How strongly effects follow the music, 0.3 .. 1.6. */
    val reactivity: Float,
    /** Per-section strength multiplier (same count as the summary's sections). */
    val boosts: List<Float>,
    val notes: String,
    val source: String,
)

object Director {
    val STYLES = mapOf(
        "calm" to "Cozy Lofi",
        "rainy" to "Rainy Window",
        "dreamy" to "Dream Pulse",
        "night" to "Neon Night",
        "retro" to "Synth Sunset",
        "tape" to "VHS Memory",
        "cinematic" to "Cinematic",
        "energetic" to "Club Visualizer",
        "glitch" to "Cyber Glitch",
        "aurora" to "Aurora Sky",
        "warm" to "Campfire Calm",
        "winter" to "Winter Snow",
        "minimal" to "Minimal Pulse",
    )

    fun summarize(ft: FeatureTrack): MusicSummary {
        val secLen = if (ft.durationS < 150f) 10 else 30
        val per = secLen * ft.fps
        val out = ArrayList<Section>()
        var k = 0
        while (k < ft.frames) {
            val e = min(ft.frames, k + per)
            val n = (e - k).coerceAtLeast(1)
            var en = 0f; var ba = 0f; var fl = 0f; var br = 0f
            for (i in k until e) { en += ft.energy[i]; ba += ft.bass[i]; fl += ft.flux[i]; br += ft.centroid[i] }
            val beats = ft.beatTimes.count { it >= k.toFloat() / ft.fps && it < e.toFloat() / ft.fps }
            out.add(Section(k.toFloat() / ft.fps, e.toFloat() / ft.fps, en / n, ba / n, beats.toFloat() / (n.toFloat() / ft.fps),
                (br / n * 2.2f).coerceIn(0f, 1f), ""))
            k = e
        }
        val med = out.map { it.energy }.sorted().let { if (it.isEmpty()) 0.5f else it[it.size / 2] }.coerceAtLeast(0.05f)
        out.forEachIndexed { i, s ->
            val prev = out.getOrNull(i - 1)?.energy ?: s.energy
            s.label = when {
                s.energy > med * 1.25f -> "loud/drop"
                s.energy < med * 0.75f -> "quiet/calm"
                s.energy > prev * 1.15f -> "building"
                i == out.lastIndex && s.energy < med -> "outro"
                else -> "steady"
            }
        }
        val avgE = out.map { it.energy }.average().toFloat()
        val dyn = (out.maxOfOrNull { it.energy } ?: 0f) - (out.minOfOrNull { it.energy } ?: 0f)
        return MusicSummary(
            ft.durationS, ft.bpm, avgE, dyn.coerceIn(0f, 1f),
            out.map { it.bright }.average().toFloat(), out.map { it.bass }.average().toFloat(), out,
        )
    }

    /** Deterministic fallback used when no language model is available (or it gives an unusable answer). */
    fun rules(s: MusicSummary, rainyHint: Boolean = false): DirectorPlan {
        val style = when {
            s.bpm >= 118f && s.avgEnergy > 0.45f -> "energetic"
            s.bpm >= 105f && s.bright > 0.5f -> "retro"
            s.avgEnergy < 0.28f && s.bright < 0.3f -> "calm"
            rainyHint -> "rainy"
            s.bright > 0.55f -> "dreamy"
            s.bassHeavy > 0.6f -> "night"
            s.avgEnergy < 0.4f -> "calm"
            else -> "tape"
        }
        val inten = (0.7f + s.avgEnergy * 0.35f).coerceIn(0.6f, 1.0f)
        val react = (0.7f + s.dynamics * 1.0f + if (s.bpm > 110f) 0.2f else 0f).coerceIn(0.5f, 1.5f)
        val med = s.sections.map { it.energy }.sorted().let { if (it.isEmpty()) 0.5f else it[it.size / 2] }.coerceAtLeast(0.05f)
        val boosts = s.sections.map { (0.78f + 0.45f * (it.energy / med - 0.5f).coerceIn(0f, 1f)).coerceIn(0.6f, 1.35f) }
        return DirectorPlan(style, STYLES.getValue(style), inten, react, boosts,
            "Tempo ${s.bpm.roundToInt()} BPM, average loudness ${(s.avgEnergy * 100).roundToInt()}%.", "rules")
    }

    /** Parses a model answer; returns null if it can't be trusted. */
    fun parse(text: String, s: MusicSummary): DirectorPlan? {
        val a = text.indexOf('{'); val b = text.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return runCatching {
            val j = JSONObject(text.substring(a, b + 1))
            val style = j.optString("style").lowercase().trim()
            if (style !in STYLES) return null
            val base = rules(s)
            val boosts = j.optJSONArray("boosts")?.let { arr: JSONArray ->
                if (arr.length() == 0) null else List(s.sections.size) { i ->
                    val idx = min(arr.length() - 1, (i * arr.length()) / max(1, s.sections.size))
                    arr.optDouble(idx, 1.0).toFloat().coerceIn(0.6f, 1.4f)
                }
            } ?: base.boosts
            DirectorPlan(
                style, STYLES.getValue(style),
                j.optDouble("intensity", base.intensity.toDouble()).toFloat().coerceIn(0.5f, 1.1f),
                j.optDouble("reactivity", base.reactivity.toDouble()).toFloat().coerceIn(0.3f, 1.6f),
                boosts, j.optString("why").take(200), "local AI",
            )
        }.getOrNull()
    }

    /** Writes the section curve into the feature track so layers that "follow the director" breathe with the song's structure. */
    fun applyCurve(ft: FeatureTrack, s: MusicSummary, plan: DirectorPlan) {
        if (s.sections.isEmpty()) return
        val centres = s.sections.map { (it.startS + it.endS) / 2f }
        for (k in 0 until ft.frames) {
            val t = k.toFloat() / ft.fps
            // linear interpolation between neighbouring section centres, so changes glide rather than jump
            var i = centres.indexOfLast { it <= t }
            val v = when {
                i < 0 -> plan.boosts.first()
                i >= centres.lastIndex -> plan.boosts.last()
                else -> {
                    val f = (t - centres[i]) / max(0.001f, centres[i + 1] - centres[i])
                    plan.boosts[i] + (plan.boosts[i + 1] - plan.boosts[i]) * f
                }
            }
            ft.direction[k] = (v * plan.intensity).coerceIn(0.3f, 1.8f)
        }
    }

    /** Returns the preset for the plan with reactivity scaled. Layers keep their own settings otherwise. */
    fun lookFor(plan: DirectorPlan, base: Look? = null): Look {
        val look = base ?: Presets.builtin.firstOrNull { it.name == plan.lookName } ?: Presets.builtin.first()
        val layers = look.layers.map { l: Layer ->
            if (l.react == React.OFF) l else l.copy(reactAmt = (l.reactAmt * plan.reactivity).coerceIn(0.05f, 2f))
        }
        return look.copy(layers = layers)
    }

    fun describe(plan: DirectorPlan) =
        "Style “${plan.style}” → ${plan.lookName} · strength ${(plan.intensity * 100).roundToInt()}% · reactivity ${(plan.reactivity * 100).roundToInt()}% (${plan.source})"

    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 0.01f
}
