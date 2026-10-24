package com.lofiautopilot.studio.auto

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lofiautopilot.studio.core.Graph
import com.lofiautopilot.studio.data.JobEntity
import com.lofiautopilot.studio.data.Stage
import com.lofiautopilot.studio.notify.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that renders and publishes videos while the screen is off. It stays alive while Autopilot is on
 * (idle: a quiet notification; busy: a progress notification) and stops itself when there is nothing left to do.
 */
class RenderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private lateinit var g: Graph

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        g = Graph.get(this)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> scope.launch { val id = g.engine.currentJobId; if (id > 0) g.engine.setStatus(id, "pause") }
            ACTION_CANCEL -> scope.launch { val id = g.engine.currentJobId; if (id > 0) g.engine.setStatus(id, "cancel") }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        val n = g.notifier.serviceNotification("SE", idleText())
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(Notifier.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(Notifier.ID_SERVICE, n)
        } catch (e: Exception) {
            g.log("Couldn't start the background service: ${e.message}", level = "error")
            stopSelf(); return START_NOT_STICKY
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    private fun idleText(): String {
        val s = g.settings.value
        return if (s.autopilot) "Autopilot is on · ${s.videosPerDay}/day around ${s.publishTime}" else "Working…"
    }

    private fun actions(): List<NotificationCompat.Action> {
        fun pi(a: String) = PendingIntent.getService(this, a.hashCode(), Intent(this, RenderService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        return listOf(
            NotificationCompat.Action(0, "Pause", pi(ACTION_PAUSE)),
            NotificationCompat.Action(0, "Cancel", pi(ACTION_CANCEL)),
        )
    }

    private fun show(j: JobEntity) {
        val title = "Video ${"%04d".format(j.id)} · ${Stage.label(j.stage)}"
        val n = g.notifier.serviceNotification(title, j.phase.ifBlank { j.title }, j.progress, actions())
        g.notifier.updateService(n)
    }

    private suspend fun run() {
        val engine = g.engine
        engine.onProgress = { j -> if (g.settings.value.notifyProgress) show(j) }
        try {
            while (true) {
                runCatching { engine.tick() }
                val ranSomething = if (engine.hasWork()) {
                    acquire()
                    try { engine.runNext() } finally { release() }
                } else false
                if (!ranSomething) {
                    val s = g.settings.value
                    val pending = g.db.jobs().runnable().isNotEmpty()
                    if (!s.autopilot && !pending) { stopSelf(); return }
                    val next = g.db.jobs().runnable().filter { it.publishAt > System.currentTimeMillis() }.minByOrNull { it.publishAt }
                    val text = if (next != null) "Next: “${next.title.ifBlank { "video" }}” at ${SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(next.publishAt))}" else idleText()
                    g.notifier.updateService(g.notifier.serviceNotification("SE · Autopilot", text))
                    runCatching { engine.cleanup() }
                    delay(30_000)
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    private fun acquire() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lofi:render").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
    }

    private fun release() { runCatching { if (wake?.isHeld == true) wake?.release() }; wake = null }

    override fun onDestroy() {
        running = false
        release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PAUSE = "lofi.PAUSE"
        const val ACTION_CANCEL = "lofi.CANCEL"
        const val ACTION_STOP = "lofi.STOP"
        @Volatile var running = false

        /** Must be called while the app is visible (a user action), or Android refuses to start it. */
        fun start(ctx: Context): Boolean = try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RenderService::class.java)); true
        } catch (e: Exception) { false }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, RenderService::class.java)) }
    }
}
