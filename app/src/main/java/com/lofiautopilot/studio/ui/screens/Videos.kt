package com.lofiautopilot.studio.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.lofiautopilot.studio.auto.RenderService
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.data.JobEntity
import com.lofiautopilot.studio.data.JobStatus
import com.lofiautopilot.studio.data.LogEntity
import com.lofiautopilot.studio.ui.components.Chip
import com.lofiautopilot.studio.ui.components.GhostButton
import com.lofiautopilot.studio.ui.components.GlassCard
import com.lofiautopilot.studio.ui.components.GradientButton
import com.lofiautopilot.studio.ui.components.ProgressRing
import com.lofiautopilot.studio.ui.components.SectionTitle
import com.lofiautopilot.studio.ui.components.pressScale
import com.lofiautopilot.studio.ui.components.staggerIn
import com.lofiautopilot.studio.ui.theme.Lofi
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun VideosScreen(g: Graph, openJob: (Long) -> Unit) {
    val c = Lofi.c
    val jobs by g.db.jobs().recent(200).collectAsState(emptyList())
    var filter by remember { mutableStateOf("All") }
    val shown = when (filter) {
        "Active" -> jobs.filter { it.status in JobStatus.active }
        "Ready" -> jobs.filter { it.status == JobStatus.DONE }
        "Problems" -> jobs.filter { it.status == JobStatus.FAILED }
        else -> jobs
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp)) {
        Text("Videos", color = c.ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            items(listOf("All", "Active", "Ready", "Problems")) { f -> Chip(f, filter == f, { filter = f }) }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (jobs.isEmpty()) "No videos yet" else "Nothing here", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Tap “Make a video now” on Home, or turn Autopilot on.", color = c.muted, fontSize = 14.sp)
                }
            }
        } else LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.id }) { j -> JobCard(j, Modifier.staggerIn(shown.indexOf(j))) { openJob(j.id) } }
        }
    }
}

@Composable
fun JobDetail(g: Graph, id: Long, onClose: () -> Unit) {
    val c = Lofi.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val job by g.db.jobs().watch(id).collectAsState(null)
    var logs by remember { mutableStateOf<List<LogEntity>>(emptyList()) }
    var title by remember(id) { mutableStateOf("") }
    var desc by remember(id) { mutableStateOf("") }
    var tags by remember(id) { mutableStateOf("") }
    var loaded by remember(id) { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(false) }

    LaunchedEffect(job?.id) { job?.let { if (!loaded) { title = it.title; desc = it.description; tags = it.tags; loaded = true } } }
    LaunchedEffect(showLogs, job?.phase) { if (showLogs) logs = g.db.logs().forJob(id, 60) }
    val j = job
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(c.surface).pressScale(onClose), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = c.ink) }
            Text("Video ${"%04d".format(id)}", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.padding(start = 12.dp))
        }
        if (j == null) { Text("This video no longer exists.", color = c.muted); return@Column }
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).staggerIn(0)) {
            Thumb(j, Modifier.fillMaxSize())
            if (j.status == JobStatus.RUNNING) Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = .55f)), contentAlignment = Alignment.Center) {
                ProgressRing(j.progress, Modifier.size(110.dp)) { Text("${(j.progress * 100).toInt()}%", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 22.sp) }
            }
        }
        GlassCard(Modifier.fillMaxWidth().staggerIn(1)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(statusLabel(j), color = statusColor(j), fontWeight = FontWeight.Bold)
                if (j.phase.isNotBlank()) Text(j.phase, color = c.muted, fontSize = 14.sp)
                if (j.durationS > 0) Text("Length ${fmtDuration(j.durationS)}", color = c.faint, fontSize = 13.sp)
                if (j.publishAt > 0 && j.status != JobStatus.DONE) Text("Publish at ${fmtWhen(j.publishAt)}", color = c.faint, fontSize = 13.sp)
                if (j.status == JobStatus.FAILED || j.error.isNotBlank()) {
                    Text(j.error, color = c.crit, fontWeight = FontWeight.SemiBold)
                    if (j.hint.isNotBlank()) Text(j.hint, color = c.muted, fontSize = 14.sp)
                }
            }
        }
        val actions = g.engine.allowedActions(j.status)
        if (actions.isNotEmpty()) Row(Modifier.fillMaxWidth().staggerIn(2), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { a ->
                val label = mapOf("pause" to "Pause", "resume" to "Resume", "retry" to "Try again", "publish_now" to "Publish now", "cancel" to "Cancel")[a] ?: a
                if (a == "retry" || a == "resume") GradientButton(label, small = true) { scope.launch { msg = g.engine.setStatus(id, a); RenderService.start(ctx) } }
                else GhostButton(label) { scope.launch { msg = g.engine.setStatus(id, a); if (a == "publish_now") RenderService.start(ctx) } }
            }
        }
        if (msg.isNotBlank()) Text(msg, color = c.amber, fontSize = 13.sp)

        if (j.status == JobStatus.DONE && j.outPath.isNotBlank() && File(j.outPath).exists()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GradientButton("Play", small = true) { view(ctx, j) }
                GhostButton("Share") { share(ctx, j) }
                if (j.ytUrl.isNotBlank()) GhostButton("YouTube") { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(j.ytUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                if (j.fbUrl.isNotBlank()) GhostButton("Facebook") { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(j.fbUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            if (j.savedToGallery.isNotBlank()) Text("Also saved in your Gallery → Movies/LofiStudio.", color = c.faint, fontSize = 12.sp)
        }

        if (j.planJson.isNotBlank() && !(j.ytUrl.isNotBlank() && j.fbUrl.isNotBlank())) {
            SectionTitle("Title & description")
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") })
            OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth(), label = { Text("Description") }, minLines = 3)
            OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("Tags, separated by commas") })
            GradientButton("Save changes", small = true) { scope.launch { msg = g.engine.updateMetadata(id, title, desc, tags.split(",")) } }
        }
        GhostButton(if (showLogs) "Hide activity log" else "Show activity log") { showLogs = !showLogs }
        if (showLogs) GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (logs.isEmpty()) Text("Nothing logged yet.", color = c.faint, fontSize = 12.sp)
                logs.forEach { l -> Text("${if (l.level == "error") "⚠ " else ""}${l.text}", color = if (l.level == "error") c.crit else c.muted, fontSize = 12.sp) }
            }
        }
        if (j.status != JobStatus.RUNNING) GhostButton("Delete this video") { scope.launch { g.engine.setStatus(id, "delete"); onClose() } }
    }
}

private fun uriFor(ctx: android.content.Context, j: JobEntity): Uri =
    if (j.savedToGallery.isNotBlank()) Uri.parse(j.savedToGallery) else FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(j.outPath))

private fun view(ctx: android.content.Context, j: JobEntity) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(ctx, j), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(i) }
}

private fun share(ctx: android.content.Context, j: JobEntity) {
    val i = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uriFor(ctx, j)).putExtra(Intent.EXTRA_TEXT, j.title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Share video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

