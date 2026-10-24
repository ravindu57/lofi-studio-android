package com.lofiautopilot.studio.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.ui.components.AuroraBackground
import com.lofiautopilot.studio.ui.screens.AssistantScreen
import com.lofiautopilot.studio.ui.screens.HomeScreen
import com.lofiautopilot.studio.ui.screens.JobDetail
import com.lofiautopilot.studio.ui.screens.LibraryScreen
import com.lofiautopilot.studio.ui.screens.OnboardingScreen
import com.lofiautopilot.studio.ui.screens.SettingsScreen
import com.lofiautopilot.studio.ui.screens.VideosScreen
import com.lofiautopilot.studio.ui.editor.EditorModel
import com.lofiautopilot.studio.ui.editor.EditorScreen
import com.lofiautopilot.studio.ui.screens.ProjectsScreen
import com.lofiautopilot.studio.vfx.Project
import com.lofiautopilot.studio.ui.theme.Lofi

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    STUDIO("Studio", Icons.Rounded.Tune),
    VIDEOS("Videos", Icons.Rounded.Movie),
    DISCOVER("Discover", Icons.Rounded.Explore),
    LIBRARY("Library", Icons.Rounded.LibraryMusic),
    ASSIST("Assistant", Icons.Rounded.AutoAwesome),
}

sealed interface Overlay {
    data object None : Overlay
    data object Settings : Overlay
    data class Job(val id: Long) : Overlay
    data class Editor(val project: Project) : Overlay
    data object Dashboard : Overlay
    data class Player(val video: com.lofiautopilot.studio.discover.TrendVideo, val feed: List<com.lofiautopilot.studio.discover.TrendVideo>) : Overlay
}

@Composable
fun App(g: Graph, jobFromIntent: androidx.compose.runtime.MutableLongState, sharedImage: MutableState<Uri?>) {
    val c = Lofi.c
    val s by g.settings.flow.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        g.engine.library.let { lib -> if (g.settings.value.musicFolderUris.isNotEmpty()) runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { lib.scanMusic() } } }
    }
    LaunchedEffect(jobFromIntent.longValue) {
        if (jobFromIntent.longValue > 0) { overlay = Overlay.Job(jobFromIntent.longValue); jobFromIntent.longValue = 0 }
    }
    LaunchedEffect(sharedImage.value) {
        sharedImage.value?.let { overlay = Overlay.Editor(Project(name = "Shared picture", media = listOf(com.lofiautopilot.studio.vfx.MediaClip(uri = it.toString(), lenS = 8f)))); sharedImage.value = null }
    }
    BackHandler(overlay != Overlay.None && overlay !is Overlay.Editor) { overlay = Overlay.None }
    BackHandler(overlay == Overlay.None && tab != 0) { tab = 0 }

    Box(Modifier.fillMaxSize()) {
        AuroraBackground()
        if (!s.onboarded) {
            OnboardingScreen(g, onDone = { go -> g.settings.update { it.copy(onboarded = true) }; if (go) tab = Tab.LIBRARY.ordinal })
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            val dir = if (targetState > initialState) 1 else -1
                            (fadeIn(tween(280)) + slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { it / 10 * dir } + scaleIn(tween(340), 0.97f)) togetherWith
                                (fadeOut(tween(160)) + slideOutHorizontally(tween(260)) { -it / 12 * dir })
                        },
                        label = "tabs",
                    ) { t ->
                        when (Tab.entries[t]) {
                            Tab.HOME -> HomeScreen(g, goTab = { tab = it.ordinal }, openJob = { overlay = Overlay.Job(it) }, openSettings = { overlay = Overlay.Settings }, openDashboard = { overlay = Overlay.Dashboard }, openProject = { overlay = Overlay.Editor(it) })
                            Tab.STUDIO -> ProjectsScreen(g, open = { overlay = Overlay.Editor(it) })
                            Tab.VIDEOS -> VideosScreen(g, openJob = { overlay = Overlay.Job(it) })
                            Tab.DISCOVER -> com.lofiautopilot.studio.ui.screens.DiscoverScreen(g, openSettings = { overlay = Overlay.Settings }, play = { v, list -> overlay = Overlay.Player(v, list) })
            Tab.LIBRARY -> LibraryScreen(g)
                            Tab.ASSIST -> AssistantScreen(g)
                        }
                    }
                }
                BottomBar(tab) { tab = it }
            }
            AnimatedVisibility(
                overlay != Overlay.None,
                enter = slideInVertically(tween(380, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(300)) + scaleIn(tween(380), 0.96f),
                exit = slideOutVertically(tween(260)) { it / 3 } + fadeOut(tween(200)) + scaleOut(tween(260), 0.96f),
            ) {
                Box(Modifier.fillMaxSize().background(c.bg)) {
                    AuroraBackground(intensity = 0.8f)
                    when (val o = overlay) {
                        Overlay.Settings -> SettingsScreen(g, onClose = { overlay = Overlay.None })
                        is Overlay.Job -> JobDetail(g, o.id, onClose = { overlay = Overlay.None })
                        Overlay.Dashboard -> com.lofiautopilot.studio.ui.screens.DashboardScreen(g, onClose = { overlay = Overlay.None }, goTab = { t -> overlay = Overlay.None; when (t) { "library" -> tab = Tab.LIBRARY.ordinal; "videos" -> tab = Tab.VIDEOS.ordinal; "claude" -> tab = Tab.ASSIST.ordinal; "settings" -> overlay = Overlay.Settings } })
                        is Overlay.Player -> com.lofiautopilot.studio.ui.screens.PlayerScreen(g, o.video, o.feed, onClose = { overlay = Overlay.None })
                        is Overlay.Editor -> {
                            val model = remember(o.project.id) { EditorModel(g, o.project) }
                            EditorScreen(g, model, onClose = { overlay = Overlay.None })
                        }
                        Overlay.None -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val c = Lofi.c
    Column(Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        BoxWithConstraints(Modifier.fillMaxWidth().height(58.dp)) {
            val itemW = maxWidth / Tab.entries.size
            val x by animateDpAsState(itemW * selected, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow), label = "ind")
            Box(Modifier.offset(x = x + itemW / 2 - 14.dp).width(28.dp).height(2.dp).background(c.ink))
            Row(Modifier.fillMaxSize()) {
                Tab.entries.forEachIndexed { i, t ->
                    val sel = i == selected
                    val tint by androidx.compose.animation.animateColorAsState(if (sel) c.ink else c.faint, tween(180), label = "tint")
                    Column(
                        Modifier.weight(1f).fillMaxSize().clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
                        ) { onSelect(i) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Icon(t.icon, t.label, Modifier.size(22.dp), tint = tint)
                        Text(t.label, fontSize = 10.sp, color = tint, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}
