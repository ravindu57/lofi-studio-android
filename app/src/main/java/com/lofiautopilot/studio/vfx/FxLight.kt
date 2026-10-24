package com.lofiautopilot.studio.vfx

private fun P(key: String, label: String, min: Float, max: Float, def: Float, step: Float = 0f) =
    Param(key, label, min, max, def, step)

/** Glow, flares and lighting. */
object FxLight {
    val all: List<FxDef> = listOf(
        FxDef(
            "lt_bloom", "Bloom", "Light", FxKind.FILTER,
            "Bright areas bleed a soft glow. Pulses with the music.", "🌟",
            params = listOf(
                P("threshold", "Threshold", 0.2f, 0.95f, 0.55f), P("radius", "Spread", 0.005f, 0.08f, 0.03f),
                P("strength", "Strength", 0f, 2f, 0.9f),
            ),
            react = React.ENERGY, reactAmt = 0.5f, colors = 1, keywords = "glow soft shine",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec3 acc = vec3(0.0);
    float golden = 2.39996323;
    for (int i = 0; i < 24; i++) {
        float fi = float(i);
        float r = sqrt((fi + 0.5) / 24.0) * p_radius;
        vec2 o = vec2(cos(fi * golden), sin(fi * golden)) * r * vec2(1.0 / uAsp, 1.0);
        vec3 c = src(uv + o).rgb;
        acc += c * smoothstep(p_threshold, p_threshold + 0.25, luma(c));
        vec3 c2 = src(uv + o * 2.6).rgb;
        acc += c2 * smoothstep(p_threshold, p_threshold + 0.25, luma(c2)) * 0.7;
    }
    acc /= 24.0;
    vec3 tint = mix(vec3(1.0), uC1 * 1.4 + 0.2, 0.35);
    return vec4(base.rgb + acc * tint * p_strength * AMT, 1.0);
}"""
        ),
        FxDef(
            "lt_dreamglow", "Dream Glow", "Light", FxKind.FILTER,
            "A soft-focus haze over the whole image.", "☁️",
            params = listOf(P("radius", "Softness", 0.005f, 0.06f, 0.025f), P("mix", "Amount", 0f, 1f, 0.5f)),
            react = React.ENERGY, reactAmt = 0.3f, keywords = "soft focus orton haze",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec3 b = diskBlur(uv, p_radius, 24);
    vec3 col = screenBlend(base.rgb, b * 0.9 * p_mix * AMT);
    col = mix(col, max(col, b), 0.15 * p_mix);
    return vec4(col, 1.0);
}"""
        ),
        FxDef(
            "lt_leaks", "Light Leaks", "Light", FxKind.OVERLAY,
            "Warm film-style light washing in from the edges.", "🧡",
            params = listOf(P("speed", "Speed", 0.05f, 1.5f, 0.3f), P("size", "Size", 0.2f, 1.2f, 0.6f)),
            react = React.BASS, reactAmt = 0.5f, colors = 2, defBlend = Blend.SCREEN, keywords = "film leak warm flare",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 q = centred(uv);
    float t = uTime * p_speed;
    vec2 a = vec2(-0.9 + 0.3 * sin(t * 0.7), 0.35 + 0.25 * sin(t * 0.5));
    vec2 b = vec2(0.95 + 0.25 * cos(t * 0.6), -0.4 + 0.3 * cos(t * 0.45));
    float la = exp(-length(q - a) / (p_size * 0.5));
    float lb = exp(-length(q - b) / (p_size * 0.5));
    float n = 0.75 + 0.25 * fbm(q * 2.0 + t * 0.2);
    vec3 col = uC1 * la + uC2 * lb;
    return vec4(col * n, clamp((la + lb) * 0.8 * AMT, 0.0, 1.0));
}"""
        ),
        FxDef(
            "lt_flare", "Lens Flare", "Light", FxKind.OVERLAY,
            "A cinematic flare with ghosts that drifts with the music.", "🔆",
            params = listOf(
                P("x", "Light X", 0f, 1f, 0.78f), P("y", "Light Y", 0f, 1f, 0.78f),
                P("size", "Size", 0.2f, 2f, 1f), P("drift", "Drift", 0f, 1f, 0.3f),
            ),
            react = React.BEAT, reactAmt = 0.4f, colors = 2, defBlend = Blend.ADD, keywords = "anamorphic cinema sun",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 L = vec2(p_x, p_y) + p_drift * 0.04 * vec2(sin(uTime * 0.4), cos(uTime * 0.33));
    vec2 q = centred(uv);
    vec2 lq = (L - 0.5) * vec2(uAsp, 1.0);
    vec3 col = vec3(0.0);
    float d0 = length(q - lq);
    col += uC1 * (0.05 * p_size / (d0 + 0.05));
    col += vec3(1.0) * exp(-d0 * 22.0 / p_size) * 0.9;
    col += uC2 * smoothstep(0.01 * p_size, 0.0, abs(q.y - lq.y)) * exp(-abs(q.x - lq.x) * 1.8) * 0.8;
    for (int i = 0; i < 4; i++) {
        float k = -0.45 + float(i) * 0.55;
        vec2 g = lq * (-k);
        float rg = (0.05 + 0.04 * float(i)) * p_size;
        float d = length(q - g);
        float ring = smoothstep(rg, rg * 0.85, d) * 0.12 + smoothstep(rg * 1.1, rg, d) * smoothstep(rg * 0.8, rg, d) * 0.2;
        col += mix(uC1, uC2, float(i) / 3.0) * ring;
    }
    return vec4(col, clamp(length(col) * (0.8 + 1.2 * uBeat * uR) * uI, 0.0, 1.0));
}"""
        ),
        FxDef(
            "lt_streaks", "Anamorphic Streaks", "Light", FxKind.FILTER,
            "Horizontal blue streaks from bright lights, like a cinema lens.", "🎬",
            params = listOf(
                P("threshold", "Threshold", 0.4f, 0.98f, 0.7f), P("length", "Length", 0.05f, 0.6f, 0.3f),
                P("strength", "Strength", 0f, 2f, 1f),
            ),
            react = React.ENERGY, reactAmt = 0.4f, colors = 1, keywords = "cinematic horizontal flare",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec3 acc = vec3(0.0);
    for (int i = -12; i <= 12; i++) {
        float fi = float(i);
        float w = exp(-abs(fi) * 0.18);
        vec3 c = src(uv + vec2(fi / 12.0 * p_length, 0.0)).rgb;
        acc += c * smoothstep(p_threshold, 1.0, luma(c)) * w;
    }
    acc /= 8.0;
    vec3 tint = mix(vec3(0.45, 0.65, 1.0), uC1, 0.6);
    return vec4(base.rgb + acc * tint * p_strength * AMT, 1.0);
}"""
        ),
        FxDef(
            "lt_rays", "Radial Rays", "Light", FxKind.FILTER,
            "Bright areas throw rays toward a point; great on moons and lamps.", "☀️",
            params = listOf(
                P("x", "Light X", 0f, 1f, 0.5f), P("y", "Light Y", 0f, 1f, 0.7f),
                P("length", "Length", 0.1f, 1f, 0.5f), P("threshold", "Threshold", 0.3f, 0.95f, 0.6f),
                P("strength", "Strength", 0f, 2f, 1f),
            ),
            react = React.BASS, reactAmt = 0.5f, colors = 1, keywords = "volumetric god rays moon",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 L = vec2(p_x, p_y);
    vec2 dir = (L - uv) * (p_length / 28.0);
    vec2 p = uv;
    float decay = 1.0;
    vec3 acc = vec3(0.0);
    for (int i = 0; i < 28; i++) {
        p += dir;
        vec3 c = src(p).rgb;
        acc += c * smoothstep(p_threshold, 1.0, luma(c)) * decay;
        decay *= 0.94;
    }
    acc /= 14.0;
    vec3 tint = mix(vec3(1.0), uC1 * 1.3, 0.4);
    return vec4(base.rgb + acc * tint * p_strength * AMT, 1.0);
}"""
        ),
        FxDef(
            "lt_vignette", "Vignette", "Light", FxKind.FILTER,
            "Darkened corners that breathe with the beat.", "⚫",
            params = listOf(P("amount", "Amount", 0f, 1.5f, 0.7f), P("size", "Size", 0.2f, 1.2f, 0.75f)),
            react = React.BASS, reactAmt = 0.3f, keywords = "dark edges focus",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float d = length(centred(uv)) / p_size;
    float v = smoothstep(0.35, 1.25, d);
    return vec4(base.rgb * (1.0 - v * p_amount * uI * (1.0 + 0.5 * uR)), 1.0);
}"""
        ),
        FxDef(
            "lt_neon", "Neon Edges", "Light", FxKind.FILTER,
            "Traces the outlines of the picture in glowing colour.", "💠",
            params = listOf(
                P("strength", "Strength", 0f, 3f, 0.7f), P("keep", "Keep picture", 0f, 1f, 0.7f),
                P("cycle", "Colour cycle", 0f, 2f, 0.4f),
            ),
            react = React.ENERGY, reactAmt = 0.5f, colors = 2, keywords = "outline edge glow tron",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 px = 1.5 / uRes;
    float tl = luma(src(uv + vec2(-px.x, px.y)).rgb);
    float tc = luma(src(uv + vec2(0.0, px.y)).rgb);
    float tr = luma(src(uv + vec2(px.x, px.y)).rgb);
    float ml = luma(src(uv + vec2(-px.x, 0.0)).rgb);
    float mr = luma(src(uv + vec2(px.x, 0.0)).rgb);
    float bl = luma(src(uv + vec2(-px.x, -px.y)).rgb);
    float bc = luma(src(uv + vec2(0.0, -px.y)).rgb);
    float br = luma(src(uv + vec2(px.x, -px.y)).rgb);
    float gx = -tl - 2.0 * ml - bl + tr + 2.0 * mr + br;
    float gy = -tl - 2.0 * tc - tr + bl + 2.0 * bc + br;
    float e = clamp(length(vec2(gx, gy)) * 1.6 - 0.12, 0.0, 1.0);
    vec3 neon = mix(uC1, uC2, 0.5 + 0.5 * sin(uTime * p_cycle + uv.x * 3.0 + uv.y * 2.0));
    vec3 glow = neon * e;
    vec3 col = base.rgb * p_keep + glow * p_strength * AMT * 1.4;
    return vec4(col, 1.0);
}"""
        ),
        FxDef(
            "lt_flicker", "Lamp Flicker", "Light", FxKind.FILTER,
            "A candle or desk-lamp flicker with a warm glow.", "🕯️",
            params = listOf(P("amount", "Flicker", 0f, 1f, 0.35f), P("speed", "Speed", 0.2f, 3f, 1f), P("warm", "Warmth", 0f, 1f, 0.4f)),
            react = React.OFF, keywords = "candle lamp warm cosy",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float f = 0.5 * noise(vec2(uTime * 7.0 * p_speed, 1.0)) + 0.3 * noise(vec2(uTime * 17.0 * p_speed, 5.0)) + 0.2 * sin(uTime * 2.0 * p_speed);
    float k = 1.0 + (f - 0.5) * p_amount * 0.5 * uI;
    float lamp = exp(-length(centred(uv) - vec2(0.2, -0.05)) * 2.2);
    vec3 col = base.rgb * k + vec3(1.0, 0.62, 0.25) * lamp * p_warm * (0.05 + 0.1 * f) * uI;
    return vec4(col, 1.0);
}"""
        ),
        FxDef(
            "lt_strobe", "Beat Flash", "Light", FxKind.FILTER,
            "A gentle flash on every beat.", "📸",
            params = listOf(P("power", "Power", 0f, 1f, 0.3f)),
            react = React.BEAT, reactAmt = 1f, colors = 1, keywords = "pulse flash club",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec3 tint = mix(vec3(1.0), uC1, 0.4);
    return vec4(base.rgb + tint * uR * p_power * uI * 0.6, 1.0);
}"""
        ),
        FxDef(
            "lt_sweep", "Light Sweep", "Light", FxKind.OVERLAY,
            "A diagonal band of light glides across now and then.", "✴️",
            params = listOf(P("period", "Every (s)", 3f, 30f, 10f), P("width", "Width", 0.05f, 0.5f, 0.18f), P("angle", "Angle", -1f, 1f, 0.5f)),
            react = React.OFF, colors = 1, defBlend = Blend.ADD, keywords = "shine glint",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float ph = fract(uTime / p_period);
    float pos = mix(-0.4, 1.6, clamp(ph / 0.35, 0.0, 1.0));
    float d = (uv.x * uAsp * 0.6 + uv.y * p_angle) - pos * 0.9;
    float band = smoothstep(p_width, 0.0, abs(d));
    float act = step(ph, 0.35);
    return vec4(mix(vec3(1.0), uC1, 0.3), band * act * 0.45 * uI);
}"""
        ),
    )
}
