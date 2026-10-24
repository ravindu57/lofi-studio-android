package com.lofiautopilot.studio.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofiautopilot.studio.ui.theme.GeistMono
import com.lofiautopilot.studio.ui.theme.Lofi

/** Flat background. (Kept under the old name so every screen picks up the new look.) */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, intensity: Float = 1f) {
    Box(modifier.fillMaxSize().background(Lofi.c.bg))
}

/** The AIM3 mark: a target ring with four reticle ticks, three equaliser bars that dance while active, and one accent dot. */
@Composable
fun AudioBarsLogo(modifier: Modifier = Modifier, size: Dp = 28.dp, active: Boolean = true) {
    val c = Lofi.c
    val t = rememberInfiniteTransition(label = "logo")
    val h1 by t.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "h1")
    val h2 by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "h2")
    val h3 by t.animateFloat(0.3f, 0.7f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "h3")
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val ctr = Offset(s / 2, s / 2)
        val sw = s * 0.045f
        val r = s * 0.30f
        drawCircle(c.ink, r, ctr, style = Stroke(sw))
        for (a in listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f)) {
            drawLine(c.ink, Offset(ctr.x + a.first * (r + sw * 1.6f), ctr.y + a.second * (r + sw * 1.6f)),
                Offset(ctr.x + a.first * (r + sw * 3.6f), ctr.y + a.second * (r + sw * 3.6f)), sw, StrokeCap.Round)
        }
        val bw = s * 0.07f
        val hs = if (active) listOf(h1, h2, h3) else listOf(0.5f, 0.85f, 0.6f)
        listOf(-1.7f, 0f, 1.7f).forEachIndexed { i, dx ->
            val bh = s * 0.3f * hs[i]
            drawRoundRect(c.ink, Offset(ctr.x + dx * bw - bw / 2, ctr.y - bh / 2), Size(bw, bh), CornerRadius(bw / 2))
        }
        drawCircle(Color(0xFF52A8FF), s * 0.032f, Offset(ctr.x + r * 0.72f, ctr.y - r * 0.72f))
    }
}

/** Subtle press feedback (a small scale and a haptic tick). */
fun Modifier.pressScale(onClick: () -> Unit, enabled: Boolean = true, haptic: Boolean = true): Modifier = composed {
    val hf = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium), label = "press")
    this.scale(s).alpha(if (enabled) 1f else 0.4f).pointerInput(enabled) {
        detectTapGestures(onPress = {
            if (!enabled) return@detectTapGestures
            pressed = true
            tryAwaitRelease()
            pressed = false
        }, onTap = {
            if (!enabled) return@detectTapGestures
            if (haptic) hf.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        })
    }
}

/** Items fade up one after another, quickly. */
@Composable
fun Modifier.staggerIn(index: Int, delayPer: Int = 35): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(320, delayMillis = index.coerceAtMost(10) * delayPer, easing = FastOutSlowInEasing)) }
    return this.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 14f }
}

/** A bordered surface. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    onClick: (() -> Unit)? = null,
    glow: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = Lofi.c
    val shape = RoundedCornerShape(corner)
    var m = modifier.clip(shape).background(c.surface).border(1.dp, if (glow != null) c.lineStrong else c.line, shape)
    if (onClick != null) m = m.pressScale(onClick, haptic = false)
    Box(m, content = content)
}

/** Primary button: solid foreground colour on background. */
@Composable
fun GradientButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, small: Boolean = false, onClick: () -> Unit) {
    val c = Lofi.c
    Box(
        modifier.height(if (small) 36.dp else 44.dp).clip(RoundedCornerShape(8.dp)).background(c.ink)
            .pressScale(onClick, enabled).padding(horizontal = if (small) 14.dp else 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = c.bg, fontWeight = FontWeight.Medium, fontSize = if (small) 13.sp else 15.sp, maxLines = 1) }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Lofi.c
    Box(
        modifier.height(36.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, c.lineStrong, RoundedCornerShape(8.dp)).background(c.bg)
            .pressScale(onClick, enabled, haptic = false).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = c.ink, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1) }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Lofi.c
    val bg by animateColorAsState(if (selected) c.ink else Color.Transparent, tween(160), label = "chipbg")
    val fg by animateColorAsState(if (selected) c.bg else c.muted, tween(160), label = "chipfg")
    Box(
        modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(bg)
            .border(1.dp, if (selected) c.ink else c.line, RoundedCornerShape(8.dp))
            .pressScale(onClick, haptic = false).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1) }
}

@Composable
fun StatusDot(color: Color, pulsing: Boolean = false, size: Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "dot")
    val s by t.animateFloat(1f, 2.4f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "s")
    val a by t.animateFloat(0.5f, 0f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "a")
    Box(Modifier.size(size * 2.4f), contentAlignment = Alignment.Center) {
        if (pulsing) Box(Modifier.size(size).scale(s).alpha(a).background(color, CircleShape))
        Box(Modifier.size(size).background(color, CircleShape))
    }
}

/** Thin progress ring. */
@Composable
fun ProgressRing(progress: Float, modifier: Modifier = Modifier, stroke: Dp = 3.dp, content: @Composable BoxScope.() -> Unit = {}) {
    val c = Lofi.c
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(600, easing = FastOutSlowInEasing), label = "ring")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            drawArc(c.line, 0f, 360f, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw))
            drawArc(c.ink, -90f, 360f * p, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
        }
        content()
    }
}

/**
 * The Autopilot hero animation. Off: a dim static target. On: radar ripples spread outward and small satellites orbit the
 * AIM3 mark. Working: the satellites speed up with comet trails and a progress ring fills around the mark.
 */
