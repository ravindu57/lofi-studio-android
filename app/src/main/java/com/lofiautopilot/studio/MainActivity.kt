package com.lofiautopilot.studio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.ui.App
import com.lofiautopilot.studio.ui.theme.LofiTheme

class MainActivity : ComponentActivity() {
    private var jobFromIntent = androidx.compose.runtime.mutableLongStateOf(0L)
    private var sharedImage = androidx.compose.runtime.mutableStateOf<android.net.Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        handle(intent)
        val g = Graph.get(this)
        setContent {
            LofiTheme(dark = true) {
                App(g, jobFromIntent, sharedImage)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i ?: return
        jobFromIntent.longValue = i.getLongExtra("jobId", 0L)
        if (i.action == Intent.ACTION_SEND && i.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            sharedImage.value = i.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }
}
