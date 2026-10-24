package com.lofiautopilot.studio.vfx

private fun P(key: String, label: String, min: Float, max: Float, def: Float, step: Float = 0f) =
    Param(key, label, min, max, def, step)

/** Distortion, glitch and retro-screen effects. */
object FxGlitch {
    val all: List<FxDef> = listOf(
        FxDef(
            "gl_chroma", "Chromatic Aberration", "Distort", FxKind.FILTER,
            "Red and blue fringing toward the edges, stronger on bass hits.", "🔴",
            params = listOf(P("amount", "Amount", 0f, 1.5f, 0.4f), P("radial", "Radial", 0f, 1f, 0.8f)),
            react = React.BASS, reactAmt = 1f, keywords = "rgb split lens fringe",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 dir = mix(vec2(1.0, 0.0), uv - 0.5, p_radial) ;
    float a = p_amount * 0.012 * AMT;
    float r = src(uv + dir * a).r;
    float g = src(uv).g;
    float b = src(uv - dir * a).b;
    return vec4(r, g, b, 1.0);
}"""
        ),
        FxDef(
            "gl_rgbbands", "RGB Slip", "Distort", FxKind.FILTER,
            "Horizontal bands slip apart in colour on the beat.", "🧬",
            params = listOf(P("bands", "Bands", 4f, 60f, 18f, 1f), P("shift", "Shift", 0f, 0.1f, 0.03f), P("chance", "Chance", 0f, 1f, 0.35f)),
            react = React.BEAT, reactAmt = 1f, keywords = "glitch tear",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float band = floor(uv.y * p_bands);
    float t = floor(uTime * 10.0);
    float rnd = hash21(vec2(band, t));
    float on = step(1.0 - p_chance * clamp(0.25 + uR * 1.2, 0.0, 1.0), rnd);
    float s = (hash21(vec2(band + 9.0, t)) - 0.5) * 2.0 * p_shift * on * uI;
    float r = src(uv + vec2(s, 0.0)).r;
    float g = src(uv + vec2(s * 0.3, 0.0)).g;
    float b = src(uv - vec2(s, 0.0)).b;
    return vec4(r, g, b, 1.0);
}"""
        ),
        FxDef(
            "gl_blocks", "Glitch Blocks", "Distort", FxKind.FILTER,
            "Digital blocks jump and tear in sync with the beat.", "🧱",
            params = listOf(P("size", "Block size", 3f, 40f, 12f, 1f), P("power", "Power", 0f, 1f, 0.5f), P("rate", "Rate", 1f, 24f, 12f)),
            react = React.BEAT, reactAmt = 1f, keywords = "digital corrupt datamosh",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float t = floor(uTime * p_rate);
    vec2 g = vec2(floor(uv.x * p_size * uAsp), floor(uv.y * p_size));
    float r = hash21(g + t);
    float trig = clamp(0.1 + uR * 1.3, 0.0, 1.0) * p_power * uI;
    float on = step(1.0 - trig * 0.35, r);
    vec2 off = (hash22(g + t + 3.0) - 0.5) * 0.15 * on;
    vec3 c = vec3(src(uv + off).r, src(uv + off * 1.4).g, src(uv + off * 0.6).b);
    return vec4(c, 1.0);
}"""
        ),
        FxDef(
            "gl_vhs", "VHS Tape", "Retro", FxKind.FILTER,
            "Wobble, tracking bars, colour bleed and tape noise.", "📼",
            params = listOf(
                P("wobble", "Wobble", 0f, 1f, 0.5f), P("noise", "Noise", 0f, 1f, 0.4f),
                P("bleed", "Colour bleed", 0f, 1f, 0.5f), P("track", "Tracking bar", 0f, 1f, 0.5f),
            ),
            react = React.ENERGY, reactAmt = 0.3f, keywords = "retro tape analog 90s",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float t = uTime;
    float wob = sin(uv.y * 38.0 + t * 3.0) * 0.0015 + sin(uv.y * 7.0 - t * 1.3) * 0.002;
    float barY = fract(-t * 0.12);
    float bar = smoothstep(0.08, 0.0, abs(uv.y - barY)) * p_track;
    float jitter = (noise(vec2(uv.y * 80.0, floor(t * 20.0))) - 0.5) * 0.02 * bar;
    vec2 u = vec2(uv.x + (wob * p_wobble + jitter) * AMT, uv.y);
    float bl = 0.003 * p_bleed;
    float r = src(u + vec2(bl * 2.0, 0.0)).r;
    float g = src(u).g;
    float b = src(u - vec2(bl * 2.0, 0.0)).b;
    vec3 c = vec3(r, g, b);
    float n = hash21(vec2(floor(uv.x * uRes.x * 0.5), floor(uv.y * uRes.y * 0.5) + floor(t * 30.0)));
    c += (n - 0.5) * 0.16 * p_noise * uI;
    c += bar * 0.18;
    float line = 0.94 + 0.06 * sin(uv.y * uRes.y * 1.5);
    c *= line;
    c = mix(vec3(luma(c)), c, 0.9);
    return vec4(mix(base.rgb, c, clamp(uI, 0.0, 1.0)), 1.0);
}"""
        ),
        FxDef(
            "gl_scan", "Scanlines", "Retro", FxKind.FILTER,
            "Fine horizontal lines like an old monitor.", "📺",
            params = listOf(P("lines", "Line count", 100f, 800f, 360f), P("depth", "Depth", 0f, 1f, 0.35f), P("roll", "Roll", 0f, 1f, 0.2f)),
            react = React.OFF, keywords = "monitor lines tv",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float s = 0.5 + 0.5 * sin((uv.y + uTime * 0.02 * p_roll) * p_lines * PI * 2.0);
    return vec4(base.rgb * (1.0 - p_depth * uI * s * 0.6), 1.0);
}"""
        ),
        FxDef(
            "gl_crt", "CRT Screen", "Retro", FxKind.FILTER,
            "Curved glass, phosphor mask and scanlines.", "🖥️",
            params = listOf(P("curve", "Curvature", 0f, 0.5f, 0.18f), P("mask", "Phosphor mask", 0f, 1f, 0.35f), P("lines", "Scanlines", 0f, 1f, 0.35f)),
            react = React.OFF, keywords = "monitor arcade curved tube",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 c = uv * 2.0 - 1.0;
    c *= 1.0 + dot(c, c) * p_curve * 0.35 * uI;
    vec2 u = c * 0.5 + 0.5;
    if (u.x < 0.0 || u.x > 1.0 || u.y < 0.0 || u.y > 1.0) return vec4(0.0, 0.0, 0.0, 1.0);
    vec3 col = vec3(src(u + vec2(0.0007, 0.0)).r, src(u).g, src(u - vec2(0.0007, 0.0)).b);
    float sl = 0.5 + 0.5 * sin(u.y * uRes.y * 1.3);
    col *= 1.0 - p_lines * 0.5 * sl;
    float m = mod(floor(u.x * uRes.x), 3.0);
    vec3 mk = vec3(m < 1.0 ? 1.0 : 0.7, (m >= 1.0 && m < 2.0) ? 1.0 : 0.7, m >= 2.0 ? 1.0 : 0.7);
    col *= mix(vec3(1.0), mk, p_mask);
    col *= 1.0 - 0.45 * dot(c, c) * 0.5;
    col *= 1.12;
    return vec4(col, 1.0);
}"""
        ),
        FxDef(
            "gl_pixel", "Pixelate", "Distort", FxKind.FILTER,
            "Chunky pixels that tighten and loosen with the music.", "🔲",
            params = listOf(P("size", "Pixel size", 2f, 64f, 7f)),
            react = React.BASS, reactAmt = 0.6f, keywords = "8bit retro mosaic",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float s = max(1.0, p_size * uI * (1.0 + 1.5 * uR));
    vec2 cell = vec2(s) / uRes;
    vec2 u = (floor(uv / cell) + 0.5) * cell;
    return vec4(src(u).rgb, 1.0);
}"""
        ),
        FxDef(
            "gl_halftone", "Halftone Dots", "Distort", FxKind.FILTER,
            "Comic-book dot pattern.", "🟤",
            params = listOf(P("size", "Dot size", 3f, 30f, 9f), P("mix", "Amount", 0f, 1f, 0.8f)),
            react = React.OFF, keywords = "comic print dots pop art",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float s = p_size;
    vec2 px = uv * uRes;
    mat2 R = rot(0.5);
    vec2 g = R * px / s;
    vec2 id = floor(g);
    vec2 f = fract(g) - 0.5;
    vec2 cu = (transpose(R) * ((id + 0.5) * s)) / uRes;
    vec3 c = src(cu).rgb;
    float l = luma(c);
    float r = sqrt(l) * 0.72;
    float d = smoothstep(r, r - 0.12, length(f));
    vec3 ht = c * (0.25 + 0.9 * d) + 0.05;
    return vec4(mix(base.rgb, ht, p_mix * clamp(uI, 0.0, 1.0)), 1.0);
}"""
        ),
        FxDef(
            "gl_wave", "Wave Warp", "Distort", FxKind.FILTER,
            "The picture ripples like water.", "🌊",
            params = listOf(P("amp", "Amplitude", 0f, 1f, 0.35f), P("freq", "Frequency", 1f, 30f, 8f), P("speed", "Speed", 0f, 4f, 1.2f), P("dir", "Direction", 0f, 1f, 0f, 1f)),
            react = React.BASS, reactAmt = 0.8f, keywords = "ripple liquid sine wobble",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float a = p_amp * 0.012 * AMT;
    vec2 off = p_dir < 0.5
        ? vec2(sin(uv.y * p_freq + uTime * p_speed) * a, 0.0)
        : vec2(0.0, sin(uv.x * p_freq * uAsp + uTime * p_speed) * a);
    return vec4(src(uv + off).rgb, 1.0);
}"""
        ),
        FxDef(
            "gl_ripple", "Centre Ripple", "Distort", FxKind.FILTER,
            "Circular ripples spreading from the middle.", "💧",
            params = listOf(P("amp", "Amplitude", 0f, 1f, 0.4f), P("freq", "Frequency", 4f, 60f, 24f), P("speed", "Speed", 0f, 6f, 2.5f), P("cy", "Centre Y", 0f, 1f, 0.5f)),
            react = React.BASS, reactAmt = 1f, keywords = "water drop circular",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 q = centred(uv) - vec2(0.0, p_cy - 0.5);
    float r = length(q);
    float w = sin(r * p_freq - uTime * p_speed) * exp(-r * 2.2);
    vec2 dir = q / (r + 0.0001);
    vec2 off = dir * w * p_amp * 0.02 * AMT * vec2(1.0 / uAsp, 1.0);
    return vec4(src(uv + off).rgb, 1.0);
}"""
        ),
        FxDef(
            "gl_shock", "Shockwave", "Distort", FxKind.FILTER,
            "Each beat sends a bending wave outward.", "💥",
            params = listOf(P("power", "Power", 0f, 1.5f, 0.6f), P("width", "Width", 0.02f, 0.3f, 0.09f), P("cy", "Centre Y", 0f, 1f, 0.5f)),
            react = React.BEAT, reactAmt = 1f, keywords = "pulse blast distortion",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 q = centred(uv) - vec2(0.0, p_cy - 0.5);
    float r = length(q);
    float ring = uPhase * 0.9;
    float d = r - ring;
    float env = exp(-d * d / (p_width * p_width)) * (1.0 - uPhase);
    float k = env * p_power * 0.05 * (0.35 + uBeat) * uI;
    vec2 off = (q / (r + 0.0001)) * k * vec2(1.0 / uAsp, 1.0);
    return vec4(src(uv - off).rgb + env * 0.05 * uBeat, 1.0);
}"""
        ),
        FxDef(
            "gl_kaleido", "Kaleidoscope", "Distort", FxKind.FILTER,
            "Mirror the picture into rotating symmetrical patterns.", "🔯",
            params = listOf(P("segments", "Segments", 2f, 16f, 6f, 1f), P("zoom", "Zoom", 0.3f, 2f, 1f), P("spin", "Spin", -1f, 1f, 0.15f), P("mix", "Amount", 0f, 1f, 1f)),
            react = React.ENERGY, reactAmt = 0.4f, keywords = "mandala symmetry trippy",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 q = centred(uv);
    float r = length(q);
    float a = atan(q.y, q.x) + uTime * p_spin;
    float seg = TAU / floor(p_segments);
    a = mod(a, seg);
    a = abs(a - seg * 0.5);
    vec2 k = vec2(cos(a), sin(a)) * r / (p_zoom * (1.0 + 0.15 * uR));
    vec2 u = vec2(k.x / uAsp, k.y) + 0.5;
    vec3 c = src(u).rgb;
    return vec4(mix(base.rgb, c, p_mix * clamp(uI, 0.0, 1.0)), 1.0);
}"""
        ),
        FxDef(
            "gl_mirror", "Mirror", "Distort", FxKind.FILTER,
            "Reflect the picture left/right, top/bottom or both.", "🪞",
            params = listOf(P("mode", "Mode 0=L 1=R 2=Top 3=Quad", 0f, 3f, 0f, 1f)),
            react = React.OFF, keywords = "symmetry reflect flip",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 u = uv;
    float m = floor(p_mode + 0.5);
    if (m < 0.5) u.x = u.x > 0.5 ? 1.0 - u.x : u.x;
    else if (m < 1.5) u.x = u.x < 0.5 ? 1.0 - u.x : u.x;
    else if (m < 2.5) u.y = u.y < 0.5 ? 1.0 - u.y : u.y;
    else u = vec2(u.x > 0.5 ? 1.0 - u.x : u.x, u.y > 0.5 ? 1.0 - u.y : u.y) ;
    return vec4(mix(base.rgb, src(u).rgb, clamp(uI, 0.0, 1.0)), 1.0);
}"""
        ),
        FxDef(
            "gl_swirl", "Swirl", "Distort", FxKind.FILTER,
            "Twist the middle of the picture.", "🌀",
            params = listOf(P("angle", "Twist", -3f, 3f, 0.8f), P("radius", "Radius", 0.1f, 1f, 0.5f)),
            react = React.BASS, reactAmt = 0.8f, keywords = "twirl vortex spin",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 q = centred(uv);
    float r = length(q);
    float k = smoothstep(p_radius, 0.0, r);
    float a = p_angle * k * k * AMT * 0.6 + sin(uTime * 0.5) * 0.1 * k;
    q = rot(a) * q;
    return vec4(src(vec2(q.x / uAsp, q.y) + 0.5).rgb, 1.0);
}"""
        ),
        FxDef(
            "gl_zoomblur", "Zoom Blur", "Distort", FxKind.FILTER,
            "Speed lines rushing from the centre on bass hits.", "🚀",
            params = listOf(P("amount", "Amount", 0f, 1f, 0.2f), P("cx", "Centre X", 0f, 1f, 0.5f), P("cy", "Centre Y", 0f, 1f, 0.5f)),
            react = React.BASS, reactAmt = 1.5f, keywords = "radial blur speed punch",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 c = vec2(p_cx, p_cy);
    vec2 d = (uv - c) * p_amount * 0.12 * AMT;
    vec3 acc = vec3(0.0);
    for (int i = 0; i < 12; i++) {
        float f = float(i) / 11.0;
        acc += src(uv - d * f).rgb;
    }
    return vec4(acc / 12.0, 1.0);
}"""
        ),
        FxDef(
            "gl_echo", "Echo Trails", "Distort", FxKind.FILTER,
            "Ghost copies fan out behind bright shapes.", "👻",
            params = listOf(P("spread", "Spread", 0f, 1f, 0.3f), P("fade", "Fade", 0.3f, 0.95f, 0.7f)),
            react = React.BASS, reactAmt = 1f, keywords = "ghost trail repeat",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec3 acc = base.rgb;
    float w = 1.0;
    float s = p_spread * 0.03 * AMT;
    for (int i = 1; i <= 5; i++) {
        float f = float(i);
        w *= p_fade;
        vec2 u = (uv - 0.5) / (1.0 + s * f) + 0.5;
        acc = max(acc, src(u).rgb * w);
    }
    return vec4(acc, 1.0);
}"""
        ),
        FxDef(
            "gl_bulge", "Lens Pulse", "Distort", FxKind.FILTER,
            "The picture swells like a fisheye lens on the kick.", "🔍",
            params = listOf(P("power", "Power", -1f, 1f, 0.4f), P("radius", "Radius", 0.2f, 1.2f, 0.8f)),
            react = React.BASS, reactAmt = 1.2f, keywords = "fisheye bulge punch zoom",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 q = centred(uv);
    float r = length(q) / p_radius;
    float k = 1.0 - p_power * 0.35 * AMT * (1.0 - clamp(r, 0.0, 1.0)) * (1.0 - clamp(r, 0.0, 1.0)) ;
    q *= k;
    return vec4(src(vec2(q.x / uAsp, q.y) + 0.5).rgb, 1.0);
}"""
        ),
    )
}
