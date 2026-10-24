package com.lofiautopilot.studio.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofiautopilot.studio.auto.RenderService
import com.lofiautopilot.studio.auto.Saf
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.core.SongPool
import com.lofiautopilot.studio.ui.components.Chip
import com.lofiautopilot.studio.ui.components.GhostButton
import com.lofiautopilot.studio.ui.components.GradientButton
import com.lofiautopilot.studio.ui.theme.Lofi
import com.lofiautopilot.studio.vfx.Presets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → Automation recipe: where intros, songs and thumbnails come from and how each automatic video is put together. */
@Composable
fun RecipeCard(g: Graph) {
    val c = Lofi.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsState()
    val saved by g.db.looks().all().collectAsState(emptyList())
    var preview by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }

    @Composable fun folderPicker(onPicked: (Uri) -> Unit) = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        Saf.persist(ctx, uri); onPicked(uri)
    }
    val pickIntro = folderPicker { u -> g.settings.update { it.copy(introFolderUri = u.toString()) } }
    val pickThumbs = folderPicker { u -> g.settings.update { it.copy(imageFolderUri = u.toString()) }; scope.launch(Dispatchers.IO) { runCatching { g.engine.library.scanImages() } } }
    val pickPool = folderPicker { u ->
        val name = Saf.folderName(u).substringAfterLast('/').ifBlank { "Pool ${s.songPools.size + 1}" }
        g.settings.update { it.copy(songPools = (it.songPools.filter { p -> p.folderUri != u.toString() } + SongPool(name, u.toString()))) }
        scope.launch { busy = "Looking for songs in “$name”…"; withContext(Dispatchers.IO) { runCatching { g.engine.library.scanMusic(); g.engine.library.measureLoudness() } }; busy = "" }
    }

    Card("Automation recipe", 2) {
        Text("How every automatic video is put together. Keep intros, songs and thumbnails in separate folders and tell SE where they are.", color = c.faint, fontSize = 12.sp)

        // ---- intros ----
        Text("Intro videos", color = c.ink, fontSize = 15.sp)
        FolderRow(if (s.introFolderUri.isBlank()) "No folder (built-in intro setting is used)" else Saf.folderName(Uri.parse(s.introFolderUri)), "Choose", { pickIntro.launch(null) }, s.introFolderUri.isNotBlank()) { g.settings.update { it.copy(introFolderUri = "") } }
        if (s.introFolderUri.isNotBlank()) {
            ChipRow("Intros used", listOf("1" to 1, "2" to 2, "3" to 3), s.introCount) { v -> g.settings.update { it.copy(introCount = v) } }
            ChipRow("Order", listOf("Name: intro 1, then intro 2" to "NAME", "Random" to "RANDOM"), s.introOrder) { v -> g.settings.update { it.copy(introOrder = v) } }
            Text("Name the files so they sort the way you want, for example “intro 1.mp4”, “intro 2.mp4”. They play first, one after the other, then the pictures.", color = c.faint, fontSize = 12.sp)
        }

        // ---- song pools ----
        Text("Song pools", color = c.ink, fontSize = 15.sp)
        Text("Each pool is a folder of songs. Turn pools on or off and choose how songs are drawn from them. With no pools, every song in your music folders is used.", color = c.faint, fontSize = 12.sp)
        s.songPools.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text(p.name, color = if (p.enabled) c.ink else c.faint, fontSize = 14.sp)
                    Text(Saf.folderName(Uri.parse(p.folderUri)), color = c.faint, fontSize = 11.sp)
                }
                androidx.compose.material3.Switch(p.enabled, { on -> g.settings.update { st -> st.copy(songPools = st.songPools.map { if (it.folderUri == p.folderUri) it.copy(enabled = on) else it }) } })
                GhostButton("Remove") { g.settings.update { st -> st.copy(songPools = st.songPools.filter { it.folderUri != p.folderUri }) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { GradientButton("Add pool folder", small = true) { pickPool.launch(null) } }
        if (busy.isNotBlank()) Text(busy, color = c.amber, fontSize = 12.sp)
        if (s.songPools.count { it.enabled } > 1) {
            ChipRow("How songs are drawn", listOf("Mixed at random" to "MIX", "Pool 1, pool 2, pool 3… in turn" to "SEQUENCE"), s.poolMode) { v -> g.settings.update { it.copy(poolMode = v) } }
        }
        ChipRow("Skip songs used in the last videos", listOf("Off" to 0, "1" to 1, "3" to 3, "5" to 5, "10" to 10), s.avoidRepeatVideos) { v -> g.settings.update { it.copy(avoidRepeatVideos = v) } }

        // ---- thumbnails ----
        Text("Thumbnails / artwork", color = c.ink, fontSize = 15.sp)
        FolderRow(if (s.imageFolderUri.isBlank()) "No folder chosen" else Saf.folderName(Uri.parse(s.imageFolderUri)), "Choose", { pickThumbs.launch(null) }, false) {}
        ChipRow("Which picture", listOf("Newest not used yet" to "NEWEST_UNUSED", "Always the newest" to "NEWEST"), s.pictureRule) { v -> g.settings.update { it.copy(pictureRule = v) } }
        ChipRow("“Newest” means", listOf("Latest file date" to "MODIFIED", "Highest name/number" to "NAME", "Last to appear" to "ADDED"), s.newestBy) { v -> g.settings.update { it.copy(newestBy = v) } }
        if (s.newestBy == "NAME") Text("Names are compared with numbers in mind: “thumb 10” is newer than “thumb 9”.", color = c.faint, fontSize = 12.sp)

        // ---- effect presets ----
        Text("Effect presets", color = c.ink, fontSize = 15.sp)
        ChipRow("Use", listOf("The look above" to "SINGLE", "A random preset" to "RANDOM", "Presets in turn" to "ROTATE"), s.lookMode) { v -> g.settings.update { it.copy(lookMode = v) } }
        if (s.lookMode != "SINGLE") {
            val all = listOf("Auto (AI arranges)" to "Auto") + Presets.builtin.map { it.name to it.name } + saved.map { "★ ${it.name}" to it.name }
            Text("Presets in the pool (tap to add or remove). Save your own in the editor under Looks.", color = c.muted, fontSize = 13.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(all) { (label, name) -> Chip(label, name in s.lookPool, { g.settings.update { st -> st.copy(lookPool = if (name in st.lookPool) st.lookPool - name else st.lookPool + name) } }) }
            }
        }

        // ---- text ----
        Text("Titles and descriptions", color = c.ink, fontSize = 15.sp)
        OutlinedTextField(s.titleTemplate, { v -> g.settings.update { it.copy(titleTemplate = v) } }, Modifier.fillMaxWidth(), label = { Text("Title template (optional)") }, singleLine = true)
        OutlinedTextField(s.descTemplate, { v -> g.settings.update { it.copy(descTemplate = v) } }, Modifier.fillMaxWidth(), label = { Text("Description template (optional)") }, minLines = 2)
        Text("You can use {mood} {scene} {length} {date} {n} {channel}. Left empty, the planner writes them.", color = c.faint, fontSize = 12.sp)

        // ---- preview / test ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            GradientButton("Preview next video", small = true) { scope.launch { preview = withContext(Dispatchers.IO) { runCatching { g.engine.recipePreview() }.getOrElse { "Couldn't read the folders: ${it.message}" } } } }
            GhostButton("Make one now") { scope.launch { g.engine.createJob("manual"); RenderService.start(ctx); preview = "Queued. You'll get a notification when it's ready." } }
        }
        if (preview.isNotBlank()) Text(preview, color = c.ink, fontSize = 13.sp)
        Text("Autopilot runs in the background at the scheduled time, saves each video to this phone, then uploads it when publishing is on (Accounts below).", color = c.faint, fontSize = 12.sp)
    }
}

@Composable
private fun FolderRow(label: String, action: String, onPick: () -> Unit, clearable: Boolean, onClear: () -> Unit) {
    val c = Lofi.c
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = c.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        GhostButton(action, onClick = onPick)
        if (clearable) GhostButton("Clear", onClick = onClear)
    }
}
