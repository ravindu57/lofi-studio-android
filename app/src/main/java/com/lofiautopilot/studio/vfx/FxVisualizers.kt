package com.lofiautopilot.studio.vfx

private fun P(key: String, label: String, min: Float, max: Float, def: Float, step: Float = 0f) =
    Param(key, label, min, max, def, step)

/** Audio visualizers: they draw the music on top of the picture. */
object FxVisualizers {

    /** Mountain-range visualizers: layered terrain whose ridgelines are shaped by the music. */
    val hills: List<FxDef> = listOf(
        FxDef(
            "viz_hills", "Spectrum Hills", "Visualizer", FxKind.OVERLAY,
            "Layered misty mountain ranges that rise and fall with the music, with rim light and haze.", "⛰️",
            params = listOf(
                P("layers", "Ranges", 2f, 7f, 5f, 1f), P("height", "Height", 0.05f, 0.7f, 0.38f), P("y", "Base height", 0f, 0.6f, 0.03f),
                P("drift", "Drift speed", 0f, 1f, 0.25f), P("shape", "Ruggedness", 0f, 1f, 0.85f), P("haze", "Mist", 0f, 1f, 0.6f),
                P("rim", "Rim light", 0f, 1f, 0.6f), P("mix", "Music amount", 0f, 1f, 0.7f),
            ),
            react = React.BASS, reactAmt = 0.4f, colors = 2, keywords = "mountains hills terrain landscape ridge valley peaks",
            glsl = """
float ridgeH(float x, float seed, float rug) {
    float h = 0.0, a = 0.55, f = 1.9;
    for (int i = 0; i < 5; i++) {
        float n = noise(vec2(x * f + seed * 7.3, seed * 3.1));
        n = mix(n, 1.0 - abs(n * 2.0 - 1.0), rug * 0.8);
        h += a * n; f *= 2.05; a *= 0.48;
    }
    return h;
}
vec4 fx(vec2 uv, vec4 base) {
    int n = int(p_layers + 0.5);
    vec3 acc = vec3(0.0);
    float accA = 0.0;
    for (int k = 0; k < 7; k++) {
        if (k >= n) break;
        float d = float(k) / max(float(n - 1), 1.0);         // 0 = far, 1 = near
        float x = uv.x * uAsp * 0.9 + uTime * p_drift * (0.03 + d * 0.16) + float(k) * 11.7;
        float sx = abs(uv.x * 2.0 - 1.0);
        float sp = (specAt(sx * 0.9 + 0.02) + specAt(sx * 0.9 + 0.06) + specAt(sx * 0.9 + 0.1)) / 3.0;
        float ridge = p_y + (1.0 - d) * p_height * 0.55 + p_height * (0.25 + 0.5 * (1.0 - d * 0.5)) * ridgeH(x, float(k) + uSeed * 5.0, p_shape)
                    + sp * p_mix * AMT * (0.03 + 0.08 * d) * p_height * 3.0;
        float aa = 0.0025;
        float inside = sstep(ridge + aa, ridge - aa, uv.y);
        vec3 far = mix(uC2, vec3(0.78, 0.84, 1.0), 0.25) * 0.75;
        vec3 near = uC1 * 0.10;
        vec3 col = mix(far, near, pow(d, 0.7));
        // depth shading: darker toward the foot of each range, lit slope on one side
        float slope = (ridgeH(x + 0.02, float(k) + uSeed * 5.0, p_shape) - ridgeH(x - 0.02, float(k) + uSeed * 5.0, p_shape));
        col *= 0.65 + 0.35 * clamp((uv.y - p_y) / max(ridge - p_y, 0.02), 0.0, 1.0);
        col += uC2 * clamp(slope * 2.5, 0.0, 0.5) * (1.0 - d * 0.5) * 0.35;
        // mist between ranges
        float mist = exp(-max(uv.y - ridge, 0.0) * 9.0) * p_haze * (1.0 - d * 0.6) * 0.5;
        float rimLine = exp(-abs(uv.y - ridge) * 260.0) * p_rim * (0.3 + 0.7 * d) * (0.5 + sp * 0.8 + uBeat * 0.3);
        acc = mix(acc, col, inside);
        accA = max(accA, inside);
        acc += uC2 * rimLine;
        accA = max(accA, clamp(rimLine, 0.0, 1.0));
        float mA = mist * (1.0 - inside);
        acc = mix(acc, mix(uC2, vec3(1.0), 0.4), mA * 0.7 * (1.0 - accA * 0.0));
        accA = max(accA, mA * 0.55);
    }
    return vec4(acc, clamp(accA, 0.0, 1.0));
}"""
        ),
        FxDef(
            "viz_terrain", "Neon Terrain", "Visualizer", FxKind.OVERLAY,
            "Synthwave wireframe mountains flying towards you, shaped by the spectrum, under a striped sun.", "🏜️",
            params = listOf(
                P("rows", "Rows", 6f, 24f, 16f, 1f), P("horizon", "Horizon", 0.25f, 0.7f, 0.5f), P("height", "Peak height", 0.05f, 0.5f, 0.24f),
                P("speed", "Speed", 0f, 2f, 0.7f), P("sun", "Sun", 0f, 1f, 0.8f), P("fill", "Solid fill", 0f, 1f, 0.8f),
                P("glow", "Glow", 0f, 1f, 0.6f),
            ),
            react = React.BASS, reactAmt = 0.4f, colors = 2, keywords = "synthwave retro grid mountains terrain wireframe outrun",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec3 acc = vec3(0.0);
    float accA = 0.0;
    // sun behind the range
    vec2 q = (uv - vec2(0.5, p_horizon + 0.1)) * vec2(uAsp, 1.0);
    float sd = length(q);
    float sunMask = sstep(0.2, 0.195, sd) * p_sun;
    float stripes = step(0.5, fract(q.y * 26.0 + 0.5)) * sstep(0.02, -0.12, q.y) ;
    vec3 sunCol = mix(uC2, uC1, clamp(0.5 - q.y * 2.5, 0.0, 1.0));
    acc = sunCol * 1.1; accA = sunMask * (1.0 - stripes * 0.95);
    float halo = exp(-sd * 3.5) * p_sun * p_glow * (0.3 + uBass * 0.5);
    acc += uC2 * halo * (1.0 - accA); accA = max(accA, halo * 0.55);
    int n = int(p_rows + 0.5);
    float flow = fract(uTime * p_speed * 0.25);
    for (int r = 0; r < 24; r++) {
        if (r >= n) break;
        float z = (float(r) + flow) / float(n);                // 0 far .. 1 near
        float zz = z * z;
        float yBase = p_horizon - zz * p_horizon * 0.95;
        float xs = (uv.x - 0.5) * (0.35 + (1.0 - z) * 2.6);    // wider field of view in the distance
        float valley = sstep(0.0, 0.45, abs(xs));
        float sp = (specAt(abs(xs) * 0.9) + specAt(abs(xs) * 0.9 + 0.05)) * 0.5;
        float wr = float(r) - floor(uTime * p_speed * 0.25);   // the terrain is attached to its row as it flows past
        float terr = noise(vec2(xs * 3.4, wr * 0.9));
        float hgt = (terr * 0.6 + sp * AMT) * p_height * valley * (0.25 + zz * 1.4);
        float line = yBase + hgt;
        float inside = sstep(line + 0.002, line - 0.002, uv.y) * p_fill * step(yBase - 0.5, uv.y);
        float edge = exp(-abs(uv.y - line) * (190.0 + 140.0 * (1.0 - z))) ;
        vec3 fillCol = mix(vec3(0.02, 0.0, 0.07), uC1 * 0.18, z);
        acc = mix(acc, fillCol, inside); accA = max(accA, inside);
        vec3 lineCol = mix(uC2, uC1, 0.5 + 0.5 * sin(z * 3.0 + uTime * 0.4)) * (1.1 + sp);
        float vis = edge * (0.35 + 0.65 * z) * (0.55 + 0.45 * p_glow * 2.0);
        acc += lineCol * vis; accA = max(accA, clamp(vis, 0.0, 1.0));
    }
    return vec4(acc, clamp(accA, 0.0, 1.0));
}"""
        ),
        FxDef(
            "viz_ridgelines", "Ridge Lines", "Visualizer", FxKind.OVERLAY,
            "Stacked glowing spectrum lines that form a mountain range, like a pulsar plot.", "〰️",
            params = listOf(
                P("rows", "Lines", 6f, 40f, 22f, 1f), P("y", "Bottom", 0f, 0.6f, 0.08f), P("span", "Depth", 0.1f, 0.7f, 0.42f),
                P("height", "Peak height", 0.02f, 0.3f, 0.12f), P("fill", "Dark fill", 0f, 1f, 0.7f), P("width", "Line width", 0.3f, 3f, 1.2f),
            ),
            react = React.BASS, reactAmt = 0.35f, colors = 2, keywords = "joy division pulsar unknown pleasures lines mountain ridge",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    int n = int(p_rows + 0.5);
    vec3 acc = vec3(0.0);
    float accA = 0.0;
    for (int r = 0; r < 40; r++) {
        if (r >= n) break;
        float k = float(r) / float(max(n - 1, 1));             // 0 far .. 1 near
        float y0 = p_y + (1.0 - k) * p_span;
        float widthK = 0.55 + 0.45 * k;
        float x = (uv.x - 0.5) / widthK;
        float env = exp(-x * x * 7.0);                         // hills fade out toward the sides
        float sp = (specAt(abs(x) * 1.2 + k * 0.03) + specAt(abs(x) * 1.2 + 0.05 + k * 0.03)) * 0.5;
        float jag = noise(vec2(x * 38.0 + float(r) * 9.0, uTime * 1.6 + float(r))) * 0.4 + noise(vec2(x * 11.0, float(r) * 3.0 - uTime * 0.2)) * 0.6;
        float y = y0 + env * (sp * AMT + 0.15 * jag) * p_height * (0.6 + k * 0.8);
        float inside = sstep(y + 0.002, y - 0.002, uv.y) * step(y0 - 0.25, uv.y) * p_fill;
        float line = exp(-abs(uv.y - y) * (260.0 / p_width)) * (0.4 + 0.6 * k) * step(abs(x), 1.0);
        vec3 lc = mix(uC2, uC1, k) * (1.0 + sp * 0.8);
        acc = mix(acc, vec3(0.01, 0.01, 0.03), inside); accA = max(accA, inside);
        acc += lc * line; accA = max(accA, clamp(line, 0.0, 1.0));
    }
    return vec4(acc, clamp(accA, 0.0, 1.0));
}"""
        ),
        FxDef(
            "viz_valley", "Moonlit Valley", "Visualizer", FxKind.OVERLAY,
            "Dark hills under a huge moon with stars and a still lake that ripples with the beat.", "🌙",
            params = listOf(
                P("moon", "Moon size", 0.05f, 0.4f, 0.17f), P("moonx", "Moon X", 0.1f, 0.9f, 0.68f), P("moony", "Moon Y", 0.4f, 0.9f, 0.7f),
                P("hills", "Hill height", 0.05f, 0.4f, 0.18f), P("lake", "Lake", 0f, 0.4f, 0.14f), P("stars", "Stars", 0f, 1f, 0.7f),
            ),
            react = React.BASS, reactAmt = 0.4f, colors = 2, keywords = "moon night hills lake valley stars landscape",
            glsl = """
float vh(float x, float s) { return noise(vec2(x * 2.2 + s, s)) * 0.6 + noise(vec2(x * 5.5 + s * 2.0, s)) * 0.3 + noise(vec2(x * 13.0, s)) * 0.1; }
vec4 fx(vec2 uv, vec4 base) {
    vec3 acc = vec3(0.0);
    float a = 0.0;
    vec2 q = (uv - vec2(p_moonx, p_moony)) * vec2(uAsp, 1.0);
    float md = length(q);
    float moon = sstep(p_moon, p_moon - 0.004, md);
    float craters = noise(q * 22.0 + 3.0) * 0.18;
    acc += mix(uC2, vec3(1.0), 0.6) * (1.0 - craters) * moon; a = max(a, moon);
    float halo = exp(-max(md - p_moon, 0.0) * 5.5) * (0.35 + uBass * 0.4) * 0.8;
    acc += uC2 * halo * (1.0 - moon); a = max(a, halo * 0.6);
    // stars
    vec2 sg = floor(uv * vec2(uAsp, 1.0) * 120.0);
    float st = step(0.985, hash21(sg)) * (0.5 + 0.5 * sin(uTime * 3.0 + hash21(sg + 3.0) * 20.0)) * p_stars * sstep(0.45, 0.8, uv.y);
    acc += vec3(1.0) * st * (1.0 - moon); a = max(a, st * (1.0 - moon));
    float sx = abs(uv.x * 2.0 - 1.0);
    float sp = (specAt(sx) + specAt(sx + 0.04)) * 0.5;
    for (int k = 0; k < 3; k++) {
        float d = float(k) / 2.0;
        float x = uv.x * uAsp + uTime * 0.01 * (1.0 + d * 3.0);
        float lakeTop = p_lake;
        float ridge = lakeTop + p_hills * (0.45 + 0.55 * (1.0 - d)) * vh(x, float(k) * 4.0 + uSeed * 3.0) + sp * 0.05 * d * AMT;
        float inside = sstep(ridge + 0.003, ridge - 0.003, uv.y) * step(lakeTop, uv.y);
        vec3 col = mix(vec3(0.07, 0.09, 0.2), vec3(0.01, 0.01, 0.04), d) + uC1 * 0.05;
        acc = mix(acc, col, inside); a = max(a, inside);
        acc += uC2 * exp(-abs(uv.y - ridge) * 220.0) * (0.15 + 0.2 * sp) * step(lakeTop, uv.y); 
    }
    // lake with moon reflection and beat ripples
    if (uv.y < p_lake) {
        float depth = (p_lake - uv.y) / max(p_lake, 0.001);
        float wob = sin(uv.y * 160.0 + uTime * 2.0 + uBass * 6.0) * 0.004 * (1.0 + uBeat * 2.0);
        float streak = exp(-abs((uv.x + wob - p_moonx) * uAsp) * (10.0 + depth * 30.0)) * (0.3 + 0.7 * noise(vec2(uv.y * 120.0, uTime * 1.5 + 5.0))) * (1.0 - depth * 0.5);
        vec3 water = mix(vec3(0.02, 0.04, 0.1), vec3(0.0, 0.0, 0.02), depth) + uC1 * 0.04;
        water += uC2 * streak * 0.8;
        acc = water; a = 1.0;
    }
    return vec4(acc, clamp(a, 0.0, 1.0));
}"""
        ),
    )

    val all: List<FxDef> = hills + listOf(
        FxDef(
            "viz_bars", "Spectrum Bars", "Visualizer", FxKind.OVERLAY,
            "Classic glowing equalizer along the bottom.", "📊",
            params = listOf(
                P("count", "Bars", 8f, 96f, 40f, 1f), P("height", "Height", 0.05f, 0.7f, 0.28f),
                P("width", "Bar width", 0.2f, 0.95f, 0.66f), P("y", "Position", 0f, 0.9f, 0.06f),
                P("round", "Roundness", 0f, 1f, 0.7f), P("glow", "Glow", 0f, 1f, 0.5f),
            ),
            react = React.BASS, reactAmt = 0.35f, colors = 2, defBlend = Blend.NORMAL,
            keywords = "equalizer eq bars spectrum",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float n = floor(p_count);
    float x = uv.x * n;
    float id = floor(x);
    float cell = fract(x) - 0.5;
    float v = specAt((id + 0.5) / n);
    float h = max(0.006, v * p_height * AMT);
    float hw = 0.5 * p_width / n * uAsp;
    vec2 q = vec2(cell / n * uAsp, uv.y - p_y - h * 0.5);
    float d = sdRoundBox(q, vec2(hw, h * 0.5), min(hw, h * 0.5) * p_round);
    float a = smoothstep(0.003, -0.003, d);
    float g = exp(-max(d, 0.0) * 38.0) * 0.45 * p_glow * (0.4 + v);
    vec3 col = mix(uC1, uC2, clamp((uv.y - p_y) / max(p_height, 0.01), 0.0, 1.0));
    col += 0.25 * v;
    return vec4(col, clamp(a + g, 0.0, 1.0));
}"""
        ),
        FxDef(
            "viz_mirror", "Mirror Bars", "Visualizer", FxKind.OVERLAY,
            "Bars that grow up and down from a centre line.", "🎚️",
            params = listOf(
                P("count", "Bars", 8f, 96f, 48f, 1f), P("height", "Height", 0.05f, 0.5f, 0.16f),
                P("width", "Bar width", 0.2f, 0.95f, 0.55f), P("y", "Centre line", 0.05f, 0.95f, 0.5f),
                P("glow", "Glow", 0f, 1f, 0.5f),
            ),
            react = React.BASS, reactAmt = 0.3f, colors = 2, keywords = "equalizer symmetric",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float n = floor(p_count);
    float x = uv.x * n;
    float id = floor(x);
    float cell = fract(x) - 0.5;
    float v = specAt(abs((id + 0.5) / n * 2.0 - 1.0));
    float h = max(0.004, v * p_height * AMT);
    float hw = 0.5 * p_width / n * uAsp;
    vec2 q = vec2(cell / n * uAsp, uv.y - p_y);
    float d = sdRoundBox(q, vec2(hw, h), hw);
    float a = smoothstep(0.003, -0.003, d);
    float g = exp(-max(d, 0.0) * 40.0) * 0.45 * p_glow * (0.3 + v);
    vec3 col = mix(uC1, uC2, clamp(abs(uv.y - p_y) / max(p_height, 0.01), 0.0, 1.0));
    return vec4(col + 0.2 * v, clamp(a + g, 0.0, 1.0));
}"""
        ),
        FxDef(
            "viz_circle", "Radial Spectrum", "Visualizer", FxKind.OVERLAY,
            "Bars radiating from a ring that pulses with the bass.", "🔆",
            params = listOf(
                P("count", "Bars", 12f, 80f, 44f, 1f), P("radius", "Radius", 0.08f, 0.45f, 0.22f),
                P("len", "Bar length", 0.03f, 0.4f, 0.16f), P("cy", "Centre Y", 0.1f, 0.9f, 0.5f),
                P("thick", "Thickness", 0.1f, 0.9f, 0.45f), P("spin", "Spin", -1f, 1f, 0.1f),
            ),
            react = React.BASS, reactAmt = 0.5f, colors = 2, keywords = "circle ring round",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 q = centred(uv) - vec2(0.0, p_cy - 0.5);
    float r = length(q);
    float ang = atan(q.y, q.x) + uTime * p_spin * 0.4;
    float s = abs(mod(ang + PI, TAU) - PI) / PI;
    float n = floor(p_count);
    float cell = fract(s * n) - 0.5;
    float id = floor(s * n);
    float v = specAt((id + 0.5) / n);
    float R = p_radius * (1.0 + 0.10 * uBass * (0.5 + uR));
    float len = max(0.004, v * p_len * AMT);
    float hw = 0.5 * p_thick * (PI / n) * R;
    vec2 qq = vec2(cell * (PI / n) * r, r - R - len * 0.5);
    float d = sdRoundBox(qq, vec2(hw, len * 0.5), hw);
    float a = smoothstep(0.003, -0.003, d);
    float g = exp(-max(d, 0.0) * 35.0) * 0.4 * (0.3 + v);
    float ring = smoothstep(0.004, 0.0, abs(r - R)) * 0.9;
    vec3 col = mix(uC1, uC2, clamp((r - R) / max(p_len, 0.01), 0.0, 1.0));
    return vec4(col + 0.2 * v, clamp(a + g + ring, 0.0, 1.0));
}"""
        ),
        FxDef(
            "viz_wave", "Waveform Line", "Visualizer", FxKind.OVERLAY,
            "A glowing oscilloscope line across the picture.", "〰️",
            params = listOf(
                P("y", "Position", 0.05f, 0.95f, 0.5f), P("amp", "Amplitude", 0.02f, 0.4f, 0.14f),
                P("thick", "Thickness", 0.001f, 0.02f, 0.004f), P("glow", "Glow", 0f, 1f, 0.6f),
            ),
            react = React.ENERGY, reactAmt = 0.4f, colors = 2, keywords = "oscilloscope line",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float e = 0.004;
    float w = waveAt(uv.x) * p_amp * AMT;
    float w2 = waveAt(uv.x + e) * p_amp * AMT;
    float slope = (w2 - w) / (e * uAsp);
    float dy = (uv.y - p_y - w) / sqrt(1.0 + slope * slope);
    float d = abs(dy);
    float a = smoothstep(p_thick, p_thick * 0.3, d);
    float g = exp(-d * 60.0) * 0.5 * p_glow;
    vec3 col = mix(uC1, uC2, uv.x);
    return vec4(col + 0.15, clamp(a + g, 0.0, 1.0));
}"""
        ),
        FxDef(
            "viz_wavering", "Waveform Ring", "Visualizer", FxKind.OVERLAY,
            "A circular oscilloscope that wobbles with the sound.", "⭕",
            params = listOf(
                P("radius", "Radius", 0.08f, 0.45f, 0.24f), P("amp", "Amplitude", 0.01f, 0.25f, 0.07f),
                P("thick", "Thickness", 0.001f, 0.02f, 0.004f), P("cy", "Centre Y", 0.1f, 0.9f, 0.5f),
                P("glow", "Glow", 0f, 1f, 0.6f),
            ),
            react = React.BASS, reactAmt = 0.4f, colors = 2, keywords = "circle oscilloscope",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 q = centred(uv) - vec2(0.0, p_cy - 0.5);
    float r = length(q);
    float ang = atan(q.y, q.x);
    float s = abs(ang) / PI;
    float w = waveAt(s) * p_amp * AMT;
    float R = p_radius * (1.0 + 0.08 * uBass * (0.5 + uR));
    float d = abs(r - R - w);
    float a = smoothstep(p_thick, p_thick * 0.3, d);
    float g = exp(-d * 55.0) * 0.5 * p_glow;
    vec3 col = mix(uC1, uC2, 0.5 + 0.5 * sin(ang * 2.0 + uTime));
    return vec4(col + 0.15, clamp(a + g, 0.0, 1.0));
}"""
        ),
        FxDef(
            "viz_dots", "Dot Matrix", "Visualizer", FxKind.OVERLAY,
            "An LED-style dot equalizer.", "🔴",
            params = listOf(
                P("cols", "Columns", 8f, 48f, 28f, 1f), P("rows", "Rows", 4f, 24f, 12f, 1f),
                P("height", "Height", 0.1f, 0.7f, 0.34f), P("y", "Position", 0f, 0.8f, 0.05f),
                P("size", "Dot size", 0.2f, 0.9f, 0.6f),
            ),
            react = React.BASS, reactAmt = 0.3f, colors = 2, keywords = "led matrix grid",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float cols = floor(p_cols);
    float rows = floor(p_rows);
    float yy = (uv.y - p_y) / p_height;
    if (yy < 0.0 || yy > 1.0) return vec4(0.0);
    float gx = uv.x * cols;
    float gy = yy * rows;
    vec2 f = vec2(fract(gx), fract(gy)) - 0.5;
    f.x *= (uAsp / cols) / (p_height / rows);
    float v = specAt((floor(gx) + 0.5) / cols) * AMT;
    float lit = step((floor(gy) + 0.5) / rows, v);
    float d = length(f);
    float a = smoothstep(p_size * 0.5, p_size * 0.5 - 0.12, d) * (0.15 + 0.85 * lit);
    vec3 col = mix(uC1, uC2, (floor(gy) + 0.5) / rows);
    return vec4(col, a);
}"""
        ),
        FxDef(
            "viz_area", "Spectrum Area", "Visualizer", FxKind.OVERLAY,
            "A smooth filled spectrum along the bottom.", "🏔️",
            params = listOf(
                P("y", "Baseline", 0f, 0.8f, 0.02f), P("height", "Height", 0.05f, 0.7f, 0.32f),
                P("mirror", "Symmetric", 0f, 1f, 1f, 1f), P("fill", "Fill", 0f, 1f, 0.55f),
            ),
            react = React.BASS, reactAmt = 0.3f, colors = 2, keywords = "mountain curve smooth",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float x = mix(uv.x, abs(uv.x * 2.0 - 1.0), p_mirror);
    float v = specAt(x * 0.9);
    float v2 = specAt(x * 0.9 + 0.02);
    float v0 = specAt(max(x * 0.9 - 0.02, 0.0));
    float h = p_y + (v * 0.5 + v2 * 0.25 + v0 * 0.25) * p_height * AMT;
    float d = uv.y - h;
    float line = smoothstep(0.006, 0.0, abs(d));
    float inside = step(d, 0.0) * smoothstep(-0.5, 0.0, d);
    float glow = exp(-abs(d) * 25.0) * 0.35;
    vec3 col = mix(uC1, uC2, clamp((uv.y - p_y) / p_height, 0.0, 1.0));
    float a = line + inside * p_fill * 0.7 + glow;
    return vec4(col + 0.2 * line, clamp(a, 0.0, 1.0));
}"""
        ),
        FxDef(
            "viz_ribbon", "Flowing Ribbons", "Visualizer", FxKind.OVERLAY,
            "Silky light ribbons that sway with the mids.", "🎗️",
            params = listOf(
                P("count", "Ribbons", 1f, 6f, 3f, 1f), P("y", "Position", 0.1f, 0.9f, 0.5f),
                P("amp", "Sway", 0.02f, 0.3f, 0.1f), P("speed", "Speed", 0.1f, 3f, 0.8f),
                P("thick", "Softness", 0.003f, 0.05f, 0.012f),
            ),
            react = React.MID, reactAmt = 0.6f, colors = 2, keywords = "waves lines flow",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec3 acc = vec3(0.0);
    float a = 0.0;
    for (int i = 0; i < 6; i++) {
        if (float(i) >= p_count) break;
        float fi = float(i);
        float sp = specAt((fi + 0.5) / 6.0);
        float y = p_y + sin(uv.x * (3.0 + fi * 1.3) * uAsp + uTime * p_speed * (1.0 + fi * 0.3) + fi * 2.0)
                  * p_amp * (0.5 + sp * 1.4) * AMT
                  + sin(uv.x * 7.0 + uTime * 0.7 + fi) * p_amp * 0.25;
        float d = abs(uv.y - y);
        float g = p_thick / (d + p_thick) ;
        g = g * g * g;
        acc += mix(uC1, uC2, fi / 5.0) * g;
        a += g;
    }
    return vec4(acc / max(a, 0.001), clamp(a * 0.9, 0.0, 1.0));
}"""
        ),
        FxDef(
            "viz_orb", "Pulse Orb", "Visualizer", FxKind.OVERLAY,
            "A soft glowing orb that breathes with the kick drum.", "🔮",
            params = listOf(
                P("size", "Size", 0.04f, 0.4f, 0.14f), P("cy", "Centre Y", 0.1f, 0.9f, 0.5f),
                P("cx", "Centre X", 0.1f, 0.9f, 0.5f), P("halo", "Halo", 0f, 1f, 0.6f),
            ),
            react = React.BASS, reactAmt = 0.8f, colors = 2, defBlend = Blend.SCREEN, keywords = "sun moon glow ball",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 q = centred(uv) - vec2((p_cx - 0.5) * uAsp, p_cy - 0.5);
    float R = p_size * (1.0 + 0.35 * uBass * (0.4 + uR) + 0.1 * uBeat);
    float r = length(q);
    float n = fbm(q * 9.0 / max(R, 0.02) + uTime * 0.3);
    float core = smoothstep(R, R * 0.2, r);
    float halo = exp(-max(r - R, 0.0) * 14.0 / (0.3 + p_halo)) * p_halo * 0.8;
    vec3 col = mix(uC2, uC1, core) + n * 0.25 * core;
    return vec4(col, clamp(core * (0.7 + 0.3 * n) + halo, 0.0, 1.0) * clamp(uI, 0.0, 1.5));
}"""
        ),
        FxDef(
            "viz_rings", "Beat Ripples", "Visualizer", FxKind.OVERLAY,
            "Rings expand outward on every beat.", "🌀",
            params = listOf(
                P("cy", "Centre Y", 0.1f, 0.9f, 0.5f), P("reach", "Reach", 0.2f, 1.2f, 0.7f),
                P("thick", "Thickness", 0.002f, 0.03f, 0.008f), P("count", "Trail", 1f, 4f, 3f, 1f),
            ),
            react = React.BEAT, reactAmt = 0.8f, colors = 2, defBlend = Blend.SCREEN, keywords = "pulse beat wave",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 q = centred(uv) - vec2(0.0, p_cy - 0.5);
    float r = length(q);
    float a = 0.0;
    for (int k = 0; k < 4; k++) {
        if (float(k) >= p_count) break;
        float ph = uPhase + float(k);
        float rr = ph * p_reach * 0.45;
        float fade = 1.0 - ph / (p_count + 0.001);
        a += smoothstep(p_thick * (1.0 + ph), 0.0, abs(r - rr)) * clamp(fade, 0.0, 1.0);
    }
    vec3 col = mix(uC1, uC2, clamp(r * 1.2, 0.0, 1.0));
    return vec4(col, clamp(a * (0.5 + 0.8 * uBeat) * uI, 0.0, 1.0));
}"""
        ),
        FxDef(
            "viz_tunnel", "Beat Tunnel", "Visualizer", FxKind.OVERLAY,
            "Neon rings rushing toward you; faster when the music is louder.", "🕳️",
            params = listOf(
                P("speed", "Speed", 0.1f, 3f, 0.7f), P("density", "Density", 2f, 20f, 8f),
                P("hole", "Centre fade", 0f, 0.4f, 0.12f),
            ),
            react = React.ENERGY, reactAmt = 0.8f, colors = 2, defBlend = Blend.ADD, keywords = "warp rings 3d",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 q = centred(uv);
    float r = length(q) + 0.001;
    float z = 0.25 / r;
    float travel = uTime * p_speed * (1.0 + uR * 2.0);
    float band = fract(z * p_density * 0.15 - travel);
    float line = smoothstep(0.1, 0.0, abs(band - 0.5) - 0.38);
    float spokes = 0.5 + 0.5 * cos(atan(q.y, q.x) * 8.0 + uTime * 0.5);
    float m = smoothstep(p_hole * 0.3, p_hole + 0.2, r);
    vec3 col = mix(uC1, uC2, fract(z * 0.2 - travel * 0.3));
    return vec4(col * (0.6 + 0.6 * uBeat), line * m * (0.25 + 0.35 * spokes) * 0.55 * clamp(uI, 0.0, 1.5));
}"""
        ),
        FxDef(
            "viz_border", "Glow Frame", "Visualizer", FxKind.OVERLAY,
            "A neon frame around the picture that flashes with the beat.", "🖼️",
            params = listOf(P("size", "Thickness", 0.02f, 0.3f, 0.1f), P("hue", "Colour drift", 0f, 1f, 0.4f)),
            react = React.BEAT, reactAmt = 0.7f, colors = 2, defBlend = Blend.SCREEN, keywords = "edge border frame",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float d = min(min(uv.x * uAsp, (1.0 - uv.x) * uAsp), min(uv.y, 1.0 - uv.y));
    float g = exp(-d / max(p_size * 0.25, 0.005));
    vec2 q = centred(uv);
    vec3 col = mix(uC1, uC2, 0.5 + 0.5 * sin(atan(q.y, q.x) * 2.0 + uTime * p_hue * 2.0));
    return vec4(col, clamp(g * (0.35 + 0.9 * uBeat * (0.5 + uR)) * uI, 0.0, 1.0));
}"""
        ),
        FxDef(
            "viz_laser", "Laser Fan", "Visualizer", FxKind.OVERLAY,
            "Light beams sweep out from below, driven by the mids and bass.", "🔦",
            params = listOf(
                P("count", "Beams", 2f, 8f, 5f, 1f), P("spread", "Spread", 0.2f, 1.6f, 0.9f),
                P("width", "Beam width", 0.003f, 0.04f, 0.012f), P("y", "Origin Y", 0f, 0.5f, 0f),
            ),
            react = React.BASS, reactAmt = 0.7f, colors = 2, defBlend = Blend.ADD, keywords = "beam light rays club",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 o = vec2(0.0, p_y - 0.5);
    vec2 q = centred(uv) - o;
    float acc = 0.0;
    vec3 col = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        if (float(i) >= p_count) break;
        float fi = float(i);
        float t = fi / max(p_count - 1.0, 1.0) - 0.5;
        float a = 1.5708 + t * p_spread * 2.0 + sin(uTime * (0.6 + fi * 0.13) + fi * 1.7) * (0.12 + uMid * 0.35);
        vec2 dir = vec2(cos(a), sin(a));
        float along = dot(q, dir);
        float d = abs(q.x * dir.y - q.y * dir.x);
        float beam = along > 0.0 ? (p_width / (d + p_width)) : 0.0;
        beam = beam * beam * smoothstep(0.0, 0.15, along) * exp(-along * 0.9);
        acc += beam;
        col += mix(uC1, uC2, fi / max(p_count - 1.0, 1.0)) * beam;
    }
    return vec4(col / max(acc, 0.001), clamp(acc * (0.35 + 0.8 * uBeat) * uI, 0.0, 1.0));
}"""
        ),
        FxDef(
            "viz_horizon", "Synthwave Horizon", "Retro", FxKind.OVERLAY,
            "A retro sun and scrolling grid; the hills dance to the spectrum.", "🌇",
            params = listOf(
                P("horizon", "Horizon", 0.15f, 0.7f, 0.4f), P("sun", "Sun size", 0.05f, 0.4f, 0.2f),
                P("speed", "Grid speed", 0f, 3f, 1f), P("grid", "Grid", 0f, 1f, 0.8f),
            ),
            react = React.BASS, reactAmt = 0.5f, colors = 2, keywords = "retro 80s outrun sunset grid",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 q = centred(uv);
    float hy = p_horizon - 0.5;
    vec3 col = vec3(0.0);
    float a = 0.0;
    // sun
    vec2 sc = vec2(0.0, hy + p_sun * 0.9);
    float rs = p_sun * (1.0 + 0.08 * uBass * (0.5 + uR));
    float ds = length(q - sc);
    float sunMask = smoothstep(rs, rs - 0.006, ds);
    float slit = step(0.0, sin((q.y - sc.y) * 120.0 + 2.0)) ;
    float lower = smoothstep(0.1, -0.1, q.y - sc.y);
    sunMask *= mix(1.0, slit, lower);
    vec3 sunCol = mix(uC2, uC1, clamp((q.y - sc.y) / rs * 0.5 + 0.5, 0.0, 1.0));
    col += sunCol * sunMask; a = max(a, sunMask);
    a = max(a, exp(-max(ds - rs, 0.0) * 10.0) * 0.35);
    col += uC1 * exp(-max(ds - rs, 0.0) * 10.0) * 0.35;
    // hills on the horizon
    float x = abs(uv.x * 2.0 - 1.0);
    float hh = hy + specAt(x * 0.8) * 0.12 * AMT;
    float hill = smoothstep(0.004, -0.004, q.y - hh) * step(hy - 0.02, q.y);
    col = mix(col, vec3(0.02, 0.0, 0.06), hill); a = max(a, hill);
    // grid below horizon
    if (q.y < hy) {
        float dy = hy - q.y;
        float z = 0.12 / dy;
        float gx = abs(fract(q.x * z * 2.0) - 0.5);
        float gz = abs(fract(z * 3.0 - uTime * p_speed * 0.8) - 0.5);
        float line = max(smoothstep(0.03 * z + 0.02, 0.0, gx), smoothstep(0.06, 0.0, gz));
        line *= smoothstep(0.0, 0.12, dy) * p_grid;
        col = mix(col, uC2 * 1.2, line * 0.8); a = max(a, line * 0.8);
        col = mix(col, vec3(0.03, 0.0, 0.08), 0.35 * (1.0 - a) );
        a = max(a, 0.35);
    }
    return vec4(col, clamp(a, 0.0, 1.0) * clamp(uI, 0.0, 1.0));
}"""
        ),
    )
}
