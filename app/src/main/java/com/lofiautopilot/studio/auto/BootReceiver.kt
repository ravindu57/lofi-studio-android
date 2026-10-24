package com.lofiautopilot.studio.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lofiautopilot.studio.core.Graph

/** Android doesn't let a data-sync service start straight from boot, so we ask the user to tap once. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = Graph.get(context)
        if (g.settings.value.autopilot) {
            g.notifier.needsAttention("Autopilot is paused", "The phone restarted. Tap to open AIM3 and resume.")
        }
    }
}
