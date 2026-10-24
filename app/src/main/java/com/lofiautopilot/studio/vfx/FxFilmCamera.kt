package com.lofiautopilot.studio.vfx

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private fun P(key: String, label: String, min: Float, max: Float, def: Float, step: Float = 0f) =
    Param(key, label, min, max, def, step)

/** Film texture, depth tricks and the virtual camera. */
object FxFilm {
    val all: List<FxDef> = listOf(
        FxDef(
            "ft_grain", "Film Grain", "Film", FxKind.FILTER,
            "Fine animated grain for an analog feel.", "🎞️",
            params = listOf(P("amount", "Amount", 0f, 1f, 0.3f), P("size", "Grain size", 0.5f, 3f, 1.2f), P("color", "Colour grain", 0f, 1f, 0.3f)),
            react = React.OFF, keywords = "noise analog texture",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float t = floor(uTime * 24.0);
    vec2 g = floor(uv * uRes / p_size);
    float n = hash21(g + t * 1.7) - 0.5;
    vec3 cn = vec3(hash21(g + t + 11.0), hash21(g + t + 23.0), hash21(g + t + 37.0)) - 0.5;
    float l = luma(base.rgb);
    float w = 1.0 - abs(l - 0.5) * 1.2;
    vec3 col = base.rgb + (vec3(n) + cn * p_color) * p_amount * 0.35 * w * uI;
    return vec4(col, 1.0);
}"""
        ),
        FxDef(
            "ft_dust", "Dust & Scratches", "Film", FxKind.OVERLAY,
            "Old-film specks, hairs and vertical scratches.", "🧵",
            params = listOf(P("dust", "Dust", 0f, 1f, 0.5f), P("scratch", "Scratches", 0f, 1f, 0.4f)),
            react = React.OFF, colors = 0, defBlend = Blend.NORMAL, keywords = "vintage old film damage",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float t = floor(uTime * 12.0);
    vec2 p = centred(uv) * 30.0;
    vec2 id = floor(p);
    vec2 f = fract(p) - 0.5;
    float present = step(1.0 - 0.06 * p_dust, hash21(id + t * 3.3));
    vec2 o = (hash22(id + t) - 0.5) * 0.6;
    float speck = smoothstep(0.18, 0.0, length(f - o)) * present;
    float sx = hash11(t + 7.0);
    float sc = step(1.0 - 0.45 * p_scratch, hash11(t + 13.0));
    float scratch = smoothstep(0.0015, 0.0, abs(uv.x - sx - (noise(vec2(uv.y * 6.0, t)) - 0.5) * 0.01)) * sc * 0.6;
    float a = clamp(speck * 0.9 + scratch, 0.0, 1.0);
    return vec4(vec3(0.95, 0.93, 0.88), a * uI);
}"""
        ),
        FxDef(
            "ft_bars", "Cinema Bars", "Film", FxKind.FILTER,
            "Widescreen black bars top and bottom.", "🎥",
            params = listOf(P("size", "Bar size", 0f, 0.3f, 0.1f), P("soft", "Softness", 0f, 0.05f, 0.002f)),
            react = React.OFF, keywords = "letterbox widescreen movie",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float d = min(uv.y, 1.0 - uv.y);
    float m = smoothstep(p_size * uI, p_size * uI + p_soft + 0.0005, d);
    return vec4(base.rgb * m, 1.0);
}"""
        ),
        FxDef(
            "ft_tape", "Tape Wobble", "Film", FxKind.FILTER,
            "Slow wow-and-flutter warping and a warm, worn colour cast.", "📻",
            params = listOf(P("wobble", "Wobble", 0f, 1f, 0.4f), P("warm", "Warmth", 0f, 1f, 0.4f), P("soft", "Softness", 0f, 1f, 0.3f)),
            react = React.BASS, reactAmt = 0.3f, keywords = "lofi cassette flutter wow",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float w = sin(uTime * 0.9 + uv.y * 3.0) * 0.0012 + sin(uTime * 2.3 + uv.y * 11.0) * 0.0006;
    vec2 u = vec2(uv.x + w * p_wobble * AMT, uv.y + sin(uTime * 0.6) * 0.0009 * p_wobble);
    vec3 c = mix(src(u).rgb, blur9(u, vec2(1.2 / uRes.x, 0.0)), p_soft * 0.7);
    c = mix(c, c * vec3(1.08, 0.98, 0.86) + vec3(0.02, 0.01, 0.0), p_warm * 0.8);
    c = mix(vec3(luma(c)), c, 0.92);
    return vec4(mix(base.rgb, c, clamp(uI, 0.0, 1.0)), 1.0);
}"""
        ),
        FxDef(
            "ft_halation", "Halation", "Film", FxKind.FILTER,
            "A warm red-orange halo around bright lights.", "🟠",
            params = listOf(P("threshold", "Threshold", 0.3f, 0.95f, 0.6f), P("radius", "Spread", 0.005f, 0.06f, 0.025f), P("strength", "Strength", 0f, 2f, 0.9f)),
            react = React.ENERGY, reactAmt = 0.3f, colors = 1, keywords = "glow film red",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec3 acc = vec3(0.0);
    float golden = 2.39996323;
    for (int i = 0; i < 20; i++) {
        float fi = float(i);
        float r = sqrt((fi + 0.5) / 20.0) * p_radius;
        vec2 o = vec2(cos(fi * golden), sin(fi * golden)) * r * vec2(1.0 / uAsp, 1.0);
        vec3 c = src(uv + o).rgb;
        acc += vec3(luma(c)) * smoothstep(p_threshold, 1.0, luma(c));
    }
    acc /= 20.0;
    vec3 tint = mix(vec3(1.0, 0.35, 0.12), uC1, 0.35);
    return vec4(base.rgb + acc * tint * p_strength * AMT * 1.6, 1.0);
}"""
        ),
        FxDef(
            "ft_sketch", "Pencil Sketch", "Film", FxKind.FILTER,
            "Turns the picture into a hand-drawn sketch.", "✏️",
            params = listOf(P("mix", "Amount", 0f, 1f, 0.8f), P("color", "Keep colour", 0f, 1f, 0.35f)),
            react = React.OFF, keywords = "drawing pencil lineart",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 px = 1.2 / uRes;
    float c = luma(base.rgb);
    float e = abs(luma(src(uv + vec2(px.x, 0.0)).rgb) - c) + abs(luma(src(uv + vec2(0.0, px.y)).rgb) - c)
            + abs(luma(src(uv - vec2(px.x, 0.0)).rgb) - c) + abs(luma(src(uv - vec2(0.0, px.y)).rgb) - c);
    float line = 1.0 - clamp(e * 5.0, 0.0, 1.0);
    float paper = 0.93 + 0.05 * noise(uv * uRes * 0.5);
    vec3 sk = vec3(line * paper);
    sk = mix(sk, sk * base.rgb * 1.3, p_color);
    return vec4(mix(base.rgb, sk, p_mix * clamp(uI, 0.0, 1.0)), 1.0);
}"""
        ),
        FxDef(
            "d_parallax", "Depth Parallax", "Depth", FxKind.FILTER,
            "Fakes 3D: nearer parts of the picture slide more than far ones.", "🧊",
            params = listOf(P("amount", "Amount", 0f, 1f, 0.4f), P("speed", "Speed", 0f, 2f, 0.35f), P("depthbias", "Foreground bias", 0f, 1f, 0.6f)),
            react = React.BASS, reactAmt = 0.4f, keywords = "3d pseudo camera move",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float l = luma(base.rgb);
    float depth = mix(1.0 - uv.y, l, 1.0 - p_depthbias) ;
    depth = mix(depth, 1.0 - uv.y, p_depthbias);
    vec2 sway = vec2(sin(uTime * p_speed), cos(uTime * p_speed * 0.7) * 0.5);
    vec2 off = (depth - 0.45) * sway * 0.02 * p_amount * AMT;
    vec2 u = uv + off;
    vec3 c = src(u).rgb;
    return vec4(c, 1.0);
}"""
        ),
        FxDef(
            "d_tilt", "Tilt-Shift", "Depth", FxKind.FILTER,
            "Blur above and below a sharp band: a miniature-world look.", "🏙️",
            params = listOf(P("focus", "Focus line", 0.1f, 0.9f, 0.5f), P("band", "Sharp band", 0.05f, 0.5f, 0.2f), P("blur", "Blur", 0.002f, 0.04f, 0.014f)),
            react = React.OFF, keywords = "miniature blur depth of field",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float d = max(abs(uv.y - p_focus) - p_band, 0.0);
    float r = clamp(d * 2.5, 0.0, 1.0) * p_blur * uI;
    if (r < 0.0007) return base;
    return vec4(diskBlur(uv, r, 18), 1.0);
}"""
        ),
        FxDef(
            "d_dreamedge", "Soft Edges", "Depth", FxKind.FILTER,
            "The outer edges go soft and out of focus, pulling the eye inward.", "🫧",
            params = listOf(P("blur", "Blur", 0.002f, 0.05f, 0.016f), P("size", "Clear centre", 0.1f, 0.9f, 0.4f)),
            react = React.BASS, reactAmt = 0.3f, keywords = "vignette blur focus portrait",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float d = length(centred(uv));
    float r = smoothstep(p_size, p_size + 0.55, d) * p_blur * AMT;
    if (r < 0.0007) return base;
    return vec4(diskBlur(uv, r, 18), 1.0);
}"""
        ),
    )
}

/** Camera moves are CPU-evaluated and merged into a single transform in the source pass. */
object FxCamera {
    val all: List<FxDef> = listOf(
        FxDef(
            "cam_kenburns", "Slow Push (Ken Burns)", "Camera", FxKind.CAMERA,
            "A slow cinematic zoom and pan that drifts back and forth.", "🎥",
            params = listOf(P("zoom", "Zoom amount", 0f, 0.5f, 0.12f), P("panx", "Pan X", -0.2f, 0.2f, 0.04f), P("pany", "Pan Y", -0.2f, 0.2f, 0.02f), P("period", "Cycle (s)", 10f, 300f, 90f)),
            keywords = "zoom pan slow cinematic documentary",
        ),
        FxDef(
            "cam_beatzoom", "Beat Zoom", "Camera", FxKind.CAMERA,
            "A quick zoom punch on every beat.", "🥁",
            params = listOf(P("zoom", "Punch", 0f, 0.25f, 0.05f)),
            react = React.BEAT, reactAmt = 1f, keywords = "pulse bounce kick",
        ),
        FxDef(
            "cam_shake", "Bass Shake", "Camera", FxKind.CAMERA,
            "The camera shudders with the low end.", "📳",
            params = listOf(P("amount", "Amount", 0f, 0.05f, 0.012f), P("rough", "Roughness", 0.5f, 8f, 3f)),
            react = React.BASS, reactAmt = 1f, keywords = "rumble handheld earthquake",
        ),
        FxDef(
            "cam_sway", "Gentle Sway", "Camera", FxKind.CAMERA,
            "A slow rocking tilt, like a boat or a dream.", "🛶",
            params = listOf(P("angle", "Angle (°)", 0f, 6f, 1.2f), P("period", "Cycle (s)", 3f, 60f, 14f), P("zoom", "Safety zoom", 0f, 0.2f, 0.04f)),
            keywords = "rock tilt float roll",
        ),
        FxDef(
            "cam_float", "Float & Breathe", "Camera", FxKind.CAMERA,
            "A subtle hovering drift with a breathing zoom. Perfect for a still image.", "🎈",
            params = listOf(P("drift", "Drift", 0f, 0.1f, 0.015f), P("breath", "Breathing", 0f, 0.1f, 0.02f), P("period", "Cycle (s)", 4f, 60f, 16f)),
            keywords = "hover idle alive subtle",
        ),
        FxDef(
            "cam_orbit", "Orbit", "Camera", FxKind.CAMERA,
            "The view circles slowly around the centre of the picture.", "🪐",
            params = listOf(P("radius", "Radius", 0f, 0.15f, 0.03f), P("period", "Cycle (s)", 4f, 120f, 30f), P("zoom", "Zoom", 0f, 0.3f, 0.08f)),
            keywords = "circle rotate drift",
        ),
    )

    /** Camera state: scale, rotation (rad), tx, ty. */
    class Cam(var scale: Float = 1f, var rot: Float = 0f, var tx: Float = 0f, var ty: Float = 0f)

    fun apply(cam: Cam, layer: Layer, def: FxDef, t: Float, react: Float, intensity: Float) {
        fun v(k: String) = layer.param(def, k)
        val tau = (2 * PI).toFloat()
        when (def.id) {
            "cam_kenburns" -> {
                val ph = 0.5f - 0.5f * cos(tau * t / v("period"))
                cam.scale *= 1f + v("zoom") * ph * intensity
                cam.tx += v("panx") * (ph - 0.5f) * intensity
                cam.ty += v("pany") * (ph - 0.5f) * intensity
            }
            "cam_beatzoom" -> cam.scale *= 1f + v("zoom") * react * intensity
            "cam_shake" -> {
                val a = v("amount") * react * intensity
                val r = v("rough")
                cam.tx += a * (sin(t * 37f * r / 3f) + 0.6f * sin(t * 61f * r / 3f + 1f))
                cam.ty += a * (sin(t * 43f * r / 3f + 2f) + 0.6f * sin(t * 71f * r / 3f))
                cam.scale *= 1f + a * 2f
            }
            "cam_sway" -> {
                cam.rot += Math.toRadians((v("angle") * sin(tau * t / v("period"))).toDouble()).toFloat() * intensity
                cam.scale *= 1f + v("zoom") * intensity
            }
            "cam_float" -> {
                val p = v("period")
                cam.tx += v("drift") * sin(tau * t / p) * intensity
                cam.ty += v("drift") * 0.7f * sin(tau * t / (p * 1.37f) + 1f) * intensity
                cam.scale *= 1f + v("breath") * (0.5f + 0.5f * sin(tau * t / (p * 0.8f))) * intensity + 0.02f * intensity
            }
            "cam_orbit" -> {
                val p = v("period")
                cam.tx += v("radius") * cos(tau * t / p) * intensity
                cam.ty += v("radius") * sin(tau * t / p) * intensity
                cam.scale *= 1f + v("zoom") * intensity
            }
        }
    }
}
