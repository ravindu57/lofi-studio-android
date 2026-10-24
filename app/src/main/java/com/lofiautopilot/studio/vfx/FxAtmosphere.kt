package com.lofiautopilot.studio.vfx

private fun P(key: String, label: String, min: Float, max: Float, def: Float, step: Float = 0f) =
    Param(key, label, min, max, def, step)

/** Weather, particles and mood. These bring a still picture to life. */
object FxAtmosphere {
    /** Shared particle field helper, prepended to the particle effects. */
    private const val PARTICLES = """
float particles(vec2 uv, float scale, vec2 vel, float size, float soft, float seed, float twinkle, float density) {
    vec2 p = centred(uv) * scale + vec2(seed * 13.1, seed * 7.7) + vel * uTime;
    vec2 id = floor(p);
    vec2 f = fract(p) - 0.5;
    vec2 rnd = hash22(id + seed);
    float present = step(1.0 - density, hash21(id + seed * 3.1 + 11.0));
    vec2 pos = (rnd - 0.5) * 0.5;
    pos += 0.12 * vec2(sin(uTime * (0.5 + rnd.x) + rnd.y * TAU), cos(uTime * (0.4 + rnd.y) + rnd.x * TAU));
    float d = length(f - pos);
    float sz = size * (0.45 + rnd.x * 0.9);
    float a = smoothstep(sz, sz * (1.0 - soft), d);
    float tw = mix(1.0, 0.5 + 0.5 * sin(uTime * (1.0 + rnd.y * 3.0) + rnd.x * TAU), twinkle);
    return a * tw * present;
}
"""

