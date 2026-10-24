package com.lofiautopilot.studio.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lofiautopilot.studio.auto.RenderService
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.data.JobStatus
import com.lofiautopilot.studio.discover.TrendsRepo
import com.lofiautopilot.studio.insights.Insights
import com.lofiautopilot.studio.insights.InsightsRepo
import com.lofiautopilot.studio.ui.Tab
import com.lofiautopilot.studio.ui.components.AudioBarsLogo
import com.lofiautopilot.studio.ui.components.AutopilotOrb
import com.lofiautopilot.studio.ui.components.Chip
import com.lofiautopilot.studio.ui.components.GhostButton
import com.lofiautopilot.studio.ui.components.GlassCard
import com.lofiautopilot.studio.ui.components.GradientButton
import com.lofiautopilot.studio.ui.components.Mono
import com.lofiautopilot.studio.ui.components.PetLevel
import com.lofiautopilot.studio.ui.components.PetOverlay
import com.lofiautopilot.studio.ui.components.ProgressRing
import com.lofiautopilot.studio.ui.components.SectionTitle
import com.lofiautopilot.studio.ui.components.pressScale
import com.lofiautopilot.studio.ui.components.staggerIn
import com.lofiautopilot.studio.ui.theme.Lofi
import com.lofiautopilot.studio.vfx.Project
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Composable
fun HomeScreen(g: Graph, goTab: (Tab) -> Unit, openJob: (Long) -> Unit, openSettings: () -> Unit, openDashboard: () -> Unit, openProject: (Project) -> Unit) {
    val c = Lofi.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsState()
    val jobs by g.db.jobs().recent(30).collectAsState(emptyList())
    val tracks by g.db.tracks().count().collectAsState(0)
    val newImgs by g.db.images().newCount().collectAsState(0)
    val images by g.db.images().all().collectAsState(emptyList())
    val projects by g.db.projects().all().collectAsState(emptyList())
    var banner by remember { mutableStateOf("") }
    var confirmLive by remember { mutableStateOf(false) }
    var insights by remember { mutableStateOf<Insights?>(null) }
    var ideas by remember { mutableStateOf<List<String>>(emptyList()) }
    val running = jobs.firstOrNull { it.status == JobStatus.RUNNING }
    val ignoringBattery = remember { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) }
    val done = jobs.count { it.status == JobStatus.DONE }
    val lastProject = remember(projects.firstOrNull()?.updatedAt) { projects.firstOrNull()?.let { e -> runCatching { json.decodeFromString<Project>(e.json) }.getOrNull()?.let { e to it } } }

    LaunchedEffect(jobs.firstOrNull()?.id, jobs.firstOrNull()?.status, s.autopilot, s.publishTime, s.videosPerDay) { insights = InsightsRepo.compute(g) }
    LaunchedEffect(s.nicheKeywords, s.trendSort, s.trendRegion) { ideas = TrendsRepo(g).cachedIdeas(s.nicheKeywords, s.trendSort, s.trendRegion) }
    LaunchedEffectBanner(banner) { banner = "" }
    // live countdown to the next scheduled video
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(20_000); value = System.currentTimeMillis() } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(Modifier.fillMaxWidth().staggerIn(0), verticalAlignment = Alignment.CenterVertically) {
                    AudioBarsLogo(size = 40.dp, active = running != null || s.autopilot)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("Synth Edit", color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
                        Text(s.channelName, color = c.muted, fontSize = 13.sp)
                    }
                    Box(Modifier.size(42.dp).clip(CircleShape).background(c.surface).pressScale(openDashboard), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Insights, "Dashboard", tint = c.ink) }
                    Box(Modifier.padding(start = 8.dp).size(42.dp).clip(CircleShape).background(c.surface).pressScale(openSettings), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Settings, "Settings", tint = c.ink) }
                }
            }
            // ---------- autopilot hero ----------
            item {
                GlassCard(Modifier.fillMaxWidth().staggerIn(1), corner = 16.dp, glow = if (s.autopilot) c.ink else null) {
                    Column(Modifier.padding(18.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        AutopilotOrb(s.autopilot, running != null, Modifier.size(170.dp), progress = running?.progress ?: 0f) {
                            if (!s.autopilot) {
                                val missing = when {
                                    tracks == 0 -> "Add some music first. Open Library."
                                    s.imageFolderUri.isBlank() -> "Choose a picture folder first. Open Library."
                                    else -> ""
                                }
                                if (missing.isNotEmpty()) { banner = missing; goTab(Tab.LIBRARY); return@AutopilotOrb }
                            }
                            val on = !s.autopilot
                            g.settings.update { it.copy(autopilot = on) }
                            if (on) { RenderService.start(ctx); banner = "Autopilot is on. New videos are made automatically." } else banner = "Autopilot is off."
                        }
                        Text(if (s.autopilot) "Autopilot is on" else "Autopilot is off", color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
                        val next = insights?.nextRun
                        Text(
                            when {
                                running != null -> "Making “${running.title.ifBlank { "your video" }}” · ${(running.progress * 100).toInt()}%"
                                s.autopilot && next != null -> "Next video ${countdown(next - now)}" + if (!s.publishEnabled) " · practice mode" else ""
                                s.autopilot -> "${s.videosPerDay}/day around ${s.publishTime}"
                                else -> "Tap the target to start making videos by itself"
                            },
                            color = c.muted, fontSize = 13.sp,
                        )
                        AnimatedVisibility(banner.isNotBlank(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                            Text(banner, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
            // ---------- quick switches ----------
            item {
                LazyRow(Modifier.staggerIn(2), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { Chip(if (s.publishEnabled) "Publishing: live" else "Practice mode", s.publishEnabled, { if (s.publishEnabled) g.settings.update { it.copy(publishEnabled = false) } else confirmLive = true }) }
                    item { Chip("AI director", s.directorOn, { g.settings.update { it.copy(directorOn = !it.directorOn) } }) }
                    item { Chip("Save to Gallery", s.saveToGallery, { g.settings.update { it.copy(saveToGallery = !it.saveToGallery) } }) }
                    item { Chip("Title on thumbnail", s.thumbnailText, { g.settings.update { it.copy(thumbnailText = !it.thumbnailText) } }) }
                }
            }
            // ---------- numbers: tap for the dashboard ----------
            item {
                Row(Modifier.fillMaxWidth().staggerIn(3), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).pressScale(openDashboard, haptic = false)) { StatTile("Music tracks", tracks, Modifier.fillMaxWidth(), c.ink) }
                    Box(Modifier.weight(1f).pressScale({ goTab(Tab.LIBRARY) }, haptic = false)) { StatTile("New pictures", newImgs, Modifier.fillMaxWidth(), c.sky) }
                    Box(Modifier.weight(1f).pressScale(openDashboard, haptic = false)) { StatTile("Videos made", done, Modifier.fillMaxWidth(), c.ink) }
                }
            }
            // ---------- weekly goal + pet level ----------
            insights?.let { d ->
                item {
                    GlassCard(Modifier.fillMaxWidth().staggerIn(4), onClick = openDashboard) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ProgressRing(d.weekCount.toFloat() / d.weekTarget.coerceAtLeast(1), Modifier.size(64.dp), stroke = 4.dp) { Mono("${d.weekCount}/${d.weekTarget}", c.ink, 12, FontWeight.Medium) }
                            Column(Modifier.weight(1f)) {
                                Text("This week", color = c.ink, fontWeight = FontWeight.SemiBold)
                                Text(if (d.weekCount >= d.weekTarget) "Goal reached. Nice." else "${d.weekTarget - d.weekCount} more video${if (d.weekTarget - d.weekCount > 1) "s" else ""} to hit your goal", color = c.muted, fontSize = 13.sp)
                                val lv = PetLevel.of(done)
                                Text("Pet: ${PetLevel.title(lv)} · Lv $lv" + if (PetLevel.nextAt(lv) < Int.MAX_VALUE) " · ${PetLevel.nextAt(lv) - done} to evolve" else " · fully evolved", color = c.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                            Text("›", color = c.faint, fontSize = 22.sp)
                        }
                    }
                }
                if (d.freeMb in 1..3000) item {
                    GlassCard(Modifier.fillMaxWidth().staggerIn(4), glow = c.warn) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Storage is low", color = c.warn, fontWeight = FontWeight.SemiBold)
                                Text("${"%.1f".format(d.freeMb / 1024.0)} GB free. Videos need space to render.", color = c.muted, fontSize = 12.sp)
                            }
                            GhostButton("Clean up") { scope.launch { g.engine.cleanup(); insights = InsightsRepo.compute(g); banner = "Old video files removed." } }
                        }
                    }
                }
            }
            if (running != null) item {
                GlassCard(Modifier.fillMaxWidth().staggerIn(5), onClick = { openJob(running.id) }, glow = c.ink) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ProgressRing(running.progress, Modifier.size(64.dp)) { Text("${(running.progress * 100).toInt()}%", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                        Column(Modifier.weight(1f)) {
                            Text(statusLabel(running), color = c.sky, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(running.title.ifBlank { "Video ${"%04d".format(running.id)}" }, color = c.ink, fontWeight = FontWeight.Medium, maxLines = 2)
                            Text(running.phase, color = c.muted, fontSize = 12.sp)
                            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GhostButton("Pause") { scope.launch { g.engine.setStatus(running.id, "pause") } }
                                GhostButton("Cancel") { scope.launch { g.engine.setStatus(running.id, "cancel") } }
                            }
                        }
                    }
                }
            }
            item {
                GradientButton("Make a video now", Modifier.fillMaxWidth().staggerIn(5)) {
                    if (tracks == 0) { banner = "Add some music first. Open Library."; goTab(Tab.LIBRARY) }
                    else scope.launch { g.engine.createJob("manual"); RenderService.start(ctx); banner = "Queued. I'll tell you when it's ready." }
                }
            }
            // ---------- continue editing ----------
            lastProject?.let { (e, p) ->
                item {
                    GlassCard(Modifier.fillMaxWidth().staggerIn(6), onClick = { openProject(p) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(width = 96.dp, height = 60.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceHi)) {
                                p.cover?.let { u -> AsyncImage(u, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Continue editing", color = c.faint, fontSize = 11.sp)
                                Text(e.name, color = c.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Mono("%d:%02d  ·  ${p.effects.size} effects  ·  ${ago(now - e.updatedAt)}".format(e.durationS.toInt() / 60, e.durationS.toInt() % 60), c.muted, 11)
                            }
                            Text("›", color = c.faint, fontSize = 22.sp)
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().staggerIn(7), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassCard(Modifier.weight(1f), onClick = { goTab(Tab.STUDIO) }) { Column(Modifier.padding(14.dp)) { Text("Studio", color = c.ink, fontWeight = FontWeight.SemiBold); Text("Timeline editor with effects and music", color = c.muted, fontSize = 12.sp) } }
                    GlassCard(Modifier.weight(1f), onClick = { goTab(Tab.ASSIST) }) { Column(Modifier.padding(14.dp)) { Text("Ask AI", color = c.ink, fontWeight = FontWeight.SemiBold); Text("Fix or change anything by chat", color = c.muted, fontSize = 12.sp) } }
                }
            }
            // ---------- idea of the day ----------
            item {
                GlassCard(Modifier.fillMaxWidth().staggerIn(8)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Idea of the day", color = c.faint, fontSize = 11.sp)
                        if (ideas.isEmpty()) {
                            Text("See what's trending in your niche", color = c.ink, fontWeight = FontWeight.SemiBold)
                            Text("Discover finds new videos for your keywords and pulls out ideas for your next one.", color = c.muted, fontSize = 13.sp)
                            GhostButton("Open Discover") { goTab(Tab.DISCOVER) }
                        } else {
                            val idea = remember(ideas) { ideas.random() }
                            Text("“$idea”", color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            Text("A phrase that keeps showing up in the best recent videos.", color = c.muted, fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GradientButton(if (s.ideaHint == idea) "Selected for next video" else "Use for next video", small = true) { g.settings.update { it.copy(ideaHint = idea) }; banner = "Your next automatic video will be inspired by “$idea”." }
                                GhostButton("More ideas") { goTab(Tab.DISCOVER) }
                            }
                        }
                    }
                }
            }
            val checks = buildList {
                add(Triple("Music folder added", tracks > 0) { goTab(Tab.LIBRARY) })
                add(Triple("Picture folder watched", s.imageFolderUri.isNotBlank()) { goTab(Tab.LIBRARY) })
                add(Triple("Runs in the background", ignoringBattery) {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
                add(Triple("Accounts connected (optional)", s.publishEnabled) { openSettings() })
            }
            if (checks.any { !it.second }) item {
                GlassCard(Modifier.fillMaxWidth().staggerIn(9)) {
                    Column(Modifier.padding(16.dp).animateContentSize()) {
                        Text("Set-up", color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Spacer6()
                        checks.forEach { (label, ok, act) ->
                            Row(Modifier.fillMaxWidth().pressScale(act).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (ok) c.ok else c.faint)
                                Text(label, color = if (ok) c.muted else c.ink, modifier = Modifier.padding(start = 12.dp), fontWeight = if (ok) FontWeight.Normal else FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            val fresh = images.filter { it.state == "new" }.take(14)
            if (fresh.isNotEmpty()) {
                item { SectionTitle("New pictures from your folder") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(fresh, key = { it.id }) { img ->
                            Box(Modifier.size(width = 150.dp, height = 94.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceHi)) {
                                AsyncImage(img.uri, img.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                Box(Modifier.padding(8.dp).clip(RoundedCornerShape(4.dp)).background(c.ink).padding(horizontal = 6.dp, vertical = 2.dp)) { Text("NEW", color = c.bg, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
            if (jobs.isNotEmpty()) {
                item { SectionTitle("Recent videos") }
                items(jobs.take(6), key = { it.id }) { j -> JobCard(j, Modifier.staggerIn(2)) { openJob(j.id) } }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.height(60.dp)) }
        }

        // the pet floats above everything and takes no space of its own
        val petMsg = when {
            running != null -> "Making your video…"
            jobs.firstOrNull()?.let { it.status == JobStatus.DONE && System.currentTimeMillis() - it.createdAt < 3_600_000L } == true -> "A video is ready!"
            tracks == 0 -> "Add some music?"
            newImgs > 0 -> "New picture found!"
            else -> null
        }
        PetOverlay(level = PetLevel.of(done), working = running != null, message = petMsg, modifier = Modifier.fillMaxSize())
    }

    if (confirmLive) AlertDialog(
        onDismissRequest = { confirmLive = false }, containerColor = c.surface,
        title = { Text("Publish for real?", color = c.ink) },
        text = { Text("New videos will be uploaded to the YouTube and Facebook accounts you connected. You can switch back to practice mode any time.", color = c.muted) },
        confirmButton = { TextButton({ g.settings.update { it.copy(publishEnabled = true) }; confirmLive = false }) { Text("Publish for real") } },
        dismissButton = { TextButton({ confirmLive = false }) { Text("Cancel") } },
    )
}

private fun countdown(ms: Long): String {
    if (ms <= 0) return "any moment now"
    val m = ms / 60_000
    return when { m < 1 -> "in under a minute"; m < 60 -> "in $m min"; m < 24 * 60 -> "in ${m / 60} h ${m % 60} min"; else -> "in ${m / 1440} d ${(m % 1440) / 60} h" }
}

private fun ago(ms: Long): String {
    val m = ms / 60_000
    return when { m < 1 -> "just now"; m < 60 -> "${m}m ago"; m < 1440 -> "${m / 60}h ago"; else -> "${m / 1440}d ago" }
}

@Composable
private fun Spacer6() = androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))

@Composable
private fun LaunchedEffectBanner(banner: String, clear: () -> Unit) {
    LaunchedEffect(banner) { if (banner.isNotBlank()) { delay(4500); clear() } }
}

