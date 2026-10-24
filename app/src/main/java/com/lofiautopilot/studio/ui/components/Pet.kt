package com.lofiautopilot.studio.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofiautopilot.studio.ui.theme.Lofi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

enum class PetAct { SIT, WALK, SLEEP, GROOM, HOP, HAPPY, DJ, HELD }

/** The pet grows with the number of videos you've made. */
object PetLevel {
    fun of(videos: Int) = when { videos >= 25 -> 4; videos >= 10 -> 3; videos >= 3 -> 2; else -> 1 }
    fun title(level: Int) = when (level) { 1 -> "Kitten"; 2 -> "Cat"; 3 -> "DJ Cat"; else -> "Legend" }
    fun nextAt(level: Int) = when (level) { 1 -> 3; 2 -> 10; 3 -> 25; else -> Int.MAX_VALUE }
    fun scale(level: Int) = when (level) { 1 -> 0.78f; 2 -> 0.92f; 3 -> 1.0f; else -> 1.08f }
}

private val Fur = Color(0xFFF2E9DB)
private val Pink = Color(0xFFFF8FB8)
private val Collar = Color(0xFF52A8FF)
private val Gold = Color(0xFFFFD166)

private fun shade(c: Color, f: Float) = Color((c.red * f).coerceIn(0f, 1f), (c.green * f).coerceIn(0f, 1f), (c.blue * f).coerceIn(0f, 1f), c.alpha)

private suspend fun Animatable<Float, *>.go(target: Float, spec: androidx.compose.animation.core.AnimationSpec<Float>) {
    try { animateTo(target, spec) } catch (e: CancellationException) { coroutineContext.ensureActive() }
}

/**
 * A small shaded, lit 3D-style cat. It floats above the screen (it takes no layout space), wanders along the bottom,
 * naps, grooms and hops, can be picked up and dropped anywhere, reacts to taps, wears headphones while a video renders,
 * and evolves as you make videos.
 */
@Composable
fun PetOverlay(level: Int, working: Boolean, message: String?, modifier: Modifier = Modifier) {
    val c = Lofi.c
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val t = rememberInfiniteTransition(label = "pet")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    var act by remember { mutableStateOf(PetAct.SIT) }
    var facing by remember { mutableFloatStateOf(1f) }
    var blink by remember { mutableStateOf(false) }
    var taps by remember { mutableIntStateOf(0) }
    var held by remember { mutableStateOf(false) }
    var bubble by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(modifier) {
        val boxPx = with(density) { 112.dp.toPx() }
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val ground = hPx - boxPx - with(density) { 2.dp.toPx() }
        val posX = remember { Animatable(wPx * 0.25f) }
        val posY = remember { Animatable(ground) }
        LaunchedEffect(ground) { if (!held) posY.snapTo(ground) }

        LaunchedEffect(Unit) { while (true) { delay(Random.nextLong(1800, 5200)); blink = true; delay(140); blink = false } }
        LaunchedEffect(working, taps, ground) {
            if (taps > 0) { act = PetAct.HAPPY; bubble = "${PetLevel.title(level)} · Lv $level"; delay(1600); bubble = null }
            if (working) { act = PetAct.DJ; return@LaunchedEffect }
            while (true) {
                while (held) delay(150)
                when (Random.nextInt(100)) {
                    in 0..27 -> { act = PetAct.SIT; delay(Random.nextLong(2500, 5500)) }
                    in 28..59 -> {
                        val target = Random.nextFloat() * (wPx - boxPx * 0.9f)
                        val dist = abs(target - posX.value)
                        if (dist > boxPx * 0.4f) {
                            facing = if (target > posX.value) 1f else -1f
                            act = PetAct.WALK
                            posX.go(target, tween((dist / wPx * 9000).toInt().coerceAtLeast(700), easing = LinearEasing))
                        }
                        if (!held) { act = PetAct.SIT; delay(700) }
                    }
                    in 60..73 -> { act = PetAct.SLEEP; delay(Random.nextLong(6000, 10000)) }
                    in 74..85 -> { act = PetAct.GROOM; delay(3200) }
                    else -> { act = PetAct.HOP; delay(2200) }
                }
            }
        }

        val unit = with(density) { (30.dp * PetLevel.scale(level)).toPx() }
        Box(
            Modifier.offset { IntOffset(posX.value.roundToInt(), posY.value.roundToInt()) }.size(112.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = { taps++ }) }
                .pointerInput(ground, wPx) {
                    detectDragGestures(
                        onDragStart = { held = true; act = PetAct.HELD },
                        onDragEnd = { held = false; scope.launch { posY.go(ground, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)); act = PetAct.HAPPY; delay(900); act = PetAct.SIT } },
                        onDragCancel = { held = false; scope.launch { posY.go(ground, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)); act = PetAct.SIT } },
                    ) { change, drag ->
                        change.consume()
                        scope.launch { posX.snapTo((posX.value + drag.x).coerceIn(0f, wPx - boxPx)); posY.snapTo((posY.value + drag.y).coerceIn(0f, ground)) }
                    }
                },
        ) {
            Canvas(Modifier.size(112.dp)) {
                val cx = size.width / 2
                val gy = size.height - 6.dp.toPx()
                val lift = if (act == PetAct.HELD) max(0f, ground - posY.value) else 0f
                drawCat(measurer, cx, gy, unit, facing, act, phase, blink, level, lift > 4f, working)
            }
        }
        val text = bubble ?: message
        AnimatedVisibility(
            text != null, enter = fadeIn(tween(250)), exit = fadeOut(tween(250)),
            modifier = Modifier.offset { IntOffset((posX.value - with(density) { 14.dp.toPx() }).roundToInt().coerceIn(0, max(0, (wPx - with(density) { 160.dp.toPx() }).roundToInt())), (posY.value - with(density) { 26.dp.toPx() }).roundToInt().coerceAtLeast(0)) },
        ) {
            Box(Modifier.background(c.surface, RoundedCornerShape(10.dp)).border(1.dp, c.lineStrong, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text(text.orEmpty(), color = c.ink, fontSize = 11.sp, maxLines = 2)
            }
        }
    }
}

