package com.lofiautopilot.studio.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object JobStatus {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val WAITING = "waiting"
    const val PAUSED = "paused"
    const val FAILED = "failed"
    const val DONE = "done"
    const val CANCELLED = "cancelled"
    val active = listOf(QUEUED, RUNNING, WAITING, PAUSED)
}

object Stage {
    const val PLAN = "plan"
    const val ART = "art"
    const val RENDER = "render"
    const val THUMB = "thumbnail"
    const val PUBLISH = "publish"
    val order = listOf(PLAN, ART, RENDER, THUMB, PUBLISH)
    fun label(s: String) = when (s) {
        PLAN -> "Planning"; ART -> "Choosing artwork"; RENDER -> "Rendering video"
        THUMB -> "Making thumbnail"; PUBLISH -> "Publishing"; else -> s
    }
}

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val source: String = "autopilot",          // autopilot | manual | studio
    val status: String = JobStatus.QUEUED,
    val stage: String = Stage.PLAN,
    val progress: Float = 0f,
    val phase: String = "",                    // human text for what is happening right now
    val title: String = "",
    val description: String = "",
    val tags: String = "",
    val mood: String = "",
    val planJson: String = "",
    val lookJson: String = "",
    val imageUri: String = "",                 // artwork chosen for this video
    val videoUri: String = "",                 // optional source clip (Studio)
    val outPath: String = "",
    val thumbPath: String = "",
    val durationS: Double = 0.0,
    val error: String = "",
    val hint: String = "",
    val attempts: Int = 0,
    val publishAt: Long = 0,                   // 0 = as soon as ready
    val pauseRequested: Boolean = false,
    val nextAttemptAt: Long = 0,
    val finishedAt: Long = 0,
    val ytUrl: String = "",
    val ytStatus: String = "",
    val fbUrl: String = "",
    val savedToGallery: String = "",
    val settingsJson: String = "",             // export settings snapshot (size, fps, ...)
)

@Entity(tableName = "tracks", indices = [Index(value = ["uri"], unique = true)])
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val title: String,
    val durationMs: Long = 0,
    val moods: String = "",
    val bpm: Float = 0f,
    val lufs: Double? = null,
    val usedCount: Int = 0,
    val lastUsedAt: Long = 0,
    val present: Boolean = true,
    val enabled: Boolean = true,
)

@Entity(tableName = "images", indices = [Index(value = ["uri"], unique = true)])
data class ImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val name: String,
    val modified: Long = 0,
    val size: Long = 0,
    val firstSeen: Long = System.currentTimeMillis(),
    /** new = not used yet, used = used for a video, ignored = user dismissed */
    val state: String = "new",
    val usedCount: Int = 0,
    val lastUsedAt: Long = 0,
    val present: Boolean = true,
)

@Entity(tableName = "logs")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long = System.currentTimeMillis(),
    val jobId: Long = 0,
    val level: String = "info",
    val text: String,
)

@Entity(tableName = "looks")
data class LookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val json: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "kv")
data class KvEntity(@PrimaryKey val k: String, val v: String)

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val json: String,
    val updatedAt: Long,
    val durationS: Float,
)
