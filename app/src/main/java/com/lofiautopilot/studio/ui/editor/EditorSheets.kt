package com.lofiautopilot.studio.ui.editor

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.max
import com.lofiautopilot.studio.audio.PcmDecoder
import com.lofiautopilot.studio.auto.RenderService
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.export.Aspect
import com.lofiautopilot.studio.export.Codec
import com.lofiautopilot.studio.export.VideoExporter
import com.lofiautopilot.studio.ui.components.Chip
import com.lofiautopilot.studio.ui.components.GhostButton
import com.lofiautopilot.studio.ui.components.GradientButton
import com.lofiautopilot.studio.ui.components.Mono
import com.lofiautopilot.studio.ui.components.Skeleton
import com.lofiautopilot.studio.ui.components.pressScale
import com.lofiautopilot.studio.ui.theme.Lofi
import com.lofiautopilot.studio.vfx.Blend
import com.lofiautopilot.studio.vfx.EffectLibrary
import com.lofiautopilot.studio.vfx.Fit
import com.lofiautopilot.studio.vfx.Intros
import com.lofiautopilot.studio.vfx.Transitions
import com.lofiautopilot.studio.vfx.FxDef
import com.lofiautopilot.studio.vfx.FxKind
import com.lofiautopilot.studio.vfx.Layer
import com.lofiautopilot.studio.vfx.Presets
import com.lofiautopilot.studio.vfx.React
import com.lofiautopilot.studio.vfx.param
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

fun categoryIcon(cat: String): ImageVector = when (cat) {
    "Camera" -> Icons.Rounded.Videocam
    "Visualizer" -> Icons.Rounded.GraphicEq
    "Atmosphere" -> Icons.Rounded.Cloud
    "Light" -> Icons.Rounded.LightMode
    "Colour" -> Icons.Rounded.Palette
    "Distort" -> Icons.Rounded.Waves
    "Retro" -> Icons.Rounded.Tv
    "Film" -> Icons.Rounded.Movie
    else -> Icons.Rounded.Layers
}

