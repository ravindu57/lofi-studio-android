package com.lofiautopilot.studio.vfx

/** A cross-clip transition. [glsl] defines `vec4 tr(vec2 uv, float p)`; A(uv) is the outgoing clip, B(uv) the incoming one, p goes 0 -> 1. */
data class TransitionDef(val id: String, val name: String, val emoji: String, val glsl: String, val mood: String = "calm")

object Transitions {
    val NONE = "none"

    val all: List<TransitionDef> = listOf(
        TransitionDef("fade", "Crossfade", "🌫️", "vec4 tr(vec2 uv, float p) { return mix(A(uv), B(uv), p); }"),
        TransitionDef("dip_black", "Dip to black", "⬛", """
vec4 tr(vec2 uv, float p) {
    float k = abs(p * 2.0 - 1.0);
    return mix(vec4(0.0, 0.0, 0.0, 1.0), p < 0.5 ? A(uv) : B(uv), sstep(0.0, 1.0, k));
}"""),
        TransitionDef("flash", "Light flash", "⚡", """
vec4 tr(vec2 uv, float p) {
    float k = 1.0 - abs(p * 2.0 - 1.0);
    vec4 c = p < 0.5 ? A(uv) : B(uv);
    return vec4(mix(c.rgb, vec3(1.0, 0.97, 0.9), k * k), 1.0);
}""", "loud"),
        TransitionDef("slide_left", "Slide left", "⬅️", """
vec4 tr(vec2 uv, float p) {
    return uv.x < 1.0 - p ? A(uv + vec2(p, 0.0)) : B(uv - vec2(1.0 - p, 0.0));
}"""),
        TransitionDef("slide_right", "Slide right", "➡️", """
vec4 tr(vec2 uv, float p) {
    return uv.x > p ? A(uv - vec2(p, 0.0)) : B(uv + vec2(1.0 - p, 0.0));
}"""),
        TransitionDef("slide_up", "Slide up", "⬆️", """
vec4 tr(vec2 uv, float p) {
    return uv.y < 1.0 - p ? A(uv + vec2(0.0, p)) : B(uv - vec2(0.0, 1.0 - p));
}"""),
        TransitionDef("cover", "Cover", "🗂️", """
vec4 tr(vec2 uv, float p) {
    float edge = uv.x - (1.0 - p);
    if (edge > 0.0) return B(uv - vec2(1.0 - p, 0.0));
    float sh = exp(edge * 9.0) * 0.55;
    return vec4(A(uv).rgb * (1.0 - sh), 1.0);
}"""),
        TransitionDef("wipe", "Soft wipe", "🪟", """
vec4 tr(vec2 uv, float p) {
    float d = (uv.x + uv.y * 0.35) / 1.35;
    float m = sstep(p * 1.3 - 0.3, p * 1.3, d);
    return mix(B(uv), A(uv), m);
}"""),
        TransitionDef("blinds", "Blinds", "🎚️", """
vec4 tr(vec2 uv, float p) {
    float cell = fract(uv.x * 10.0);
    float m = sstep(p * 1.2 - 0.2, p * 1.2, cell);
    return mix(B(uv), A(uv), m);
}"""),
        TransitionDef("iris", "Iris", "⭕", """
vec4 tr(vec2 uv, float p) {
    vec2 q = (uv - 0.5) * vec2(uAsp, 1.0);
    float r = length(q);
    float R = p * 1.1;
    float m = sstep(R - 0.04, R, r);
    float ring = exp(-abs(r - R) * 60.0) * 0.5 * (1.0 - p);
    return vec4(mix(B(uv), A(uv), m).rgb + ring, 1.0);
}"""),
        TransitionDef("zoom", "Zoom through", "🔍", """
vec4 tr(vec2 uv, float p) {
    vec2 c = uv - 0.5;
    vec4 a = A(0.5 + c / (1.0 + p * 0.9));
    vec4 b = B(0.5 + c * (1.0 + (1.0 - p) * 0.9));
    return mix(a, b, sstep(0.25, 0.75, p));
}"""),
        TransitionDef("zoom_blur", "Zoom blur", "💨", """
vec4 tr(vec2 uv, float p) {
    float k = sin(p * PI);
    vec2 d = (uv - 0.5) * 0.12 * k;
    vec4 a = vec4(0.0), b = vec4(0.0);
    for (int i = 0; i < 10; i++) {
        float f = float(i) / 10.0;
        a += A(uv - d * f * 2.0);
        b += B(uv - d * f * 2.0);
    }
    return mix(a / 10.0, b / 10.0, sstep(0.3, 0.7, p));
}""", "loud"),
        TransitionDef("spin", "Spin", "🌀", """
vec4 tr(vec2 uv, float p) {
    vec2 q = (uv - 0.5) * vec2(uAsp, 1.0);
    float k = sin(p * PI);
    float a = k * 1.6 * (p < 0.5 ? 1.0 : -1.0);
    q = rot(a) * q / (1.0 + k * 0.35);
    vec2 u = q / vec2(uAsp, 1.0) + 0.5;
    return mix(A(u), B(u), sstep(0.35, 0.65, p));
}""", "loud"),
        TransitionDef("pixelate", "Pixelate", "🟦", """
vec4 tr(vec2 uv, float p) {
    float k = sin(p * PI);
    float n = mix(900.0, 14.0, k);
    vec2 u = (floor(uv * vec2(n * uAsp, n)) + 0.5) / vec2(n * uAsp, n);
    return mix(A(u), B(u), sstep(0.4, 0.6, p));
}""", "loud"),
        TransitionDef("glitch", "Glitch", "📺", """
vec4 tr(vec2 uv, float p) {
    float k = sin(p * PI);
    float t = floor(uTime * 24.0);
    float row = floor(uv.y * 24.0);
    float shift = (hash21(vec2(row, t)) - 0.5) * 0.25 * k * step(0.55, hash21(vec2(row * 1.7, t)));
    vec2 u = vec2(uv.x + shift, uv.y);
    float s = 0.012 * k;
    vec4 a = vec4(A(u + vec2(s, 0.0)).r, A(u).g, A(u - vec2(s, 0.0)).b, 1.0);
    vec4 b = vec4(B(u + vec2(s, 0.0)).r, B(u).g, B(u - vec2(s, 0.0)).b, 1.0);
    float m = step(hash21(vec2(row, t + 7.0)), p);
    return mix(a, b, m);
}""", "loud"),
        TransitionDef("dissolve", "Noise dissolve", "🌊", """
vec4 tr(vec2 uv, float p) {
    float n = fbm(uv * vec2(uAsp, 1.0) * 5.0);
    float m = sstep(p * 1.4 - 0.4, p * 1.4, n);
    vec4 c = mix(B(uv), A(uv), m);
    float edge = exp(-abs(n - (p * 1.4 - 0.2)) * 30.0) * 0.4 * sin(p * PI);
    return vec4(c.rgb + edge * vec3(1.0, 0.8, 0.6), 1.0);
}"""),
        TransitionDef("ripple", "Ripple", "💧", """
vec4 tr(vec2 uv, float p) {
    vec2 q = (uv - 0.5) * vec2(uAsp, 1.0);
    float r = length(q);
    float w = sin(r * 38.0 - p * 18.0) * 0.018 * sin(p * PI) / (1.0 + r * 4.0);
    vec2 u = uv + normalize(q + 1e-5) * w / vec2(uAsp, 1.0);
    return mix(A(u), B(u), sstep(0.3, 0.7, p));
}"""),
        TransitionDef("beat_pulse", "Beat pulse", "🥁", """
vec4 tr(vec2 uv, float p) {
    float k = sin(p * PI) * (0.5 + uBeat);
    vec2 u = 0.5 + (uv - 0.5) / (1.0 + k * 0.25);
    vec4 c = mix(A(u), B(u), sstep(0.3, 0.7, p));
    return vec4(c.rgb * (1.0 + k * 0.35), 1.0);
}""", "loud"),
    )

    val byId: Map<String, TransitionDef> = all.associateBy { it.id }
    fun get(id: String) = byId[id]
    fun name(id: String) = if (id == NONE) "None" else byId[id]?.name ?: id

    /** Smooth ease-in-out so the move starts and ends gently. */
    fun ease(p: Float): Float { val x = p.coerceIn(0f, 1f); return x * x * x * (x * (x * 6f - 15f) + 10f) }

    val calm = all.filter { it.mood == "calm" }.map { it.id }
    val loud = all.filter { it.mood == "loud" }.map { it.id }
}
