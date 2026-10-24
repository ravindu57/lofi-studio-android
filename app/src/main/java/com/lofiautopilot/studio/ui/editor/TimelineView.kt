package com.lofiautopilot.studio.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofiautopilot.studio.audio.Waveforms
import com.lofiautopilot.studio.export.ImageLoader
import com.lofiautopilot.studio.ui.components.Skeleton
import com.lofiautopilot.studio.ui.theme.GeistMono
import com.lofiautopilot.studio.ui.theme.Lofi
import com.lofiautopilot.studio.vfx.AudioClip
import com.lofiautopilot.studio.vfx.EffectLibrary
import com.lofiautopilot.studio.vfx.Intros
import com.lofiautopilot.studio.vfx.Layer
import com.lofiautopilot.studio.vfx.MediaClip
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val RULER_H = 22.dp
private val HANDLE_TOUCH = 24.dp // each side of the clip edge → 48 dp touch target in total

private fun fmtTime(s: Float): String {
    val t = max(0f, s)
    val m = (t / 60).toInt(); val sec = (t % 60).toInt()
    return "%d:%02d".format(m, sec)
}

/** Colour for an effect clip by category, so the timeline reads at a glance. */
@Composable
private fun fxColor(l: Layer): Color {
    val c = Lofi.c
    return when (EffectLibrary.get(l.fx)?.category) {
        "Visualizer" -> Color(0xFF52A8FF)
        "Atmosphere" -> Color(0xFF46C281)
        "Light" -> Color(0xFFF5A623)
        "Colour" -> Color(0xFFBF7AF0)
        "Distort" -> Color(0xFFFF6369)
        "Retro" -> Color(0xFFFF8FB8)
        "Camera" -> Color(0xFF7B7BFF)
        else -> c.muted
    }
}

/**
 * Multi-lane timeline in the style of mobile video editors: the playhead stays in the centre and the tracks slide under it.
 * One finger scrolls, two fingers zoom, long-press a clip to move it (it snaps magnetically with a haptic tick),
 * tap to select, drag the handles of a selected clip to trim.
 */
