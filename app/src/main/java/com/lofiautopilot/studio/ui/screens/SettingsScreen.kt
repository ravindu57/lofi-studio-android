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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofiautopilot.studio.ai.LocalModels
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.core.SecretStore
import com.lofiautopilot.studio.export.Aspect
import com.lofiautopilot.studio.publish.DeviceCode
import com.lofiautopilot.studio.ui.components.Chip
import com.lofiautopilot.studio.ui.components.GhostButton
import com.lofiautopilot.studio.ui.components.GlassCard
import com.lofiautopilot.studio.ui.components.GradientButton
import com.lofiautopilot.studio.ui.components.pressScale
import com.lofiautopilot.studio.ui.components.staggerIn
import com.lofiautopilot.studio.ui.theme.Lofi
import com.lofiautopilot.studio.vfx.Presets
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(g: Graph, onClose: () -> Unit) {
    val c = Lofi.c
    val s by g.settings.flow.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(c.surface).pressScale(onClose), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = c.ink) }
            Text("Settings", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 26.sp, modifier = Modifier.padding(start = 12.dp))
        }
        Card("Channel", 0) {
            OutlinedTextField(s.channelName, { v -> g.settings.update { it.copy(channelName = v) } }, Modifier.fillMaxWidth(), label = { Text("Channel name") }, singleLine = true)
        }
        AutopilotCard(g)
        RecipeCard(g)
        VideoCard(g)
        AiCard(g)
        AccountsCard(g)
        NotifyCard(g)
        BackgroundCard()
        Text("Synth Edit (SE) · everything runs on this phone. Keys are stored encrypted in the Android Keystore.", color = c.faint, fontSize = 12.sp)
    }
}

@Composable
internal fun Card(title: String, index: Int, content: @Composable ColumnScope.() -> Unit) {
    val c = Lofi.c
    GlassCard(Modifier.fillMaxWidth().staggerIn(index)) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            content()
        }
    }
}

@Composable
internal fun ToggleRow(label: String, hint: String = "", checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Lofi.c
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, color = c.ink, fontSize = 15.sp); if (hint.isNotBlank()) Text(hint, color = c.faint, fontSize = 12.sp) }
        Switch(checked, onChange)
    }
}

@Composable
internal fun <T> ChipRow(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    val c = Lofi.c
    Text(label, color = c.muted, fontSize = 13.sp)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(options) { (t, v) -> Chip(t, v == selected, { onSelect(v) }) } }
}

@Composable
private fun SecretField(g: Graph, label: String, key: String, hint: String = "") {
    val c = Lofi.c
    var v by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(g.secrets.masked(key)) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            v, { v = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(if (saved.isNotEmpty()) "$label  ($saved saved)" else label) },
            visualTransformation = PasswordVisualTransformation(),
            trailingIcon = {
                if (v.isNotBlank()) TextButton({
                    g.secrets.put(key, v); v = ""; saved = g.secrets.masked(key)
                    if (key in listOf(SecretStore.GEMINI, SecretStore.GROQ, SecretStore.OPENROUTER)) g.settings.update { it.copy(planner = if (it.planner == "rules") "cloud" else it.planner) }
                }) { Text("Save") }
                else if (saved.isNotEmpty()) TextButton({ g.secrets.put(key, ""); saved = "" }) { Text("Remove") }
            },
        )
        if (hint.isNotBlank()) Text(hint, color = c.faint, fontSize = 12.sp)
    }
}

