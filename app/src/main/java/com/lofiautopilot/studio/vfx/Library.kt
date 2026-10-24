package com.lofiautopilot.studio.vfx

object EffectLibrary {
    val all: List<FxDef> = FxVisualizers.all + FxAtmosphere.all + FxLight.all + FxColor.all + FxGlitch.all + FxFilm.all + FxCamera.all

    val byId: Map<String, FxDef> = all.associateBy { it.id }

    /** Display order for the effect browser. */
    val categories: List<String> = listOf("Camera", "Visualizer", "Atmosphere", "Light", "Colour", "Distort", "Retro", "Film", "Depth")

    fun inCategory(c: String) = all.filter { it.category == c }

    fun get(id: String): FxDef? = byId[id]

    fun search(q: String): List<FxDef> {
        val s = q.trim().lowercase()
        if (s.isEmpty()) return all
        return all.filter { (it.name + " " + it.category + " " + it.keywords + " " + it.blurb).lowercase().contains(s) }
    }

    fun newLayer(id: String): Layer? = get(id)?.let { LayerFactory.create(it) }
}

/** Ready-made looks. Each is a normal Look, so users can open and tweak every layer. */
object Presets {
    private fun L(fx: String, intensity: Float = 1f, react: React? = null, amt: Float? = null, blend: Blend? = null,
                  c1: Int? = null, c2: Int? = null, opacity: Float = 1f, vararg p: Pair<String, Float>): Layer {
        val def = EffectLibrary.byId.getValue(fx)
        val base = LayerFactory.create(def)
        return base.copy(
            intensity = intensity, opacity = opacity, react = react ?: base.react, reactAmt = amt ?: base.reactAmt,
            blend = blend ?: base.blend, c1 = c1, c2 = c2, p = base.p + p.toMap(),
        )
    }

    val builtin: List<Look> = listOf(
        Look(
            "Cozy Lofi", listOf(
                L("cam_float"), L("cg_dream", 0.7f), L("lt_dreamglow", 0.8f), L("fx_dust", 0.8f),
                L("ft_tape", 0.8f), L("ft_grain", 0.7f), L("lt_vignette", 0.8f),
            ), note = "Soft, warm and slow. The classic study-beats look.",
        ),
        Look(
            "Rainy Window", listOf(
                L("cam_float"), L("cg_night", 0.5f), L("fx_glass", 1f), L("fx_rain", 0.8f), L("lt_bloom", 0.7f),
                L("ft_grain", 0.5f), L("lt_vignette", 0.9f), L("viz_area", 0.45f, opacity = 0.55f),
            ), note = "Raindrops on glass with a low glowing spectrum.",
        ),
        Look(
            "Neon Night", listOf(
                L("cam_beatzoom"), L("cg_split", 0.9f), L("lt_bloom", 1.1f), L("viz_bars", 1f), L("viz_circle", 0.0f).copy(on = false),
                L("gl_chroma", 0.6f), L("fx_fireflies", 0.8f), L("lt_vignette", 0.8f),
            ), note = "Glow, bars and chromatic edges for night-city pictures.",
        ),
        Look(
            "Synth Sunset", listOf(
                L("cam_kenburns"), L("cg_duotone", 0.6f, c1 = 0xFF2A0B5E.toInt(), c2 = 0xFFFF7ACB.toInt()), L("viz_horizon", 0.9f),
                L("lt_streaks", 0.8f), L("gl_scan", 0.6f), L("viz_mirror", 0.7f), L("ft_grain", 0.5f),
            ), note = "Retro sun, grid and scanlines.",
        ),
        Look(
            "Dream Pulse", listOf(
                L("cam_beatzoom", 0.6f), L("cam_sway"), L("cg_dream", 0.8f),
                L("viz_orb", 0.5f).let { it.copy(p = it.p + mapOf("cy" to 0.8f, "size" to 0.09f)) },
                L("viz_rings", 0.45f).let { it.copy(p = it.p + mapOf("cy" to 0.8f, "count" to 2f)) },
                L("fx_bokeh", 0.35f), L("lt_dreamglow", 0.8f), L("lt_leaks", 0.5f),
            ), note = "A breathing orb, ripples and bokeh.",
        ),
        Look(
            "VHS Memory", listOf(
                L("cam_float"), L("cg_dream", 0.5f), L("gl_vhs", 1f), L("ft_tape", 0.9f), L("ft_dust", 0.7f),
                L("gl_rgbbands", 0.6f), L("viz_bars", 0.6f, opacity = 0.7f), L("lt_vignette", 0.9f),
            ), note = "90s tape. Wobble, noise and tracking.",
        ),
        Look(
            "Cyber Glitch", listOf(
                L("cam_shake"), L("cam_beatzoom"), L("cg_hue", 0.8f), L("gl_blocks", 1f), L("gl_rgbbands", 1f),
                L("gl_chroma", 1f), L("viz_wave", 1f), L("lt_neon", 0.5f), L("gl_scan", 0.5f),
            ), note = "Hard-hitting, beat-triggered glitching.",
        ),
        Look(
            "Aurora Sky", listOf(
                L("cam_kenburns"), L("cg_night", 0.5f), L("fx_stars", 1f), L("fx_aurora", 1f), L("fx_shooting", 0.8f),
                L("lt_bloom", 0.7f), L("viz_ribbon", 0.5f, opacity = 0.6f), L("lt_vignette", 0.7f),
            ), note = "Northern lights over a starry night.",
        ),
        Look(
            "Cinematic", listOf(
                L("cam_kenburns"), L("cg_split", 0.8f), L("lt_streaks", 0.7f), L("lt_leaks", 0.4f), L("ft_halation", 0.8f),
                L("ft_grain", 0.6f), L("ft_bars", 1f), L("lt_vignette", 0.8f),
            ), note = "Slow push, streaks, grain and letterbox.",
        ),
        Look(
            "Club Visualizer", listOf(
                L("cam_beatzoom"), L("cam_shake", 0.5f), L("lt_bloom", 1.2f), L("viz_laser", 1f), L("viz_circle", 1f),
                L("viz_bars", 0.9f), L("lt_strobe", 0.7f), L("gl_zoomblur", 0.7f), L("viz_border", 0.7f),
            ), note = "High energy: lasers, ring, bars and flashes.",
        ),
        Look(
            "Campfire Calm", listOf(
                L("cam_float"), L("cg_grade", 1f).let { it.copy(p = it.p + mapOf("temp" to 0.5f, "contrast" to 1.1f)) }, L("lt_flicker", 1f), L("fx_embers", 1f),
                L("fx_fog", 0.5f), L("lt_dreamglow", 0.6f), L("ft_grain", 0.5f), L("lt_vignette", 1f),
            ), note = "Warm flicker and rising sparks.",
        ),
        Look(
            "Winter Snow", listOf(
                L("cam_float"), L("cg_night", 0.4f), L("fx_snow", 1f), L("fx_fog", 0.4f), L("lt_dreamglow", 0.7f),
                L("fx_sparkle", 0.6f), L("lt_vignette", 0.7f),
            ), note = "Cold blue light and falling snow.",
        ),
        Look(
            "Minimal Pulse", listOf(
                L("cam_float"), L("cg_grade", 0.8f), L("viz_wavering", 0.9f), L("lt_vignette", 0.6f),
            ), note = "Just a clean ring over your picture.",
        ),
    )
}
