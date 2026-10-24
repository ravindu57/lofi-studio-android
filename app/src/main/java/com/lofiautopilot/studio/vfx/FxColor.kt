package com.lofiautopilot.studio.vfx

private fun P(key: String, label: String, min: Float, max: Float, def: Float, step: Float = 0f) =
    Param(key, label, min, max, def, step)

/** Colour grading. Every grade fades in with Intensity, so you can dial it back. */
object FxColor {
    val all: List<FxDef> = listOf(
        FxDef(
            "cg_grade", "Colour Grade", "Colour", FxKind.FILTER,
            "Exposure, contrast, saturation, warmth and tint in one place.", "🎨",
            params = listOf(
                P("exposure", "Exposure", -1f, 1f, 0f), P("contrast", "Contrast", 0.5f, 1.8f, 1.08f),
                P("saturation", "Saturation", 0f, 2f, 1.1f), P("temp", "Warmth", -1f, 1f, 0.1f),
                P("tint", "Tint", -1f, 1f, 0f), P("lift", "Lift blacks", 0f, 0.2f, 0.02f),
            ),
            react = React.OFF, keywords = "color correct brightness warm cool",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec3 c = base.rgb;
    vec3 g = c * exp2(p_exposure);
    g = (g - 0.5) * p_contrast + 0.5;
    float l = luma(g);
    g = mix(vec3(l), g, p_saturation);
    g += vec3(p_temp * 0.06, -p_tint * 0.04, -p_temp * 0.06);
    g = g * (1.0 - p_lift) + p_lift;
    return vec4(mix(c, g, clamp(uI, 0.0, 1.0)), 1.0);
}"""
        ),
        FxDef(
            "cg_hue", "Hue Drift", "Colour", FxKind.FILTER,
            "Colours slowly cycle, or jump on the beat.", "🌈",
            params = listOf(P("shift", "Shift", 0f, 1f, 0f), P("speed", "Drift speed", 0f, 1f, 0.05f), P("jump", "Beat jump", 0f, 0.5f, 0.06f), P("sat", "Saturation boost", 0.5f, 1.6f, 1.1f)),
            react = React.BEAT, reactAmt = 1f, keywords = "rainbow color cycle psychedelic",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec3 h = rgb2hsv(base.rgb);
    h.x = fract(h.x + p_shift + uTime * p_speed + uR * p_jump);
    h.y = clamp(h.y * p_sat, 0.0, 1.0);
    return vec4(mix(base.rgb, hsv2rgb(h), clamp(uI, 0.0, 1.0)), 1.0);
}"""
        ),
        FxDef(
            "cg_duotone", "Duotone", "Colour", FxKind.FILTER,
            "Paint the picture in two colours, shadows to highlights.", "🟣",
            params = listOf(P("mix", "Amount", 0f, 1f, 0.6f), P("contrast", "Contrast", 0.6f, 1.8f, 1.1f)),
            react = React.OFF, colors = 2, keywords = "two tone gradient map poster",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float l = clamp((luma(base.rgb) - 0.5) * p_contrast + 0.5, 0.0, 1.0);
    vec3 d = mix(uC1 * 0.55, uC2, l);
    return vec4(mix(base.rgb, d, p_mix * clamp(uI, 0.0, 1.0)), 1.0);
}"""
        ),
        FxDef(
            "cg_split", "Split Toning", "Colour", FxKind.FILTER,
            "Cool shadows and warm highlights for a filmic look.", "🎞️",
            params = listOf(P("balance", "Balance", -0.5f, 0.5f, 0f), P("strength", "Strength", 0f, 1f, 0.6f)),
            react = React.OFF, colors = 2, keywords = "teal orange cinematic film",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float l = luma(base.rgb);
    float t = smoothstep(0.2 + p_balance, 0.8 + p_balance, l);
    vec3 tone = mix(uC1, uC2, t);
    vec3 col = mix(base.rgb, screenBlend(base.rgb * 0.9, tone * 0.5) * 1.05, p_strength * clamp(uI, 0.0, 1.0));
    return vec4(col, 1.0);
}"""
        ),
        FxDef(
            "cg_bw", "Black & White", "Colour", FxKind.FILTER,
            "Monochrome with optional tint and punch.", "⚪",
            params = listOf(P("contrast", "Contrast", 0.6f, 2f, 1.2f), P("tint", "Tint", 0f, 1f, 0.15f)),
            react = React.OFF, colors = 1, keywords = "mono noir grey",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float l = clamp((luma(base.rgb) - 0.5) * p_contrast + 0.5, 0.0, 1.0);
    vec3 m = mix(vec3(l), l * (uC1 * 1.6 + 0.3), p_tint);
    return vec4(mix(base.rgb, m, clamp(uI, 0.0, 1.0)), 1.0);
}"""
        ),
        FxDef(
            "cg_night", "Night Mode", "Colour", FxKind.FILTER,
            "Turns a daytime picture into a moonlit one.", "🌙",
            params = listOf(P("dark", "Darkness", 0f, 1f, 0.45f), P("blue", "Blue", 0f, 1f, 0.6f)),
            react = React.OFF, colors = 1, keywords = "dark moon blue evening",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float l = luma(base.rgb);
    vec3 col = mix(base.rgb, vec3(l), 0.35);
    col *= mix(vec3(1.0), vec3(0.55, 0.65, 1.05), p_blue);
    col = mix(col, col * uC1 * 1.6, 0.25 * p_blue);
    col *= 1.0 - p_dark * 0.6;
    return vec4(mix(base.rgb, col, clamp(uI, 0.0, 1.0)), 1.0);
}"""
        ),
        FxDef(
            "cg_dream", "Pastel Dream", "Colour", FxKind.FILTER,
            "Faded blacks and candy tones. Very lofi.", "🍬",
            params = listOf(P("fade", "Fade", 0f, 0.4f, 0.14f), P("pink", "Pink / blue", -1f, 1f, 0.3f), P("sat", "Saturation", 0.6f, 1.6f, 1.0f)),
            react = React.OFF, keywords = "lofi pastel soft vaporwave",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec3 c = base.rgb;
    float l = luma(c);
    c = mix(vec3(l), c, p_sat);
    c = c * (1.0 - p_fade) + p_fade * mix(vec3(0.75, 0.6, 0.9), vec3(0.55, 0.7, 0.95), p_pink * 0.5 + 0.5);
    c = mix(c, c * vec3(1.06, 0.95, 1.08), abs(p_pink));
    return vec4(mix(base.rgb, c, clamp(uI, 0.0, 1.0)), 1.0);
}"""
        ),
        FxDef(
            "cg_bleach", "Bleach Bypass", "Colour", FxKind.FILTER,
            "Gritty, desaturated, high-contrast movie look.", "🪖",
            params = listOf(P("amount", "Amount", 0f, 1f, 0.7f)),
            react = React.OFF, keywords = "gritty war cinema harsh",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float l = luma(base.rgb);
    vec3 b = base.rgb * l * 2.0 + base.rgb * base.rgb * (1.0 - l * 2.0) ;
    b = mix(vec3(l), b, 0.75);
    return vec4(mix(base.rgb, clamp(b, 0.0, 1.0), p_amount * clamp(uI, 0.0, 1.0)), 1.0);
}"""
        ),
        FxDef(
            "cg_poster", "Posterize", "Colour", FxKind.FILTER,
            "Flat bands of colour, like a screen print.", "🖨️",
            params = listOf(P("levels", "Levels", 2f, 12f, 5f, 1f)),
            react = React.OFF, keywords = "comic cartoon flat",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float n = floor(p_levels);
    vec3 q = floor(base.rgb * n + 0.5) / n;
    return vec4(mix(base.rgb, q, clamp(uI, 0.0, 1.0)), 1.0);
}"""
        ),
    )
}