@Composable
private fun AutopilotCard(g: Graph) {
    val c = Lofi.c
    val s by g.settings.flow.collectAsState()
    var picking by remember { mutableStateOf(false) }
    Card("Autopilot schedule", 1) {
        ChipRow("Videos per day", (1..4).map { "$it" to it }, s.videosPerDay) { v -> g.settings.update { it.copy(videosPerDay = v) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Publish around", color = c.ink); Text("The first video of the day goes out at this time", color = c.faint, fontSize = 12.sp) }
            GhostButton(s.publishTime) { picking = true }
        }
        Text("Start making each video ${"%.1f".format(s.leadHours)} h before it's due", color = c.muted, fontSize = 13.sp)
        Slider(s.leadHours, { v -> g.settings.update { it.copy(leadHours = (Math.round(v * 2) / 2f)) } }, valueRange = 0.5f..8f)
        Text("Long videos take a while to render. Give the phone enough time before the publish time.", color = c.faint, fontSize = 12.sp)
    }
    if (picking) {
        val (h, m) = s.publishTime.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 18) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
        val st = rememberTimePickerState(h, m, true)
        AlertDialog(onDismissRequest = { picking = false }, title = { Text("Publish time") }, text = { TimeInput(st) },
            confirmButton = { TextButton({ g.settings.update { it.copy(publishTime = "%02d:%02d".format(st.hour, st.minute)) }; picking = false }) { Text("OK") } },
            dismissButton = { TextButton({ picking = false }) { Text("Cancel") } })
    }
}

@Composable
private fun VideoCard(g: Graph) {
    val c = Lofi.c
    val s by g.settings.flow.collectAsState()
    val saved by g.db.looks().all().collectAsState(emptyList())
    Card("Automatic videos", 2) {
        val looks = listOf("Auto (AI arranges effects)" to "Auto") + Presets.builtin.map { it.name to it.name } + saved.map { "★ ${it.name}" to it.name }
        ChipRow("Look (visual style)", looks, s.lookName) { v -> g.settings.update { it.copy(lookName = v) } }
        ToggleRow("Let the rhythm director adapt it", "Strength follows the song's quiet and loud parts", s.directorOn) { v -> g.settings.update { it.copy(directorOn = v) } }
        ChipRow("Quality", listOf("360p" to 360, "720p" to 720, "1080p" to 1080, "1440p" to 1440), s.quality) { v -> g.settings.update { it.copy(quality = v) } }
        ChipRow("Frame rate", listOf("24" to 24, "30" to 30, "60" to 60), s.fps) { v -> g.settings.update { it.copy(fps = v) } }
        ChipRow("Shape", Aspect.entries.map { it.label to it.name }, s.aspect) { v -> g.settings.update { it.copy(aspect = v) } }
        ChipRow("Format", com.lofiautopilot.studio.export.Codec.entries.map { it.label to it.name }, s.codec) { v -> g.settings.update { it.copy(codec = v) } }
        ChipRow("Pictures per video", listOf("1" to 1, "2" to 2, "3" to 3, "4" to 4, "6" to 6), s.slideshowPics) { v -> g.settings.update { it.copy(slideshowPics = v) } }
        if (s.slideshowPics > 1) Text("The pictures take turns, cutting where the music changes, with a fitting transition at each cut.", color = c.faint, fontSize = 12.sp)
        ChipRow("Intro", listOf("None" to "", "Auto" to "auto") + com.lofiautopilot.studio.vfx.Intros.all.map { it.name to it.id }, s.introId) { v -> g.settings.update { it.copy(introId = v) } }
        Text("Length: ${s.targetMinutes} min", color = c.muted, fontSize = 13.sp)
        Slider(s.targetMinutes.toFloat(), { v -> g.settings.update { it.copy(targetMinutes = (Math.round(v / 5f) * 5).coerceAtLeast(1)) } }, valueRange = 5f..180f)
        Text("Crossfade between songs: ${"%.0f".format(s.crossfadeS)} s", color = c.muted, fontSize = 13.sp)
        Slider(s.crossfadeS, { v -> g.settings.update { it.copy(crossfadeS = Math.round(v).toFloat()) } }, valueRange = 0f..12f)
        ToggleRow("Also save to Gallery", "Movies / LofiStudio", s.saveToGallery) { v -> g.settings.update { it.copy(saveToGallery = v) } }
        ToggleRow("Title on thumbnail", "", s.thumbnailText) { v -> g.settings.update { it.copy(thumbnailText = v) } }
        Text("Delete finished video files after ${s.keepDays} days (0 = keep)", color = c.muted, fontSize = 13.sp)
        Slider(s.keepDays.toFloat(), { v -> g.settings.update { it.copy(keepDays = v.toInt()) } }, valueRange = 0f..60f)
    }
}

