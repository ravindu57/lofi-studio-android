package com.lofiautopilot.studio.ai

import com.lofiautopilot.studio.audio.FeatureTrack
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.vfx.EffectLibrary
import com.lofiautopilot.studio.vfx.Intros
import com.lofiautopilot.studio.vfx.MediaClip
import com.lofiautopilot.studio.vfx.Transitions
import com.lofiautopilot.studio.vfx.Layer
import com.lofiautopilot.studio.vfx.LayerFactory
import com.lofiautopilot.studio.vfx.Project
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** One style's recipe: what plays all the time, and what fits quiet, steady, building and loud sections. */
private class Recipe(
    val base: List<Pair<String, Float>>,
    val quiet: List<String>, val steady: List<String>, val building: List<String>, val loud: List<String>,
    val accent: List<String>,
)

/**
 * Places effect clips on the timeline from what the music does: calm effects while it's quiet, visualizers while it
 * plays, build-ups into drops, short accents at big changes. Rules always work; a language model (on-device or cloud)
 * can choose the effects per section instead.
 */
object AutoTimeline {
    private val recipes: Map<String, Recipe> = mapOf(
        "calm" to Recipe(
            listOf("cam_float" to 1f, "cg_dream" to 0.7f, "lt_dreamglow" to 0.7f, "ft_tape" to 0.7f, "ft_grain" to 0.6f, "lt_vignette" to 0.8f),
            listOf("fx_dust", "fx_fireflies"), listOf("viz_hills", "fx_dust"), listOf("lt_leaks", "viz_hills"), listOf("viz_hills", "lt_bloom", "fx_bokeh"),
            listOf("lt_sweep", "lt_strobe"),
        ),
        "rainy" to Recipe(
            listOf("cam_float" to 1f, "cg_night" to 0.5f, "fx_glass" to 0.9f, "ft_grain" to 0.5f, "lt_vignette" to 0.9f),
            listOf("fx_rain"), listOf("fx_rain", "viz_area"), listOf("fx_rain", "lt_bloom", "viz_area"), listOf("fx_rain", "lt_bloom", "fx_lightning"),
            listOf("lt_sweep", "gl_shock"),
        ),
        "dreamy" to Recipe(
            listOf("cam_float" to 1f, "cam_sway" to 0.7f, "cg_dream" to 0.8f, "lt_dreamglow" to 0.9f, "lt_vignette" to 0.7f),
            listOf("fx_bokeh", "fx_dust"), listOf("fx_bokeh", "viz_orb"), listOf("viz_hills", "lt_leaks", "fx_sparkle"), listOf("viz_orb", "viz_rings", "lt_bloom", "fx_bokeh"),
            listOf("lt_sweep", "gl_shock"),
        ),
        "night" to Recipe(
            listOf("cam_float" to 1f, "cg_split" to 0.8f, "lt_bloom" to 0.8f, "lt_vignette" to 0.8f),
            listOf("fx_fireflies", "fx_stars"), listOf("viz_valley", "fx_fireflies"), listOf("viz_ridgelines", "gl_chroma"), listOf("viz_ridgelines", "viz_circle", "gl_chroma", "lt_streaks"),
            listOf("gl_zoomblur", "lt_strobe"),
        ),
        "retro" to Recipe(
            listOf("cam_kenburns" to 1f, "cg_duotone" to 0.5f, "gl_scan" to 0.5f, "ft_grain" to 0.5f),
            listOf("viz_horizon"), listOf("viz_terrain", "viz_mirror"), listOf("viz_terrain", "lt_streaks", "viz_mirror"), listOf("viz_terrain", "viz_mirror", "gl_chroma", "lt_streaks"),
            listOf("gl_rgbbands", "lt_sweep"),
        ),
        "tape" to Recipe(
            listOf("cam_float" to 1f, "cg_dream" to 0.5f, "gl_vhs" to 0.9f, "ft_tape" to 0.9f, "ft_dust" to 0.6f, "lt_vignette" to 0.9f),
            listOf("fx_dust"), listOf("viz_bars", "fx_dust"), listOf("viz_bars", "gl_rgbbands"), listOf("viz_bars", "gl_rgbbands", "lt_bloom"),
            listOf("gl_rgbbands", "gl_blocks"),
        ),
        "cinematic" to Recipe(
            listOf("cam_kenburns" to 1f, "cg_split" to 0.8f, "ft_halation" to 0.7f, "ft_grain" to 0.6f, "ft_bars" to 1f, "lt_vignette" to 0.8f),
            listOf("fx_dust", "fx_fog"), listOf("fx_fog", "lt_leaks"), listOf("lt_leaks", "lt_streaks"), listOf("lt_streaks", "lt_leaks", "lt_bloom"),
            listOf("lt_sweep", "gl_zoomblur"),
        ),
        "energetic" to Recipe(
            listOf("cam_beatzoom" to 0.7f, "lt_bloom" to 0.9f, "lt_vignette" to 0.7f),
            listOf("viz_wave", "viz_bars"), listOf("viz_bars", "viz_mirror"), listOf("viz_circle", "viz_bars", "lt_strobe"), listOf("viz_laser", "viz_terrain", "viz_circle", "lt_strobe", "gl_zoomblur"),
            listOf("gl_zoomblur", "gl_shock", "lt_strobe"),
        ),
        "glitch" to Recipe(
            listOf("cam_beatzoom" to 0.8f, "cam_shake" to 0.5f, "cg_hue" to 0.6f, "gl_scan" to 0.4f),
            listOf("viz_wave"), listOf("viz_wave", "gl_rgbbands"), listOf("gl_blocks", "gl_rgbbands", "viz_wave"), listOf("gl_blocks", "gl_chroma", "viz_wave", "lt_neon"),
            listOf("gl_blocks", "gl_rgbbands"),
        ),
        "aurora" to Recipe(
            listOf("cam_kenburns" to 1f, "cg_night" to 0.5f, "lt_bloom" to 0.6f, "lt_vignette" to 0.7f),
            listOf("fx_stars", "fx_aurora"), listOf("fx_aurora", "viz_hills", "fx_stars"), listOf("fx_aurora", "viz_ribbon"), listOf("fx_aurora", "viz_ribbon", "fx_shooting", "lt_bloom"),
            listOf("lt_sweep", "gl_shock"),
        ),
        "warm" to Recipe(
            listOf("cam_float" to 1f, "cg_grade" to 0.9f, "lt_flicker" to 1f, "ft_grain" to 0.5f, "lt_vignette" to 1f),
            listOf("fx_embers", "fx_fog"), listOf("fx_embers", "lt_dreamglow"), listOf("fx_embers", "lt_leaks"), listOf("fx_embers", "lt_bloom", "lt_leaks"),
            listOf("lt_sweep"),
        ),
        "winter" to Recipe(
            listOf("cam_float" to 1f, "cg_night" to 0.4f, "lt_dreamglow" to 0.7f, "lt_vignette" to 0.7f),
            listOf("fx_snow"), listOf("fx_snow", "fx_fog"), listOf("fx_snow", "fx_sparkle"), listOf("fx_snow", "fx_sparkle", "lt_bloom"),
            listOf("lt_sweep"),
        ),
        "minimal" to Recipe(
            listOf("cam_float" to 1f, "cg_grade" to 0.8f, "lt_vignette" to 0.6f),
            listOf("viz_wavering"), listOf("viz_wavering"), listOf("viz_ridgelines", "viz_rings"), listOf("viz_ridgelines", "viz_wavering", "lt_bloom"),
            listOf("lt_sweep"),
        ),
    )

