package com.lofiautopilot.studio.vfx

/** A built-in intro video. They are drawn by shaders, so they need no files and render at any resolution. */
data class IntroDef(val id: String, val name: String, val blurb: String, val lenS: Float, val glsl: String) {
    val uri: String get() = MediaClip.INTRO_PREFIX + id
}

object Intros {
    val all: List<IntroDef> = listOf(
        IntroDef("neongrid", "Neon Grid", "Synthwave sun over a racing grid.", 5f, """
vec3 intro(vec2 uv) {
    vec2 p = (uv - 0.5) * vec2(uAsp, 1.0);
    float hz = 0.04;
    vec3 sky = mix(vec3(0.02, 0.0, 0.08), vec3(0.55, 0.08, 0.45), pow(clamp(uv.y - 0.35, 0.0, 1.0), 0.7));
    vec3 col = sky;
    // sun
    vec2 sc = vec2(0.0, 0.12);
    float sd = length(p - sc);
    float sun = sstep(0.27, 0.265, sd);
    float cut = step(0.5, fract((p.y - sc.y) * 22.0 + uTime * 0.5)) * sstep(0.04, -0.2, p.y - sc.y);
    vec3 sunCol = mix(vec3(1.0, 0.35, 0.5), vec3(1.0, 0.85, 0.3), clamp((p.y - sc.y) * 3.0 + 0.5, 0.0, 1.0));
    col = mix(col, sunCol, sun * (1.0 - cut * 0.9));
    col += vec3(1.0, 0.3, 0.6) * exp(-sd * 3.2) * (0.35 + uBass * 0.5);
    // floor grid in perspective
    if (p.y < hz) {
        float depth = 0.4 / (hz - p.y + 0.001);
        vec2 g = vec2(p.x * depth * 1.2, depth + uTime * 2.2);
        vec2 f = abs(fract(g) - 0.5);
        float line = max(sstep(0.47, 0.5, f.x), sstep(0.47, 0.5, f.y));
        vec3 fl = mix(vec3(0.06, 0.0, 0.14), vec3(0.0, 0.0, 0.05), clamp(depth * 0.03, 0.0, 1.0));
        float fade = exp(-depth * 0.09);
        col = fl + vec3(0.2, 0.9, 1.0) * line * fade * (0.8 + uBeat * 0.6);
        col += vec3(1.0, 0.2, 0.7) * exp(-(hz - p.y) * 18.0) * 0.3;
    }
    // mountains on the horizon driven by the music
    float x = abs(p.x) * 1.4;
    float ridge = hz + 0.02 + specAt(x * 0.8) * 0.16 * sstep(0.0, 0.5, abs(p.x)) + noise(vec2(p.x * 6.0, 1.0)) * 0.03;
    if (p.y > hz && p.y < ridge) col = mix(vec3(0.03, 0.0, 0.1), col, 0.15) + vec3(0.9, 0.2, 0.8) * exp(-(ridge - p.y) * 90.0);
    return col;
}"""),
        IntroDef("vinyl", "Vinyl Dawn", "A record spins up in warm morning light.", 5f, """
vec3 intro(vec2 uv) {
    vec2 p = (uv - 0.5) * vec2(uAsp, 1.0);
    vec3 col = mix(vec3(0.95, 0.62, 0.42), vec3(0.28, 0.14, 0.32), clamp(uv.y * 1.1, 0.0, 1.0));
    col += vec3(1.0, 0.8, 0.5) * exp(-length(p - vec2(0.35, 0.28)) * 3.0) * 0.5;
    float spin = uTime * (2.0 + min(uTime, 2.0) * 3.0);
    vec2 q = rot(spin * 0.3) * (p + vec2(0.0, 0.0));
    float r = length(p);
    float R = 0.36;
    float disc = sstep(R, R - 0.004, r);
    float groove = 0.5 + 0.5 * sin(r * 520.0);
    vec3 vinyl = vec3(0.05) + groove * 0.035;
    float sheen = pow(max(cos(atan(q.y, q.x) * 2.0), 0.0), 8.0) * 0.18 * sstep(0.08, 0.2, r);
    vinyl += sheen;
    float label = sstep(0.115, 0.112, r);
    vec3 labCol = mix(vec3(0.9, 0.35, 0.3), vec3(1.0, 0.8, 0.45), step(0.0, sin(atan(q.y, q.x) * 3.0)));
    vinyl = mix(vinyl, labCol, label);
    vinyl *= 1.0 - sstep(0.012, 0.0, r);
    col = mix(col * 0.9, vinyl, disc);
    col += vec3(1.0, 0.7, 0.5) * exp(-abs(r - R) * 70.0) * (0.25 + uBass * 0.5);
    // floating dust
    for (int i = 0; i < 14; i++) {
        float fi = float(i);
        vec2 d = vec2(fract(hash21(vec2(fi, 3.0)) + uTime * 0.02 * (1.0 + fi * 0.1)) - 0.5, fract(hash21(vec2(fi, 9.0)) - uTime * 0.03) - 0.5) * vec2(uAsp, 1.0);
        col += vec3(1.0, 0.9, 0.8) * sstep(0.006, 0.0, length(p - d)) * 0.5;
    }
    return col;
}"""),
        IntroDef("aurora", "Aurora Night", "Northern lights over a still, starry sky.", 5f, """
vec3 intro(vec2 uv) {
    vec2 p = (uv - 0.5) * vec2(uAsp, 1.0);
    vec3 col = mix(vec3(0.0, 0.01, 0.05), vec3(0.01, 0.06, 0.12), uv.y);
    vec2 s = floor((uv * vec2(uAsp, 1.0)) * 160.0);
    float star = step(0.9965, hash21(s));
    col += star * (0.5 + 0.5 * sin(uTime * 2.0 + hash21(s) * 30.0));
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float y = 0.55 + fi * 0.06 + (fbm(vec2(p.x * 1.4 + uTime * 0.12 + fi * 4.0, fi)) - 0.5) * 0.3 + specAt(abs(p.x) * 0.9) * 0.05;
        float band = exp(-abs(uv.y - y) * 9.0) * sstep(0.25, 0.8, uv.y);
        float rays = 0.55 + 0.45 * noise(vec2(p.x * 22.0 + fi * 9.0, uTime * 0.4));
        vec3 ac = mix(vec3(0.1, 1.0, 0.55), vec3(0.55, 0.3, 1.0), fi * 0.5 + uv.y * 0.4);
        col += ac * band * rays * (0.55 + uMid * 0.6);
    }
    // black hills with a lake reflection
    float h = 0.2 + fbm(vec2(p.x * 2.2, 3.0)) * 0.18;
    if (uv.y < h) col = mix(col * 0.2, vec3(0.0, 0.01, 0.02), 0.8);
    return col;
}"""),
        IntroDef("rain", "Rainy City", "Out-of-focus city lights behind a rainy window.", 5f, """
vec3 intro(vec2 uv) {
    vec2 p = (uv - 0.5) * vec2(uAsp, 1.0);
    vec3 col = mix(vec3(0.02, 0.03, 0.09), vec3(0.08, 0.06, 0.16), uv.y);
    for (int i = 0; i < 26; i++) {
        float fi = float(i);
        vec2 c = vec2(hash21(vec2(fi, 1.0)) - 0.5, hash21(vec2(fi, 2.0)) * 0.5 - 0.15) * vec2(uAsp * 1.1, 1.0);
        float r = 0.035 + hash21(vec2(fi, 5.0)) * 0.06;
        float b = sstep(r, r * 0.55, length(p - c));
        vec3 bc = hsvlike(hash21(vec2(fi, 7.0)));
        col += bc * b * (0.35 + 0.35 * sin(uTime * (0.6 + fi * 0.03) + fi)) * (0.8 + uBeat * 0.4);
    }
    // rain streaks
    for (int j = 0; j < 3; j++) {
        float fj = float(j);
        vec2 g = vec2(p.x * (30.0 + fj * 22.0) + fj * 7.0, p.y * (3.0 + fj) + uTime * (2.4 + fj * 1.1));
        float id = floor(g.x);
        float y = fract(g.y + hash21(vec2(id, fj)));
        float x = abs(fract(g.x) - 0.5);
        col += vec3(0.6, 0.7, 1.0) * sstep(0.06, 0.0, x) * sstep(0.35, 0.0, y) * step(0.6, hash21(vec2(id, fj + 4.0))) * 0.25;
    }
    col *= 0.85 + 0.15 * (1.0 - length(p) * 0.9);
    return col;
}""".replace("hsvlike(hash21(vec2(fi, 7.0)))", "(0.5 + 0.5 * cos(TAU * (hash21(vec2(fi, 7.0)) + vec3(0.0, 0.33, 0.67))))")),
        IntroDef("pulse", "Pulse Rings", "Clean rings pulse out from the centre on every beat.", 4f, """
vec3 intro(vec2 uv) {
    vec2 p = (uv - 0.5) * vec2(uAsp, 1.0);
    float r = length(p);
    vec3 col = vec3(0.015, 0.015, 0.03) + vec3(0.03, 0.05, 0.12) * (1.0 - r);
    float a = atan(p.y, p.x) / TAU + 0.5;
    float sp = specAt(abs(a * 2.0 - 1.0));
    float ring = 0.2 + sp * 0.07 * (0.5 + uBass);
    col += vec3(0.4, 0.75, 1.0) * exp(-abs(r - ring) * 120.0);
    for (int i = 0; i < 4; i++) {
        float fi = float(i);
        float rr = fract(uTime * 0.35 + fi * 0.25) * 0.8;
        col += vec3(0.5, 0.4, 1.0) * exp(-abs(r - rr) * 90.0) * (1.0 - rr / 0.8) * 0.8;
    }
    col += vec3(0.3, 0.6, 1.0) * exp(-r * 6.0) * (0.12 + uBeat * 0.2);
    return col;
}"""),
    )

    val byId: Map<String, IntroDef> = all.associateBy { it.id }
    fun forUri(uri: String): IntroDef? = if (uri.startsWith(MediaClip.INTRO_PREFIX)) byId[uri.removePrefix(MediaClip.INTRO_PREFIX)] else null
}
