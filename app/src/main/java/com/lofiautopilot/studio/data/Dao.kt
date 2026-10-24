package com.lofiautopilot.studio.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface JobDao {
    @Insert suspend fun insert(j: JobEntity): Long
    @Update suspend fun update(j: JobEntity)
    @Query("SELECT * FROM jobs WHERE id = :id") suspend fun get(id: Long): JobEntity?
    @Query("SELECT * FROM jobs WHERE id = :id") fun watch(id: Long): Flow<JobEntity?>
    @Query("SELECT * FROM jobs ORDER BY id DESC LIMIT :n") fun recent(n: Int): Flow<List<JobEntity>>
    @Query("SELECT * FROM jobs ORDER BY id DESC LIMIT :n") suspend fun recentNow(n: Int): List<JobEntity>
    @Query("SELECT * FROM jobs WHERE status IN ('queued','running','waiting') ORDER BY id ASC") suspend fun runnable(): List<JobEntity>
    @Query("SELECT COUNT(*) FROM jobs WHERE status IN ('queued','running','waiting','paused')") fun activeCount(): Flow<Int>
    @Query("SELECT * FROM jobs WHERE status = 'done' ORDER BY id DESC LIMIT :n") fun done(n: Int): Flow<List<JobEntity>>
    @Query("DELETE FROM jobs WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE jobs SET status='queued', phase='Restarting after the app stopped' WHERE status='running'") suspend fun requeueRunning()
    @Query("SELECT COUNT(*) FROM jobs WHERE source='autopilot' AND createdAt >= :since AND status != 'cancelled'") suspend fun autopilotSince(since: Long): Int
}

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(t: TrackEntity): Long
    @Update suspend fun update(t: TrackEntity)
    @Query("SELECT * FROM tracks WHERE present = 1 ORDER BY title") fun all(): Flow<List<TrackEntity>>
    @Query("SELECT * FROM tracks WHERE present = 1 AND enabled = 1 AND durationMs > 0") suspend fun usable(): List<TrackEntity>
    @Query("SELECT * FROM tracks WHERE uri = :uri") suspend fun byUri(uri: String): TrackEntity?
    @Query("SELECT * FROM tracks WHERE id = :id") suspend fun get(id: Long): TrackEntity?
    @Query("SELECT * FROM tracks") suspend fun allNow(): List<TrackEntity>
    @Query("UPDATE tracks SET present = 0 WHERE uri NOT IN (:keep)") suspend fun markMissing(keep: List<String>)
    @Query("DELETE FROM tracks WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT COUNT(*) FROM tracks WHERE present = 1") fun count(): Flow<Int>
}

@Dao
interface ImageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(i: ImageEntity): Long
    @Update suspend fun update(i: ImageEntity)
    @Query("SELECT * FROM images WHERE present = 1 ORDER BY modified DESC") fun all(): Flow<List<ImageEntity>>
    @Query("SELECT * FROM images WHERE present = 1 AND state = 'new' ORDER BY modified DESC") suspend fun newOnes(): List<ImageEntity>
    @Query("SELECT * FROM images WHERE present = 1 AND state != 'ignored' ORDER BY usedCount ASC, lastUsedAt ASC") suspend fun candidates(): List<ImageEntity>
    @Query("SELECT * FROM images WHERE uri = :uri") suspend fun byUri(uri: String): ImageEntity?
    @Query("SELECT * FROM images") suspend fun allNow(): List<ImageEntity>
    @Query("UPDATE images SET present = 0 WHERE uri NOT IN (:keep)") suspend fun markMissing(keep: List<String>)
    @Query("SELECT COUNT(*) FROM images WHERE present = 1 AND state = 'new'") fun newCount(): Flow<Int>
    @Query("UPDATE images SET state = 'new', usedCount = 0 WHERE present = 1") suspend fun resetAll()
}

@Dao
interface LogDao {
    @Insert suspend fun insert(l: LogEntity)
    @Query("SELECT * FROM logs ORDER BY id DESC LIMIT :n") fun recent(n: Int): Flow<List<LogEntity>>
    @Query("SELECT * FROM logs ORDER BY id DESC LIMIT :n") suspend fun recentNow(n: Int): List<LogEntity>
    @Query("SELECT * FROM logs WHERE jobId = :jobId ORDER BY id DESC LIMIT :n") suspend fun forJob(jobId: Long, n: Int): List<LogEntity>
    @Query("DELETE FROM logs WHERE id < (SELECT MAX(id) - 1500 FROM logs)") suspend fun trim()
}

@Dao
interface LookDao {
    @Insert suspend fun insert(l: LookEntity): Long
    @Update suspend fun update(l: LookEntity)
    @Query("SELECT * FROM looks ORDER BY createdAt DESC") fun all(): Flow<List<LookEntity>>
    @Query("SELECT * FROM looks ORDER BY createdAt DESC") suspend fun allNow(): List<LookEntity>
    @Query("SELECT * FROM looks WHERE name = :name LIMIT 1") suspend fun byName(name: String): LookEntity?
    @Delete suspend fun delete(l: LookEntity)
}

@Dao
interface KvDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(e: KvEntity)
    @Query("SELECT v FROM kv WHERE k = :key") suspend fun get(key: String): String?
}

@Dao
interface ProjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(p: ProjectEntity)
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC") fun all(): Flow<List<ProjectEntity>>
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC") suspend fun allNow(): List<ProjectEntity>
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun get(id: String): ProjectEntity?
    @Query("DELETE FROM projects WHERE id = :id") suspend fun delete(id: String)
}

@Database(
    entities = [JobEntity::class, TrackEntity::class, ImageEntity::class, LogEntity::class, LookEntity::class, KvEntity::class, ProjectEntity::class],
    version = 3, exportSchema = true,
)
abstract class AppDb : RoomDatabase() {
    abstract fun jobs(): JobDao
    abstract fun tracks(): TrackDao
    abstract fun images(): ImageDao
    abstract fun logs(): LogDao
    abstract fun looks(): LookDao
    abstract fun kv(): KvDao
    abstract fun projects(): ProjectDao
}
