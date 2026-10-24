package com.lofiautopilot.studio.ai

import com.lofiautopilot.studio.vfx.EffectLibrary
import org.json.JSONObject

/** What the user asked for in words, boiled down to things the arranger understands. */
data class Brief(
    val style: String? = null,
    /** Effects the user's request calls for (effect ids). They are placed where they make sense for the music. */
    val fx: List<String> = emptyList(),
    /** ARGB colours for glow/visualizer layers. */
    val colors: List<Int> = emptyList(),
    val intensity: Float = 1f,
    /** Cloud AI only: effects per section index, and an accent effect for big changes. */
    val picks: Map<Int, List<String>> = emptyMap(),
    val accent: String? = null,
    val source: String = "",
) {
    fun merge(o: Brief) = Brief(
        style = o.style ?: style, fx = (o.fx + fx).distinct(), colors = o.colors.ifEmpty { colors },
        intensity = if (o.intensity != 1f) o.intensity else intensity, picks = o.picks.ifEmpty { picks }, accent = o.accent ?: accent,
        source = listOf(source, o.source).filter { it.isNotBlank() }.joinToString(" + "),
    )
    val isEmpty get() = style == null && fx.isEmpty() && colors.isEmpty() && intensity == 1f && picks.isEmpty()
}

object StylePrompt {
    /** Short names (cheap for a small model to write) → effect ids. */
    val SHORT: Map<String, String> = linkedMapOf(
        "dust" to "fx_dust", "fireflies" to "fx_fireflies", "bokeh" to "fx_bokeh", "fog" to "fx_fog", "rain" to "fx_rain", "glass" to "fx_glass",
        "snow" to "fx_snow", "stars" to "fx_stars", "aurora" to "fx_aurora", "embers" to "fx_embers", "sparkle" to "fx_sparkle",
        "lightning" to "fx_lightning", "shooting" to "fx_shooting", "matrix" to "fx_matrix",
        "bars" to "viz_bars", "mirror" to "viz_mirror", "circle" to "viz_circle", "wave" to "viz_wave", "ring" to "viz_wavering",
        "hills" to "viz_hills", "area" to "viz_area", "terrain" to "viz_terrain", "ridges" to "viz_ridgelines", "valley" to "viz_valley", "orb" to "viz_orb", "ripples" to "viz_rings", "ribbon" to "viz_ribbon", "laser" to "viz_laser", "horizon" to "viz_horizon",
        "bloom" to "lt_bloom", "dreamglow" to "lt_dreamglow", "leaks" to "lt_leaks", "streaks" to "lt_streaks", "strobe" to "lt_strobe",
        "neon" to "lt_neon", "flare" to "lt_flare", "rays" to "lt_rays", "flicker" to "lt_flicker", "vignette" to "lt_vignette",
        "chroma" to "gl_chroma", "rgbslip" to "gl_rgbbands", "blocks" to "gl_blocks", "zoomblur" to "gl_zoomblur", "shock" to "gl_shock",
        "vhs" to "gl_vhs", "scanlines" to "gl_scan", "crt" to "gl_crt", "kaleido" to "gl_kaleido",
        "grain" to "ft_grain", "tape" to "ft_tape", "halation" to "ft_halation", "sketch" to "ft_sketch",
        "bw" to "cg_bw", "nightgrade" to "cg_night", "dream" to "cg_dream", "duotone" to "cg_duotone", "hue" to "cg_hue",
        "float" to "cam_float", "kenburns" to "cam_kenburns", "beatzoom" to "cam_beatzoom", "shake" to "cam_shake", "sway" to "cam_sway",
    )
    val STYLES = Director.STYLES.keys.toList()

    private class Rule(val rx: Regex, val style: String? = null, val fx: List<String> = emptyList(), val intensity: Float? = null)

    private fun r(p: String, style: String? = null, fx: List<String> = emptyList(), i: Float? = null) = Rule(Regex(p, RegexOption.IGNORE_CASE), style, fx.map { SHORT[it] ?: it }, i)

