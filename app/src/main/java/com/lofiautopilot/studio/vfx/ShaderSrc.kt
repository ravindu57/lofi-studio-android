package com.lofiautopilot.studio.vfx

/** GLSL ES 3.00 sources shared by every effect. Effects only supply `vec4 fx(vec2 uv, vec4 base)`. */
object ShaderSrc {
    const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    private const val HEADER = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uSrc;
uniform sampler2D uSpec;
uniform sampler2D uWave;
uniform sampler2D uTex;
uniform vec2 uRes;
uniform float uAsp;
uniform float uTime;
uniform float uBass, uMid, uHigh, uEnergy, uBeat, uPhase;
uniform float uI;
uniform float uR;
uniform float uP[8];
uniform vec3 uC1, uC2;
uniform vec3 uPal0, uPal1, uPal2;
uniform float uSeed;
uniform float uOp;
uniform int uKind;
uniform int uBlend;
#define AMT (uI * (1.0 + 2.0 * uR))
#define PI 3.14159265359
#define TAU 6.28318530718

// smoothstep() is undefined when edge0 >= edge1, which effects use for "falling" edges; this version is well defined.
float sstep(float a, float b, float x) { float t = clamp((x - a) / (b - a), 0.0, 1.0); return t * t * (3.0 - 2.0 * t); }
vec2 sstep(float a, float b, vec2 x) { vec2 t = clamp((x - a) / (b - a), 0.0, 1.0); return t * t * (3.0 - 2.0 * t); }
vec3 sstep(float a, float b, vec3 x) { vec3 t = clamp((x - a) / (b - a), 0.0, 1.0); return t * t * (3.0 - 2.0 * t); }
#define smoothstep sstep
float hash11(float p) { p = fract(p * 0.1031); p *= p + 33.33; p *= p + p; return fract(p); }
float hash21(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
vec2 hash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}
float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1.0, 0.0)), f.x),
               mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), f.x), f.y);
}
float fbm(vec2 p) {
    float a = 0.5, s = 0.0;
    for (int i = 0; i < 5; i++) { s += a * noise(p); p = p * 2.03 + 17.1; a *= 0.5; }
    return s;
}
mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}
vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
float specAt(float x) { return textureLod(uSpec, vec2(clamp(x, 0.0, 1.0), 0.5), 0.0).r; }
float waveAt(float x) { return textureLod(uWave, vec2(clamp(x, 0.0, 1.0), 0.5), 0.0).r * 2.0 - 1.0; }
vec4 src(vec2 uv) { return textureLod(uSrc, clamp(uv, 0.0, 1.0), 0.0); }
vec2 centred(vec2 uv) { return (uv - 0.5) * vec2(uAsp, 1.0); }
float sdSeg(vec2 p, vec2 a, vec2 b) { vec2 pa = p - a, ba = b - a; float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0); return length(pa - ba * h); }
float sdRoundBox(vec2 p, vec2 b, float r) { vec2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }
vec3 screenBlend(vec3 a, vec3 b) { return 1.0 - (1.0 - a) * (1.0 - b); }
vec3 blur9(vec2 uv, vec2 px) {
    vec3 s = src(uv).rgb * 0.2270270270;
    s += (src(uv + px * 1.3846153846).rgb + src(uv - px * 1.3846153846).rgb) * 0.3162162162;
    s += (src(uv + px * 3.2307692308).rgb + src(uv - px * 3.2307692308).rgb) * 0.0702702703;
    return s;
}
vec3 diskBlur(vec2 uv, float radius, int taps) {
    vec3 acc = vec3(0.0);
    float tot = 0.0;
    float golden = 2.39996323;
    for (int i = 0; i < 40; i++) {
        if (i >= taps) break;
        float fi = float(i);
        float r = sqrt((fi + 0.5) / float(taps)) * radius;
        float a = fi * golden;
        vec2 o = vec2(cos(a), sin(a)) * r * vec2(1.0 / uAsp, 1.0);
        acc += src(uv + o).rgb;
        tot += 1.0;
    }
    return acc / max(tot, 1.0);
}
"""

    private const val FOOTER = """
