package com.lofiautopilot.studio.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.insights.Insights
import com.lofiautopilot.studio.insights.InsightsRepo
import com.lofiautopilot.studio.insights.YtStat
import com.lofiautopilot.studio.ui.components.GhostButton
import com.lofiautopilot.studio.ui.components.GlassCard
import com.lofiautopilot.studio.ui.components.GradientButton
import com.lofiautopilot.studio.ui.components.Mono
import com.lofiautopilot.studio.ui.components.Skeleton
import com.lofiautopilot.studio.ui.components.pressScale
import com.lofiautopilot.studio.ui.components.staggerIn
import com.lofiautopilot.studio.ui.theme.Lofi
import kotlinx.coroutines.launch
import java.io.File

/** Numbers and plain-language insights about the channel, the library and the phone. */
@Composable
fun DashboardScreen(g: Graph, onClose: () -> Unit, goTab: (String) -> Unit) {
    val c = Lofi.c
    val scope = rememberCoroutineScope()
    val jobs by g.db.jobs().recent(1).collectAsState(emptyList())
    var data by remember { mutableStateOf<Insights?>(null) }
    var yt by remember { mutableStateOf<List<YtStat>?>(null) }
    var cleaned by remember { mutableStateOf("") }
    LaunchedEffect(jobs.firstOrNull()?.id, jobs.firstOrNull()?.status) {
        val d = InsightsRepo.compute(g); data = d
        yt = InsightsRepo.youtubeStats(g, d.ytIds, false)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().background(c.bg).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(c.surface).pressScale(onClose), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = c.ink) }
            Text("Dashboard", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.7).sp, modifier = Modifier.padding(start = 12.dp))
        }
        val d = data
        if (d == null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Skeleton(Modifier.fillMaxWidth().height(90.dp), 12.dp); Skeleton(Modifier.fillMaxWidth().height(160.dp), 12.dp); Skeleton(Modifier.fillMaxWidth().height(120.dp), 12.dp) }
            return@Column
        }
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // KPIs
            item {
                Column(Modifier.staggerIn(0), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Kpi("Videos made", d.totalVideos.toString(), Modifier.weight(1f))
                        Kpi("Hours of video", "%.1f".format(d.totalHours), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Kpi("Success rate", "${d.successRate}%", Modifier.weight(1f), if (d.successRate < 80) c.warn else c.ok)
                        Kpi("Day streak", d.streak.toString(), Modifier.weight(1f))
                    }
                }
            }
            // activity chart + weekly goal
            item {
                GlassCard(Modifier.fillMaxWidth().staggerIn(1)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Last 14 days", color = c.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Mono("${d.weekCount} / ${d.weekTarget} this week", c.muted, 12)
                        }
                        BarChart(d.daily.map { it.second.toFloat() }, d.daily.map { it.first }, Modifier.fillMaxWidth().height(110.dp).padding(top = 10.dp))
                        Box(Modifier.padding(top = 12.dp)) { Meter(d.weekCount.toFloat() / d.weekTarget.coerceAtLeast(1), if (d.weekCount >= d.weekTarget) c.ok else c.ink) }
                        Text(if (d.weekCount >= d.weekTarget) "Weekly goal reached" else "${d.weekTarget - d.weekCount} more to reach this week's goal", color = c.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            // insights
            item {
                GlassCard(Modifier.fillMaxWidth().staggerIn(2)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Insights", color = c.ink, fontWeight = FontWeight.SemiBold)
                        d.tips.forEach { t ->
                            Row(Modifier.fillMaxWidth().then(if (t.action.isNotBlank()) Modifier.pressScale({ if (t.action == "cleanup") { scope.launch { g.engine.cleanup(); cleaned = "Old video files removed."; data = InsightsRepo.compute(g) } } else goTab(t.action) }, haptic = false) else Modifier), verticalAlignment = Alignment.Top) {
                                Box(Modifier.padding(top = 6.dp).size(8.dp).background(when (t.level) { 2 -> c.crit; 1 -> c.warn; else -> c.ok }, CircleShape))
                                Text(t.text, color = c.ink, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 10.dp).weight(1f))
                                if (t.action.isNotBlank()) Text("›", color = c.faint, fontSize = 18.sp)
                            }
                        }
                        if (cleaned.isNotBlank()) Text(cleaned, color = c.ok, fontSize = 12.sp)
                        GhostButton("Ask the assistant to analyse") { goTab("claude") }
                    }
                }
            }
            // rendering and storage
            item {
                GlassCard(Modifier.fillMaxWidth().staggerIn(3)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Phone", color = c.ink, fontWeight = FontWeight.SemiBold)
                        Row { Text("Average time to make a video", color = c.muted, fontSize = 13.sp, modifier = Modifier.weight(1f)); Mono(if (d.avgRenderMin > 0) "${d.avgRenderMin.toInt()} min" else "—", c.ink, 13) }
                        Row { Text("Space used by videos", color = c.muted, fontSize = 13.sp, modifier = Modifier.weight(1f)); Mono(fmtMb(d.storageUsedMb), c.ink, 13) }
                        Row { Text("Free space", color = c.muted, fontSize = 13.sp, modifier = Modifier.weight(1f)); Mono(fmtMb(d.freeMb), if (d.freeMb < 3000) c.crit else c.ink, 13) }
                        Meter(d.storageUsedMb.toFloat() / (d.storageUsedMb + d.freeMb).coerceAtLeast(1), c.ink)
                    }
                }
            }
            // library
            item {
                GlassCard(Modifier.fillMaxWidth().staggerIn(4)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row { Text("Library", color = c.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Mono("${d.tracks} tracks · %.1f h".format(d.musicHours), c.muted, 12) }
                        if (d.moods.isNotEmpty()) {
                            Text("Moods", color = c.faint, fontSize = 12.sp)
                            val mx = d.moods.maxOf { it.second }.toFloat()
                            d.moods.forEach { (m, n) -> HBar(m, n, n / mx) }
                        }
                        if (d.topTracks.isNotEmpty()) {
                            Text("Most used songs", color = c.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                            val mx = d.topTracks.maxOf { it.second }.toFloat()
                            d.topTracks.forEach { (t, n) -> HBar(t, n, n / mx) }
                        }
                    }
                }
            }
            if (d.topEffects.isNotEmpty()) item {
                GlassCard(Modifier.fillMaxWidth().staggerIn(5)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row { Text("Favourite effects", color = c.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Mono("${d.projects} projects", c.muted, 12) }
                        val mx = d.topEffects.maxOf { it.second }.toFloat()
                        d.topEffects.forEach { (e, n) -> HBar(e, n, n / mx) }
                    }
                }
            }
            // publishing
            item {
                GlassCard(Modifier.fillMaxWidth().staggerIn(6)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row { Text("Published", color = c.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Mono("${d.published} videos", c.muted, 12) }
                        val list = yt
                        when {
                            d.ytIds.isEmpty() -> Text("Nothing on YouTube yet. Connect YouTube and turn on “Publish for real” in Settings.", color = c.faint, fontSize = 12.sp)
                            list == null -> Text("Add a YouTube Data API key (Settings → Accounts) to see views and likes here.", color = c.faint, fontSize = 12.sp)
                            else -> {
                                Row { Text("Total views", color = c.muted, fontSize = 13.sp, modifier = Modifier.weight(1f)); Mono(list.sumOf { it.views }.toString(), c.ink, 13) }
                                Row { Text("Total likes", color = c.muted, fontSize = 13.sp, modifier = Modifier.weight(1f)); Mono(list.sumOf { it.likes }.toString(), c.ink, 13) }
                                val mx = list.maxOfOrNull { it.views }?.toFloat()?.coerceAtLeast(1f) ?: 1f
                                list.sortedByDescending { it.views }.take(5).forEach { v -> HBar(v.title.ifBlank { v.id }, v.views.toInt(), v.views / mx) }
                                GhostButton("Refresh numbers") { scope.launch { yt = InsightsRepo.youtubeStats(g, d.ytIds, true) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fmtMb(mb: Long) = if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "$mb MB"

@Composable
private fun Kpi(label: String, value: String, modifier: Modifier, tint: Color = Lofi.c.ink) {
    val c = Lofi.c
    GlassCard(modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(value, color = tint, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = com.lofiautopilot.studio.ui.theme.GeistMono)
            Text(label, color = c.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Meter(fraction: Float, color: Color) {
    val c = Lofi.c
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(700, easing = FastOutSlowInEasing), label = "meter")
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.line)) {
        Box(Modifier.fillMaxWidth(f).fillMaxSize().clip(RoundedCornerShape(3.dp)).background(color))
    }
}

@Composable
private fun HBar(label: String, count: Int, fraction: Float) {
    val c = Lofi.c
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(700, easing = FastOutSlowInEasing), label = "hbar")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.42f))
        Box(Modifier.weight(0.46f).padding(horizontal = 8.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.line)) {
            Box(Modifier.fillMaxWidth(f).fillMaxSize().clip(RoundedCornerShape(4.dp)).background(c.ink))
        }
        Mono(count.toString(), c.muted, 11, modifier = Modifier.weight(0.12f))
    }
}

/** Bars grow in; today (the last bar) is highlighted. */
@Composable
private fun BarChart(values: List<Float>, labels: List<String>, modifier: Modifier) {
    val c = Lofi.c
    val grow by animateFloatAsState(1f, tween(800, easing = FastOutSlowInEasing), label = "grow")
    val mx = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val style = androidx.compose.ui.text.TextStyle(fontFamily = com.lofiautopilot.studio.ui.theme.GeistMono, fontSize = 9.sp, color = c.faint)
    Canvas(modifier) {
        val n = values.size
        val labelH = 14.dp.toPx()
        val slot = size.width / n
        val bw = slot * 0.56f
        val chartH = size.height - labelH
        drawLine(c.line, Offset(0f, chartH), Offset(size.width, chartH), 1.dp.toPx())
        values.forEachIndexed { i, v ->
            val h = (v / mx * (chartH - 4.dp.toPx()) * grow).coerceAtLeast(if (v > 0) 3.dp.toPx() else 1.5.dp.toPx())
            val x = i * slot + (slot - bw) / 2
            drawRoundRect(if (i == n - 1) c.ink else c.lineStrong, Offset(x, chartH - h), Size(bw, h), CornerRadius(3.dp.toPx()))
            labels.getOrNull(i)?.let { l -> drawText(measurer, l, Offset(x + bw / 2 - 3.dp.toPx(), chartH + 2.dp.toPx()), style) }
        }
    }
}
