package com.lofiautopilot.studio.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.core.SecretStore
import com.lofiautopilot.studio.discover.TrendVideo
import com.lofiautopilot.studio.discover.TrendsException
import com.lofiautopilot.studio.discover.TrendsRepo
import com.lofiautopilot.studio.ui.components.Chip
import com.lofiautopilot.studio.ui.components.GhostButton
import com.lofiautopilot.studio.ui.components.GlassCard
import com.lofiautopilot.studio.ui.components.GradientButton
import com.lofiautopilot.studio.ui.components.Mono
import com.lofiautopilot.studio.ui.components.Skeleton
import com.lofiautopilot.studio.ui.components.pressScale
import com.lofiautopilot.studio.ui.components.staggerIn
import com.lofiautopilot.studio.ui.theme.Lofi
import kotlinx.coroutines.launch

internal fun fmtCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 10_000 -> "${n / 1000}K"
    n >= 1_000 -> "%.1fK".format(n / 1e3)
    else -> n.toString()
}

internal fun fmtAge(ms: Long): String {
    val d = (System.currentTimeMillis() - ms) / 86_400_000.0
    return when {
        d < 1 / 24.0 -> "just now"
        d < 1 -> "${(d * 24).toInt().coerceAtLeast(1)}h ago"
        d < 14 -> "${d.toInt()}d ago"
        else -> "${(d / 7).toInt()}w ago"
    }
}

internal fun fmtDur(s: Int): String = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