    val all: List<FxDef> = listOf(
        FxDef(
            "fx_rain", "Rain", "Atmosphere", FxKind.OVERLAY,
            "Falling rain in three depth layers.", "🌧️",
            params = listOf(
                P("density", "Amount", 0.1f, 1f, 0.6f), P("speed", "Speed", 0.3f, 3f, 1.3f),
                P("length", "Streak length", 0.02f, 0.25f, 0.1f), P("angle", "Wind", -0.6f, 0.6f, 0.12f),
            ),
            react = React.ENERGY, reactAmt = 0.3f, colors = 1, defBlend = Blend.SCREEN, keywords = "storm weather wet",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float a = 0.0;
    for (int l = 0; l < 3; l++) {
        float fl = float(l);
        float cols = 26.0 + fl * 22.0;
        vec2 p = vec2(uv.x * uAsp + uv.y * p_angle, uv.y);
        float id = floor(p.x * cols);
        float rnd = hash11(id + fl * 31.7);
        float on = step(1.0 - p_density * (0.55 - fl * 0.1), hash11(id * 1.37 + fl * 5.1));
        float y = fract(p.y + uTime * p_speed * (0.7 + rnd * 0.6 + fl * 0.35) + rnd * 9.0);
        float len = p_length * (0.6 + fl * 0.35);
        float streak = smoothstep(len, 0.0, y);
        float across = smoothstep(0.07 + fl * 0.02, 0.0, abs(fract(p.x * cols) - 0.5) * 0.9 - 0.015);
        a += streak * across * on * (0.45 + 0.2 * fl);
    }
    return vec4(mix(vec3(0.75, 0.85, 1.0), uC1, 0.5), clamp(a * 0.7 * AMT, 0.0, 1.0));
}"""
        ),
        FxDef(
            "fx_glass", "Rainy Window", "Atmosphere", FxKind.FILTER,
            "Raindrops sliding down a window that bend the scene behind.", "🪟",
            params = listOf(
                P("size", "Drop size", 0.5f, 3f, 1.3f), P("amount", "Amount", 0.1f, 1f, 0.6f),
                P("speed", "Slide speed", 0f, 2f, 0.6f), P("blur", "Frosted blur", 0f, 1f, 0.35f),
            ),
            react = React.OFF, keywords = "drops condensation glass window refraction",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 p = vec2(uv.x * uAsp, uv.y) * (5.0 / p_size);
    vec2 offs = vec2(0.0);
    float shade = 0.0;
    for (int l = 0; l < 2; l++) {
        float fl = float(l);
        vec2 q = p * (1.0 + fl * 0.8) + vec2(fl * 7.3, 0.0);
        q.y += uTime * p_speed * 0.12 * (1.0 + fl * 0.5);
        vec2 id = floor(q);
        vec2 f = fract(q) - 0.5;
        vec2 r = hash22(id + fl * 3.0);
        float exists = step(1.0 - p_amount, r.x);
        float slide = fract(uTime * p_speed * 0.05 * (0.5 + r.y) + r.x * 5.0);
        vec2 c = vec2((r.x - 0.5) * 0.4, 0.3 - slide * 0.6);
        vec2 d = f - c;
        d.y *= 0.8;
        float rad = 0.12 + 0.06 * r.y;
        float dist = length(d);
        float body = smoothstep(rad, rad * 0.55, dist) * exists;
        float trail = smoothstep(0.02, 0.0, abs(f.x - c.x)) * step(f.y, c.y + 0.02) * step(c.y - 0.5 + slide * 0.2, f.y) * 0.25 * exists;
        offs += normalize(d + 0.0001) * body * (rad - dist) * 0.9;
        shade += body * 0.35 + trail * 0.2;
    }
    vec2 uv2 = uv - offs * 0.05 * vec2(1.0 / uAsp, 1.0) * 3.0;
    vec3 sharp = src(uv2).rgb;
    vec3 soft = diskBlur(uv, 0.012 * p_blur, 12);
    float mist = p_blur * (1.0 - clamp(shade * 3.0, 0.0, 1.0));
    vec3 col = mix(sharp, soft, mist * 0.8);
    col += shade * 0.12;
    return vec4(mix(base.rgb, col, clamp(uI, 0.0, 1.0)), 1.0);
}"""
        ),
        FxDef(
            "fx_snow", "Snowfall", "Atmosphere", FxKind.OVERLAY,
            "Soft flakes drifting down in several layers.", "❄️",
            params = listOf(
                P("amount", "Amount", 0.1f, 1f, 0.6f), P("speed", "Fall speed", 0.02f, 0.6f, 0.15f),
                P("size", "Flake size", 0.04f, 0.3f, 0.12f), P("wind", "Wind", -0.3f, 0.3f, 0.04f),
            ),
            react = React.OFF, colors = 1, defBlend = Blend.NORMAL, keywords = "winter flakes",
            glsl = PARTICLES + """
vec4 fx(vec2 uv, vec4 base) {
    float a = 0.0;
    a += particles(uv, 14.0, vec2(p_wind, -p_speed * 1.6), p_size * 0.6, 0.6, 1.0, 0.0, p_amount * 0.55);
    a += particles(uv, 9.0, vec2(p_wind * 0.8, -p_speed * 1.2), p_size * 0.85, 0.7, 2.0, 0.0, p_amount * 0.5);
    a += particles(uv, 5.5, vec2(p_wind * 0.5, -p_speed * 0.8), p_size * 1.2, 0.85, 3.0, 0.0, p_amount * 0.45);
    return vec4(mix(vec3(1.0), uC1, 0.35), clamp(a * 0.9 * AMT, 0.0, 1.0));
}"""
        ),
        FxDef(
            "fx_dust", "Floating Dust", "Atmosphere", FxKind.OVERLAY,
            "Warm specks of dust drifting in the light.", "✨",
            params = listOf(
                P("amount", "Amount", 0.1f, 1f, 0.55f), P("speed", "Drift", 0f, 0.3f, 0.04f),
                P("size", "Size", 0.03f, 0.25f, 0.09f),
            ),
            react = React.HIGH, reactAmt = 0.3f, colors = 1, defBlend = Blend.SCREEN, keywords = "particles motes bokeh",
            glsl = PARTICLES + """
vec4 fx(vec2 uv, vec4 base) {
    float a = 0.0;
    a += particles(uv, 12.0, vec2(p_speed * 0.4, p_speed), p_size * 0.6, 0.8, 4.0, 0.7, p_amount * 0.5);
    a += particles(uv, 7.0, vec2(-p_speed * 0.3, p_speed * 0.7), p_size, 0.9, 5.0, 0.6, p_amount * 0.4);
    return vec4(mix(vec3(1.0, 0.92, 0.75), uC1, 0.45), clamp(a * 0.8 * AMT, 0.0, 1.0));
}"""
        ),
        FxDef(
            "fx_fireflies", "Fireflies", "Atmosphere", FxKind.OVERLAY,
            "Glowing fireflies that blink on and off.", "🪲",
            params = listOf(
                P("amount", "Amount", 0.05f, 1f, 0.35f), P("speed", "Drift", 0f, 0.3f, 0.05f),
                P("size", "Glow size", 0.05f, 0.4f, 0.2f),
            ),
            react = React.BASS, reactAmt = 0.3f, colors = 2, defBlend = Blend.ADD, keywords = "lights glow night",
            glsl = PARTICLES + """
vec4 fx(vec2 uv, vec4 base) {
    float a = 0.0;
    a += particles(uv, 8.0, vec2(p_speed * 0.5, p_speed * 0.3), p_size * 0.9, 0.98, 6.0, 1.0, p_amount * 0.5);
    a += particles(uv, 5.0, vec2(-p_speed * 0.4, p_speed * 0.2), p_size * 1.2, 0.98, 7.0, 1.0, p_amount * 0.45);
    vec3 col = mix(uC1, uC2, fract(sin(uv.x * 40.0) * 3.0));
    return vec4(col, clamp(a * 1.1 * AMT, 0.0, 1.0));
}"""
        ),
        FxDef(
            "fx_embers", "Rising Embers", "Atmosphere", FxKind.OVERLAY,
            "Sparks lifting off a fire.", "🔥",
            params = listOf(
                P("amount", "Amount", 0.1f, 1f, 0.5f), P("speed", "Rise speed", 0.05f, 0.8f, 0.25f),
                P("size", "Size", 0.03f, 0.2f, 0.07f),
            ),
            react = React.BASS, reactAmt = 0.4f, colors = 2, defBlend = Blend.ADD, keywords = "sparks fire campfire",
            glsl = PARTICLES + """
vec4 fx(vec2 uv, vec4 base) {
    float a = 0.0;
    a += particles(uv, 14.0, vec2(0.05, p_speed * 1.5), p_size * 0.7, 0.9, 8.0, 0.9, p_amount * 0.45);
    a += particles(uv, 8.0, vec2(-0.04, p_speed), p_size, 0.9, 9.0, 0.8, p_amount * 0.4);
    float fade = smoothstep(0.0, 0.9, 1.0 - uv.y * 0.6);
    return vec4(mix(uC1, uC2, uv.y), clamp(a * fade * AMT, 0.0, 1.0));
}"""
        ),
        FxDef(
            "fx_stars", "Twinkling Stars", "Atmosphere", FxKind.OVERLAY,
            "Stars that appear in the dark parts of the picture.", "🌟",
            params = listOf(
                P("amount", "Amount", 0.1f, 1f, 0.6f), P("size", "Size", 0.02f, 0.15f, 0.055f),
                P("dark", "Only in darkness", 0f, 1f, 0.8f),
            ),
            react = React.HIGH, reactAmt = 0.3f, colors = 1, defBlend = Blend.ADD, keywords = "night sky galaxy",
            glsl = PARTICLES + """
vec4 fx(vec2 uv, vec4 base) {
    float a = particles(uv, 22.0, vec2(0.0), p_size * 0.7, 0.8, 10.0, 1.0, p_amount * 0.35);
    a += particles(uv, 12.0, vec2(0.0), p_size, 0.85, 11.0, 1.0, p_amount * 0.3);
    float darkness = 1.0 - smoothstep(0.1, 0.55, luma(base.rgb));
    a *= mix(1.0, darkness, p_dark);
    return vec4(mix(vec3(1.0), uC1, 0.3), clamp(a * 1.3 * AMT, 0.0, 1.0));
}"""
        ),
        FxDef(
            "fx_sparkle", "Glitter Sparkle", "Atmosphere", FxKind.OVERLAY,
            "Little four-point stars that glint on bright areas.", "💫",
            params = listOf(P("amount", "Amount", 0.05f, 1f, 0.35f), P("size", "Size", 0.1f, 0.6f, 0.3f)),
            react = React.HIGH, reactAmt = 0.6f, colors = 1, defBlend = Blend.ADD, keywords = "glint shine twinkle",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float scale = 16.0;
    vec2 p = centred(uv) * scale;
    vec2 id = floor(p);
    vec2 f = fract(p) - 0.5;
    vec2 rnd = hash22(id + 21.0);
    float present = step(1.0 - p_amount * 0.35, hash21(id + 5.0));
    float life = 0.5 + 0.5 * sin(uTime * (1.5 + rnd.x * 3.0) + rnd.y * TAU);
    life = pow(life, 6.0) * (1.0 + 1.5 * uR);
    vec2 d = f - (rnd - 0.5) * 0.4;
    float core = smoothstep(0.12, 0.0, length(d));
    float cross = smoothstep(0.015, 0.0, abs(d.x)) * smoothstep(p_size, 0.0, abs(d.y))
                + smoothstep(0.015, 0.0, abs(d.y)) * smoothstep(p_size, 0.0, abs(d.x));
    float onBright = smoothstep(0.25, 0.7, luma(src(uv).rgb) + 0.2);
    float a = (core + cross) * life * present * onBright;
    return vec4(mix(vec3(1.0), uC1, 0.3), clamp(a * uI, 0.0, 1.0));
}"""
        ),
        FxDef(
            "fx_bokeh", "Bokeh Lights", "Atmosphere", FxKind.OVERLAY,
            "Big out-of-focus light discs that float and pulse.", "🎇",
            params = listOf(
                P("amount", "Amount", 0.1f, 1f, 0.5f), P("size", "Size", 0.1f, 0.45f, 0.22f),
                P("speed", "Drift", 0f, 0.2f, 0.03f),
            ),
            react = React.BASS, reactAmt = 0.5f, colors = 2, defBlend = Blend.SCREEN, keywords = "lights blur circles city",
            glsl = """
float disc(vec2 uv, float scale, float seed, float sizeMul) {
    vec2 p = centred(uv) * scale + vec2(seed * 9.1, seed * 4.7) + vec2(0.4, 1.0) * uTime * p_speed * scale * 0.3;
    vec2 id = floor(p);
    vec2 f = fract(p) - 0.5;
    vec2 rnd = hash22(id + seed);
    float present = step(1.0 - p_amount * 0.55, hash21(id + seed * 2.0));
    vec2 pos = (rnd - 0.5) * 0.35;
    float d = length(f - pos);
    float sz = p_size * sizeMul * (0.6 + rnd.x * 0.8) * (1.0 + 0.25 * uBass * uR);
    float body = smoothstep(sz, sz * 0.9, d) * 0.45;
    float rim = smoothstep(sz * 0.72, sz, d) * smoothstep(sz * 1.02, sz * 0.95, d) * 0.7;
    return (body + rim) * present;
}
vec4 fx(vec2 uv, vec4 base) {
    float a = disc(uv, 3.2, 1.0, 1.0) + disc(uv, 5.0, 2.0, 0.8) + disc(uv, 7.5, 3.0, 0.6);
    float h = hash21(floor(centred(uv) * 4.0));
    vec3 col = mix(uC1, uC2, h);
    return vec4(col, clamp(a * 0.7 * uI, 0.0, 1.0));
}"""
        ),
        FxDef(
            "fx_fog", "Drifting Mist", "Atmosphere", FxKind.OVERLAY,
            "Slow rolling fog that hugs the ground.", "🌫️",
            params = listOf(
                P("density", "Density", 0.1f, 1f, 0.55f), P("speed", "Speed", 0f, 1f, 0.15f),
                P("height", "Fog height", 0.1f, 1f, 0.6f), P("scale", "Scale", 0.5f, 4f, 1.6f),
            ),
            react = React.OFF, colors = 1, keywords = "mist haze clouds",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 p = centred(uv) * p_scale;
    float n = fbm(p + vec2(uTime * p_speed * 0.5, uTime * p_speed * 0.12));
    n = n * 0.7 + 0.3 * fbm(p * 2.3 - vec2(uTime * p_speed * 0.3, 0.0));
    float h = smoothstep(p_height, 0.0, uv.y);
    float a = smoothstep(0.3, 0.8, n) * h * p_density;
    return vec4(mix(vec3(0.8, 0.85, 0.95), uC1, 0.5), clamp(a * AMT, 0.0, 1.0));
}"""
        ),
        FxDef(
            "fx_aurora", "Aurora", "Atmosphere", FxKind.OVERLAY,
            "Shimmering northern lights across the sky.", "🌌",
            params = listOf(
                P("height", "Height", 0.3f, 0.9f, 0.68f), P("speed", "Speed", 0.1f, 2f, 0.6f),
                P("bands", "Curtains", 1f, 4f, 3f, 1f),
            ),
            react = React.MID, reactAmt = 0.5f, colors = 2, defBlend = Blend.SCREEN, keywords = "northern lights sky",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec3 acc = vec3(0.0);
    float tot = 0.0;
    for (int i = 0; i < 4; i++) {
        if (float(i) >= p_bands) break;
        float fi = float(i);
        float band = fbm(vec2(uv.x * uAsp * 1.4 + uTime * 0.06 * p_speed + fi * 7.0, uTime * 0.04 * p_speed + fi));
        float yc = p_height + 0.05 * fi + (band - 0.5) * 0.35;
        float d = uv.y - yc;
        float curtain = exp(-d * d * 90.0) * smoothstep(-0.25, 0.0, d + 0.0) ;
        curtain = exp(-(d > 0.0 ? d * d * 500.0 : d * d * 90.0));
        float rays = 0.55 + 0.45 * noise(vec2(uv.x * uAsp * 55.0 + fi * 3.0, uTime * 0.5 * p_speed));
        float c = curtain * rays * (0.6 + 0.4 * sin(uTime * 0.7 + fi * 2.0));
        acc += mix(uC1, uC2, fi / 3.0 + band * 0.3) * c;
        tot += c;
    }
    return vec4(acc / max(tot, 0.001), clamp(tot * 0.28 * AMT, 0.0, 0.7));
}"""
        ),
        FxDef(
            "fx_godrays", "Light Shafts", "Atmosphere", FxKind.OVERLAY,
            "Sunbeams slicing through a window or trees.", "🌤️",
            params = listOf(
                P("x", "Source X", -0.2f, 1.2f, 0.15f), P("y", "Source Y", 0.2f, 1.3f, 1.1f),
                P("rays", "Ray count", 4f, 30f, 12f), P("reach", "Reach", 0.3f, 2f, 1.1f),
            ),
            react = React.ENERGY, reactAmt = 0.3f, colors = 1, defBlend = Blend.ADD, keywords = "sun beams window rays",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 q = (uv - vec2(p_x, p_y)) * vec2(uAsp, 1.0);
    float r = length(q);
    float a = atan(q.y, q.x);
    float rays = noise(vec2(a * p_rays, uTime * 0.12)) * 0.7 + noise(vec2(a * p_rays * 2.3 + 4.0, -uTime * 0.07)) * 0.5;
    rays = smoothstep(0.45, 1.0, rays);
    float fall = exp(-r / p_reach * 1.6);
    return vec4(mix(vec3(1.0, 0.93, 0.78), uC1, 0.5), clamp(rays * fall * 0.7 * AMT, 0.0, 1.0));
}"""
        ),
        FxDef(
            "fx_lightning", "Distant Lightning", "Atmosphere", FxKind.OVERLAY,
            "Occasional storm flashes lighting the clouds.", "⚡",
            params = listOf(P("rate", "How often", 0.1f, 1f, 0.45f), P("power", "Brightness", 0.2f, 1f, 0.7f)),
            react = React.OFF, colors = 1, defBlend = Blend.SCREEN, keywords = "storm thunder flash",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float period = 3.0;
    float cell = floor(uTime / period);
    float ph = fract(uTime / period) * period;
    float r = hash11(cell + 3.0);
    float fire = step(1.0 - p_rate, r);
    float delay = hash11(cell + 7.0) * (period - 1.2);
    float t = ph - delay;
    float flash = t > 0.0 ? exp(-t * 7.0) * (0.55 + 0.45 * sin(t * 70.0)) : 0.0;
    flash *= fire;
    float clouds = 0.35 + 0.65 * fbm(centred(uv) * 2.5 + 3.0);
    float top = smoothstep(0.1, 0.9, uv.y);
    return vec4(mix(vec3(0.75, 0.82, 1.0), uC1, 0.4), clamp(flash * clouds * top * p_power * uI, 0.0, 1.0));
}"""
        ),
        FxDef(
            "fx_shooting", "Shooting Stars", "Atmosphere", FxKind.OVERLAY,
            "A meteor now and then streaks across the sky.", "🌠",
            params = listOf(P("rate", "How often", 0.1f, 1f, 0.5f), P("length", "Tail", 0.1f, 0.6f, 0.3f)),
            react = React.OFF, colors = 1, defBlend = Blend.ADD, keywords = "meteor comet night",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float period = 2.5;
    float cell = floor(uTime / period);
    float ph = fract(uTime / period);
    float on = step(1.0 - p_rate, hash11(cell * 1.7 + 2.0));
    vec2 start = vec2(hash11(cell + 5.0) * 1.0 + 0.1, 0.6 + hash11(cell + 9.0) * 0.35);
    vec2 dir = normalize(vec2(-1.0, -0.5 - hash11(cell + 1.0) * 0.4));
    float t = ph * 1.6;
    vec2 head = start + dir * t * 0.9;
    vec2 pa = (uv - head) * vec2(uAsp, 1.0);
    vec2 tail = -dir * p_length;
    vec2 db = tail * vec2(uAsp, 1.0) / vec2(uAsp, 1.0);
    float h = clamp(dot(pa, vec2(-dir.x, -dir.y)) / p_length, 0.0, 1.0);
    vec2 closest = vec2(-dir.x, -dir.y) * h * p_length;
    float d = length(pa - closest);
    float a = smoothstep(0.006 * (1.0 - h) + 0.001, 0.0, d) * (1.0 - h) * smoothstep(0.0, 0.1, ph) * smoothstep(1.0, 0.7, ph);
    return vec4(mix(vec3(1.0), uC1, 0.3), clamp(a * on * uI * 1.3, 0.0, 1.0));
}"""
        ),
        FxDef(
            "fx_matrix", "Digital Rain", "Retro", FxKind.OVERLAY,
            "Green code streams falling down the screen.", "💻",
            params = listOf(
                P("cols", "Columns", 10f, 80f, 36f, 1f), P("speed", "Speed", 0.2f, 3f, 1f),
                P("tail", "Tail", 0.1f, 1f, 0.5f),
            ),
            react = React.HIGH, reactAmt = 0.4f, colors = 1, defBlend = Blend.ADD, keywords = "code hacker cyber",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float cols = floor(p_cols);
    float cx = floor(uv.x * cols);
    float rnd = hash11(cx + 1.0);
    float rows = cols * 1.7 / uAsp * 1.0;
    float y = uv.y * rows;
    float head = fract(uTime * p_speed * (0.15 + rnd * 0.25) + rnd * 7.0) * (rows + 14.0) - 7.0;
    float dist = (rows - y) - head;
    float trail = dist > 0.0 ? exp(-dist / (p_tail * 10.0)) : 0.0;
    trail = dist < 0.0 ? 0.0 : trail;
    vec2 cell = vec2(cx, floor(y));
    float glyph = step(0.38, hash21(cell + floor(uTime * 8.0 * hash21(cell)) * 3.7));
    vec2 f = fract(vec2(uv.x * cols, y));
    float shape = step(0.15, f.x) * step(f.x, 0.85) * step(0.12, f.y) * step(f.y, 0.88);
    float lead = smoothstep(0.7, 0.0, abs(dist)) ;
    float a = trail * glyph * shape;
    vec3 col = mix(vec3(0.1, 1.0, 0.45), uC1, 0.6) + lead * 0.6;
    return vec4(col, clamp(a * AMT, 0.0, 1.0));
}"""
        ),
        FxDef(
            "fx_caustics", "Underwater Light", "Atmosphere", FxKind.OVERLAY,
            "Rippling caustic light like sunlight on a pool floor.", "🌊",
            params = listOf(P("scale", "Scale", 1f, 8f, 3.5f), P("speed", "Speed", 0.1f, 2f, 0.6f)),
            react = React.BASS, reactAmt = 0.3f, colors = 1, defBlend = Blend.SCREEN, keywords = "pool water light waves",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 p = centred(uv) * p_scale;
    float t = uTime * p_speed;
    vec2 i = p;
    float c = 1.0;
    float inten = 0.005;
    for (int n = 0; n < 4; n++) {
        float tt = t * (1.0 - (3.5 / float(n + 1)));
        i = p + vec2(cos(tt - i.x) + sin(tt + i.y), sin(tt - i.y) + cos(tt + i.x));
        c += 1.0 / length(vec2(p.x / (sin(i.x + tt) / inten), p.y / (cos(i.y + tt) / inten)));
    }
    c /= 4.0;
    c = 1.17 - pow(c, 1.4);
    float v = pow(abs(c), 8.0);
    return vec4(mix(vec3(0.7, 0.95, 1.0), uC1, 0.5), clamp(v * 0.22 * AMT, 0.0, 0.5));
}"""
        ),
        FxDef(
            "fx_heat", "Heat Haze", "Atmosphere", FxKind.FILTER,
            "A warm shimmering wobble in the air.", "♨️",
            params = listOf(P("strength", "Strength", 0.1f, 2f, 0.7f), P("speed", "Speed", 0.1f, 3f, 1f)),
            react = React.BASS, reactAmt = 0.3f, keywords = "shimmer distort desert",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    vec2 p = vec2(uv.x * uAsp * 6.0, uv.y * 8.0 - uTime * p_speed);
    float n = noise(p) + 0.5 * noise(p * 2.1 + 5.0) - 0.75;
    vec2 off = vec2(n * 0.006, n * 0.003) * p_strength * AMT * (0.3 + 0.7 * (1.0 - uv.y));
    return vec4(src(uv + off).rgb, 1.0);
}"""
        ),
        FxDef(
            "fx_clouds", "Cloud Shadows", "Atmosphere", FxKind.FILTER,
            "Clouds drift past, dimming and brightening the scene.", "⛅",
            params = listOf(
                P("depth", "Shadow depth", 0.1f, 1f, 0.45f), P("speed", "Speed", 0f, 1f, 0.15f),
                P("scale", "Scale", 0.3f, 3f, 1.1f),
            ),
            react = React.OFF, keywords = "shade sky moving light",
            glsl = """
vec4 fx(vec2 uv, vec4 base) {
    float c = fbm(centred(uv) * p_scale + vec2(uTime * p_speed * 0.12, uTime * p_speed * 0.03));
    float shadow = smoothstep(0.42, 0.72, c);
    vec3 col = base.rgb * (1.0 - shadow * p_depth * uI * 0.8);
    col += (1.0 - shadow) * 0.06 * p_depth * uI * vec3(1.0, 0.95, 0.85);
    return vec4(col, 1.0);
}"""
        ),
    )
}
