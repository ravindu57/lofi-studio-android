package com.lofiautopilot.studio.auto

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lofiautopilot.studio.core.Graph
import java.util.concurrent.TimeUnit

/** Every 15 minutes: if Autopilot should be running but the service was killed (battery savers do this), try to revive it. */
class KeepAliveWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val g = Graph.get(applicationContext)
        if (g.settings.value.autopilot && !RenderService.running) {
            val ok = RenderService.start(applicationContext)
            if (!ok) g.notifier.needsAttention("Autopilot was stopped by the phone", "Tap to resume. Allow background activity for SE in battery settings to keep it running.")
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("keepalive", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