@Composable
private fun AiCard(g: Graph) {
    val c = Lofi.c
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsState()
    val dl by g.engine.llm.download.collectAsState()
    Card("AI helpers", 3) {
        ChipRow("Who writes titles and picks visuals?", listOf("Built-in rules" to "rules", "On-device AI" to "local", "Cloud AI (free)" to "cloud"), if (s.planner == "claude") "cloud" else s.planner) { v -> g.settings.update { it.copy(planner = v) } }
        CloudAiBlock(g)
        Text("On-device AI listens to a numeric summary of your music (tempo, loudness, bass, sections) and decides how the visuals should move. It works offline and never sends audio anywhere.", color = c.faint, fontSize = 12.sp)
        if (g.engine.llm.crashedLastTime) {
            Text("The on-device AI stopped the app last time, so it is switched off. Everything still works with the built-in rules.", color = c.warn, fontSize = 12.sp)
            GhostButton("Switch on-device AI back on") { g.engine.llm.reEnable() }
        }
        LocalModels.catalog.forEach { m ->
            val installed = g.engine.llm.installed(m)
            val active = s.localModelId == m.id && installed
            GlassCard(Modifier.fillMaxWidth(), corner = 16.dp, glow = if (active) c.ok else null) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(m.name, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(m.blurb, color = c.muted, fontSize = 12.sp)
                    val d = dl?.takeIf { it.modelId == m.id }
                    if (d != null && !d.finished && d.error.isBlank() && !installed) LinearProgressIndicator({ d.fraction }, Modifier.fillMaxWidth())
                    if (d != null && d.error.isNotBlank()) Text(d.error, color = c.crit, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!installed) GradientButton("Download (${m.sizeMb} MB)", small = true) { scope.launch { g.engine.llm.download(m) } }
                        else {
                            if (!active) GradientButton("Use this", small = true) { g.settings.update { it.copy(localModelId = m.id, localModelPath = g.engine.llm.fileFor(m).absolutePath) } }
                            else Text("✓ In use", color = c.ok, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                            GhostButton("Delete") { g.engine.llm.delete(m) }
                        }
                    }
                }
            }
        }
        SecretField(g, "Hugging Face token (only for Gemma)", SecretStore.HF_TOKEN)
        SecretField(g, "OpenAI API key (optional)", SecretStore.OPENAI, "Only for generating artwork from your reference pictures.")
    }
}