void main() {
    vec4 base = texture(uSrc, vUv);
    vec4 r = fx(vUv, base);
    vec3 col;
    if (uKind == 0) {
        col = r.rgb;
    } else {
        float a = clamp(r.a, 0.0, 1.0);
        vec3 ov = r.rgb;
        if (uBlend == 1) col = base.rgb + ov * a;
        else if (uBlend == 2) col = screenBlend(base.rgb, ov * a);
        else if (uBlend == 3) {
            vec3 lo = 2.0 * base.rgb * ov;
            vec3 hi = 1.0 - 2.0 * (1.0 - base.rgb) * (1.0 - ov);
            col = mix(base.rgb, mix(lo, hi, step(0.5, base.rgb)), a);
        } else col = mix(base.rgb, ov, a);
    }
    fragColor = vec4(mix(base.rgb, clamp(col, 0.0, 1.0), uOp), 1.0);
}
"""

    fun fragment(def: FxDef): String {
        val defines = def.params.withIndex().joinToString("\n") { (i, p) -> "#define p_${p.key} uP[$i]" }
        return HEADER + defines + "\n" + def.glsl + FOOTER
    }

    /** Draws the picture (or a video frame) into the first buffer, applying the camera transform. */
    fun source(external: Boolean): String {
        val ext = if (external) "#extension GL_OES_EGL_image_external_essl3 : require\n" else ""
        val sampler = if (external) "samplerExternalOES" else "sampler2D"
        return """#version 300 es
$ext
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform $sampler uImg;
uniform vec2 uOut;      // output size
uniform vec2 uImgSize;  // picture size
uniform vec4 uCam;      // scale, rotation, tx, ty
uniform int uFit;       // 0 cover, 1 contain over blurred backdrop
uniform mat4 uTexMat;   // SurfaceTexture transform (identity for stills)
uniform int uFlip;      // 1 for still pictures (first row = top)
vec4 pick(vec2 p) {
    if (uFlip == 1) p.y = 1.0 - p.y;
    vec2 t = (uTexMat * vec4(p, 0.0, 1.0)).xy;
    return texture(uImg, t);
}
vec4 blurred(vec2 p) {
    vec4 s = vec4(0.0);
    for (int i = 0; i < 12; i++) {
        float a = float(i) * 2.39996323;
        float r = sqrt((float(i) + 0.5) / 12.0) * 0.035;
        s += pick(p + vec2(cos(a), sin(a)) * r);
    }
    return s / 12.0;
}
void main() {
    float outA = uOut.x / uOut.y;
    float imgA = uImgSize.x / uImgSize.y;
    vec2 p = (vUv - 0.5) * vec2(outA, 1.0);
    float c = cos(uCam.y), s = sin(uCam.y);
    p = mat2(c, -s, s, c) * p;
    p = p / uCam.x - vec2(uCam.z * outA, uCam.w);
    // p is now in "output height = 1" space. Map to picture uv.
    vec2 uv;
    if (uFit == 0) {
        // size of the picture in "output height = 1" units, scaled so it covers the frame
        vec2 size = outA > imgA ? vec2(outA, outA / imgA) : vec2(imgA, 1.0);
        uv = p / size + 0.5;
        fragColor = vec4(pick(clamp(uv, 0.0, 1.0)).rgb, 1.0);
    } else {
        vec2 size = outA > imgA ? vec2(imgA, 1.0) : vec2(outA, outA / imgA);
        vec2 cover = outA > imgA ? vec2(outA, outA / imgA) : vec2(imgA, 1.0);
        vec2 uvc = p / cover + 0.5;
        vec3 bg = blurred(clamp(uvc, 0.0, 1.0)).rgb * 0.55;
        uv = p / size + 0.5;
        vec2 inside = step(vec2(0.0), uv) * step(uv, vec2(1.0));
        float m = inside.x * inside.y;
        vec3 fg = pick(clamp(uv, 0.0, 1.0)).rgb;
        fragColor = vec4(mix(bg, fg, m), 1.0);
    }
}
"""
    }

    private const val TRANS_HEADER = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uA;
uniform sampler2D uB;
uniform float uProg;
uniform float uTime;
uniform float uAsp;
uniform float uBeat;
uniform float uBass;
#define PI 3.14159265359
float sstep(float a, float b, float x) { float t = clamp((x - a) / (b - a), 0.0, 1.0); return t * t * (3.0 - 2.0 * t); }
float hash21(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1.0, 0.0)), f.x), mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), f.x), f.y);
}
float fbm(vec2 p) { float a = 0.5, s = 0.0; for (int i = 0; i < 4; i++) { s += a * noise(p); p = p * 2.03 + 17.1; a *= 0.5; } return s; }
mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }
vec4 A(vec2 uv) { return textureLod(uA, clamp(uv, 0.0, 1.0), 0.0); }
vec4 B(vec2 uv) { return textureLod(uB, clamp(uv, 0.0, 1.0), 0.0); }
"""

    fun transition(def: TransitionDef): String =
        TRANS_HEADER + def.glsl + "\nvoid main() { fragColor = vec4(tr(vUv, uProg).rgb, 1.0); }\n"

    /** Built-in intro clips: they generate their own picture. `intro(uv)` returns the colour; the title is added on top. */
    fun intro(def: IntroDef): String = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uSpec;