    private val rules = listOf(
        r("rain|storm|drizzle|window", "rainy", listOf("rain", "glass")),
        r("snow|winter|frost|cold", "winter", listOf("snow")),
        r("star|galaxy|night sky|space|cosmic", null, listOf("stars", "shooting")),
        r("aurora|northern light", "aurora", listOf("aurora")),
        r("fire|campfire|ember|cozy|cosy|warm|fireplace", "warm", listOf("embers", "flicker")),
        r("dust|dusty|particles", null, listOf("dust")),
        r("fog|mist|haze|smoke", null, listOf("fog")),
        r("firefl", null, listOf("fireflies")),
        r("bokeh|city lights|blurry lights", null, listOf("bokeh")),
        r("neon|cyber|tron|futur", "night", listOf("neon", "bloom", "chroma")),
        r("glitch|digital|distort|broken|hack", "glitch", listOf("blocks", "rgbslip", "chroma")),
        r("retro|synthwave|80s|outrun|vaporwave|miami", "retro", listOf("horizon", "scanlines")),
        r("vhs|cassette|tape|90s|nostalg|old school|analog", "tape", listOf("vhs", "tape", "grain")),
        r("film|cinema|movie|cinematic|epic", "cinematic", listOf("streaks", "halation", "grain")),
        r("dream|soft|pastel|ethereal|magical", "dreamy", listOf("dream", "dreamglow", "bokeh")),
        r("calm|chill|relax|sleep|slow|peace|quiet|gentle|mellow", "calm", i = 0.8f),
        r("energetic|club|party|dance|hype|intense|aggressive|loud|banger", "energetic", listOf("laser", "circle", "strobe", "zoomblur"), 1.15f),
        r("minimal|clean|simple|subtle", "minimal", i = 0.7f),
        r("mountain|hills?\\b|peaks?\\b|landscape|ridge|highland", null, listOf("hills")),
        r("terrain|wireframe", null, listOf("terrain")),
        r("moon|moonlit|lake|valley", null, listOf("valley")),
        r("pulsar|joy division|unknown pleasures|stacked lines", null, listOf("ridges")),
        r("bars|equali[sz]er|spectrum", null, listOf("bars")),
        r("radial|circular|circle", null, listOf("circle")),
        r("oscillo|waveform|sound wave", null, listOf("wave", "ring")),
        r("orb|pulse|pulsing|heartbeat", null, listOf("orb", "ripples")),
        r("laser|beam", null, listOf("laser")),
        r("ribbon|flow", null, listOf("ribbon")),
        r("black and white|monochrome|noir|b&w|greyscale|grayscale", null, listOf("bw")),
        r("sketch|drawing|pencil|cartoon", null, listOf("sketch")),
        r("kaleidoscope|trippy|psychedelic|acid", null, listOf("kaleido", "hue")),
        r("glow|bloom|shine|radiant", null, listOf("bloom")),
        r("sparkle|glitter|magic", null, listOf("sparkle")),
        r("lightning|thunder", null, listOf("lightning")),
        r("dark|moody|gloomy|midnight|late night|night", null, listOf("nightgrade", "vignette")),
        r("punch|bounce|kick|beat zoom|pump", null, listOf("beatzoom")),
        r("shake|rumble|vibrat", null, listOf("shake")),
        r("slow zoom|push in|documentary", null, listOf("kenburns")),
        r("flare|sunlight|sun rays|god rays", null, listOf("flare", "rays")),
    )

    private val colorWords = listOf(
        "purple|violet|lavender|lilac" to 0xFF9B5CFF, "pink|magenta|rose|fuchsia" to 0xFFFF5CC8, "cyan|teal|turquoise|aqua" to 0xFF22D3EE,
        "blue|navy|azure" to 0xFF3B82F6, "green|lime|emerald|mint" to 0xFF4ADE80, "red|crimson|scarlet" to 0xFFFF4D4D,
        "orange|sunset|amber" to 0xFFFF9F45, "gold|golden|yellow" to 0xFFFFD166, "white|silver" to 0xFFFFFFFF,
    ).map { Regex("\\b(${it.first})\\b", RegexOption.IGNORE_CASE) to it.second.toInt() }