    /** The effects the language model may choose from, with a plain description each. */
    val catalogue: List<Pair<String, String>> = listOf(
        "fx_dust" to "floating dust, calm", "fx_fireflies" to "blinking fireflies, calm night", "fx_bokeh" to "soft light discs",
        "fx_fog" to "drifting mist", "fx_rain" to "falling rain", "fx_snow" to "falling snow", "fx_stars" to "twinkling stars",
        "fx_aurora" to "northern lights", "fx_embers" to "rising sparks", "fx_sparkle" to "glitter", "fx_lightning" to "storm flashes",
        "viz_bars" to "spectrum bars", "viz_mirror" to "mirrored bars", "viz_circle" to "radial spectrum", "viz_wave" to "waveform line",
        "viz_wavering" to "waveform ring", "viz_area" to "plain glowing spectrum area", "viz_hills" to "layered misty mountains moving with the music", "viz_terrain" to "synthwave wireframe mountains and sun", "viz_ridgelines" to "stacked pulsar spectrum lines", "viz_valley" to "moonlit hills and lake", "viz_orb" to "pulsing orb", "viz_rings" to "beat ripples",
        "viz_ribbon" to "flowing ribbons", "viz_laser" to "laser beams, energetic", "viz_horizon" to "synthwave sun and grid",
        "lt_bloom" to "glow", "lt_leaks" to "film light leaks", "lt_streaks" to "cinema light streaks", "lt_strobe" to "flash on beats",
        "gl_chroma" to "colour fringing", "gl_rgbbands" to "RGB slip glitch", "gl_blocks" to "block glitch", "gl_zoomblur" to "speed zoom blur",
        "gl_shock" to "shockwave on beat", "lt_neon" to "neon outlines",
    )

