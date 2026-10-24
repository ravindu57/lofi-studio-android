package com.lofiautopilot.studio.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.discover.TrendVideo
import com.lofiautopilot.studio.ui.components.GhostButton
import com.lofiautopilot.studio.ui.components.Mono
import com.lofiautopilot.studio.ui.components.pressScale
import com.lofiautopilot.studio.ui.theme.Lofi

/**
 * In-app video page in the style of the YouTube app: the official embedded player (with its own controls and full
 * screen), the title and numbers underneath, and an "Up next" list from your feed. Playback is YouTube's own player.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PlayerScreen(g: Graph, first: TrendVideo, feed: List<TrendVideo>, onClose: () -> Unit) {
    val c = Lofi.c
    val ctx = LocalContext.current
    val view = LocalView.current
    var video by remember { mutableStateOf(first) }
    var customView by remember { mutableStateOf<View?>(null) }
    var customCb by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var note by remember { mutableStateOf("") }
    val up = remember(video.id, feed) { feed.filter { it.id != video.id } }

    // full screen (the player's own button) → landscape + hide system bars
    DisposableEffect(customView != null) {
        val act = ctx as? Activity
        val ctl = act?.window?.let { WindowInsetsControllerCompat(it, view) }
        if (customView != null) {
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (customView != null) {
                ctl?.show(WindowInsetsCompat.Type.systemBars())
                act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
    BackHandler(customView != null) { customCb?.onCustomViewHidden(); customView = null }

    val web = remember {
        WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webViewClient = object : WebViewClient() {
                // keep navigation inside the embed; anything else opens in the browser
                override fun shouldOverrideUrlLoading(v: WebView, req: android.webkit.WebResourceRequest): Boolean {
                    val u = req.url.toString()
                    return if (u.contains("youtube.com/embed") || u.contains("youtube-nocookie.com/embed")) false
                    else { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, req.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; true }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(v: View, cb: CustomViewCallback) { customView = v; customCb = cb }
                override fun onHideCustomView() { customView = null; customCb = null }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { runCatching { web.loadUrl("about:blank"); web.destroy() } } }

    // (re)load when the video changes. A Referer that identifies the app is required by YouTube's embedded player.
    androidx.compose.runtime.LaunchedEffect(video.id) {
        web.loadUrl(
            "https://www.youtube.com/embed/${video.id}?autoplay=1&playsinline=1&rel=0&modestbranding=1&fs=1",
            mapOf("Referer" to "https://${ctx.packageName}"),
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().background(c.bg)) {
            Box(Modifier.fillMaxWidth().background(Color.Black).statusBarsPadding()) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                    AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.padding(6.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f)).pressScale(onClose, haptic = false), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                }
            }
            LazyColumn(Modifier.weight(1f).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(video.title, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp)
                        Text("${fmtCount(video.views)} views  ·  ${fmtAge(video.publishedMs)}  ·  ${fmtCount(video.viewsPerDay.toLong())}/day", color = c.muted, fontSize = 13.sp)
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(c.surfaceHi).border(1.dp, c.line, CircleShape), contentAlignment = Alignment.Center) {
                                Text(video.channel.firstOrNull()?.uppercase() ?: "?", color = c.ink, fontWeight = FontWeight.SemiBold)
                            }
                            Text(video.channel, color = c.ink, fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton("Use as idea") {
                                val idea = video.title.split("·", "|", " - ", "—").first().trim().take(70)
                                g.settings.update { it.copy(ideaHint = idea) }; note = "Your next automatic video will be inspired by “$idea”."
                            }
                            GhostButton("Open in YouTube") { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${video.id}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            GhostButton("Share") {
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${video.title}\nhttps://youtu.be/${video.id}"), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                        if (note.isNotBlank()) Text(note, color = c.ok, fontSize = 12.sp)
                        Text("If a video says it can't be played here, its owner has switched off embedding. Use “Open in YouTube”.", color = c.faint, fontSize = 11.sp)
                        if (up.isNotEmpty()) Text("Up next", color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
                    }
                }
                items(up, key = { it.id }) { v ->
                    Row(Modifier.fillMaxWidth().pressScale({ video = v; note = "" }, haptic = false).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.width(160.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(c.surfaceHi)) {
                            if (v.thumb.isNotBlank()) AsyncImage(v.thumb, v.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(3.dp)).background(Color.Black.copy(alpha = .8f)).padding(horizontal = 4.dp, vertical = 1.dp)) { Mono(fmtDur(v.durationS), Color.White, 10) }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(v.title, color = c.ink, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
                            Text(v.channel, color = c.muted, fontSize = 12.sp, maxLines = 1)
                            Text("${fmtCount(v.views)} views · ${fmtAge(v.publishedMs)}", color = c.faint, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        // the player's own full-screen view
        customView?.let { cv ->
            AndroidView(factory = { (cv.parent as? ViewGroup)?.removeView(cv); cv }, modifier = Modifier.fillMaxSize().background(Color.Black))
        }
    }
}