@Composable
fun AutopilotOrb(on: Boolean, busy: Boolean, modifier: Modifier = Modifier, progress: Float = 0f, onClick: () -> Unit) {
    val c = Lofi.c
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (busy) 2600 else 9000, easing = LinearEasing), RepeatMode.Restart), label = "sp")
    val ripple by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart), label = "rp")
    val bars by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bars")
    val level by animateFloatAsState(if (on) 1f else 0.22f, tween(500), label = "lvl")
    val prog by animateFloatAsState(progress.coerceIn(0f, 1f), tween(600, easing = FastOutSlowInEasing), label = "prog")
    Box(modifier.pressScale(onClick, haptic = true), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val ctr = Offset(size.width / 2, size.height / 2)
            // soft glow
            drawCircle(Brush.radialGradient(listOf(c.ink.copy(alpha = 0.10f * level), Color.Transparent), ctr, r), r, ctr)
            // radar ripples
            if (on) for (i in 0 until 3) {
                val p = (ripple + i / 3f) % 1f
                drawCircle(c.ink.copy(alpha = (1f - p) * (if (busy) 0.28f else 0.20f)), r * (0.3f + 0.7f * p), ctr, style = Stroke(1.2.dp.toPx()))
            }
            // guide rings
            drawCircle(c.line.copy(alpha = 0.35f + 0.65f * level), r * 0.96f, ctr, style = Stroke(1.dp.toPx()))
            drawCircle(c.line.copy(alpha = 0.25f + 0.5f * level), r * 0.64f, ctr, style = Stroke(1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx()), spin / 10f)))
            // orbiting satellites
            val n = if (on) 7 else 3
            for (i in 0 until n) {
                val ang = Math.toRadians((spin * (1f + i * 0.13f) * (if (i % 2 == 0) 1 else -1) + i * 360f / n).toDouble())
                val rad = r * (if (i % 2 == 0) 0.80f else 0.64f)
                val p = Offset(ctr.x + (kotlin.math.cos(ang) * rad).toFloat(), ctr.y + (kotlin.math.sin(ang) * rad).toFloat())
                if (busy) {
                    val back = Offset(ctr.x + (kotlin.math.cos(ang - 0.35) * rad).toFloat(), ctr.y + (kotlin.math.sin(ang - 0.35) * rad).toFloat())
                    drawLine(Brush.linearGradient(listOf(Color.Transparent, c.ink.copy(alpha = 0.6f)), back, p), back, p, 2.dp.toPx(), StrokeCap.Round)
                }
                drawCircle(if (i == 0) c.sky else c.ink.copy(alpha = 0.35f + 0.65f * level), (if (i == 0) 3.6.dp else 2.4.dp).toPx(), p)
            }
            // progress ring while working
            if (busy) {
                drawArc(c.ink.copy(alpha = 0.18f), 0f, 360f, false, Offset(ctr.x - r * 0.96f, ctr.y - r * 0.96f), Size(r * 1.92f, r * 1.92f), style = Stroke(3.dp.toPx()))
                drawArc(c.ink, -90f, 360f * prog, false, Offset(ctr.x - r * 0.96f, ctr.y - r * 0.96f), Size(r * 1.92f, r * 1.92f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
            // the AIM3 mark in the middle
            val mr = r * 0.30f
            drawCircle(c.ink.copy(alpha = level), mr, ctr, style = Stroke(r * 0.035f))
            val bw = r * 0.06f
            val hs = if (on) listOf(0.35f + 0.5f * bars, 0.9f - 0.4f * bars, 0.45f + 0.35f * (1f - bars)) else listOf(0.5f, 0.85f, 0.6f)
            listOf(-1.7f, 0f, 1.7f).forEachIndexed { i, dx ->
                val bh = mr * 1.3f * hs[i]
                drawRoundRect(c.ink.copy(alpha = level), Offset(ctr.x + dx * bw - bw / 2, ctr.y - bh / 2), Size(bw, bh), CornerRadius(bw / 2))
            }
            drawCircle(c.sky.copy(alpha = level), r * 0.028f, Offset(ctr.x + mr * 0.72f, ctr.y - mr * 0.72f))
        }
    }
}

@Composable
fun shimmerBrush(): Brush {
    val c = Lofi.c
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-400f, 1200f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    return Brush.linearGradient(listOf(c.surfaceHi, c.line.copy(alpha = 0.7f), c.surfaceHi), Offset(x, 0f), Offset(x + 400f, 200f))
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val c = Lofi.c
    Row(modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun AnimatedCount(value: Int, style: TextStyle, color: Color) {
    val v by animateFloatAsState(value.toFloat(), tween(700, easing = FastOutSlowInEasing), label = "count")
    Text(v.toInt().toString(), style = style.copy(fontFamily = GeistMono), color = color)
}

@Composable
fun Skeleton(modifier: Modifier = Modifier, corner: Dp = 6.dp) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(shimmerBrush()))
}

@Composable
fun Spacer8(h: Int = 8) = androidx.compose.foundation.layout.Spacer(Modifier.height(h.dp))

@Composable
fun SpacerW(w: Int = 8) = androidx.compose.foundation.layout.Spacer(Modifier.width(w.dp))

@Composable
fun Labeled(label: String, value: String) {
    val c = Lofi.c
    Column { Text(label, color = c.faint, fontSize = 11.sp); Text(value, color = c.ink, fontWeight = FontWeight.Medium, fontSize = 15.sp) }
}

/** Monospace figures for time codes and counters. */
@Composable
fun Mono(text: String, color: Color = Lofi.c.muted, size: Int = 12, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier) {
    Text(text, color = color, fontFamily = GeistMono, fontSize = size.sp, fontWeight = weight, modifier = modifier, maxLines = 1)
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Lofi.c.line))
}
