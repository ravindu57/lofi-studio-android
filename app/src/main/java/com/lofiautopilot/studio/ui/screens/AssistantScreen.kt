package com.lofiautopilot.studio.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.core.SecretStore
import com.lofiautopilot.studio.ui.components.Chip
import com.lofiautopilot.studio.ui.components.GhostButton
import com.lofiautopilot.studio.ui.components.GlassCard
import com.lofiautopilot.studio.ui.components.pressScale
import com.lofiautopilot.studio.ui.theme.Lofi
import kotlinx.coroutines.launch

@Composable
fun AssistantScreen(g: Graph) {
    val c = Lofi.c
    val scope = rememberCoroutineScope()
    val msgs by g.assistant.messages.collectAsState()
    val busy by g.assistant.busy.collectAsState()
    val s by g.settings.flow.collectAsState()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size, busy) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size) }
    val hasClaude = g.engine.cloud.configured

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 14.dp)) {
        Row(Modifier.padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Assistant", color = c.ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.9).sp, modifier = Modifier.weight(1f))
            GhostButton("New chat") { g.assistant.clear() }
        }
        if (!hasClaude) GlassCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), glow = c.amber) {
            Text(
                if (g.engine.llm.available) "No AI key yet. I can still do simple things offline. Add a free Gemini or Groq key in Settings → AI for full help." else "Add a free AI key (Settings → AI, Gemini takes a minute) so the assistant can manage and fix things for you. A small offline model also works for simple commands.",
                color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(12.dp),
            )
        }
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(msgs.size) { i ->
                val m = msgs[i]
                AnimatedVisibility(true, enter = fadeIn(tween(300)) + slideInVertically(tween(320)) { it / 3 }) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.fromUser) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (m.steps.isNotEmpty()) m.steps.takeLast(4).forEach { st ->
                            Text("› ${st.take(90)}", color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))
                        }
                        Box(
                            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp, 20.dp, if (m.fromUser) 4.dp else 20.dp, if (m.fromUser) 20.dp else 4.dp))
                                .background(if (m.fromUser) c.ink else if (m.error) c.crit.copy(alpha = .15f) else c.surfaceHi)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) { Text(m.text, color = if (m.fromUser) c.bg else c.ink, fontSize = 15.sp, lineHeight = 21.sp) }
                    }
                }
            }
            if (busy) item { TypingDots() }
        }
        if (msgs.size <= 2) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            items(listOf("What's the status?", "Why did my last video fail?", "Make a 30 minute video", "Make the Studio look dreamy with purple glow", "Turn the schedule to 2 videos a day")) { q ->
                Chip(q, false, { scope.launch { g.assistant.send(q) } })
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Ask or tell me anything…") }, maxLines = 4, shape = RoundedCornerShape(26.dp))
            Box(Modifier.size(48.dp).clip(CircleShape).background(c.ink).pressScale({ val t = input; input = ""; scope.launch { g.assistant.send(t) } }, enabled = input.isNotBlank() && !busy), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = c.bg)
            }
        }
    }
}

@Composable
private fun TypingDots() {
    val c = Lofi.c
    val t = rememberInfiniteTransition(label = "typing")
    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 150, easing = LinearEasing), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(9.dp).alpha(a).background(c.amber, CircleShape))
        }
    }
}