    /** Gentler placement for effects that would otherwise sit on the subject's face or look too busy for lofi. */
    private val overrides: Map<String, Map<String, Float>> = mapOf(
        "viz_orb" to mapOf("cy" to 0.8f, "size" to 0.08f),
        "viz_rings" to mapOf("cy" to 0.8f, "count" to 2f, "reach" to 0.5f),
        "fx_bokeh" to mapOf("amount" to 0.28f, "size" to 0.16f),
        "viz_circle" to mapOf("radius" to 0.16f, "len" to 0.09f),
        "viz_wavering" to mapOf("radius" to 0.17f, "amp" to 0.05f),
        "fx_dust" to mapOf("amount" to 0.4f),
    )
    private val strength: Map<String, Float> = mapOf("viz_orb" to 0.6f, "viz_rings" to 0.6f, "fx_bokeh" to 0.7f, "lt_bloom" to 0.8f, "lt_dreamglow" to 0.8f, "lt_leaks" to 0.7f, "viz_circle" to 0.85f)

    private fun layer(id: String, from: Float, to: Float, intensity: Float, track: Int): Layer? {
        val def = EffectLibrary.get(id) ?: return null
        val base = LayerFactory.create(def)
        return base.copy(intensity = intensity * (strength[id] ?: 1f), from = from, to = to, track = track, auto = true, p = base.p + (overrides[id] ?: emptyMap()))
    }

    /** Lane (stacking) for each kind of effect so that the same sort of effect shares a row. */
    private fun laneFor(id: String): Int {
        val d = EffectLibrary.get(id) ?: return 20
        return when {
            d.category == "Camera" -> 0
            d.category == "Colour" -> 1
            d.category == "Film" || d.category == "Depth" -> 2
            d.category == "Atmosphere" -> 10
            d.category == "Visualizer" || d.category == "Retro" -> 11
            d.category == "Light" -> 12
            else -> 13
        }
    }