/** All bottom sheets of the editor. They keep the preview visible above them (no dimming scrim). */
@Composable
internal fun Sheets(
    g: Graph, model: EditorModel, sheet: SheetKind, setSheet: (SheetKind) -> Unit,
    onPickImage: () -> Unit, onPickVideo: () -> Unit, onPickAudio: () -> Unit, onExported: () -> Unit,
) {
    if (sheet == SheetKind.NONE) return
    val c = Lofi.c
    val density = androidx.compose.ui.platform.LocalDensity.current
    val visible = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxH = maxHeight
        val rest = (if (sheet in listOf(SheetKind.EQ, SheetKind.ADJUST, SheetKind.EFFECTS, SheetKind.REPLACE_FX, SheetKind.EXPORT, SheetKind.LOOKS, SheetKind.AI, SheetKind.MEDIA, SheetKind.TRANSITION)) 300.dp else 214.dp).coerceAtMost(maxH - 40.dp)
        var panelH by remember(sheet) { mutableStateOf(rest) }
        val animatedH by androidx.compose.animation.core.animateDpAsState(panelH, androidx.compose.animation.core.tween(if (panelH == rest || panelH == maxH) 200 else 0), label = "panel")
        val isFull = panelH >= maxH - 1.dp
        // tap outside the panel to close it
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { setSheet(SheetKind.NONE) } })
        androidx.compose.animation.AnimatedVisibility(
            visibleState = visible, modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(240)) { it } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
        ) {
            val shape = RoundedCornerShape(topStart = if (isFull) 0.dp else 12.dp, topEnd = if (isFull) 0.dp else 12.dp)
            Column(
                Modifier.fillMaxWidth().height(animatedH).clip(shape).background(c.surface).border(1.dp, c.line, shape)
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                // drag handle row: drag up/down to resize, tap the arrow for full screen
                Row(
                    Modifier.fillMaxWidth().pointerInput(maxH) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                panelH = when {
                                    panelH < 140.dp -> { setSheet(SheetKind.NONE); rest }
                                    panelH > rest + (maxH - rest) * 0.35f -> maxH
                                    else -> rest
                                }
                            },
                        ) { change, dy ->
                            change.consume()
                            panelH = (panelH - with(density) { dy.toDp() }).coerceIn(100.dp, maxH)
                        }
                    }.padding(top = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).pressScale({ panelH = if (isFull) rest else maxH }, haptic = false), contentAlignment = Alignment.Center) {
                        Icon(if (isFull) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull, "Full screen", tint = c.muted, modifier = Modifier.size(20.dp))
                    }
                    Box(Modifier.weight(1f).height(44.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(width = 40.dp, height = 4.dp).clip(CircleShape).background(c.lineStrong)) }
                    Box(Modifier.size(44.dp).pressScale({ setSheet(SheetKind.NONE) }, haptic = false), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, "Close", tint = c.muted, modifier = Modifier.size(20.dp)) }
                }
                Column(Modifier.weight(1f).padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                    when (sheet) {
                        SheetKind.MEDIA -> MediaSheet(g, model, onPickImage, onPickVideo) { setSheet(SheetKind.NONE) }
                        SheetKind.TRANSITION -> TransitionSheet(model)
                        SheetKind.MUSIC -> MusicSheet(g, model, onPickAudio) { setSheet(SheetKind.NONE) }
                        SheetKind.EFFECTS -> EffectsSheet { def -> model.addEffect(def.id); setSheet(SheetKind.NONE) }
                        SheetKind.REPLACE_FX -> EffectsSheet("Replace with…") { def -> (model.selection as? Sel.Effect)?.let { model.replaceEffect(it.id, def.id) }; setSheet(SheetKind.NONE) }
                        SheetKind.LOOKS -> LooksSheet(g, model) { setSheet(SheetKind.NONE) }
                        SheetKind.ADJUST -> AdjustSheet(model)
                        SheetKind.VOLUME -> VolumeSheet(model)
                        SheetKind.FADE -> FadeSheet(model)
                        SheetKind.EQ -> EqSheet(model)
                        SheetKind.FORMAT -> FormatSheet(model)
                        SheetKind.AI -> AiSheet(g, model)
                        SheetKind.EXPORT -> ExportSheet(g, model, onExported)
                        SheetKind.NONE -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String, sub: String = "") {
    val c = Lofi.c
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(text, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        if (sub.isNotBlank()) Text(sub, color = c.muted, fontSize = 12.sp)
    }
}

@Composable
private fun MediaSheet(g: Graph, model: EditorModel, onImage: () -> Unit, onVideo: () -> Unit, close: () -> Unit) {
    val c = Lofi.c
    val images by g.db.images().all().collectAsState(emptyList())
    var picked by remember { mutableStateOf(listOf<String>()) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SheetTitle("Pictures, videos and intros", "Everything is added at the playhead, and several at once go in one after another. Tap the ＋ between two clips to blend them with a transition.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GradientButton("Pictures…", small = true) { onImage(); close() }
            GhostButton("Videos…") { onVideo(); close() }
        }
        Text("Built-in intros", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Intros.all, key = { it.id }) { d ->
                val i = Intros.all.indexOf(d)
                val grads = listOf(listOf(Color(0xFF1A0838), Color(0xFFFF4FA0)), listOf(Color(0xFF8A3A2A), Color(0xFFF0B060)), listOf(Color(0xFF031428), Color(0xFF30E0A0)), listOf(Color(0xFF080C20), Color(0xFF5070C0)), listOf(Color(0xFF05050C), Color(0xFF6070FF)))
                Box(
                    Modifier.size(width = 138.dp, height = 74.dp).clip(RoundedCornerShape(8.dp))
                        .background(androidx.compose.ui.graphics.Brush.linearGradient(grads[i % grads.size]))
                        .pressScale({ model.addIntro(d.id); close() }).padding(8.dp),
                ) {
                    Column(Modifier.align(Alignment.BottomStart)) {
                        Text("✦ " + d.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("${d.lenS.toInt()} s · " + d.blurb, color = Color.White.copy(alpha = 0.75f), fontSize = 9.sp, maxLines = 2)
                    }
                }
            }
        }
        if (images.isNotEmpty()) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("From your watched folder", color = c.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (picked.isNotEmpty()) GradientButton("Add ${picked.size}", small = true) {
                    picked.forEach { model.addMedia(it, false, advance = picked.size > 1) }; picked = emptyList(); close()
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(images.take(40), key = { it.id }) { img ->
                    val on = img.uri in picked
                    Box(
                        Modifier.size(width = 112.dp, height = 70.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceHi)
                            .border(if (on) 2.dp else 0.dp, if (on) c.ink else Color.Transparent, RoundedCornerShape(8.dp))
                            .pressScale({ picked = if (on) picked - img.uri else picked + img.uri }),
                    ) {
                        AsyncImage(img.uri, img.name, Modifier.fillMaxWidth(), contentScale = ContentScale.Crop)
                        if (on) Box(Modifier.padding(4.dp).clip(CircleShape).background(c.ink).size(18.dp), contentAlignment = Alignment.Center) { Text("${picked.indexOf(img.uri) + 1}", color = c.bg, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        else if (img.state == "new") Box(Modifier.padding(4.dp).clip(RoundedCornerShape(4.dp)).background(c.ink).padding(horizontal = 5.dp, vertical = 1.dp)) { Text("NEW", color = c.bg, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Text("Tap several, in the order you want, then Add.", color = c.faint, fontSize = 11.sp)
        }
    }
}

/** Pick how the selected clip blends in from the clip before it. Choosing one plays the cut so you see it straight away. */
@Composable
private fun TransitionSheet(model: EditorModel) {
    val c = Lofi.c
    val sel = model.selection as? Sel.Media
    val clip = sel?.let { model.mediaById(it.id) }
    val idx = clip?.let { cl -> model.project.media.indexOfFirst { it.id == cl.id } } ?: -1
    SheetTitle("Transition", "How this clip comes in from the one before it.")
    if (clip == null || idx <= 0) {
        Text(if (model.project.media.size < 2) "Add a second picture or video — or split a clip at the playhead — then pick a transition for the cut." else "Tap a clip (other than the first) or the ＋ between two clips.", color = c.faint, fontSize = 12.sp)
        return
    }
    fun preview() { model.seek(max(0f, clip.startS - 0.6f)); model.play() }
    var sliding by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Length ${"%.1f".format(clip.transS)} s", color = c.muted, fontSize = 12.sp, modifier = Modifier.width(78.dp))
        Slider(clip.transS, { v -> if (!sliding) { sliding = true; model.beginDrag() }; model.updateMedia(clip.id, drag = true) { it.copy(transS = v) } }, Modifier.weight(1f), valueRange = 0.2f..3f, onValueChangeFinished = { sliding = false; model.endDrag(); preview() })
        GhostButton("Play it") { preview() }
        GhostButton("All cuts") { model.setAllTransitions(clip.transition) }
    }
    LazyVerticalGrid(GridCells.Adaptive(118.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val list = listOf(null) + Transitions.all
        items(list, key = { it?.id ?: "none" }) { d ->
            val id = d?.id ?: "none"
            val on = clip.transition == id
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(if (on) 2.dp else 1.dp, if (on) c.ink else c.line, RoundedCornerShape(8.dp))
                    .pressScale({ model.setTransition(clip.id, id); if (id != "none") preview() }).padding(horizontal = 8.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(d?.emoji ?: "✂️", fontSize = 16.sp)
                Text(d?.name ?: "Hard cut", color = c.ink, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun MusicSheet(g: Graph, model: EditorModel, onFiles: () -> Unit, close: () -> Unit) {
    val c = Lofi.c
    val tracks by g.db.tracks().all().collectAsState(emptyList())
    SheetTitle("Add music", "Tap a song to place it after the last clip. Drag it on the timeline to move it, drag its handles to trim.")
    GhostButton("Pick audio files…") { onFiles(); close() }
    Box(Modifier.padding(top = 6.dp))
    if (tracks.isEmpty()) Text("Your Library is empty. Add a music folder in the Library tab, or pick files above.", color = c.faint, fontSize = 12.sp)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(tracks, key = { it.id }) { t ->
            val used = model.project.audio.count { it.uri == t.uri }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).pressScale({ model.addMusic(t.uri, t.title, t.durationMs / 1000f, t.lufs) }, haptic = false).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = c.ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Mono("%d:%02d".format(t.durationMs / 60000, t.durationMs / 1000 % 60) + if (t.moods.isNotBlank()) "  ·  ${t.moods.replace(",", ", ")}" else "", c.faint, 11)
                }
                if (used > 0) Mono("×$used", c.ok, 12)
                Text("＋", color = c.ink, fontSize = 18.sp, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

@Composable
private fun EffectsSheet(title: String = "Effects", onPick: (FxDef) -> Unit) {
    val c = Lofi.c
    var q by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("All") }
    val list = EffectLibrary.search(q).filter { cat == "All" || it.category == cat }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search ${EffectLibrary.all.size} effects", fontSize = 13.sp) }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp), shape = RoundedCornerShape(8.dp))
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        items(listOf("All") + EffectLibrary.categories) { k -> Chip(k, cat == k, { cat = k }) }
    }
    LazyVerticalGrid(GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(list, key = { it.id }) { d ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, c.line, RoundedCornerShape(8.dp)).pressScale({ onPick(d) }).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(categoryIcon(d.category), null, tint = c.muted, modifier = Modifier.size(20.dp))
                Column {
                    Text(d.name, color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(d.category, color = c.faint, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun LooksSheet(g: Graph, model: EditorModel, close: () -> Unit) {
    val c = Lofi.c
    val saved by g.db.looks().all().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    val json = remember { kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true } }
    SheetTitle("Looks (effect presets)", "A look is a ready-made stack of effects covering the whole video. Save yours here, then pick it in Settings → Automation recipe so automatic videos use it.")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Save current effects as…", fontSize = 13.sp) }, shape = RoundedCornerShape(8.dp))
        GhostButton("Save", enabled = name.isNotBlank() && model.project.effects.isNotEmpty()) {
            scope.launch {
                val look = com.lofiautopilot.studio.vfx.Look(name, model.project.effects.map { it.copy(from = 0f, to = -1f) }, model.project.fit)
                val js = json.encodeToString(com.lofiautopilot.studio.vfx.Look.serializer(), look)
                val ex = g.db.looks().byName(name)
                if (ex != null) g.db.looks().update(ex.copy(json = js)) else g.db.looks().insert(com.lofiautopilot.studio.data.LookEntity(name = name, json = js))
                name = ""
            }
        }
    }
    LazyColumn(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(saved, key = { "s${it.id}" }) { l ->
            val look = remember(l.json) { runCatching { json.decodeFromString(com.lofiautopilot.studio.vfx.Look.serializer(), l.json) }.getOrNull() }
            LookRow("★ ${l.name}", "Saved look", look != null, { look?.let { model.applyLook(it.layers, it.fit, true); close() } }, { look?.let { model.applyLook(it.layers, it.fit, false); close() } })
        }
        items(Presets.builtin, key = { it.name }) { p ->
            LookRow(p.name, p.note, true, { model.applyLook(p.layers, p.fit, true); close() }, { model.applyLook(p.layers, p.fit, false); close() })
        }
    }
}

@Composable
private fun LookRow(title: String, sub: String, ok: Boolean, replace: () -> Unit, add: () -> Unit) {
    val c = Lofi.c
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(sub, color = c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GhostButton("Replace", enabled = ok) { replace() }
        Box(Modifier.padding(start = 6.dp)) { GhostButton("Add", enabled = ok) { add() } }
    }
}

@Composable
private fun AdjustSheet(model: EditorModel) {
    val c = Lofi.c
    val sel = model.selection as? Sel.Effect ?: return
    val layer = model.effectById(sel.id) ?: return
    val def = EffectLibrary.get(layer.fx) ?: return
    val dur = model.duration
    var showOn by remember { mutableStateOf(true) }
    val set: (Layer) -> Unit = { n -> model.updateEffect(sel.id) { n } }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { SheetTitle(def.name, def.blurb) }
            Switch(layer.on, { set(layer.copy(on = it)) })
        }
        if (def.kind != FxKind.CAMERA) {
            ParamSlider("Intensity", layer.intensity, 0f, 1.5f) { set(layer.copy(intensity = it)) }
            ParamSlider("Opacity", layer.opacity, 0f, 1f) { set(layer.copy(opacity = it)) }
        }
        def.params.forEach { p ->
            val v = layer.param(def, p.key)
            ParamSlider(p.label, v, p.min, p.max, if (p.step > 0f) ((p.max - p.min) / p.step).roundToInt() - 1 else 0) { nv ->
                set(layer.copy(p = layer.p + (p.key to (if (p.step > 0f) nv.roundToInt().toFloat() else nv))))
            }
        }
        if (def.kind == FxKind.OVERLAY) {
            Text("Blend", color = c.muted, fontSize = 12.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(Blend.entries) { b -> Chip(b.label, layer.blend == b, { set(layer.copy(blend = b)) }) } }
        }
        Text("Reacts to the music", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(React.entries) { r -> Chip(r.label, layer.react == r, { set(layer.copy(react = r)) }) } }
        if (layer.react != React.OFF) ParamSlider("Reaction strength", layer.reactAmt, 0f, 2f) { set(layer.copy(reactAmt = it)) }
        if (def.colors > 0) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("Colours", color = c.muted, fontSize = 12.sp)
            ColorSwatch(layer.c1) { set(layer.copy(c1 = it)) }
            if (def.colors > 1) ColorSwatch(layer.c2) { set(layer.copy(c2 = it)) }
            Text("Auto = from your picture", color = c.faint, fontSize = 11.sp)
        }
        val end = model.project.layerEnd(layer)
        Text("Timing  ·  start ${"%.1f".format(layer.from)} s  ·  end ${"%.1f".format(end)} s", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Slider(layer.from.coerceIn(0f, dur), { v -> set(layer.copy(from = v.coerceAtMost(end - 0.2f), to = end)) }, valueRange = 0f..dur)
        Slider(end.coerceIn(0f, dur), { v -> set(layer.copy(to = v.coerceAtLeast(layer.from + 0.2f))) }, valueRange = 0f..dur)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Follow the AI loud/quiet curve", color = c.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Switch(layer.follow, { set(layer.copy(follow = it)) })
        }
    }
}

@Composable
private fun ParamSlider(label: String, value: Float, min: Float, max: Float, steps: Int = 0, onChange: (Float) -> Unit) {
    val c = Lofi.c
    Column {
        Row { Text(label, color = c.ink, fontSize = 12.sp, modifier = Modifier.weight(1f)); Mono("%.2f".format(value), c.faint, 11) }
        Slider(value.coerceIn(min, max), onChange, valueRange = min..max, steps = steps.coerceAtLeast(0))
    }
}

@Composable
private fun VolumeSheet(model: EditorModel) {
    val c = Lofi.c
    val sel = model.selection as? Sel.Audio ?: return
    val clip = model.audioById(sel.id) ?: return
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SheetTitle(clip.title, "Trim by dragging the handles on the timeline. Long-press and drag to move.")
        ParamSlider("Volume", clip.gain, 0f, 2f) { v -> model.updateAudio(sel.id) { it.copy(gain = v) } }
        ParamSlider("Fade in (s)", clip.fadeInS, 0f, minOf(12f, clip.lenS / 2)) { v -> model.updateAudio(sel.id) { it.copy(fadeInS = v) } }
        ParamSlider("Fade out (s)", clip.fadeOutS, 0f, minOf(12f, clip.lenS / 2)) { v -> model.updateAudio(sel.id) { it.copy(fadeOutS = v) } }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Column(Modifier.weight(1f)) { Text("Even out loudness between songs", color = c.ink, fontSize = 12.sp); Text("Brings every clip to about −14 LUFS", color = c.faint, fontSize = 11.sp) }
            Switch(model.project.normalize, { v -> model.edit { it.copy(normalize = v) } })
        }
    }
}

@Composable
private fun FormatSheet(model: EditorModel) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SheetTitle("Format")
        FormatOptions(model)
    }
}

/** Shape, quality, frame rate, codec and picture fit. Shown in the Format tool and again in the Export sheet. */
@Composable
private fun FormatOptions(model: EditorModel) {
    val p = model.project
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(Aspect.entries) { a -> Chip(a.label, p.aspect == a.name, { model.edit { it.copy(aspect = a.name) } }) } }
    Text("Quality (a phone that can't encode it drops to the next size down)", color = Lofi.c.muted, fontSize = 12.sp)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(listOf(360, 480, 720, 1080, 1440, 2160)) { q -> Chip(if (q == 2160) "4K" else "${q}p", p.quality == q, { model.edit { it.copy(quality = q) } }) } }
    Text("Frame rate", color = Lofi.c.muted, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(24, 30, 60).forEach { f -> Chip("$f", p.fps == f, { model.edit { it.copy(fps = f) } }) } }
    Text("Codec", color = Lofi.c.muted, fontSize = 12.sp)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(Codec.entries) { k -> Chip(k.label, p.codec == k.name, { model.edit { it.copy(codec = k.name) } }) } }
    Text("Picture fit", color = Lofi.c.muted, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Fit.entries.forEach { f -> Chip(f.label, p.fit == f, { model.edit { it.copy(fit = f) } }) } }
}

@Composable
private fun AiSheet(g: Graph, model: EditorModel) {
    val c = Lofi.c
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf<List<String>>(emptyList()) }
    var prompt by remember { mutableStateOf("") }
    val planner = if (s.planner == "claude") "cloud" else s.planner
    // first time here with AI available but "Rules" selected: switch to the AI so it is actually used
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (g.settings.value.planner == "rules") {
            if (g.engine.cloud.configured) g.settings.update { it.copy(planner = "cloud") } else if (g.engine.llm.available) g.settings.update { it.copy(planner = "local") }
        }
    }
    val brainNote = when (planner) {
        "local" -> when { g.engine.llm.crashedLastTime -> "On-device AI is switched off (Settings → AI)."; g.engine.llm.available -> "On-device AI is ready."; else -> "No on-device model installed yet (Settings → AI)." }
        "cloud" -> if (g.engine.cloud.configured) "Cloud AI is ready (${g.engine.cloud.providers().first().label.substringBefore(" (")})." else "No cloud key yet (Settings → AI)."
        else -> "Uses your words and the music, no AI model."
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SheetTitle("AI style & auto-arrange", "Describe the look you want. The AI reads your words and the music, then places effect clips and colours on the timeline. Your own clips are kept.")
        Text("Who decides", color = c.muted, fontSize = 12.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(listOf("Rules" to "rules", "On-device AI" to "local", "Cloud AI" to "cloud")) { (label, v) -> Chip(label, planner == v, { g.settings.update { it.copy(planner = v) } }) }
        }
        Text(brainNote, color = if (brainNote.contains("ready") || brainNote.startsWith("Uses")) c.faint else c.warn, fontSize = 11.sp)
        OutlinedTextField(
            prompt, { prompt = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
            placeholder = { Text("e.g. rainy night, purple glow on the bass, calm and dreamy", fontSize = 13.sp) }, shape = RoundedCornerShape(8.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(listOf("Dreamy rain at night", "Neon club, pink and blue", "Retro synthwave sunset", "Cozy campfire, warm and calm", "Glitchy cyber, red", "Cinematic and moody", "Snowy winter, soft glow")) { q -> Chip(q, false, { prompt = q }) }
        }
        if (model.project.audio.isEmpty()) Text("Add music first so the AI can listen.", color = c.warn, fontSize = 12.sp)
        if (model.analysing in 0f..0.999f) { Text("Still listening to the music…", color = c.muted, fontSize = 12.sp); LinearProgressIndicator({ model.analysing }, Modifier.fillMaxWidth()) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GradientButton(if (busy) "Working…" else if (prompt.isBlank()) "Arrange from the music" else "Generate", small = true, enabled = !busy && model.project.audio.isNotEmpty() && model.features != null) {
                scope.launch {
                    busy = true; msg = ""; notes = emptyList()
                    try {
                        val ft = model.features!!
                        val r = withContext(Dispatchers.Default) { g.engine.arranger.arrange(model.project, ft, true, prompt.trim()) }
                        model.edit { p -> p.copy(effects = p.effects.filter { !it.auto } + r.layers) }
                        msg = "Placed ${r.layers.size} clips in “${r.plan.style}” style (${r.by})."
                        notes = r.notes
                        model.selection = null
                    } catch (e: Exception) { msg = "Couldn't arrange: ${e.message}" }
                    busy = false
                }
            }
            GhostButton("Remove AI clips", enabled = model.project.effects.any { it.auto }) { model.clearEffects(onlyAuto = true) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotBlank()) Text(msg, color = if (msg.startsWith("Couldn't")) c.crit else c.ok, fontSize = 12.sp)
        notes.forEach { Text("• $it", color = c.warn, fontSize = 11.sp) }
        if (model.project.media.size > 0) {
            Text("Pictures & intro", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Text(if (model.project.media.count { !it.isIntro } > 1) "Cuts your pictures to the music: they take turns where the music changes, each cut gets a transition that fits." else "Add two or more pictures (Media) and they can be cut to the music with transitions. You can also add an intro.", color = c.faint, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Cut to the music", enabled = model.features != null && model.project.media.count { !it.isIntro } > 1) { model.autoCutMedia(); msg = "Cut the picture track to the music." }
                GhostButton("＋ Random intro") { model.addIntro(Intros.all.random().id); msg = "Added an intro at the start." }
            }
        }
        Text("Tip: then tap any clip to adjust it, drag to move it, or delete it. Add more detail to the prompt and generate again to refine; only AI clips are replaced.", color = c.faint, fontSize = 11.sp)
    }
}

@Composable
private fun ExportSheet(g: Graph, model: EditorModel, onDone: () -> Unit) {
    val c = Lofi.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = model.project
    val (w, h) = Aspect.valueOf(p.aspect).size(p.quality)
    val mb = p.durationS * VideoExporter.bitrateFor(w, h, p.fps, Codec.valueOf(p.codec), true) / 8 / 1e6
    var msg by remember { mutableStateOf("") }
    val ready = p.media.isNotEmpty() && (p.audio.isNotEmpty() || p.media.any { it.video })
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SheetTitle("Export video", "Rendering happens in the background with a progress notification. You can close the app.")
        Mono("${w}×$h  ·  ${p.fps} fps  ·  %d:%02d  ·  about %.0f MB".format(p.durationS.toInt() / 60, p.durationS.toInt() % 60, mb), c.ink, 13)
        FormatOptions(model)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("AI rhythm director", color = c.ink, fontSize = 13.sp); Text("Effects swell with loud parts and calm down in quiet ones", color = c.faint, fontSize = 11.sp) }
            Switch(p.director, { v -> model.edit { it.copy(director = v) } })
        }
        if (!ready) Text("Add a picture or video and some music first.", color = c.warn, fontSize = 12.sp)
        GradientButton("Render now", Modifier.fillMaxWidth(), enabled = ready) {
            scope.launch {
                model.save()
                val id = g.engine.createJob("studio", project = model.project, title = model.project.name)
                RenderService.start(ctx)
                msg = "Rendering as video ${"%04d".format(id)}. Find it in Videos."
            }
        }
        if (msg.isNotBlank()) { Text(msg, color = c.ok, fontSize = 12.sp); GhostButton("Done") { onDone() } }
    }
}

private val swatches = listOf(0xFFFFFFFF, 0xFFFFD9A0, 0xFFFFB46B, 0xFFFF7A5C, 0xFFFF5C8A, 0xFFE58CFF, 0xFF9D8CFF, 0xFF6C8CFF, 0xFF52A8FF, 0xFF5FE0D0, 0xFF7CFF9B, 0xFFE8FF6C).map { it.toInt() }

@Composable
fun ColorSwatch(color: Int?, onPick: (Int?) -> Unit) {
    val c = Lofi.c
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(if (color != null) Color(color) else c.surfaceHi).border(1.dp, c.lineStrong, CircleShape).pressScale({ open = true }),
        contentAlignment = Alignment.Center,
    ) { if (color == null) Text("A", color = c.muted, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
    if (open) AlertDialog(
        onDismissRequest = { open = false }, containerColor = c.surface, title = { Text("Pick a colour", color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                swatches.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { sw -> Box(Modifier.size(36.dp).clip(CircleShape).background(Color(sw)).border(1.dp, c.line, CircleShape).pressScale({ onPick(sw); open = false })) }
                    }
                }
                GhostButton("Automatic (from the picture)") { onPick(null); open = false }
            }
        },
        confirmButton = { TextButton({ open = false }) { Text("Close") } },
    )
}


/** Fade in / fade out for the selected song, with quick presets and a way to apply the same fades to every song. */
@Composable
private fun FadeSheet(model: EditorModel) {
    val c = Lofi.c
    val sel = model.selection as? Sel.Audio ?: return
    val clip = model.audioById(sel.id) ?: return
    val maxF = minOf(15f, clip.lenS / 2)
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SheetTitle("Fade · ${clip.title}", "Fades are equal-power, so crossfades between overlapping songs stay smooth. You can see the ramps on the clip.")
        Text("Presets", color = c.muted, fontSize = 12.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val presets = listOf("None" to (0f to 0f), "Soft 1 s" to (1f to 1f), "Smooth 3 s" to (3f to 3f), "Long 6 s" to (6f to 6f), "Intro only" to (4f to 0f), "Outro only" to (0f to 5f))
            items(presets) { (name, v) -> Chip(name, kotlin.math.abs(clip.fadeInS - v.first) < 0.05f && kotlin.math.abs(clip.fadeOutS - v.second) < 0.05f, { model.updateAudio(sel.id) { it.copy(fadeInS = minOf(v.first, maxF), fadeOutS = minOf(v.second, maxF)) } }) }
        }
        ParamSlider("Fade in (s)", clip.fadeInS, 0f, maxF) { v -> model.updateAudio(sel.id) { it.copy(fadeInS = v) } }
        ParamSlider("Fade out (s)", clip.fadeOutS, 0f, maxF) { v -> model.updateAudio(sel.id) { it.copy(fadeOutS = v) } }
        GhostButton("Use these fades on every song") {
            model.edit { p -> p.copy(audio = p.audio.map { a -> a.copy(fadeInS = minOf(clip.fadeInS, a.lenS / 2), fadeOutS = minOf(clip.fadeOutS, a.lenS / 2)) }) }
        }
    }
}

/** Five-band equaliser for the selected song with presets. Plays live while you drag. */
@Composable
private fun EqSheet(model: EditorModel) {
    val c = Lofi.c
    val sel = model.selection as? Sel.Audio ?: return
    val clip = model.audioById(sel.id) ?: return
    val gains = if (clip.eq.size == 5) clip.eq else List(5) { 0f }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SheetTitle("Equalizer · ${clip.title}", "Shape the sound of this song. Presets are a starting point; drag the bands to taste.")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(com.lofiautopilot.studio.audio.ClipEq.PRESETS) { (name, g) ->
                Chip(name, gains.zip(g).all { (a, b) -> kotlin.math.abs(a - b) < 0.05f }, { model.updateAudio(sel.id) { it.copy(eq = if (g.all { v -> v == 0f }) emptyList() else g) } })
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            gains.forEachIndexed { i, db ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Mono("%+.0f".format(db), if (kotlin.math.abs(db) < 0.5f) c.faint else c.ink, 11)
                    VerticalBand(db, onStart = { model.beginDrag() }, onChange = { v ->
                        model.updateAudio(sel.id, drag = true) { a -> a.copy(eq = (if (a.eq.size == 5) a.eq else List(5) { 0f }).toMutableList().also { it[i] = v }) }
                    }, onFinish = { model.endDrag() })
                    Mono(com.lofiautopilot.studio.audio.ClipEq.LABELS[i], c.muted, 11)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            GhostButton("Reset") { model.updateAudio(sel.id) { it.copy(eq = emptyList()) } }
            GhostButton("Use on every song") { val e = clip.eq; model.edit { p -> p.copy(audio = p.audio.map { a -> a.copy(eq = e) }) } }
        }
    }
}

/** A vertical slider, −12 dB … +12 dB, with a centre mark. */
@Composable
private fun VerticalBand(db: Float, onStart: () -> Unit, onChange: (Float) -> Unit, onFinish: () -> Unit) {
    val c = Lofi.c
    val density = androidx.compose.ui.platform.LocalDensity.current
    val h = 110.dp
    val hPx = with(density) { h.toPx() }
    Box(
        Modifier.size(width = 56.dp, height = h).pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = { off -> onStart(); onChange((12f - (off.y / hPx) * 24f).coerceIn(-12f, 12f)) },
                onDragEnd = onFinish, onDragCancel = onFinish,
            ) { change, _ ->
                change.consume()
                onChange((12f - (change.position.y / hPx) * 24f).coerceIn(-12f, 12f))
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2
            val mid = size.height / 2
            val y = size.height * (1f - (db + 12f) / 24f)
            drawLine(c.line, androidx.compose.ui.geometry.Offset(cx, 0f), androidx.compose.ui.geometry.Offset(cx, size.height), 3.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(c.faint, androidx.compose.ui.geometry.Offset(cx - 10.dp.toPx(), mid), androidx.compose.ui.geometry.Offset(cx + 10.dp.toPx(), mid), 1.dp.toPx())
            drawLine(c.ink, androidx.compose.ui.geometry.Offset(cx, mid), androidx.compose.ui.geometry.Offset(cx, y), 3.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(c.ink, 8.dp.toPx(), androidx.compose.ui.geometry.Offset(cx, y))
            drawCircle(c.bg, 3.dp.toPx(), androidx.compose.ui.geometry.Offset(cx, y))
        }
    }
}
