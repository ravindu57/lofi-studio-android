package com.lofiautopilot.studio.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.io.File

/** Saves the last crash so the next launch can show it, since there is no cable or logcat on the phone. */
object CrashReport {
    private fun file(c: Context) = File(c.filesDir, "last_crash.txt")

    fun install(c: Context) {
        val app = c.applicationContext
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val text = "Thread ${t.name}\n" + e.stackTraceToString().take(6000)
                file(app).writeText(text)
                app.getExternalFilesDir(null)?.let { File(it, "last_crash.txt").writeText(text) }
            }
            old?.uncaughtException(t, e)
        }
    }

    /** The saved crash, or how the process last died (native crash, out of memory, hang), if either happened. */
    fun pending(c: Context): String? {
        val f = file(c)
        if (f.exists()) return f.readText().also { f.delete() }
        return runCatching {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val bad = am.getHistoricalProcessExitReasons(c.packageName, 0, 3).firstOrNull {
                it.reason in setOf(ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, ApplicationExitInfo.REASON_SIGNALED) &&
                    System.currentTimeMillis() - it.timestamp < 6 * 3600_000L
            } ?: return null
            val seen = c.getSharedPreferences("crash", Context.MODE_PRIVATE)
            if (seen.getLong("exit", 0L) == bad.timestamp) return null
            seen.edit().putLong("exit", bad.timestamp).apply()
            "Process ended: reason=${bad.reason} status=${bad.status} ${bad.description ?: ""}\n" + (runCatching { bad.traceInputStream?.bufferedReader()?.readText()?.take(3000) }.getOrNull() ?: "")
        }.getOrNull()
    }
}