// ---------- drawing: lit spheres and ellipsoids, a soft shadow, a head that turns ----------

private fun DrawScope.sphere(ctr: Offset, r: Float, base: Color) {
    drawCircle(Brush.radialGradient(listOf(shade(base, 1.1f), base, shade(base, 0.6f)), ctr + Offset(-r * 0.35f, -r * 0.42f), r * 1.6f), r, ctr)
}

private fun DrawScope.ellipsoid(tl: Offset, sz: Size, base: Color) {
    drawOval(Brush.radialGradient(listOf(shade(base, 1.1f), base, shade(base, 0.58f)), tl + Offset(sz.width * 0.32f, sz.height * 0.26f), max(sz.width, sz.height) * 0.9f), tl, sz)
}

private fun DrawScope.limb(a: Offset, b: Offset, w: Float, base: Color) {
    drawLine(Brush.linearGradient(listOf(shade(base, 1.04f), shade(base, 0.66f)), a - Offset(w, 0f), b + Offset(w, 0f)), a, b, w, StrokeCap.Round)
}

private fun DrawScope.drawCat(m: TextMeasurer, cx: Float, ground: Float, u: Float, facing: Float, act: PetAct, ph: Float, blink: Boolean, level: Int, airborne: Boolean, working: Boolean) {
    val tau = (2 * Math.PI).toFloat()
    val s = sin(ph * tau)
    val s2 = sin(ph * tau * 2)
    val hopH = when (act) {
        PetAct.HAPPY -> abs(s) * 0.35f * u
        PetAct.HOP -> abs(sin(ph * tau / 2f)) * 0.55f * u
        else -> 0f
    }
    // contact shadow (shrinks as the cat leaves the ground)
    val shW = (if (act == PetAct.SLEEP) 1.7f else 1.35f) * u * (1f - hopH / (u * 1.4f)) * (if (airborne) 0.6f else 1f)
    drawOval(Color.Black.copy(alpha = 0.32f), Offset(cx - shW / 2, ground - 0.07f * u), Size(shW, 0.2f * u))
    if (level >= 4) drawCircle(Brush.radialGradient(listOf(Gold.copy(alpha = 0.18f), Color.Transparent), Offset(cx, ground - 1.1f * u), 1.7f * u), 1.7f * u, Offset(cx, ground - 1.1f * u))
    val dj = act == PetAct.DJ || level >= 3
    withTransform({ translate(cx, ground - hopH); scale(facing, 1f, Offset.Zero) }) {
        when (act) {
            PetAct.SIT, PetAct.GROOM, PetAct.HAPPY, PetAct.DJ, PetAct.HELD -> {
                val bob = if (act == PetAct.DJ) s2 * 0.05f * u else 0f
                val dangle = if (act == PetAct.HELD) s * 7f else 0f
                withTransform({ if (act == PetAct.HELD) rotate(dangle, Offset(0f, -2.0f * u)) }) {
                    val sway = s * 0.25f * u * (if (act == PetAct.HAPPY) 2f else 1f)
                    val tail = Path().apply { moveTo(-0.38f * u, -0.18f * u); cubicTo(-0.9f * u, -0.1f * u, -1.0f * u + sway, -0.6f * u, -0.85f * u + sway, -0.95f * u) }
                    drawPath(tail, Brush.linearGradient(listOf(shade(Fur, 0.9f), shade(Fur, 0.6f)), Offset(-1f * u, -1f * u), Offset(0f, 0f)), style = Stroke(0.17f * u, cap = StrokeCap.Round))
                    // body (lit from the upper left) and belly
                    ellipsoid(Offset(-0.5f * u, -1.12f * u), Size(1.0f * u, 1.14f * u), Fur)
                    ellipsoid(Offset(-0.22f * u, -0.9f * u), Size(0.44f * u, 0.62f * u), shade(Fur, 1.05f))
                    // legs (back haunch + front legs)
                    val dang = if (act == PetAct.HELD) 0.25f * u else 0f
                    limb(Offset(-0.22f * u, -0.55f * u), Offset(-0.22f * u, -0.04f * u + dang), 0.17f * u, Fur)
                    limb(Offset(0.14f * u, -0.55f * u), Offset(0.14f * u, -0.04f * u + dang), 0.17f * u, Fur)
                    ellipsoid(Offset(0.3f * u, -0.55f * u), Size(0.34f * u, 0.5f * u), shade(Fur, 0.95f)) // haunch
                    // collar
                    drawLine(Collar, Offset(-0.24f * u, -1.02f * u + bob), Offset(0.3f * u, -1.02f * u + bob), 0.09f * u, StrokeCap.Round)
                    sphere(Offset(0.05f * u, -0.93f * u + bob), 0.065f * u, Gold)
                    val hx = 0.04f * u
                    val hy = -1.3f * u + bob + (if (act == PetAct.GROOM) 0.06f * u else 0f)
                    val tilt = if (act == PetAct.GROOM) 0.08f * u * s else 0f
                    drawHead(hx + tilt, hy, 0.47f * u, blink || act == PetAct.GROOM, act == PetAct.HAPPY, 0.12f)
                    if (act == PetAct.GROOM) {
                        val lift = (0.5f + 0.5f * s2) * 0.12f * u
                        limb(Offset(0.28f * u, -0.55f * u), Offset(0.3f * u, -1.0f * u - lift), 0.16f * u, Fur)
                    }
                    if (dj) {
                        val p = Path().apply { moveTo(hx - 0.47f * u, hy); cubicTo(hx - 0.52f * u, hy - 0.78f * u, hx + 0.52f * u, hy - 0.78f * u, hx + 0.47f * u, hy) }
                        drawPath(p, shade(Collar, 0.85f), style = Stroke(0.1f * u, cap = StrokeCap.Round))
                        for (sx in listOf(-1f, 1f)) { ellipsoid(Offset(hx + sx * 0.5f * u - 0.09f * u, hy - 0.14f * u), Size(0.19f * u, 0.32f * u), Collar) }
                        if (act == PetAct.DJ) {
                            val a = (ph * 2) % 1f
                            drawText(m, "♪", Offset(0.55f * u + a * 0.4f * u, -1.95f * u - a * 0.7f * u), TextStyle(color = Color.White.copy(alpha = 1f - a), fontSize = (0.45f * u / density).sp))
                        }
                    }
                    if (level >= 4) { // crown
                        val cr = Path().apply { moveTo(hx - 0.28f * u, hy - 0.62f * u); lineTo(hx - 0.28f * u, hy - 0.95f * u); lineTo(hx - 0.12f * u, hy - 0.78f * u); lineTo(hx, hy - 1.02f * u); lineTo(hx + 0.12f * u, hy - 0.78f * u); lineTo(hx + 0.28f * u, hy - 0.95f * u); lineTo(hx + 0.28f * u, hy - 0.62f * u); close() }
                        drawPath(cr, Brush.verticalGradient(listOf(shade(Gold, 1.1f), shade(Gold, 0.7f)), hy - 1.0f * u, hy - 0.6f * u))
                    }
                    if (act == PetAct.HAPPY) for (i in 0 until 3) {
                        val a = (ph + i / 3f) % 1f
                        drawHeart(Offset((-0.3f + i * 0.3f) * u, -2.0f * u - a * 0.9f * u), 0.13f * u, Pink.copy(alpha = 1f - a))
                    }
                }
            }
            PetAct.WALK, PetAct.HOP -> {
                val bob = abs(s) * 0.07f * u
                val sway = s * 0.2f * u
                val tail = Path().apply { moveTo(-0.62f * u, -0.9f * u - bob); cubicTo(-1.0f * u, -1.0f * u, -1.05f * u + sway, -1.4f * u, -0.9f * u + sway, -1.65f * u) }
                drawPath(tail, Brush.linearGradient(listOf(shade(Fur, 0.9f), shade(Fur, 0.6f)), Offset(-1f * u, -1.7f * u), Offset(-0.6f * u, -0.9f * u)), style = Stroke(0.16f * u, cap = StrokeCap.Round))
                val legBase = floatArrayOf(-0.45f, -0.18f, 0.27f, 0.52f)
                legBase.forEachIndexed { i, bx ->
                    val q = ph * tau + (if (i % 2 == 0) 0f else Math.PI.toFloat())
                    val fx = bx * u + sin(q) * 0.18f * u
                    val lift = max(0f, cos(q)) * 0.1f * u
                    limb(Offset(bx * u, -0.62f * u - bob), Offset(fx, -lift), 0.17f * u, if (i % 2 == 0) shade(Fur, 0.85f) else Fur)
                }
                ellipsoid(Offset(-0.72f * u, -1.14f * u - bob), Size(1.44f * u, 0.66f * u), Fur)
                drawLine(Collar, Offset(0.5f * u, -1.12f * u - bob), Offset(0.58f * u, -0.62f * u - bob), 0.09f * u, StrokeCap.Round)
                drawHead(0.78f * u, -1.0f * u - bob, 0.41f * u, blink, false, 0.16f)
                if (level >= 3) {
                    val hx = 0.78f * u; val hy = -1.0f * u - bob
                    val p = Path().apply { moveTo(hx - 0.41f * u, hy); cubicTo(hx - 0.45f * u, hy - 0.7f * u, hx + 0.45f * u, hy - 0.7f * u, hx + 0.41f * u, hy) }
                    drawPath(p, shade(Collar, 0.85f), style = Stroke(0.09f * u, cap = StrokeCap.Round))
                }
            }
            PetAct.SLEEP -> {
                val breathe = s * 0.03f * u
                ellipsoid(Offset(-0.72f * u, -0.66f * u - breathe), Size(1.44f * u, 0.68f * u + breathe), Fur)
                val tail = Path().apply { moveTo(-0.6f * u, -0.1f * u); cubicTo(-0.2f * u, 0.05f * u, 0.6f * u, 0.05f * u, 0.85f * u, -0.18f * u) }
                drawPath(tail, shade(Fur, 0.8f), style = Stroke(0.15f * u, cap = StrokeCap.Round))
                drawHead(0.6f * u, -0.5f * u, 0.35f * u, true, false, 0.1f)
                for (i in 0 until 3) {
                    val a = (ph * 0.5f + i / 3f) % 1f
                    drawText(m, "z", Offset(0.9f * u + a * 0.5f * u, -1.0f * u - a * 0.9f * u), TextStyle(color = Color.White.copy(alpha = 1f - a), fontSize = ((0.3f + 0.2f * a) * u / density).sp))
                }
            }
        }
    }
}