@Composable
private fun AccountsCard(g: Graph) {
    val c = Lofi.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsState()
    var code by remember { mutableStateOf<DeviceCode?>(null) }
    var msg by remember { mutableStateOf("") }
    var ytConnected by remember { mutableStateOf(g.engine.ytAuth.connected) }
    var short by remember { mutableStateOf("") }
    var pages by remember { mutableStateOf<List<com.lofiautopilot.studio.publish.FbPage>>(emptyList()) }
    Card("Accounts & publishing", 4) {
        ToggleRow("Publish for real", if (s.publishEnabled) "Videos are uploaded to the connected accounts" else "Practice mode: videos are made and saved, nothing is uploaded", s.publishEnabled) { v -> g.settings.update { it.copy(publishEnabled = v) } }
        Text("YouTube", color = c.ink, fontWeight = FontWeight.SemiBold)
        SecretField(g, "YouTube Data API key (for Discover)", SecretStore.YOUTUBE_API, "Lets Discover find new videos in your niche. Free quota is 10,000 units a day; results are cached for 3 hours.")
        SecretField(g, "Google OAuth client ID", SecretStore.YT_CLIENT_ID, "Create a “TVs and Limited Input devices” client in Google Cloud Console and enable the YouTube Data API.")
        SecretField(g, "Google OAuth client secret", SecretStore.YT_CLIENT_SECRET)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!ytConnected) GradientButton("Connect YouTube", small = true) {
                scope.launch {
                    try {
                        val d = g.engine.ytAuth.start(); code = d
                        val ok = g.engine.ytAuth.finish(d)
                        code = null
                        ytConnected = ok
                        if (ok) { val t = runCatching { g.engine.ytAuth.channelTitle() }.getOrDefault(""); g.settings.update { it.copy(ytChannelTitle = t) }; msg = "YouTube connected${if (t.isNotBlank()) " as $t" else ""}." }
                        else msg = "Sign-in wasn't completed."
                    } catch (e: Exception) { code = null; msg = (e.message ?: "Couldn't start sign-in.") + if (e is com.lofiautopilot.studio.publish.PublishException && e.hint.isNotBlank()) " ${e.hint}" else "" }
                }
            } else {
                Text("✓ ${s.ytChannelTitle.ifBlank { "Connected" }}", color = c.ok, fontWeight = FontWeight.Bold)
                GhostButton("Disconnect") { g.engine.ytAuth.disconnect(); ytConnected = false }
            }
        }
        ToggleRow("Upload to YouTube", "", s.youtubeOn) { v -> g.settings.update { it.copy(youtubeOn = v) } }
        ToggleRow("My API project is approved by Google", "Until Google approves, uploads stay private. Turn on after approval.", s.ytAudited) { v -> g.settings.update { it.copy(ytAudited = v) } }

        Text("Facebook Page", color = c.ink, fontWeight = FontWeight.SemiBold)
        SecretField(g, "Facebook app ID", SecretStore.FB_APP_ID)
        SecretField(g, "Facebook app secret", SecretStore.FB_APP_SECRET)
        OutlinedTextField(short, { short = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Short-lived user token (from Graph API Explorer)") })
        GhostButton("Find my Pages") {
            scope.launch {
                msg = try {
                    pages = g.engine.facebook.pagesFromUserToken(g.secrets.get(SecretStore.FB_APP_ID), g.secrets.get(SecretStore.FB_APP_SECRET), short.trim())
                    "Pick your Page below."
                } catch (e: Exception) { e.message ?: "Couldn't reach Facebook." }
            }
        }
        pages.forEach { p -> GradientButton("Use “${p.name}”", small = true) { g.secrets.put(SecretStore.FB_TOKEN, p.token); g.settings.update { it.copy(fbPageId = p.id, fbPageName = p.name) }; pages = emptyList(); short = ""; msg = "Connected to ${p.name}." } }
        if (s.fbPageId.isNotBlank()) Text("✓ ${s.fbPageName.ifBlank { s.fbPageId }}", color = c.ok, fontWeight = FontWeight.Bold)
        ToggleRow("Post to Facebook", "", s.facebookOn) { v -> g.settings.update { it.copy(facebookOn = v) } }
        if (msg.isNotBlank()) Text(msg, color = c.amber, fontSize = 13.sp)
    }
    code?.let { d ->
        AlertDialog(onDismissRequest = {}, title = { Text("Connect YouTube") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("On this phone or another device, open the page and type this code:")
                    Text(d.userCode, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = c.amber)
                    Text(d.url, color = c.sky)
                    Text("Waiting for you to approve…", color = c.muted, fontSize = 13.sp)
                }
            },
            confirmButton = { TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open page") } })
    }
}

@Composable
private fun NotifyCard(g: Graph) {
    val s by g.settings.flow.collectAsState()
    Card("Notifications", 5) {
        ToggleRow("Show progress while rendering", "", s.notifyProgress) { v -> g.settings.update { it.copy(notifyProgress = v) } }
        ToggleRow("Video ready / published", "", s.notifyDone) { v -> g.settings.update { it.copy(notifyDone = v) } }
        ToggleRow("Something needs attention", "", s.notifyFailed) { v -> g.settings.update { it.copy(notifyFailed = v) } }
        ToggleRow("New picture found in folder", "", s.notifyNewImage) { v -> g.settings.update { it.copy(notifyNewImage = v) } }
        Text("Also tell me elsewhere (optional)", color = Lofi.c.muted, fontSize = 13.sp)
        OutlinedTextField(s.ntfyTopic, { v -> g.settings.update { it.copy(ntfyTopic = v.trim()) } }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("ntfy.sh topic (install the ntfy app and subscribe)") })
        OutlinedTextField(s.webhookUrl, { v -> g.settings.update { it.copy(webhookUrl = v.trim()) } }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Discord webhook URL") })
        SecretField(g, "Telegram bot token", SecretStore.TELEGRAM)
        OutlinedTextField(s.telegramChatId, { v -> g.settings.update { it.copy(telegramChatId = v.trim()) } }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Telegram chat id") })
        GhostButton("Send a test notification") { g.notifier.test() }
    }
}

@Composable
private fun BackgroundCard() {
    val c = Lofi.c
    val ctx = LocalContext.current
    val ok = remember { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) }
    Card("Keep running in the background", 6) {
        Text(
            if (ok) "✓ Battery optimisation is off for SE." else "Phones stop background apps to save battery. Allow SE so Autopilot can render overnight.",
            color = if (ok) c.ok else c.muted, fontSize = 14.sp,
        )
        if (!ok) GradientButton("Allow background activity", small = true) {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Text("Oppo / ColorOS: also open Settings → Battery → SE and allow “Background activity” and “Auto launch”, and lock the app in the Recents screen (pull the card down or tap the lock).", color = c.faint, fontSize = 12.sp)
        GhostButton("Open app settings") { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}



/** Free cloud AI: pick a provider, paste its free key, test it. Extra keys act as automatic backups. */
@Composable
private fun CloudAiBlock(g: Graph) {
    val c = Lofi.c
    val scope = rememberCoroutineScope()
    val s by g.settings.flow.collectAsState()
    var result by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    val prov = com.lofiautopilot.studio.ai.Provider.of(s.aiProvider)
    Text("Cloud AI (free)", color = c.ink, fontWeight = FontWeight.SemiBold)
    Text("Powers the Assistant tab, smarter titles and AI timeline arranging. Gemini is the best free choice; Groq and OpenRouter also have free keys. Keys you add for the others are used automatically as backups when one hits its free limit.", color = c.faint, fontSize = 12.sp)
    ChipRow("Main provider", com.lofiautopilot.studio.ai.Provider.entries.map { it.label to it.id }, s.aiProvider) { v -> g.settings.update { it.copy(aiProvider = v) } }
    SecretField(g, "${prov.label.substringBefore(" (")} API key", prov.secret, "Free key: ${prov.keyUrl}")
    val model = when (prov) { com.lofiautopilot.studio.ai.Provider.GEMINI -> s.geminiModel; com.lofiautopilot.studio.ai.Provider.GROQ -> s.groqModel; else -> s.openrouterModel }
    OutlinedTextField(model, { v -> g.settings.update { when (prov) { com.lofiautopilot.studio.ai.Provider.GEMINI -> it.copy(geminiModel = v.trim()); com.lofiautopilot.studio.ai.Provider.GROQ -> it.copy(groqModel = v.trim()); else -> it.copy(openrouterModel = v.trim()) } } },
        Modifier.fillMaxWidth(), singleLine = true, label = { Text("Model") })
    Text("Backup keys", color = c.muted, fontSize = 12.sp)
    com.lofiautopilot.studio.ai.Provider.entries.filter { it != prov }.forEach { p -> SecretField(g, "${p.label.substringBefore(" (")} API key (backup)", p.secret, "Free key: ${p.keyUrl}") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GradientButton(if (testing) "Testing…" else "Test connection", small = true, enabled = !testing) {
            scope.launch {
                testing = true; result = ""
                result = try { g.engine.cloud.test(prov) } catch (e: com.lofiautopilot.studio.ai.AiException) { e.message.orEmpty() + if (e.hint.isNotBlank()) " ${e.hint}" else "" } catch (e: Exception) { "Test failed: ${e.message}" }
                testing = false
            }
        }
    }
    if (result.isNotBlank()) Text(result, color = if (result.contains("works")) c.ok else c.crit, fontSize = 12.sp)
    Text("Free tiers: the provider may use what you send to improve its models. SE sends only short text (your chat, settings, music statistics), never your audio or pictures.", color = c.faint, fontSize = 11.sp)
}
