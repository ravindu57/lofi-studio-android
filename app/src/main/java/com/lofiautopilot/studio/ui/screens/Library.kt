package com.lofiautopilot.studio.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lofiautopilot.studio.auto.Saf
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.data.ImageEntity
import com.lofiautopilot.studio.ui.components.Chip
import com.lofiautopilot.studio.ui.components.GhostButton
import com.lofiautopilot.studio.ui.components.GlassCard
import com.lofiautopilot.studio.ui.components.GradientButton
import com.lofiautopilot.studio.ui.components.SectionTitle
import com.lofiautopilot.studio.ui.components.pressScale
import com.lofiautopilot.studio.ui.components.staggerIn
import com.lofiautopilot.studio.ui.theme.Lofi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(g: Graph) {
    val c = Lofi.c
    var page by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp)) {
        Text("Library", color = c.ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            Chip("Music", page == 0, { page = 0 }); Chip("Pictures", page == 1, { page = 1 })
        }
        if (page == 0) MusicPage(g) else PicturesPage(g)
    }
}

@Composable
private fun MusicPage(g: Graph) {
    val c = Lofi.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsState()
    val tracks by g.db.tracks().all().collectAsState(emptyList())
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        Saf.persist(ctx, uri)
        g.settings.update { it.copy(musicFolderUris = (it.musicFolderUris + uri.toString()).distinct()) }
        scope.launch {
            busy = true; status = "Looking for music…"
            val n = withContext(Dispatchers.IO) { g.engine.library.scanMusic() }
            status = "Found $n track(s). Measuring loudness so every song sounds even…"
            withContext(Dispatchers.IO) { g.engine.library.measureLoudness { i, t -> status = "Measuring loudness ${i + 1}/$t" } }
            status = "Done."; busy = false
        }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            GlassCard(Modifier.fillMaxWidth().staggerIn(0)) {
                Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Music folders", color = c.ink, fontWeight = FontWeight.Bold)
                    Text("Pick the folders that hold your songs. Subfolders are included.", color = c.muted, fontSize = 13.sp)
                    s.musicFolderUris.forEach { u ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(Saf.folderName(Uri.parse(u)), color = c.ink, modifier = Modifier.weight(1f), fontSize = 14.sp)
                            GhostButton("Remove") { g.settings.update { it.copy(musicFolderUris = it.musicFolderUris - u) } }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GradientButton("Add folder", small = true) { pick.launch(null) }
                        if (s.musicFolderUris.isNotEmpty()) GhostButton("Rescan", enabled = !busy) {
                            scope.launch {
                                busy = true; status = "Scanning…"
                                val n = withContext(Dispatchers.IO) { g.engine.library.scanMusic() }
                                withContext(Dispatchers.IO) { g.engine.library.measureLoudness { i, t -> status = "Measuring loudness ${i + 1}/$t" } }
                                status = "Found $n new track(s)."; busy = false
                            }
                        }
                    }
                    if (status.isNotBlank()) Text(status, color = c.amber, fontSize = 13.sp)
                }
            }
        }
        item { SectionTitle("${tracks.size} tracks") }
        items(tracks, key = { it.id }) { t ->
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = if (t.enabled) c.ink else c.faint, fontWeight = FontWeight.SemiBold, maxLines = 1, fontSize = 14.sp)
                        Text(
                            "${fmtDuration(t.durationMs / 1000.0)}" + (if (t.moods.isNotBlank()) "  ·  ${t.moods.replace(",", ", ")}" else "") +
                                (t.lufs?.let { "  ·  %.0f LUFS".format(it) } ?: ""),
                            color = c.faint, fontSize = 12.sp, maxLines = 1,
                        )
                    }
                    Switch(t.enabled, { on -> scope.launch { g.db.tracks().update(t.copy(enabled = on)) } })
                }
            }
        }
    }
}