    /**
     * [picks] optionally overrides the ids chosen for each section (index → ids), e.g. from a language model.
     * Returns the new auto-placed clips; the caller keeps the user's own.
     */
    fun arrange(project: Project, ft: FeatureTrack, summary: MusicSummary, plan: DirectorPlan, picks: Map<Int, List<String>> = emptyMap(), accent: String? = null, brief: Brief? = null): List<Layer> {
        val rec = recipes[brief?.style ?: plan.style] ?: recipes.getValue("calm")
        val dur = max(project.durationS, ft.durationS)
        val rng = Random(project.id.hashCode().toLong() xor plan.style.hashCode().toLong())
        val out = ArrayList<Layer>()
        // always-on base
        rec.base.forEach { (id, inten) -> layer(id, 0f, -1f, inten, laneFor(id))?.let { out.add(it) } }

        // per-section picks
        val perSection = summary.sections.mapIndexed { i, s ->
            picks[i]?.filter { EffectLibrary.get(it) != null }?.takeIf { it.isNotEmpty() } ?: run {
                val pool = when (s.label) {
                    "quiet/calm", "outro" -> rec.quiet
                    "building" -> rec.building
                    "loud/drop" -> rec.loud
                    else -> rec.steady
                }
                val n = if (s.label == "loud/drop") min(pool.size, 3) else min(pool.size, 2)
                // keep a stable core and rotate the last one for variety
                pool.take(max(1, n - 1)) + pool.drop(max(1, n - 1)).let { if (it.isEmpty()) emptyList() else listOf(it[rng.nextInt(it.size)]) }
            }
        }
        // merge identical consecutive picks into long clips
        val open = HashMap<String, Float>()
        for (i in summary.sections.indices) {
            val s = summary.sections[i]
            val ids = perSection[i].toSet()
            for (id in open.keys.toList()) if (id !in ids) { out.add(layer(id, open.remove(id)!!, s.startS, 0.85f, laneFor(id)) ?: continue) }
            for (id in ids) if (id !in open) open[id] = s.startS
        }
        for ((id, start) in open) layer(id, start, -1f, 0.85f, laneFor(id))?.let { out.add(it.copy(to = dur)) }

        // loud parts get a camera punch
        summary.sections.forEach { s ->
            if (s.label == "loud/drop") layer("cam_beatzoom", s.startS, s.endS, 0.8f, 0)?.let { out.add(it) }
        }
        // accents where the energy changes a lot
        val med = summary.sections.map { it.energy }.sorted().let { if (it.isEmpty()) 0.5f else it[it.size / 2] }.coerceAtLeast(0.05f)
        val acc = rec.accent
        for (i in 1 until summary.sections.size) {
            val a = summary.sections[i - 1]; val b = summary.sections[i]
            if (abs(b.energy - a.energy) > 0.25f * med && b.startS > 4f) {
                val id = accent?.takeIf { EffectLibrary.get(it) != null } ?: acc[rng.nextInt(acc.size)]
                layer(id, max(0f, b.startS - 0.4f), min(dur, b.startS + 0.9f), 0.9f, 13)?.let { out.add(it) }
            }
        }
        // things the user's request asked for: put each where it makes sense for this music
        if (brief != null) {
            val secs = summary.sections
            fun spans(pred: (Section) -> Boolean): List<Pair<Float, Float>> {
                val res = ArrayList<Pair<Float, Float>>(); var start: Float? = null; var end = 0f
                for (sc in secs) { if (pred(sc)) { if (start == null) start = sc.startS; end = sc.endS } else if (start != null) { res.add(start to end); start = null } }
                if (start != null) res.add(start to end)
                return res
            }
            for (id in brief.fx) {
                if (out.any { it.fx == id }) continue
                val def = EffectLibrary.get(id) ?: continue
                val clips = when (def.category) {
                    "Camera", "Colour", "Film", "Depth", "Atmosphere" -> listOf(0f to dur)
                    "Light" -> if (id == "lt_strobe" || id == "lt_flare") spans { it.label == "loud/drop" || it.label == "building" }.ifEmpty { listOf(0f to dur) } else listOf(0f to dur)
                    "Visualizer", "Retro" -> spans { it.label != "quiet/calm" && it.label != "outro" }.ifEmpty { listOf(0f to dur) }
                    else -> spans { it.label == "loud/drop" }.ifEmpty { secs.maxByOrNull { it.energy }?.let { listOf(it.startS to it.endS) } ?: listOf(0f to dur) }
                }
                val inten = when (def.category) { "Atmosphere", "Light" -> 0.75f; "Distort" -> 0.8f; else -> 0.9f }
                clips.forEach { (a, b) -> layer(id, a, if (b >= dur - 0.5f) dur else b, inten, laneFor(id))?.let { out.add(it) } }
            }
        }
        // colours and overall strength from the request
        val m = brief?.intensity ?: 1f
        val cols = brief?.colors.orEmpty()
        return out.map { l ->
            var n = l
            if (m != 1f) n = n.copy(intensity = (n.intensity * m).coerceIn(0.2f, 1.4f))
            if (cols.isNotEmpty() && (EffectLibrary.get(l.fx)?.colors ?: 0) > 0) n = n.copy(c1 = cols[0], c2 = cols.getOrElse(1) { cols[0] })
            n
        }
    }

    /** A built-in intro as a media clip; the title defaults to the project name. */
    fun introClip(id: String, title: String): MediaClip? {
        val def = Intros.byId[id] ?: return null
        return MediaClip(uri = def.uri, lenS = def.lenS, title = title)
    }

    /** Picks a transition that suits the music at a cut and how long it should last. */
    private fun transitionFor(label: String, rng: Random, last: String?): Pair<String, Float> {
        val pool = when (label) {
            "loud/drop" -> Transitions.loud
            "building" -> listOf("zoom", "wipe", "slide_left", "slide_right", "zoom_blur", "slide_up", "ripple")
            else -> Transitions.calm + listOf("dip_black", "iris", "dissolve")
        }.filter { it != last }.ifEmpty { Transitions.calm }
        val id = pool[rng.nextInt(pool.size)]
        val len = when (label) { "loud/drop" -> 0.5f; "building" -> 0.8f; else -> 1.4f }
        return id to len
    }