/** A YouTube-style feed of fresh videos in the niche the user defines with keywords, plus ideas pulled from what is doing well. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(g: Graph, openSettings: () -> Unit, play: (TrendVideo, List<TrendVideo>) -> Unit) {
    val c = Lofi.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsState()
    val repo = remember { TrendsRepo(g) }
    var items by remember { mutableStateOf<List<TrendVideo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<TrendsException?>(null) }
    var note by remember { mutableStateOf("") }
    var newWord by remember { mutableStateOf("") }
    val hasKey = g.secrets.has(SecretStore.YOUTUBE_API)

    suspend fun load(force: Boolean) {
        if (!hasKey || s.nicheKeywords.isEmpty()) return
        loading = true; error = null
        try { items = repo.load(s.nicheKeywords, s.trendSort, s.trendRegion, force) }
        catch (e: TrendsException) { error = e }
        catch (e: Exception) { error = TrendsException("Something went wrong loading videos.", e.message.orEmpty()) }
        loading = false
    }
    LaunchedEffect(s.nicheKeywords, s.trendSort, s.trendRegion, hasKey) { load(false) }
    val ideas = remember(items, s.nicheKeywords) { TrendsRepo.ideas(items, s.nicheKeywords) }

    val bg = c.bg
    PullToRefreshBox(isRefreshing = loading && items.isNotEmpty(), onRefresh = { scope.launch { load(true) } }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            // header scrolls away so the feed gets the whole screen
            item {
                Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp)) {
                    Text("Discover", color = c.ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.9).sp, modifier = Modifier.padding(top = 12.dp))
                    Text("Fresh videos in your niche. Tune it with keywords.", color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(s.nicheKeywords) { k ->
                            Row(Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, c.lineStrong, RoundedCornerShape(8.dp)).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(k, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Box(Modifier.size(32.dp).pressScale({ g.settings.update { it.copy(nicheKeywords = it.nicheKeywords - k) } }, haptic = false), contentAlignment = Alignment.Center) { Text("✕", color = c.faint, fontSize = 11.sp) }
                            }
                        }
                    }
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(newWord, { newWord = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Add a keyword, e.g. rain sounds", fontSize = 13.sp) }, shape = RoundedCornerShape(8.dp))
                        GhostButton("Add", enabled = newWord.isNotBlank() && s.nicheKeywords.size < 5) {
                            g.settings.update { it.copy(nicheKeywords = (it.nicheKeywords + newWord.trim().lowercase()).distinct().take(5)) }; newWord = ""
                        }
                    }
                }
            }
            // sticky sort bar
            stickyHeader {
                Row(Modifier.fillMaxWidth().background(bg).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("TRENDING" to "Trending", "LATEST" to "Latest", "RISING" to "Rising fast").forEach { (k, label) -> Chip(label, s.trendSort == k, { g.settings.update { it.copy(trendSort = k) } }) }
                }
            }
            if (!hasKey) item {
                GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Connect Discover", color = c.ink, fontWeight = FontWeight.SemiBold)
                        Text("Add a free YouTube Data API key to see new and trending videos for your keywords. Only public titles and numbers are read.", color = c.muted, fontSize = 13.sp)
                        GradientButton("Add API key", small = true) { openSettings() }
                    }
                }
            }
            if (hasKey && ideas.isNotEmpty()) item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Trending ideas", color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Words that keep showing up in the best videos. Tap one to inspire your next automatic video.", color = c.faint, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(ideas) { w -> Chip(w, s.ideaHint == w, { g.settings.update { it.copy(ideaHint = w) }; note = "Your next automatic video will be inspired by “$w”." }) }
                    }
                    if (note.isNotBlank()) Text(note, color = c.ok, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            error?.let { e -> item {
                GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(e.message.orEmpty(), color = c.crit, fontWeight = FontWeight.Medium)
                        if (e.hint.isNotBlank()) Text(e.hint, color = c.muted, fontSize = 13.sp)
                        GhostButton("Try again") { scope.launch { load(true) } }
                    }
                }
            } }
            if (hasKey && loading && items.isEmpty()) items(3) { SkeletonCard() }
            if (hasKey && !loading && items.isEmpty() && error == null) item { Text("No videos found for these keywords this week. Try broader words.", color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
            items(items, key = { it.id }) { v ->
                VideoCard(v, Modifier.staggerIn(items.indexOf(v).coerceAtMost(3)),
                    onOpen = { play(v, items) },
                    onIdea = {
                        val idea = v.title.split("·", "|", " - ", "—").first().trim().take(70)
                        g.settings.update { it.copy(ideaHint = idea) }; note = "Your next automatic video will be inspired by “$idea”."
                    })
            }
        }
    }
}

@Composable
private fun VideoCard(v: TrendVideo, modifier: Modifier, onOpen: () -> Unit, onIdea: () -> Unit) {
    val c = Lofi.c
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(c.surfaceHi).pressScale(onOpen, haptic = false)) {
            if (v.thumb.isNotBlank()) AsyncImage(v.thumb, v.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = .8f)).padding(horizontal = 5.dp, vertical = 2.dp)) {
                Mono(fmtDur(v.durationS), Color.White, 11)
            }
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(c.surfaceHi).border(1.dp, c.line, CircleShape), contentAlignment = Alignment.Center) {
                Text(v.channel.firstOrNull()?.uppercase() ?: "?", color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(v.title, color = c.ink, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp, modifier = Modifier.pressScale(onOpen, haptic = false))
                Text("${v.channel}  ·  ${fmtCount(v.views)} views  ·  ${fmtAge(v.publishedMs)}", color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Mono("${fmtCount(v.viewsPerDay.toLong())}/day", c.ok, 11)
                    if (v.keyword.isNotBlank()) Text("#${v.keyword}", color = c.faint, fontSize = 11.sp)
                    Box(Modifier.weight(1f))
                    GhostButton("Use idea") { onIdea() }
                }
            }
        }
    }
}

@Composable
private fun SkeletonCard() {
    Column(Modifier.fillMaxWidth()) {
        Skeleton(Modifier.fillMaxWidth().aspectRatio(16f / 9f), corner = 0.dp)
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Skeleton(Modifier.size(36.dp), corner = 18.dp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Skeleton(Modifier.fillMaxWidth(0.8f).height(14.dp)); Skeleton(Modifier.fillMaxWidth(0.5f).height(12.dp)) }
        }
    }
}
