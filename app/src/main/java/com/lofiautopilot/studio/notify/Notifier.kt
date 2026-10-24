package com.lofiautopilot.studio.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lofiautopilot.studio.MainActivity
import com.lofiautopilot.studio.R
import com.lofiautopilot.studio.core.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Local push notifications, plus optional webhook / Telegram / ntfy for when you are away from the phone. */
class Notifier(private val ctx: Context, private val graph: Graph) {
    companion object {
        const val CH_PROGRESS = "progress"
        const val CH_RESULT = "result"
        const val CH_IMAGES = "images"
        const val CH_AUTOPILOT = "autopilot"
        const val ID_SERVICE = 4001
        private const val ID_RESULT_BASE = 5000
        private const val ID_IMAGES = 4002
        private const val ID_ATTENTION = 4003
    }

    private val nm get() = NotificationManagerCompat.from(ctx)

    fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val m = ctx.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Rendering progress", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while a video is being made"; setShowBadge(false)
        })
        m.createNotificationChannel(NotificationChannel(CH_RESULT, "Video results", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A video is ready, published, or something needs you"
        })
        m.createNotificationChannel(NotificationChannel(CH_IMAGES, "New pictures", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "New pictures found in your watched folder"
        })
        m.createNotificationChannel(NotificationChannel(CH_AUTOPILOT, "Autopilot", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Autopilot is running, and reminders when it needs to be reopened"
        })
    }

    private fun canPost() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun open(jobId: Long = 0): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (jobId > 0) putExtra("jobId", jobId)
        }
        return PendingIntent.getActivity(ctx, jobId.toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The ongoing notification that keeps the render/autopilot service alive. */
    fun serviceNotification(title: String, text: String, progress: Float? = null, actions: List<NotificationCompat.Action> = emptyList()): Notification {
        val b = NotificationCompat.Builder(ctx, if (progress != null) CH_PROGRESS else CH_AUTOPILOT)
            .setSmallIcon(R.drawable.ic_stat_lofi)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (progress != null) b.setProgress(1000, (progress * 1000).toInt().coerceIn(0, 1000), false)
        actions.forEach { b.addAction(it) }
        return b.build()
    }

    fun updateService(n: Notification) {
        if (!canPost()) return
        runCatching { nm.notify(ID_SERVICE, n) }
    }

    fun videoReady(jobId: Long, title: String, published: Boolean) {
        val s = graph.settings.value
        val head = if (published) "Published" else "Video ready"
        push(jobId.toInt() + ID_RESULT_BASE, head, title.ifBlank { "Your new video" }, s.notifyDone, jobId, CH_RESULT)
        remote("${if (published) "✅" else "🎬"} $head: $title")
    }

    fun videoFailed(jobId: Long, title: String, error: String) {
        val s = graph.settings.value
        push(jobId.toInt() + ID_RESULT_BASE, "Video stopped", "${title.ifBlank { "Job $jobId" }}: $error", s.notifyFailed, jobId, CH_RESULT)
        remote("⚠️ Job $jobId stopped: $error")
    }

    fun newImages(count: Int, name: String) {
        val s = graph.settings.value
        val text = if (count == 1) "“$name” was added to your folder and will be used next." else "$count new pictures are ready to use."
        push(ID_IMAGES, "New picture found", text, s.notifyNewImage, 0, CH_IMAGES)
    }

    fun needsAttention(title: String, text: String) {
        push(ID_ATTENTION, title, text, true, 0, CH_AUTOPILOT)
    }

    fun test() {
        push(ID_ATTENTION, "Notifications work", "You'll hear from SE here when a video is ready.", true, 0, CH_RESULT)
        remote("SE test notification. If you can read this, remote notifications work.")
    }

    private fun push(id: Int, title: String, text: String, enabled: Boolean, jobId: Long, channel: String) {
        if (!enabled || !canPost()) return
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_lofi)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open(jobId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { nm.notify(id, n) }
    }

    /** Webhook (Discord-style), Telegram bot and ntfy.sh, for reaching you when the app is closed. */
    fun remote(text: String) {
        val s = graph.settings.value
        val tg = graph.secrets.get(com.lofiautopilot.studio.core.SecretStore.TELEGRAM)
        graph.scope.launch(Dispatchers.IO) {
            runCatching {
                if (s.webhookUrl.isNotBlank()) {
                    val body = JSONObject().put("content", text).toString().toRequestBody("application/json".toMediaType())
                    graph.http.newCall(Request.Builder().url(s.webhookUrl).post(body).build()).execute().close()
                }
                if (tg.isNotBlank() && s.telegramChatId.isNotBlank()) {
                    val body = FormBody.Builder().add("chat_id", s.telegramChatId).add("text", text).build()
                    graph.http.newCall(Request.Builder().url("https://api.telegram.org/bot$tg/sendMessage").post(body).build()).execute().close()
                }
                if (s.ntfyTopic.isNotBlank()) {
                    graph.http.newCall(
                        Request.Builder().url("https://ntfy.sh/${s.ntfyTopic.trim()}")
                            .post(text.toRequestBody("text/plain".toMediaType())).build(),
                    ).execute().close()
                }
            }
        }
    }
}

