package com.lofiautopilot.studio

import android.app.Application
import com.lofiautopilot.studio.core.Graph
import kotlinx.coroutines.launch

class LofiApp : Application() {
    val graph: Graph by lazy { Graph.get(this) }

    override fun onCreate() {
        super.onCreate()
        com.lofiautopilot.studio.core.CrashReport.install(this)
        graph.notifier.createChannels()
        com.lofiautopilot.studio.auto.KeepAliveWorker.schedule(this)
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { graph.db.jobs().requeueRunning() }
    }
}
