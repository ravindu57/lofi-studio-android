package com.lofiautopilot.studio.ui.editor

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LayersClear
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.VerticalAlignCenter
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lofiautopilot.studio.audio.PcmDecoder
import com.lofiautopilot.studio.auto.RenderService
import com.lofiautopilot.studio.auto.Saf
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.export.Aspect
import com.lofiautopilot.studio.ui.components.Mono
import com.lofiautopilot.studio.ui.components.pressScale
import com.lofiautopilot.studio.ui.theme.Lofi
import com.lofiautopilot.studio.vfx.EffectLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SheetKind { NONE, EFFECTS, MUSIC, LOOKS, ADJUST, VOLUME, FADE, EQ, FORMAT, EXPORT, AI, MEDIA, TRANSITION }

/**
 * The full-screen editor. It forces landscape, hides the system bars, and shows only the tools that apply to what is
 * selected (progressive disclosure): global tools when nothing is selected, clip tools when a clip is.
 */
@Composable
fun EditorScreen(g: Graph, model: EditorModel, onClose: () -> Unit) {
    val c = Lofi.c
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf(SheetKind.NONE) }
    var expandedTimeline by remember { mutableStateOf(false) }
    var fullPreview by remember { mutableStateOf(false) }
    val preview = remember { PreviewEngine(model) }

    // landscape + immersive while editing
    DisposableEffect(Unit) {
        val act = ctx as? Activity
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val w = act?.window
        val ctl = w?.let { WindowInsetsControllerCompat(it, view) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            ctl?.show(WindowInsetsCompat.Type.systemBars())
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            preview.release()
            model.close()
        }
    }
    // playhead follows the audio clock while playing
    LaunchedEffect(model) {
        while (true) { withFrameNanos { model.tick() } }
    }
    // initial zoom: show the whole project
    val hasLength = model.project.durationS > 1f
    LaunchedEffect(model.project.id, hasLength) {
        if (hasLength) model.dpPerSec = (260f / model.project.durationS).coerceIn(0.4f, 60f)
    }
    BackHandler { if (fullPreview) fullPreview = false else if (sheet != SheetKind.NONE) sheet = SheetKind.NONE else if (model.selection != null) model.selection = null else onClose() }

    // several pictures or videos at once: they go in one after another from the playhead
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { us: List<Uri> ->
        scope.launch(Dispatchers.IO) { us.forEach { runCatching { Saf.persist(ctx, it); model.addMedia(it.toString(), false, advance = us.size > 1) } } }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { us: List<Uri> ->
        scope.launch(Dispatchers.IO) { us.forEach { runCatching { Saf.persist(ctx, it); model.addMedia(it.toString(), true, advance = us.size > 1) } } }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { us: List<Uri> ->
        scope.launch {
            us.forEach {
                Saf.persist(ctx, it)
                val d = withContext(Dispatchers.IO) { PcmDecoder.probe(ctx, it) } / 1e6f
                val title = (it.lastPathSegment ?: "Track").substringAfterLast('/').substringBeforeLast('.')
                if (d > 0f) model.addMusic(it.toString(), title, d, null)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // ---------- preview ----------
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val a = runCatching { Aspect.valueOf(model.project.aspect) }.getOrDefault(Aspect.WIDE)
                val ar = a.w.toFloat() / a.h
                Box(Modifier.fillMaxHeight().aspectRatio(ar, matchHeightConstraintsFirst = true).padding(vertical = 2.dp).clip(RoundedCornerShape(4.dp))) {
                    AndroidView(factory = { cx ->
                        GLSurfaceView(cx).apply {
                            setEGLContextClientVersion(3)
                            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
                            preserveEGLContextOnPause = false
                            setRenderer(preview.renderer)
                            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                            preview.attach(this)
                        }
                    }, modifier = Modifier.fillMaxSize(), onRelease = { it.onPause() })
                    if (model.project.media.isEmpty()) Box(Modifier.fillMaxSize().background(c.surface), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Start with pictures, videos or an intro", color = c.ink, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Box(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(c.ink).pressScale({ sheet = SheetKind.MEDIA }).padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Text("Choose media", color = c.bg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    if (model.analysing in 0f..0.999f) Row(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = .7f)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Mono("Listening ${(model.analysing * 100).toInt()}%", Color.White, 11)
                    }
                }
                // top bar overlay
                if (!fullPreview) Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { onClose() }
                    Text(model.project.name, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp).weight(1f))
                    IconBtn(Icons.AutoMirrored.Rounded.Undo, "Undo", enabled = model.canUndo) { model.undo() }
                    IconBtn(Icons.AutoMirrored.Rounded.Redo, "Redo", enabled = model.canRedo) { model.redo() }
                    Box(Modifier.padding(start = 6.dp).height(34.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).pressScale({ sheet = SheetKind.EXPORT }).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text("Export", color = Color.Black, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    }
                }
            }
            // ---------- transport + timeline ----------
            if (!fullPreview) Row(Modifier.fillMaxWidth().height(32.dp).background(c.bg).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(if (model.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play", Color.White) { model.togglePlay() }
                Mono(timeText(model.posS), Color.White, 12, FontWeight.Medium, Modifier.padding(start = 4.dp))
                Mono(" / " + timeText(model.project.durationS), c.faint, 12)
                Box(Modifier.weight(1f))
                IconBtn(Icons.Rounded.UnfoldMore, "Taller timeline", if (expandedTimeline) Color.White else c.faint) { expandedTimeline = !expandedTimeline }
                IconBtn(Icons.Rounded.ZoomOutMap, "Fit timeline", c.faint) { if (model.project.durationS > 1f) model.dpPerSec = (300f / model.project.durationS).coerceIn(0.4f, 60f) }
                IconBtn(Icons.Rounded.Fullscreen, "Full screen preview", Color.White) { model.selection = null; sheet = SheetKind.NONE; fullPreview = true }
            }
            if (!fullPreview) TimelineView(model, expandedTimeline, Modifier.fillMaxWidth().height(if (expandedTimeline) 196.dp else 150.dp), onTransition = { sheet = SheetKind.TRANSITION })
            // ---------- contextual toolbar ----------
            if (!fullPreview) Box(Modifier.fillMaxWidth().height(54.dp).background(c.bg).border(1.dp, c.line)) {
                val sel = model.selection
                AnimatedContent(
                    targetState = when (sel) { is Sel.Audio -> 1; is Sel.Effect -> 2; is Sel.Media -> 3; null -> 0 },
                    transitionSpec = { (fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 }) togetherWith (fadeOut(tween(100)) + slideOutVertically(tween(160)) { -it / 3 }) },
                    label = "toolbar",
                ) { mode ->
                    LazyRow(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        when (mode) {
                            0 -> {
                                tool(Icons.Rounded.Image, "Media") { sheet = SheetKind.MEDIA }
                                tool(Icons.Rounded.SwapHoriz, "Transitions", enabled = model.project.media.size > 1) { sheet = SheetKind.TRANSITION }
                                tool(Icons.Rounded.MusicNote, "Music") { sheet = SheetKind.MUSIC }
                                tool(Icons.Rounded.GraphicEq, "Effects") { sheet = SheetKind.EFFECTS }
                                tool(Icons.Rounded.AutoAwesome, "Auto AI") { sheet = SheetKind.AI }
                                tool(Icons.Rounded.Palette, "Looks") { sheet = SheetKind.LOOKS }
                                tool(Icons.Rounded.AspectRatio, "Format") { sheet = SheetKind.FORMAT }
                                tool(Icons.Rounded.LayersClear, "Clear FX", enabled = model.project.effects.isNotEmpty()) { model.clearEffects() }
                            }
                            1 -> {
                                tool(Icons.Rounded.ContentCut, "Split") { model.split(sel!!) }
                                tool(Icons.Rounded.VolumeUp, "Volume") { sheet = SheetKind.VOLUME }
                                tool(Icons.Rounded.Gradient, "Fade") { sheet = SheetKind.FADE }
                                tool(Icons.Rounded.Equalizer, "EQ") { sheet = SheetKind.EQ }
                                tool(Icons.Rounded.ContentCopy, "Duplicate") { model.duplicate(sel!!) }
                                tool(Icons.Rounded.Delete, "Delete") { model.delete(sel!!) }
                                tool(Icons.Rounded.MusicNote, "Add music") { sheet = SheetKind.MUSIC }
                            }
                            2 -> {
                                tool(Icons.Rounded.Tune, "Adjust") { sheet = SheetKind.ADJUST }
                                tool(Icons.Rounded.ContentCut, "Split") { model.split(sel!!) }
                                tool(Icons.Rounded.GraphicEq, "On beat") {
                                    val id = (sel as Sel.Effect).id
                                    model.effectById(id)?.let { l -> model.nearestBeat(l.from)?.let { b -> model.updateEffect(id) { it.copy(from = b, to = b + (model.project.layerEnd(l) - l.from)) } } }
                                }
                                tool(Icons.Rounded.ContentCopy, "Duplicate") { model.duplicate(sel!!) }
                                tool(Icons.Rounded.ArrowUpward, "Forward") { model.moveLayerOrder((sel as Sel.Effect).id, 1) }
                                tool(Icons.Rounded.ArrowDownward, "Back") { model.moveLayerOrder((sel as Sel.Effect).id, -1) }
                                tool(Icons.Rounded.Delete, "Delete") { model.delete(sel!!) }
                            }
                            else -> {
                                val mid = (sel as Sel.Media).id
                                val mc = model.mediaById(mid)
                                val idx = model.project.media.indexOfFirst { it.id == mid }
                                tool(Icons.Rounded.ContentCut, "Split", enabled = mc?.isIntro != true) { model.split(sel) }
                                tool(Icons.Rounded.SwapHoriz, "Transition", enabled = idx > 0) { sheet = SheetKind.TRANSITION }
                                tool(Icons.Rounded.Image, "Add media") { sheet = SheetKind.MEDIA }
                                tool(Icons.Rounded.ChevronLeft, "Move left", enabled = idx > 0) { model.moveMedia(mid, -1) }
                                tool(Icons.Rounded.ChevronRight, "Move right", enabled = idx in 0 until model.project.media.lastIndex) { model.moveMedia(mid, 1) }
                                tool(Icons.Rounded.ContentCopy, "Duplicate") { model.duplicate(sel) }
                                tool(Icons.Rounded.Slideshow, if (model.project.fit == com.lofiautopilot.studio.vfx.Fit.COVER) "Fit + blur" else "Fill", enabled = mc?.isIntro != true) {
                                    model.edit { it.copy(fit = if (it.fit == com.lofiautopilot.studio.vfx.Fit.COVER) com.lofiautopilot.studio.vfx.Fit.CONTAIN_BLUR else com.lofiautopilot.studio.vfx.Fit.COVER) }
                                }
                                tool(Icons.Rounded.Delete, "Delete") { model.delete(sel) }
                            }
                        }
                    }
                }
            }
        }
        if (fullPreview) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { model.togglePlay() }) })
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(if (model.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play", Color.White) { model.togglePlay() }
                Mono(timeText(model.posS), Color.White, 12, FontWeight.Medium)
                Mono(" / " + timeText(model.project.durationS), Color(0xFFAAAAAA), 12)
                IconBtn(Icons.Rounded.FullscreenExit, "Exit full screen", Color.White) { fullPreview = false }
            }
        }
        Sheets(g, model, sheet, { sheet = it }, onPickImage = { pickImage.launch(arrayOf("image/*")) }, onPickVideo = { pickVideo.launch(arrayOf("video/*")) }, onPickAudio = { pickAudio.launch(arrayOf("audio/*")) }, onExported = { sheet = SheetKind.NONE })
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.tool(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    item(key = label) { ToolButton(icon, label, enabled, onClick) }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Lofi.c
    Column(
        Modifier.width(64.dp).height(52.dp).clip(RoundedCornerShape(8.dp)).pressScale(onClick, enabled),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, tint = c.ink, modifier = Modifier.size(22.dp))
        Text(label, color = c.muted, fontSize = 10.sp, maxLines = 1, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun IconBtn(icon: ImageVector, desc: String, tint: Color = Color.White, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).pressScale(onClick, enabled, haptic = false), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(22.dp))
    }
}

private fun timeText(s: Float): String {
    val t = s.coerceAtLeast(0f)
    val m = (t / 60).toInt(); val sec = (t % 60).toInt(); val d = ((t * 10) % 10).toInt()
    return "%02d:%02d.%d".format(m, sec, d)
}

