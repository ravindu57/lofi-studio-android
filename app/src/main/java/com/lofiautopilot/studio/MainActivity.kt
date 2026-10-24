package com.lofiautopilot.studio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
        val crash = com.lofiautopilot.studio.core.CrashReport.pending(this)
        setContent {
            LofiTheme(dark = true) {
                var report by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(crash) }
                App(g, jobFromIntent, sharedImage)
                report?.let { r ->
                    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { report = null },
                        title = { androidx.compose.material3.Text("The app crashed last time") },
                        text = { androidx.compose.material3.Text(r.take(1500), fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp), modifier = androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) },
                        confirmButton = { androidx.compose.material3.TextButton({ clip.setText(androidx.compose.ui.text.AnnotatedString(r)); report = null }) { androidx.compose.material3.Text("Copy and close") } },
                        dismissButton = { androidx.compose.material3.TextButton({ report = null }) { androidx.compose.material3.Text("Close") } },
                    )
                }
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