@Composable
private fun PicturesPage(g: Graph) {
    val c = Lofi.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsState()
    val images by g.db.images().all().collectAsState(emptyList())
    var status by remember { mutableStateOf("") }
    var prompt by remember(s.defaultPrompt) { mutableStateOf(s.defaultPrompt) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        Saf.persist(ctx, uri)
        g.settings.update { it.copy(imageFolderUri = uri.toString()) }
        scope.launch { withContext(Dispatchers.IO) { g.engine.library.scanImages() }; status = "Watching this folder." }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            GlassCard(Modifier.fillMaxWidth().staggerIn(0)) {
                Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Watched picture folder", color = c.ink, fontWeight = FontWeight.Bold)
                    Text("Save new pictures into this folder. SE notices them (even in the background while Autopilot is on) and uses the newest one next.", color = c.muted, fontSize = 13.sp)
                    if (s.imageFolderUri.isNotBlank()) Text(Saf.folderName(Uri.parse(s.imageFolderUri)), color = c.ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GradientButton(if (s.imageFolderUri.isBlank()) "Choose folder" else "Change folder", small = true) { pick.launch(null) }
                        if (s.imageFolderUri.isNotBlank()) GhostButton("Check now") {
                            scope.launch {
                                val fresh = withContext(Dispatchers.IO) { g.engine.library.scanImages() }
                                status = if (fresh.isEmpty()) "No new pictures." else "Found ${fresh.size} new picture(s)."
                            }
                        }
                    }
                    if (status.isNotBlank()) Text(status, color = c.amber, fontSize = 13.sp)
                }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth().staggerIn(1)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("How new pictures are used", color = c.ink, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("As the artwork", s.imageMode == "DIRECT", { g.settings.update { it.copy(imageMode = "DIRECT") } })
                        Chip("As references", s.imageMode == "REFERENCE", { g.settings.update { it.copy(imageMode = "REFERENCE") } })
                    }
                    Text(
                        if (s.imageMode == "DIRECT") "The newest picture becomes the video's artwork as it is."
                        else "New pictures guide the AI image model together with your default prompt to create fresh artwork. Needs an OpenAI key (Settings → AI). Without one the picture is used directly.",
                        color = c.muted, fontSize = 13.sp,
                    )
                    if (s.imageMode == "REFERENCE") {
                        OutlinedTextField(prompt, { prompt = it; g.settings.update { st -> st.copy(defaultPrompt = it) } }, Modifier.fillMaxWidth(), label = { Text("Default prompt") }, minLines = 3)
                        Text("References per image: ${s.refsPerPrompt}", color = c.muted, fontSize = 13.sp)
                        Slider(s.refsPerPrompt.toFloat(), { v -> g.settings.update { it.copy(refsPerPrompt = v.toInt()) } }, valueRange = 0f..8f, steps = 7)
                    }
                }
            }
        }
        item { SectionTitle("${images.size} pictures · ${images.count { it.state == "new" }} new", trailing = { GhostButton("Mark all new") { scope.launch { g.db.images().resetAll() } } }) }
        val rows = images.chunked(3)
        items(rows.size) { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rows[r].forEach { img -> PictureTile(g, img, Modifier.weight(1f)) }
                repeat(3 - rows[r].size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PictureTile(g: Graph, img: ImageEntity, modifier: Modifier) {
    val c = Lofi.c
    val scope = rememberCoroutineScope()
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(c.surfaceHi).pressScale({
        scope.launch {
            val next = when (img.state) { "new" -> "ignored"; "ignored" -> "used"; else -> "new" }
            g.db.images().update(img.copy(state = next))
        }
    })) {
        AsyncImage(img.uri, img.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        val (label, col) = when (img.state) { "new" -> "NEW" to c.rose; "ignored" -> "SKIP" to c.faint; else -> "USED ×${img.usedCount}" to c.violet }
        Box(Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(50)).background(col).padding(horizontal = 7.dp, vertical = 2.dp)) {
            Text(label, color = c.bg, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}