/** A sphere head with ears, glossy eyes and whiskers. [look] shifts the face toward where the cat is heading (a cheap turn of the head). */
private fun DrawScope.drawHead(cx: Float, cy: Float, r: Float, closed: Boolean, happy: Boolean, look: Float) {
    val dark = Color(0xFF2A2420)
    val ear = Path().apply { moveTo(cx - r * 0.9f, cy - r * 0.3f); lineTo(cx - r * 0.72f, cy - r * 1.5f); lineTo(cx - r * 0.1f, cy - r * 0.8f); close() }
    val ear2 = Path().apply { moveTo(cx + r * 0.9f, cy - r * 0.3f); lineTo(cx + r * 0.72f, cy - r * 1.5f); lineTo(cx + r * 0.1f, cy - r * 0.8f); close() }
    drawPath(ear, Brush.linearGradient(listOf(shade(Fur, 1.05f), shade(Fur, 0.65f)), Offset(cx - r, cy - r * 1.5f), Offset(cx, cy - r * 0.5f)))
    drawPath(ear2, Brush.linearGradient(listOf(shade(Fur, 0.9f), shade(Fur, 0.55f)), Offset(cx, cy - r * 1.5f), Offset(cx + r, cy - r * 0.5f)))
    val i1 = Path().apply { moveTo(cx - r * 0.72f, cy - r * 0.6f); lineTo(cx - r * 0.64f, cy - r * 1.18f); lineTo(cx - r * 0.3f, cy - r * 0.84f); close() }
    val i2 = Path().apply { moveTo(cx + r * 0.72f, cy - r * 0.6f); lineTo(cx + r * 0.64f, cy - r * 1.18f); lineTo(cx + r * 0.3f, cy - r * 0.84f); close() }
    drawPath(i1, Pink.copy(alpha = 0.85f)); drawPath(i2, Pink.copy(alpha = 0.7f))
    sphere(Offset(cx, cy), r, Fur)
    // cheek blush
    drawCircle(Pink.copy(alpha = 0.22f), r * 0.2f, Offset(cx - r * 0.62f + look * r, cy + r * 0.28f)); drawCircle(Pink.copy(alpha = 0.22f), r * 0.2f, Offset(cx + r * 0.62f + look * r, cy + r * 0.28f))
    val fx = look * r
    val ey = cy - r * 0.05f
    val ex = r * 0.42f
    val er = r * 0.2f
    if (closed || happy) {
        val w = r * 0.22f
        for (sx in listOf(-1f, 1f)) {
            val x = cx + fx + sx * ex
            val p = Path().apply { moveTo(x - w, ey + (if (happy) w * 0.4f else 0f)); quadraticTo(x, ey + (if (happy) -w * 0.9f else w * 0.7f), x + w, ey + (if (happy) w * 0.4f else 0f)) }
            drawPath(p, dark, style = Stroke(r * 0.09f, cap = StrokeCap.Round))
        }
    } else {
        for (sx in listOf(-1f, 1f)) {
            val e = Offset(cx + fx + sx * ex, ey)
            drawCircle(Color.White, er * 1.18f, e)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF6B4A2B), dark), e, er), er, e + Offset(look * er * 0.3f, 0f))
            drawCircle(Color.White, er * 0.32f, e + Offset(-er * 0.35f + look * er * 0.3f, -er * 0.38f))
            drawCircle(Color.White.copy(alpha = 0.7f), er * 0.15f, e + Offset(er * 0.3f + look * er * 0.3f, er * 0.3f))
        }
    }
    val nose = Path().apply { moveTo(cx + fx - r * 0.1f, cy + r * 0.2f); lineTo(cx + fx + r * 0.1f, cy + r * 0.2f); lineTo(cx + fx, cy + r * 0.32f); close() }
    drawPath(nose, Pink)
    val mouth = Path().apply { moveTo(cx + fx, cy + r * 0.32f); quadraticTo(cx + fx - r * 0.12f, cy + r * 0.5f, cx + fx - r * 0.25f, cy + r * 0.4f); moveTo(cx + fx, cy + r * 0.32f); quadraticTo(cx + fx + r * 0.12f, cy + r * 0.5f, cx + fx + r * 0.25f, cy + r * 0.4f) }
    drawPath(mouth, dark.copy(alpha = 0.8f), style = Stroke(r * 0.05f, cap = StrokeCap.Round))
    for (sx in listOf(-1f, 1f)) for (k in -1..1) {
        drawLine(Color.White.copy(alpha = 0.75f), Offset(cx + fx + sx * r * 0.55f, cy + r * 0.28f + k * r * 0.08f), Offset(cx + fx + sx * r * 1.18f, cy + r * 0.2f + k * r * 0.2f), r * 0.03f, StrokeCap.Round)
    }
}

private fun DrawScope.drawHeart(c: Offset, r: Float, color: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y + r)
        cubicTo(c.x - r * 2f, c.y - r * 0.2f, c.x - r * 0.8f, c.y - r * 1.6f, c.x, c.y - r * 0.5f)
        cubicTo(c.x + r * 0.8f, c.y - r * 1.6f, c.x + r * 2f, c.y - r * 0.2f, c.x, c.y + r)
    }
    drawPath(p, color)
}

