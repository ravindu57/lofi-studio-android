package com.lofiautopilot.studio.audio

import android.content.Context
import com.lofiautopilot.studio.vfx.AudioClip
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Remembers what "listening" to a mix found (spectrum, beats, tempo, loudness) on disk, so reopening a project or
 * exporting it again does not decode every song a second time. The key covers everything that changes the sound.
 */
object AnalysisCache {
    private const val MAGIC = 0x53454331
    private const val MAX_FILES = 40
    private const val MAX_BYTES = 400L * 1024 * 1024

    private fun dir(c: Context) = File(c.filesDir, "analysis").apply { mkdirs() }

    fun key(clips: List<AudioClip>, normalize: Boolean): String {
        val sb = StringBuilder("v1|").append(normalize).append('|').append(OUT_RATE)
        clips.sortedBy { it.startS }.forEach { k ->
            sb.append('|').append(k.uri).append(',').append(k.srcDurS).append(',').append(k.startS).append(',').append(k.inS).append(',').append(k.lenS)
                .append(',').append(k.gain).append(',').append(k.fadeInS).append(',').append(k.fadeOutS).append(',').append(k.lufs).append(',').append(k.track)
                .append(',').append(k.eq.joinToString("/"))
        }
        return MessageDigest.getInstance("SHA-1").digest(sb.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun load(c: Context, clips: List<AudioClip>, normalize: Boolean): AnalysisResult? = runCatching {
        val f = File(dir(c), key(clips, normalize) + ".bin")
        if (!f.exists()) return null
        DataInputStream(GZIPInputStream(f.inputStream().buffered(1 shl 16))).use { i ->
            if (i.readInt() != MAGIC) return null
            val fps = i.readInt(); val frames = i.readInt(); val bpm = i.readFloat(); val total = i.readLong()
            fun bytes(n: Int) = ByteArray(n).also { i.readFully(it) }
            fun floats(n: Int): FloatArray {
                val raw = bytes(n * 4)
                return FloatArray(n).also { ByteBuffer.wrap(raw).asFloatBuffer().get(it) }
            }
            val spec = bytes(frames * BANDS); val wave = bytes(frames * WAVE_N)
            val bass = floats(frames); val mid = floats(frames); val high = floats(frames); val energy = floats(frames)
            val flux = floats(frames); val centroid = floats(frames); val beat = floats(frames); val phase = floats(frames)
            val bt = floats(i.readInt())
            val lufs = HashMap<String, Double?>()
            repeat(i.readInt()) { val id = i.readUTF(); lufs[id] = if (i.readBoolean()) i.readDouble() else null }
            val ft = FeatureTrack(fps, frames, spec, wave, bass, mid, high, energy, flux, centroid, beat, phase, bt, bpm)
            ft.marks = clips.sortedBy { it.startS }.map { TrackMark(it.startS, it.title) }
            f.setLastModified(System.currentTimeMillis())
            AnalysisResult(ft, lufs, total)
        }
    }.getOrNull()

    fun save(c: Context, clips: List<AudioClip>, normalize: Boolean, r: AnalysisResult) {
        runCatching {
            val ft = r.features
            val f = File(dir(c), key(clips, normalize) + ".bin")
            val tmp = File(f.path + ".tmp")
            DataOutputStream(GZIPOutputStream(tmp.outputStream().buffered(1 shl 16))).use { o ->
                o.writeInt(MAGIC); o.writeInt(ft.fps); o.writeInt(ft.frames); o.writeFloat(ft.bpm); o.writeLong(r.totalFrames)
                fun floats(a: FloatArray) { val b = ByteBuffer.allocate(a.size * 4); b.asFloatBuffer().put(a); o.write(b.array()) }
                o.write(ft.spec, 0, ft.frames * BANDS); o.write(ft.wave, 0, ft.frames * WAVE_N)
                floats(ft.bass); floats(ft.mid); floats(ft.high); floats(ft.energy); floats(ft.flux); floats(ft.centroid); floats(ft.beat); floats(ft.phase)
                o.writeInt(ft.beatTimes.size); floats(ft.beatTimes)
                o.writeInt(r.lufs.size)
                r.lufs.forEach { (id, v) -> o.writeUTF(id); o.writeBoolean(v != null); if (v != null) o.writeDouble(v) }
            }
            tmp.renameTo(f)
            prune(c)
        }
    }

    /** Keeps the newest few analyses and drops the rest, so the cache never grows without bound. */
    private fun prune(c: Context) {
        val files = dir(c).listFiles { x -> x.name.endsWith(".bin") }?.sortedByDescending { it.lastModified() } ?: return
        var bytes = 0L
        files.forEachIndexed { idx, x ->
            bytes += x.length()
            if (idx >= MAX_FILES || bytes > MAX_BYTES) x.delete()
        }
    }
}