    /** Works offline and instantly. Also used to back up an AI answer that came back thin. */
    fun keywordBrief(prompt: String): Brief {
        if (prompt.isBlank()) return Brief()
        var style: String? = null
        val fx = ArrayList<String>()
        var intensity = 1f
        for (rule in rules) if (rule.rx.containsMatchIn(prompt)) {
            if (style == null && rule.style != null) style = rule.style
            fx.addAll(rule.fx)
            rule.intensity?.let { intensity = it }
        }
        val colors = ArrayList<Int>()
        Regex("#[0-9a-fA-F]{6}\\b").findAll(prompt).forEach { m -> runCatching { colors.add(android.graphics.Color.parseColor(m.value)) } }
        for ((rx, col) in colorWords) if (rx.containsMatchIn(prompt) && col !in colors) colors.add(col)
        return Brief(style, fx.distinct(), colors.take(2), intensity, source = "keywords")
    }

    // ---------- on-device model: one tiny question, a tiny JSON answer ----------

    fun localPrompt(request: String): Pair<String, String> {
        val sys = "You turn a video style request into JSON. Reply with JSON only."
        val user = buildString {
            append("Request: ${request.take(200)}\n")
            append("Effects: ${SHORT.keys.take(44).joinToString(",")}\n")
            append("Styles: ${STYLES.joinToString(",")}\n")
            append("Example: Request: purple neon night with bass bars -> {\"style\":\"night\",\"fx\":[\"bars\",\"neon\",\"bloom\"],\"colors\":[\"#9b5cff\"],\"intensity\":1.0}\n")
            append("Request: ${request.take(200)} ->")
        }
        return sys to user
    }

    /** Forgiving: a small model may wrap the JSON in chatter or break it, so fall back to scanning for known words. */
    fun parseLocal(answer: String): Brief? {
        val style = Regex("\"style\"\\s*:\\s*\"([a-z]+)\"").find(answer)?.groupValues?.get(1)?.takeIf { it in STYLES }
            ?: STYLES.firstOrNull { Regex("\\b$it\\b", RegexOption.IGNORE_CASE).containsMatchIn(answer.substringBefore("\"fx\"")) }
        val fxBlock = Regex("\"fx\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(answer)?.groupValues?.get(1) ?: answer
        val fx = Regex("[a-z]+").findAll(fxBlock.lowercase()).map { it.value }.filter { it in SHORT }.distinct().map { SHORT.getValue(it) }.take(8).toList()
        val colors = Regex("#[0-9a-fA-F]{6}").findAll(answer).mapNotNull { runCatching { android.graphics.Color.parseColor(it.value) }.getOrNull() }.take(2).toList()
        val inten = Regex("\"intensity\"\\s*:\\s*([0-9.]+)").find(answer)?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(0.5f, 1.4f) ?: 1f
        if (style == null && fx.isEmpty() && colors.isEmpty()) return null
        return Brief(style, fx, colors, inten, source = "on-device AI")
    }

    // ---------- cloud model: the full picture, section by section ----------

    fun cloudPrompt(request: String, summary: MusicSummary, style: String): Pair<String, List<IntRange>>? {
        val (base, ranges) = AutoTimeline.prompt(summary, style) ?: return null
        val head = buildString {
            if (request.isNotBlank()) append("The user's request for the look: \"${request.take(400)}\". Follow it closely.\n")
            append("Also choose a style from: ${STYLES.joinToString(", ")}, an overall intensity 0.5-1.3 and up to two colours as hex that suit the request.\n")
        }
        val tail = "\nExtend the JSON with \"style\":\"...\",\"intensity\":1.0,\"colors\":[\"#rrggbb\"],\"base\":[\"id\"] (effects that run the whole video, from the list)."
        return (head + base + tail) to ranges
    }

    fun parseCloud(raw: String, ranges: List<IntRange>): Brief? {
        val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return runCatching {
            val j = JSONObject(raw.substring(a, b + 1))
            val ids = AutoTimeline.catalogue.map { it.first }.toSet()
            val (picks, accent) = AutoTimeline.parsePicks(raw, ranges) ?: (emptyMap<Int, List<String>>() to null)
            val base = j.optJSONArray("base")?.let { arr -> List(arr.length()) { arr.getString(it) } }?.filter { it in ids || EffectLibrary.get(it) != null }.orEmpty()
            val colors = j.optJSONArray("colors")?.let { arr -> List(arr.length()) { arr.getString(it) } }.orEmpty().mapNotNull { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }.take(2)
            val style = j.optString("style").lowercase().takeIf { it in STYLES }
            Brief(style, base, colors, j.optDouble("intensity", 1.0).toFloat().coerceIn(0.5f, 1.4f), picks, accent, "cloud AI")
        }.getOrNull()
    }
}