    /**
     * Re-cuts the picture track to the music: pictures/videos take turns, cutting where the music changes (on a beat),
     * each cut gets a transition that fits the energy there. [extra] are more pictures to rotate in. With a single
     * picture there is nothing to cut between, so only the optional intro is added.
     */
    fun autoCut(project: Project, summary: MusicSummary, ft: FeatureTrack, extra: List<String> = emptyList(), introId: String? = null, title: String = ""): List<MediaClip> {
        val dur = max(project.durationS, ft.durationS)
        val existing = project.media.filter { !it.isIntro }
        val srcs = ArrayList<MediaClip>()
        existing.forEach { c -> if (srcs.none { it.uri == c.uri }) srcs.add(c) }
        extra.forEach { u -> if (srcs.none { it.uri == u }) srcs.add(MediaClip(uri = u)) }
        val intro = introId?.let { introClip(it, title.ifBlank { project.name }) } ?: project.media.firstOrNull { it.isIntro }
        if (srcs.isEmpty() || dur < 1f) return project.media
        val rng = Random(project.id.hashCode().toLong() xor 0x5EED)
        val out = ArrayList<MediaClip>()
        val introLen = intro?.lenS ?: 0f
        if (srcs.size == 1) {
            val c = srcs[0]
            if (intro != null) {
                out.add(intro.copy(transition = "none"))
                out.add(c.copy(startS = introLen, lenS = max(1f, dur - introLen), transition = "dip_black", transS = 1.0f, id = java.util.UUID.randomUUID().toString().take(8)))
            } else out.add(c.copy(lenS = max(dur, 1f)))
            return out
        }
        // where to cut
        val n = max(srcs.size, (dur / 40f).toInt()).coerceAtMost(18)
        val len = dur / n
        val cuts = ArrayList<Float>()
        val starts = summary.sections.map { it.startS }.filter { it > 4f && it < dur - 4f }
        var t = 0f
        while (cuts.size < n - 1) {
            val want = t + len
            if (want > dur - len * 0.4f) break
            val near = starts.filter { it > t + len * 0.55f && abs(it - want) < len * 0.4f }.minByOrNull { abs(it - want) }
            var c = near ?: want
            val beat = ft.beatTimes.minByOrNull { abs(it - c) }
            if (beat != null && abs(beat - c) < 0.6f) c = beat
            cuts.add(c); t = c
        }
        val bounds = listOf(0f) + cuts + listOf(dur)
        var last: String? = null
        var order = srcs.shuffled(rng)
        var prevUri: String? = null
        for (i in 0 until bounds.size - 1) {
            if (i > 0 && i % order.size == 0) order = srcs.shuffled(rng).let { o -> if (o.first().uri == prevUri && o.size > 1) o.drop(1) + o.first() else o }
            val src = order[i % order.size]
            prevUri = src.uri
            val label = summary.sections.lastOrNull { it.startS <= bounds[i] + 0.5f }?.label ?: "steady"
            val (tid, tlen) = if (i == 0) "none" to 0.9f else transitionFor(label, rng, last)
            if (i > 0) last = tid
            out.add(src.copy(id = java.util.UUID.randomUUID().toString().take(8), startS = bounds[i], lenS = bounds[i + 1] - bounds[i], inS = 0f, transition = tid, transS = tlen))
        }
        if (intro != null) {
            val first = out.first()
            out[0] = first.copy(lenS = max(1f, first.lenS - introLen), transition = "dip_black", transS = 1.0f)
            out.add(0, intro.copy(transition = "none"))
        }
        // back to back
        var at = 0f
        return out.map { c -> c.copy(startS = at).also { at += c.lenS } }
    }

    /** Prompt for a language model: choose effects per group of sections. Returns null when there is nothing to choose. */
    fun prompt(summary: MusicSummary, style: String, groups: Int = 12): Pair<String, List<IntRange>>? {
        if (summary.sections.isEmpty()) return null
        val n = summary.sections.size
        val g = min(groups, n)
        val ranges = (0 until g).map { (it * n / g) until max((it + 1) * n / g, it * n / g + 1) }
        val sb = StringBuilder()
        sb.append("Style: $style. Tempo ${summary.bpm.toInt()} BPM.\nEffects (id: what it looks like):\n")
        catalogue.forEach { (id, d) -> sb.append("$id: $d\n") }
        sb.append("Music sections (number: loudness%/bass%, mood):\n")
        ranges.forEachIndexed { k, r ->
            val e = r.map { summary.sections[it].energy }.average(); val b = r.map { summary.sections[it].bass }.average()
            val label = summary.sections[r.first].label
            sb.append("${k + 1}: ${(e * 100).toInt()}/${(b * 100).toInt()} $label\n")
        }
        sb.append("For EACH numbered section choose 1 to 3 effect ids that fit its mood: quiet = gentle ambience, loud = bold visualizers and light. ")
        sb.append("Vary them between sections. Reply with JSON only: {\"sections\":[{\"n\":1,\"fx\":[\"id\",\"id\"]}],\"accent\":\"id\"}")
        return sb.toString() to ranges
    }

