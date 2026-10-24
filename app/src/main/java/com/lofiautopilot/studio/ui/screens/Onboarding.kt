package com.lofiautopilot.studio.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.ui.components.AudioBarsLogo
import com.lofiautopilot.studio.ui.components.GhostButton
import com.lofiautopilot.studio.ui.components.GradientButton
import com.lofiautopilot.studio.ui.components.staggerIn
import com.lofiautopilot.studio.ui.theme.Lofi

@Composable
fun OnboardingScreen(g: Graph, onDone: (goLibrary: Boolean) -> Unit) {
    val c = Lofi.c
    var step by remember { mutableIntStateOf(0) }
    val pages = listOf(
        Triple("", "Your lofi channel,\non autopilot", "SE picks your music and your newest picture, adds beautiful effects that move with the beat, renders the video right on this phone and can publish it for you."),
        Triple("", "A real video editor,\nin your pocket", "Build a timeline in landscape: add songs, place 80+ effects exactly where you want them, drag, trim and snap to the beat, or let AI arrange everything."),
        Triple("", "Smart helpers,\nall optional", "A small AI model on your phone listens to the rhythm and directs the visuals. Add a free AI key and you can fix or change anything just by chatting."),
    )
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AnimatedContent(step, transitionSpec = {
                (fadeIn(tween(400)) + slideInHorizontally(tween(450)) { it / 4 }) togetherWith (fadeOut(tween(200)) + slideOutHorizontally(tween(300)) { -it / 4 })
            }, label = "onb") { i ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    AudioBarsLogo(size = 72.dp)
                    Text(pages[i].second, color = c.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 38.sp, modifier = Modifier.staggerIn(1))
                    Text(pages[i].third, color = c.muted, fontSize = 16.sp, textAlign = TextAlign.Center, lineHeight = 23.sp, modifier = Modifier.staggerIn(2))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 22.dp)) {
            pages.indices.forEach { i ->
                val w by androidx.compose.animation.core.animateDpAsState(if (i == step) 26.dp else 8.dp, tween(300), label = "dot")
                Box(Modifier.size(width = w, height = 8.dp).background(if (i == step) c.amber else c.line, CircleShape))
            }
        }
        if (step < pages.lastIndex) {
            GradientButton("Next", Modifier.fillMaxWidth()) { step++ }
            Box(Modifier.padding(top = 8.dp)) { GhostButton("Skip") { onDone(false) } }
        } else {
            GradientButton("Set up my folders", Modifier.fillMaxWidth()) { onDone(true) }
            Box(Modifier.padding(top = 8.dp)) { GhostButton("I'll do it later") { onDone(false) } }
        }
    }
}