@Composable
fun TimelineView(model: EditorModel, expanded: Boolean, modifier: Modifier = Modifier, onTransition: (String) -> Unit = {}) {
    val c = Lofi.c
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val project = model.project
    val laneAudio = if (expanded) 48.dp else 38.dp
    val laneFx = if (expanded) 38.dp else 30.dp
    val laneVisual = if (expanded) 44.dp else 34.dp
    val gap = 4.dp

    BoxWithConstraints(modifier.background(c.bg)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val centerPx = widthPx / 2f
        val pps = with(density) { model.dpPerSec.dp.toPx() }
        fun xOf(t: Float) = centerPx + (t - model.posS) * pps
        fun tick() { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }

        val audioLanes = project.audio.map { it.track }.distinct().sorted().ifEmpty { listOf(0) }
        val fxLanes = project.effects.map { it.track }.distinct().sorted()
        val scroll = rememberScrollState()
        val flingScope = rememberCoroutineScope()
        var flingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        var snapLine by remember { mutableFloatStateOf(-1f) }

        // ---- snapping ----
        fun snapPoints(excludeAudio: String?, excludeFx: String?): List<Float> {
            val pts = ArrayList<Float>()
            pts.add(0f); pts.add(model.posS); pts.add(project.durationS)
            project.audio.forEach { if (it.id != excludeAudio) { pts.add(it.startS); pts.add(it.endS) } }
            project.effects.forEach { if (it.id != excludeFx) { pts.add(it.from); pts.add(project.layerEnd(it)) } }
            project.media.forEach { pts.add(it.startS); pts.add(it.endS) }
            if (model.dpPerSec >= 28f) model.features?.beatTimes?.let { b ->
                val lo = model.posS - widthPx / pps; val hi = model.posS + widthPx / pps
                for (x in b) if (x in lo..hi) pts.add(x)
            }
            return pts
        }
        val thrS = with(density) { 12.dp.toPx() } / pps
        /** Returns the time adjustment (seconds) that aligns the nearest of [edges] with a snap point, and the target. */
        fun snap(edges: List<Float>, pts: List<Float>): Pair<Float, Float?> {
            var best = Float.MAX_VALUE; var target: Float? = null
            for (e in edges) for (p in pts) { val d = p - e; if (abs(d) < abs(best) && abs(d) <= thrS) { best = d; target = p } }
            return if (target == null) 0f to null else best to target
        }
        var lastSnap by remember { mutableStateOf<Float?>(null) }
        fun noteSnap(target: Float?) {
            if (target != null && lastSnap != target) tick()
            lastSnap = target
            snapLine = target ?: -1f
        }

        Box(
            Modifier.fillMaxSize()
                // two-finger pinch zoom: runs first and takes over when a second finger is down
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                val z = event.calculateZoom()
                                if (z != 1f) model.dpPerSec = (model.dpPerSec * z).coerceIn(0.4f, 420f)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                // one-finger horizontal scroll: quick swipes travel further than slow ones, and flings coast a long way
                .pointerInput(Unit) {
                    /** 1 when zoomed far out (boost the swipe), 0 from about 38 dp per second upward (finger and content move together). */
                    fun zoomEase() = (1f - (model.dpPerSec - 8f) / 30f).coerceIn(0f, 1f)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        flingJob?.cancel()
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        val first = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over -> change.consume() }
                        if (first != null) {
                            model.pause()
                            val dens = density.density
                            var lastT = first.uptimeMillis
                            horizontalDrag(first.id) { change ->
                                val dx = change.positionChange().x
                                val dt = (change.uptimeMillis - lastT).coerceAtLeast(1L)
                                lastT = change.uptimeMillis
                                val speed = abs(dx) / dt * 1000f / dens            // dp per second
                                val gain = 1f + (speed / 700f).coerceIn(0f, 1f) * 1.2f * zoomEase()  // 1x for careful drags; quick swipes up to about 2x when zoomed far out, fading to 1x by normal zoom and beyond
                                model.posS = (model.posS - dx * gain / pps).coerceIn(0f, model.duration)
                                tracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                            }
                            val v = tracker.calculateVelocity().x
                            if (abs(v) > 250f) flingJob = flingScope.launch {
                                var last = 0f
                                AnimationState(0f, -v * (1.0f + 0.6f * zoomEase()) / pps).animateDecay(exponentialDecay<Float>(0.7f)) {
                                    model.posS = (model.posS + (value - last)).coerceIn(0f, model.duration)
                                    last = value
                                    if (model.posS <= 0f || model.posS >= model.duration) flingJob?.cancel()
                                }
                            }
                        }
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { model.selection = null }) },
        ) {
            Column(Modifier.fillMaxSize()) {
                // ---- ruler ----
                Ruler(model, pps, centerPx, Modifier.fillMaxWidth().height(RULER_H))
                // ---- lanes ----
                Column(Modifier.weight(1f).verticalScroll(scroll).padding(top = 2.dp, bottom = 6.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap)) {
                    // picture / video / intro lane
                    Box(Modifier.fillMaxWidth().height(laneVisual)) {
                        if (project.media.isEmpty()) {
                            Text("Tap “Media” to add pictures and videos", color = c.faint, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
                        } else {
                            val tail = project.durationS - project.mediaEndS
                            if (tail > 0.05f) Canvas(Modifier.fillMaxSize()) {
                                val x0 = xOf(project.mediaEndS); val x1 = xOf(project.durationS)
                                if (x1 > 0f && x0 < size.width) drawRect(c.lineStrong, Offset(x0, 3.dp.toPx()), Size(x1 - x0, size.height - 6.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
                            }
                            project.media.forEachIndexed { i, clip ->
                                key(clip.id) {
                                    MediaClipView(model, clip, i, pps, centerPx, laneVisual, { snapPoints(null, null) }, ::snap, ::noteSnap)
                                }
                            }
                            // transition markers sit on the cut between two clips
                            project.media.forEachIndexed { i, clip ->
                                if (i > 0) key("tr" + clip.id) {
                                    val has = clip.transition != "none"
                                    Box(
                                        Modifier.offset { IntOffset((xOf(clip.startS) - 14.dp.toPx()).roundToInt(), 0) }
                                            .align(Alignment.CenterStart).size(28.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(if (has) c.ink else c.surface)
                                            .border(1.5.dp, if (has) c.ink else c.lineStrong, androidx.compose.foundation.shape.CircleShape)
                                            .pointerInput(clip.id) { detectTapGestures(onTap = { model.selection = Sel.Media(clip.id); onTransition(clip.id) }) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(if (has) "⇄" else "+", color = if (has) c.bg else c.muted, fontSize = if (has) 13.sp else 15.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                    // audio lanes
                    audioLanes.forEachIndexed { li, lane ->
                        Box(Modifier.fillMaxWidth().height(laneAudio)) {
                            if (project.audio.isEmpty()) Text("Tap “Music” to add songs", color = c.faint, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
                            project.audio.filter { it.track == lane }.forEach { clip ->
                                key(clip.id) {
                                    AudioClipView(
                                        model, clip, pps, centerPx, laneAudio, { snapPoints(clip.id, null) }, ::snap, ::noteSnap,
                                        laneCount = audioLanes.size, laneIndex = li, lanes = audioLanes, laneHeightPx = with(density) { (laneAudio + gap).toPx() },
                                    )
                                }
                            }
                        }
                    }
                    // effect lanes
                    if (fxLanes.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(laneFx)) {
                            Text("Tap “Effects” to place effects on the timeline", color = c.faint, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
                        }
                    }
                    fxLanes.forEachIndexed { li, lane ->
                        Box(Modifier.fillMaxWidth().height(laneFx)) {
                            project.effects.filter { it.track == lane }.forEach { layer ->
                                key(layer.id) {
                                    EffectClipView(
                                        model, layer, pps, centerPx, laneFx, { snapPoints(null, layer.id) }, ::snap, ::noteSnap,
                                        laneIndex = li, lanes = fxLanes, laneHeightPx = with(density) { (laneFx + gap).toPx() },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // playhead
            Canvas(Modifier.fillMaxSize()) {
                if (snapLine >= 0f) {
                    val sx = xOf(snapLine)
                    drawLine(Color(0xFF52A8FF), Offset(sx, 0f), Offset(sx, size.height), strokeWidth = 1.5.dp.toPx())
                }
                drawLine(c.ink, Offset(centerPx, 0f), Offset(centerPx, size.height), strokeWidth = 1.5.dp.toPx())
                val r = 4.dp.toPx()
                drawCircle(c.ink, r, Offset(centerPx, r + 1f))
            }
        }
    }
}

@Composable
private fun Ruler(model: EditorModel, pps: Float, centerPx: Float, modifier: Modifier) {
    val c = Lofi.c
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = GeistMono, fontSize = 9.sp, color = c.faint)
    Canvas(
        modifier
            .pointerInput(pps) { detectTapGestures { off -> model.seek(model.posS + (off.x - centerPx) / pps) } }
            // dragging the ruler scrubs the whole project edge to edge: the quick way to get around a long video
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    var moved = false
                    drag(down.id) { change ->
                        if (!moved && abs(change.position.x - down.position.x) < slop) return@drag
                        moved = true
                        model.pause()
                        model.posS = (change.position.x / size.width).coerceIn(0f, 1f) * model.duration
                        change.consume()
                    }
                }
            },
    ) {
        val pos = model.posS
        val left = pos - centerPx / pps
        val right = pos + (size.width - centerPx) / pps
        // pick a tick step so labels are ~80dp apart
        val steps = floatArrayOf(0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 300f, 600f)
        val wantPx = 80.dp.toPx()
        val step = steps.firstOrNull { it * pps >= wantPx } ?: 600f
        val minor = step / 5f
        var t = (kotlin.math.floor(left / minor) * minor).coerceAtLeast(0f)
        val dur = model.project.durationS
        while (t <= right) {
            val x = centerPx + (t - pos) * pps
            val major = abs(t / step - (t / step).roundToInt()) < 0.001f
            if (t <= dur + 0.001f) {
                drawLine(c.line, Offset(x, size.height - (if (major) 9.dp.toPx() else 4.dp.toPx())), Offset(x, size.height), 1f)
                if (major && x + 3.dp.toPx() < size.width - 40.dp.toPx()) drawText(measurer, fmtTime(t), Offset(x + 3.dp.toPx(), 2.dp.toPx()), style, softWrap = false, maxLines = 1)
            }
            t += minor
        }
        // beat markers
        if (model.dpPerSec >= 28f) model.features?.beatTimes?.forEach { b ->
            if (b in left..right) drawCircle(c.faint, 1.5.dp.toPx(), Offset(centerPx + (b - pos) * pps, size.height - 2.dp.toPx()))
        }
        // end of project
        val ex = centerPx + (dur - pos) * pps
        drawLine(c.lineStrong, Offset(ex, 0f), Offset(ex, size.height), 1f)
    }
}

private const val CLIP_MARGIN = 200f

/**
 * The common clip chrome: positioned by time, with selection border and trim handles whose touch area is 48 dp.
 * Only the part of a clip that is on (or near) the screen is laid out, so hour-long clips never exceed layout limits.
 */
@Composable
private fun ClipBox(
    startS: Float, endS: Float, pps: Float, centerPx: Float, posProvider: () -> Float, height: Dp, color: Color, selected: Boolean,
    label: String, showHandles: Boolean = true,
    onTap: () -> Unit = {},
    onMoveStart: () -> Unit = {}, onMove: (dxPx: Float, dyPx: Float) -> Unit = { _, _ -> }, onMoveEnd: () -> Unit = {},
    onTrimStart: (edge: Int) -> Unit = {}, onTrim: (edge: Int, dxPx: Float) -> Unit = { _, _ -> }, onTrimEnd: () -> Unit = {},
    body: @Composable (originX: () -> Float) -> Unit = {},
) {
    val c = Lofi.c
    val density = LocalDensity.current
    val view = LocalView.current
    val pad = if (selected && showHandles) HANDLE_TOUCH else 0.dp
    val padPx = with(density) { pad.toPx() }
    val heightPx = with(density) { height.roundToPx() }
    // screen x where the drawing area of this clip starts (clamped to the visible window)
    fun leftEdge() = centerPx + (startS - posProvider()) * pps - padPx
    fun originX() = max(leftEdge(), -CLIP_MARGIN) + padPx
    Box(
        Modifier.layout { m, cons ->
            val l = leftEdge()
            val r = centerPx + (endS - posProvider()) * pps + padPx
            val vl = max(l, -CLIP_MARGIN)
            val vr = min(r, cons.maxWidth + CLIP_MARGIN)
            val w = max(vr - vl, 8f).roundToInt()
            val pl = m.measure(androidx.compose.ui.unit.Constraints.fixed(w, heightPx))
            layout(cons.maxWidth, heightPx) { pl.place(vl.roundToInt(), 0) }
        },
    ) {
        Box(
            Modifier.padding(horizontal = pad).fillMaxSize().clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = if (selected) 0.30f else 0.18f))
                .border(if (selected) 1.5.dp else 1.dp, if (selected) c.ink else color.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onTap(); onMoveStart() },
                        onDrag = { change, drag -> change.consume(); onMove(drag.x, drag.y) },
                        onDragEnd = onMoveEnd, onDragCancel = onMoveEnd,
                    )
                },
        ) {
            body(::originX)
            if (label.isNotEmpty()) Text(
                label, color = c.ink, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                modifier = Modifier.padding(start = 6.dp, top = 2.dp),
            )
        }
        if (selected && showHandles) {
            Handle(Modifier.align(Alignment.CenterStart), height, { onTrimStart(0) }, { onTrim(0, it) }, onTrimEnd)
            Handle(Modifier.align(Alignment.CenterEnd), height, { onTrimStart(1) }, { onTrim(1, it) }, onTrimEnd)
        }
    }
}

/** A trim handle: 10 dp wide bar drawn inside a 48 dp wide touch target. */
@Composable
private fun Handle(modifier: Modifier, height: Dp, onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit) {
    val c = Lofi.c
    val view = LocalView.current
    Box(
        modifier.width(HANDLE_TOUCH * 2).height(max(height.value, 48f).dp).offset(y = 0.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    onStart()
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    horizontalDrag(down.id) { change -> onDrag(change.positionChange().x); change.consume() }
                    onEnd()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(10.dp).fillMaxHeight(0.9f).clip(RoundedCornerShape(3.dp)).background(c.ink), contentAlignment = Alignment.Center) {
            Box(Modifier.width(2.dp).height(10.dp).background(c.bg))
        }
    }
}

@Composable
private fun AudioClipView(
    model: EditorModel, clip: AudioClip, pps: Float, centerPx: Float, height: Dp,
    points: () -> List<Float>, snap: (List<Float>, List<Float>) -> Pair<Float, Float?>, noteSnap: (Float?) -> Unit,
    laneCount: Int, laneIndex: Int, lanes: List<Int>, laneHeightPx: Float,
) {
    val c = Lofi.c
    val selected = (model.selection as? Sel.Audio)?.id == clip.id
    var orig by remember { mutableStateOf(clip) }
    var accX by remember { mutableFloatStateOf(0f) }
    var accY by remember { mutableFloatStateOf(0f) }
    val peaks by produceState<FloatArray?>(model.waveforms.cached(clip.uri), clip.uri) { value = model.waveforms.peaks(clip.uri) }
    ClipBox(
        clip.startS, clip.endS, pps, centerPx, { model.posS }, height, c.clipAudio, selected, clip.title,
        onTap = { model.selection = Sel.Audio(clip.id) },
        onMoveStart = { model.beginDrag(); orig = model.audioById(clip.id) ?: clip; accX = 0f; accY = 0f },
        onMove = { dx, dy ->
            accX += dx; accY += dy
            val cur = orig
            var ns = (cur.startS + accX / pps).coerceAtLeast(0f)
            val (adj, target) = snap(listOf(ns, ns + cur.lenS), points())
            ns = (ns + adj).coerceAtLeast(0f)
            noteSnap(target)
            val shift = (accY / laneHeightPx).roundToInt()
            val idx = laneIndex + shift
            val track = when { idx < 0 -> (lanes.firstOrNull() ?: 0) - 1; idx >= lanes.size -> (lanes.lastOrNull() ?: 0) + 1; else -> lanes[idx] }
            model.updateAudio(clip.id, drag = true) { it.copy(startS = ns, track = track) }
        },
        onMoveEnd = { noteSnap(null); model.endDrag() },
        onTrimStart = { _ -> model.beginDrag(); orig = model.audioById(clip.id) ?: clip; accX = 0f },
        onTrim = { edge, dx ->
            accX += dx
            val o = orig
            if (edge == 0) {
                var ns = o.startS + accX / pps
                ns = ns.coerceIn(max(0f, o.startS - o.inS), o.endS - 0.3f)
                val (adj, target) = snap(listOf(ns), points()); noteSnap(target)
                ns = (ns + adj).coerceIn(max(0f, o.startS - o.inS), o.endS - 0.3f)
                val d = ns - o.startS
                model.updateAudio(clip.id, drag = true) { it.copy(startS = ns, inS = o.inS + d, lenS = o.lenS - d) }
            } else {
                var ne = o.endS + accX / pps
                ne = ne.coerceIn(o.startS + 0.3f, o.startS + (o.srcDurS - o.inS))
                val (adj, target) = snap(listOf(ne), points()); noteSnap(target)
                ne = (ne + adj).coerceIn(o.startS + 0.3f, o.startS + (o.srcDurS - o.inS))
                model.updateAudio(clip.id, drag = true) { it.copy(lenS = ne - o.startS) }
            }
        },
        onTrimEnd = { noteSnap(null); model.endDrag() },
        body = { origin ->
            val pk = peaks
            if (pk == null) Skeleton(Modifier.fillMaxSize().padding(vertical = 8.dp), corner = 3.dp)
            else Waveform(pk, clip, pps, centerPx, { model.posS }, origin)
        },
    )
}

@Composable
private fun Waveform(peaks: FloatArray, clip: AudioClip, pps: Float, centerPx: Float, pos: () -> Float, originX: () -> Float) {
    val c = Lofi.c
    val col = c.clipAudio
    Canvas(Modifier.fillMaxSize()) {
        val clipLeft = centerPx + (clip.startS - pos()) * pps
        val shift = originX() - clipLeft          // clip-relative px of this canvas' left edge
        val lenPx = clip.lenS * pps
        val step = 3f
        val mid = size.height / 2f
        val span = max(1, (step / pps * Waveforms.PER_SEC).roundToInt())
        // fade ramps: darken the part of the clip that is being faded and draw the gain line
        val fiPx = clip.fadeInS * pps; val foPx = clip.fadeOutS * pps
        if (fiPx > 2f) {
            val x0 = -shift; val x1 = fiPx - shift
            if (x1 > 0f && x0 < size.width) {
                val p = androidx.compose.ui.graphics.Path().apply { moveTo(x0, 0f); lineTo(x1, 0f); lineTo(x0, size.height); close() }
                drawPath(p, Color.Black.copy(alpha = 0.5f)); drawLine(Color.White.copy(alpha = 0.8f), Offset(x0, size.height), Offset(x1, 0f), 1.5f)
            }
        }
        if (foPx > 2f) {
            val x0 = lenPx - foPx - shift; val x1 = lenPx - shift
            if (x1 > 0f && x0 < size.width) {
                val p = androidx.compose.ui.graphics.Path().apply { moveTo(x0, 0f); lineTo(x1, 0f); lineTo(x1, size.height); close() }
                drawPath(p, Color.Black.copy(alpha = 0.5f)); drawLine(Color.White.copy(alpha = 0.8f), Offset(x0, 0f), Offset(x1, size.height), 1.5f)
            }
        }
        var x = 0f
        while (x < size.width) {
            val rel = x + shift
            if (rel > lenPx) break
            if (rel >= 0f) {
                val i = ((clip.inS + rel / pps) * Waveforms.PER_SEC).toInt()
                if (i in peaks.indices) {
                    var m = 0f
                    for (k in i until min(peaks.size, i + span)) m = max(m, peaks[k])
                    val h = (m.coerceAtMost(1f) * (size.height - 8.dp.toPx())).coerceAtLeast(1.5f)
                    drawLine(col.copy(alpha = 0.9f), Offset(x, mid - h / 2), Offset(x, mid + h / 2), strokeWidth = 2f)
                }
            }
            x += step
        }
    }
}

@Composable
private fun EffectClipView(
    model: EditorModel, layer: Layer, pps: Float, centerPx: Float, height: Dp,
    points: () -> List<Float>, snap: (List<Float>, List<Float>) -> Pair<Float, Float?>, noteSnap: (Float?) -> Unit,
    laneIndex: Int, lanes: List<Int>, laneHeightPx: Float,
) {
    val selected = (model.selection as? Sel.Effect)?.id == layer.id
    val def = EffectLibrary.get(layer.fx)
    var orig by remember { mutableStateOf(layer) }
    var accX by remember { mutableFloatStateOf(0f) }
    var accY by remember { mutableFloatStateOf(0f) }
    val end = model.project.layerEnd(layer)
    val dur = model.duration
    ClipBox(
        layer.from, end, pps, centerPx, { model.posS }, height, fxColor(layer), selected, (def?.name ?: layer.fx) + if (!layer.on) " (off)" else "",
        onTap = { model.selection = Sel.Effect(layer.id) },
        onMoveStart = { model.beginDrag(); orig = model.effectById(layer.id) ?: layer; accX = 0f; accY = 0f },
        onMove = { dx, dy ->
            accX += dx; accY += dy
            val o = orig
            val len = model.project.layerEnd(o) - o.from
            var ns = (o.from + accX / pps).coerceIn(0f, max(0f, dur - 0.2f))
            val (adj, target) = snap(listOf(ns, ns + len), points()); noteSnap(target)
            ns = (ns + adj).coerceAtLeast(0f)
            val shift = (accY / laneHeightPx).roundToInt()
            val idx = laneIndex + shift
            val track = when { idx < 0 -> (lanes.firstOrNull() ?: 0) - 1; idx >= lanes.size -> (lanes.lastOrNull() ?: 0) + 1; else -> lanes[idx] }
            model.updateEffect(layer.id, drag = true) { it.copy(from = ns, to = ns + len, track = track) }
        },
        onMoveEnd = { noteSnap(null); model.endDrag() },
        onTrimStart = { _ -> model.beginDrag(); orig = model.effectById(layer.id) ?: layer; accX = 0f },
        onTrim = { edge, dx ->
            accX += dx
            val o = orig
            val oEnd = model.project.layerEnd(o)
            if (edge == 0) {
                var ns = (o.from + accX / pps).coerceIn(0f, oEnd - 0.2f)
                val (adj, target) = snap(listOf(ns), points()); noteSnap(target)
                ns = (ns + adj).coerceIn(0f, oEnd - 0.2f)
                model.updateEffect(layer.id, drag = true) { it.copy(from = ns, to = oEnd) }
            } else {
                var ne = (oEnd + accX / pps).coerceAtLeast(o.from + 0.2f)
                val (adj, target) = snap(listOf(ne), points()); noteSnap(target)
                ne = (ne + adj).coerceAtLeast(o.from + 0.2f)
                model.updateEffect(layer.id, drag = true) { it.copy(to = ne) }
            }
        },
        onTrimEnd = { noteSnap(null); model.endDrag() },
    )
}

@Composable
private fun MediaClipView(
    model: EditorModel, clip: MediaClip, index: Int, pps: Float, centerPx: Float, height: Dp,
    points: () -> List<Float>, snap: (List<Float>, List<Float>) -> Pair<Float, Float?>, noteSnap: (Float?) -> Unit,
) {
    val c = Lofi.c
    val selected = (model.selection as? Sel.Media)?.id == clip.id
    var orig by remember { mutableStateOf(clip) }
    var origIndex by remember { mutableStateOf(index) }
    var accX by remember { mutableFloatStateOf(0f) }
    val label = when {
        clip.isIntro -> "Intro · " + (Intros.forUri(clip.uri)?.name ?: "")
        clip.video -> "Video"
        else -> "Picture"
    }
    ClipBox(
        clip.startS, clip.endS, pps, centerPx, { model.posS }, height, if (clip.isIntro) Color(0xFFE5A84B) else c.clipVisual, selected, label,
        onTap = { model.selection = Sel.Media(clip.id) },
        // long-press and drag: the clip hops over its neighbours (the track stays gap-free)
        onMoveStart = { model.beginDrag(); orig = model.mediaById(clip.id) ?: clip; origIndex = model.project.media.indexOfFirst { it.id == clip.id }; accX = 0f },
        onMove = { dx, _ ->
            accX += dx
            val o = orig
            val mid = o.startS + o.lenS / 2f + accX / pps
            val others = model.project.media.filter { it.id != o.id }
            val target = others.count { it.startS + it.lenS / 2f < mid }
            val cur = model.project.media.indexOfFirst { it.id == o.id }
            if (target != cur) model.drag { p ->
                val list = p.media.toMutableList()
                val item = list.removeAt(cur); list.add(target, item)
                p.copy(media = list).relayout()
            }
        },
        onMoveEnd = { noteSnap(null); model.endDrag() },
        onTrimStart = { _ -> model.beginDrag(); orig = model.mediaById(clip.id) ?: clip; accX = 0f },
        onTrim = { edge, dx ->
            accX += dx
            val o = orig
            val d = accX / pps
            if (edge == 0) {
                // trim the start: the clip keeps its place in the track and loses (or gains) footage at the front
                val maxCut = o.lenS - 0.5f
                val lowest = if (o.video) -o.inS else -3600f
                val cut = d.coerceIn(lowest, maxCut)
                model.updateMedia(clip.id, drag = true) { it.copy(inS = if (o.video) o.inS + cut else o.inS, lenS = o.lenS - cut) }
            } else {
                var ne = o.endS + d
                val (adj, target) = snap(listOf(ne), points()); noteSnap(target)
                ne = (ne + adj).coerceAtLeast(o.startS + 0.5f)
                model.updateMedia(clip.id, drag = true) { it.copy(lenS = ne - o.startS) }
            }
        },
        onTrimEnd = { noteSnap(null); model.endDrag() },
        body = { origin ->
            if (clip.isIntro) IntroStrip(Intros.forUri(clip.uri)?.name ?: "Intro")
            else VisualStrip(clip.uri, clip.video, clip.startS, clip.inS, pps, centerPx, { model.posS }, origin)
        },
    )
}

@Composable
private fun IntroStrip(name: String) {
    Box(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF3A2352), Color(0xFFC0602F), Color(0xFFE5A84B)))).padding(start = 6.dp),
        contentAlignment = Alignment.BottomStart,
    ) { Text("✦ $name", color = Color.White.copy(alpha = 0.85f), fontSize = 9.sp, modifier = Modifier.padding(bottom = 3.dp)) }
}

@Composable
private fun VisualStrip(uri: String, video: Boolean, startS: Float, inS: Float, pps: Float, centerPx: Float, pos: () -> Float, originX: () -> Float) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri, inS) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                if (video) {
                    val r = android.media.MediaMetadataRetriever()
                    r.setDataSource(ctx, Uri.parse(uri))
                    val f = r.getScaledFrameAtTime(((inS + 0.5f) * 1_000_000L).toLong(), android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 135)
                    r.release(); f?.asImageBitmap()
                } else ImageLoader.load(ctx, Uri.parse(uri), 320).asImageBitmap()
            }.getOrNull()
        }
    }
    val b = bmp
    if (b == null) { Skeleton(Modifier.fillMaxSize(), corner = 3.dp); return }
    Canvas(Modifier.fillMaxSize()) {
        val h = size.height
        val tileW = h * (b.width.toFloat() / b.height)
        val clipLeft = centerPx + (startS - pos()) * pps
        val shift = originX() - clipLeft
        var x = -(shift % tileW)
        while (x < size.width) {
            drawImage(b, srcOffset = IntOffset.Zero, srcSize = IntSize(b.width, b.height), dstOffset = IntOffset(x.roundToInt(), 0), dstSize = IntSize(tileW.roundToInt() + 1, h.roundToInt()), alpha = 0.85f)
            x += tileW
        }
    }
}