    fun parsePicks(raw: String, ranges: List<IntRange>): Pair<Map<Int, List<String>>, String?>? {
        val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return runCatching {
            val j = JSONObject(raw.substring(a, b + 1))
            val arr = j.getJSONArray("sections")
            val ids = catalogue.map { it.first }.toSet()
            val picks = HashMap<Int, List<String>>()
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                val n = o.optInt("n", k + 1) - 1
                val fx = o.optJSONArray("fx")?.let { f -> List(f.length()) { f.getString(it) } }?.filter { it in ids }?.distinct()?.take(3) ?: continue
                if (n in ranges.indices && fx.isNotEmpty()) ranges[n].forEach { picks[it] = fx }
            }
            if (picks.isEmpty()) return null
            picks to j.optString("accent").takeIf { it in ids }
        }.getOrNull()
    }
}

/** Runs the arranger with the best brain available: cloud AI, the on-device model, or plain rules. [notes] says exactly what happened. */
class AutoArranger(private val g: Graph, private val llm: LocalLlm, private val cloud: CloudAi) {
    class Result(val layers: List<Layer>, val plan: DirectorPlan, val by: String, val notes: List<String> = emptyList())

    suspend fun arrange(project: Project, ft: FeatureTrack, useAi: Boolean = true, prompt: String = ""): Result {
        val summary = Director.summarize(ft)
        val planner = g.settings.value.planner.let { if (it == "claude") "cloud" else it }
        val notes = ArrayList<String>()
        var plan = Director.rules(summary)
        var brief = StylePrompt.keywordBrief(prompt)
        var by = if (prompt.isBlank()) "rules" else "keywords + rules"
        if (useAi) when (planner) {
            "local" -> when {
                llm.crashedLastTime -> notes.add("On-device AI is switched off after it stopped the app. Re-enable it in Settings → AI. Used rules.")
                !llm.available -> notes.add("No on-device model is installed (Settings → AI → download one). Used rules.")
                else -> {
                    if (prompt.isNotBlank()) {
                        val (sys, user) = StylePrompt.localPrompt(prompt)
                        val answer = llm.ask(sys, user, 300)
                        val parsed = answer?.let { StylePrompt.parseLocal(it) }
                        when {
                            answer == null -> notes.add("The on-device AI didn't answer (see the activity log). Used your keywords and rules.")
                            parsed == null -> notes.add("The on-device AI's reply wasn't usable. Used your keywords and rules.")
                            else -> { brief = brief.merge(parsed); by = "on-device AI" }
                        }
                    } else {
                        plan = llm.direct(summary)
                        by = if (plan.source == "local AI") "on-device AI" else "rules"
                        if (plan.source != "local AI") notes.add("The on-device AI's reply wasn't usable. Used rules.")
                    }
                }
            }
            "cloud" -> if (!cloud.configured) notes.add("No cloud AI key yet (Settings → AI). Used rules.") else {
                try {
                    val style = brief.style ?: plan.style
                    val (p, ranges) = StylePrompt.cloudPrompt(prompt, summary, style) ?: throw AiException("The music is too short to arrange.")
                    val raw = cloud.text("You are an editor placing visual effects on a music video timeline. Reply with one JSON object only.", p, 1800)
                    val parsed = StylePrompt.parseCloud(raw, ranges)
                    if (parsed == null) notes.add("The cloud AI's reply wasn't usable. Used your keywords and rules.") else { brief = brief.merge(parsed); by = "cloud AI" }
                } catch (e: AiException) {
                    notes.add((e.message ?: "The cloud AI didn't answer.") + " Used your keywords and rules.")
                }
            }
        }
        brief.style?.let { st -> plan = plan.copy(style = st, lookName = Director.STYLES.getValue(st)) }
        Director.applyCurve(ft, summary, plan)
        val layers = AutoTimeline.arrange(project, ft, summary, plan, brief.picks, brief.accent, brief)
        return Result(layers, plan, by, notes)
    }
}
