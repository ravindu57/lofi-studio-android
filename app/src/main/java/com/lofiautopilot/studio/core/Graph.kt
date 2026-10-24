package com.lofiautopilot.studio.core

import android.content.Context
import androidx.room.Room
import com.lofiautopilot.studio.data.AppDb
import com.lofiautopilot.studio.data.LogEntity
import com.lofiautopilot.studio.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Tiny service locator: one place that owns the database, settings, secrets and shared clients. */
class Graph(val app: Context) {
    val db: AppDb = Room.databaseBuilder(app, AppDb::class.java, "lofi.db")
        .addMigrations(object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `projects` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `json` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `durationS` REAL NOT NULL, PRIMARY KEY(`id`))")
            }
        })
        .addMigrations(object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `jobs` ADD COLUMN `finishedAt` INTEGER NOT NULL DEFAULT 0")
            }
        })
        .fallbackToDestructiveMigration().build()
    val settings = Settings(app)
    val secrets = SecretStore(app)
    /** The editor session that is open right now (so the AI assistant can work on the same project). */
    @Volatile var editor: com.lofiautopilot.studio.ui.editor.EditorModel? = null
    val assistant by lazy { com.lofiautopilot.studio.ai.Assistant(this) }
    val notifier = Notifier(app, this)
    val engine by lazy { com.lofiautopilot.studio.auto.Engine(app, this) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Writes a line to the in-app activity log. */
    fun log(text: String, jobId: Long = 0, level: String = "info") {
        android.util.Log.i("Lofi", "[$jobId] $text")
        scope.launch {
            db.logs().insert(LogEntity(jobId = jobId, level = level, text = text))
            db.logs().trim()
        }
    }

    companion object {
        @Volatile private var inst: Graph? = null
        fun get(ctx: Context): Graph = inst ?: synchronized(this) {
            inst ?: Graph(ctx.applicationContext).also { inst = it }
        }
    }
}
