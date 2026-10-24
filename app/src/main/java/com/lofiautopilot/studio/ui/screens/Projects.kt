package com.lofiautopilot.studio.ui.screens

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.data.ProjectEntity
import com.lofiautopilot.studio.ui.components.GhostButton
import com.lofiautopilot.studio.ui.components.GlassCard
import com.lofiautopilot.studio.ui.components.GradientButton
import com.lofiautopilot.studio.ui.components.Mono
import com.lofiautopilot.studio.ui.components.staggerIn
import com.lofiautopilot.studio.ui.theme.Lofi
import com.lofiautopilot.studio.vfx.Project
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** The Studio tab: your projects. Opening one switches to the landscape timeline editor. */
@Composable
fun ProjectsScreen(g: Graph, open: (Project) -> Unit) {
    val c = Lofi.c
    val scope = rememberCoroutineScope()
    val list by g.db.projects().all().collectAsState(emptyList())
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp)) {
        Row(Modifier.padding(top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Studio", color = c.ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.9).sp)
                Text("Edit with a timeline: music, effects, pictures and video.", color = c.muted, fontSize = 13.sp)
            }
            GradientButton("New project", small = true) {
                val newest = kotlinx.coroutines.runBlocking { g.db.images().candidates().firstOrNull() }
                open(Project(name = "Project ${list.size + 1}", media = listOfNotNull(newest?.let { com.lofiautopilot.studio.vfx.MediaClip(uri = it.uri, lenS = 8f) })))
            }
        }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No projects yet", color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text("Create one, add songs, place effects on the timeline, or let AI arrange them for you.", color = c.muted, fontSize = 14.sp)
                }
            }
        } else LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.id }) { e -> ProjectCard(e, Modifier.staggerIn(list.indexOf(e)), onOpen = {
                runCatching { json.decodeFromString<Project>(e.json) }.getOrNull()?.let(open)
            }, onDelete = { scope.launch { g.db.projects().delete(e.id) } })
            }
        }
    }
}

@Composable
private fun ProjectCard(e: ProjectEntity, modifier: Modifier, onOpen: () -> Unit, onDelete: () -> Unit) {
    val c = Lofi.c
    val p = remember(e.json) { runCatching { json.decodeFromString<Project>(e.json) }.getOrNull() }
    GlassCard(modifier.fillMaxWidth(), onClick = onOpen) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(c.surfaceHi)) {
                p?.cover?.let { u -> AsyncImage(u, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Column(Modifier.padding(10.dp)) {
                Text(e.name, color = c.ink, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Mono("%d:%02d".format(e.durationS.toInt() / 60, e.durationS.toInt() % 60) + "  ·  ${p?.effects?.size ?: 0} fx", c.faint, 11, modifier = Modifier.weight(1f))
                    Box(Modifier.clip(RoundedCornerShape(6.dp))) { GhostButton("Delete") { onDelete() } }
                }
            }
        }
    }
}