uniform sampler2D uWave;
uniform sampler2D uTitle;
uniform float uHasTitle;
uniform float uTime;
uniform float uLen;
uniform float uAsp;
uniform float uBass, uMid, uHigh, uEnergy, uBeat;
#define PI 3.14159265359
#define TAU 6.28318530718
float sstep(float a, float b, float x) { float t = clamp((x - a) / (b - a), 0.0, 1.0); return t * t * (3.0 - 2.0 * t); }
float hash21(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1.0, 0.0)), f.x), mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), f.x), f.y);
}
float fbm(vec2 p) { float a = 0.5, s = 0.0; for (int i = 0; i < 5; i++) { s += a * noise(p); p = p * 2.03 + 17.1; a *= 0.5; } return s; }
mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }
float specAt(float x) { return textureLod(uSpec, vec2(clamp(x, 0.0, 1.0), 0.5), 0.0).r; }
${def.glsl}
void main() {
    vec3 col = intro(vUv);
    float tw = uAsp > 1.0 ? 0.46 : 0.86;
    float th = tw * uAsp / 4.0;
    vec2 tu = vec2((vUv.x - 0.5) / tw + 0.5, (vUv.y - 0.5) / th + 0.5);
    float inside = step(0.0, tu.x) * step(tu.x, 1.0) * step(0.0, tu.y) * step(tu.y, 1.0);
    float m = texture(uTitle, vec2(tu.x, 1.0 - tu.y)).a * inside * uHasTitle;
    float show = sstep(0.5, 1.3, uTime) * (1.0 - sstep(uLen - 0.6, uLen, uTime));
    float glow = 0.0;
    for (int i = 0; i < 6; i++) {
        float a = float(i) * 1.0472;
        vec2 o = vec2(cos(a), sin(a)) * 0.012;
        glow += texture(uTitle, vec2(tu.x + o.x, 1.0 - (tu.y + o.y))).a;
    }
    glow = glow / 6.0 * uHasTitle * show;
    col = col + glow * 0.35 * vec3(0.8, 0.85, 1.0);
    col = mix(col, vec3(1.0), m * show);
    col *= sstep(0.0, 0.5, uTime);
    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
"""

    /** Plain copy used for the final blit and for effect thumbnails. */
    const val BLIT = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uSrc;
void main() { fragColor = vec4(texture(uSrc, vUv).rgb, 1.0); }
"""
}
