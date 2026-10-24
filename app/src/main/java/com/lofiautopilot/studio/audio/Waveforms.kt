package com.lofiautopilot.studio.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/** Peak envelopes of audio files for drawing waveforms on the timeline (25 peaks per second, cached in memory and on disk). */
class Waveforms(private val ctx: Context) {
    companion object { const val PER_SEC = 25 }

    private val mem = ConcurrentHashMap<String, FloatArray>()
    private val gate = Semaphore(1)

    fun cached(uri: String): FloatArray? = mem[uri]

    suspend fun peaks(uri: String): FloatArray? = withContext(Dispatchers.IO) {
        mem[uri]?.let { return@withContext it }
        val f = File(File(ctx.cacheDir, "wave").apply { mkdirs() }, uri.hashCode().toUInt().toString(16) + ".pk")
        if (f.exists() && f.length() > 8) {
            runCatching {
                val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(b.remaining() / 4).also { b.asFloatBuffer().get(it) }
            }.getOrNull()?.let { mem[uri] = it; return@withContext it }
        }
        gate.withPermit {
            mem[uri]?.let { return@withPermit it }
            try {
                PcmDecoder(ctx, Uri.parse(uri)).use { dec ->
                    val block = OUT_RATE / PER_SEC
                    val buf = ShortArray(block * 2 * 8)
                    val out = ArrayList<Float>()
                    var pk = 0
                    var cnt = 0
                    while (true) {
                        val n = dec.read(buf, buf.size / 2)
                        if (n <= 0) break
                        for (i in 0 until n) {
                            pk = max(pk, max(abs(buf[i * 2].toInt()), abs(buf[i * 2 + 1].toInt())))
                            if (++cnt >= block) { out.add(pk / 32768f); pk = 0; cnt = 0 }
                        }
                    }
                    val arr = out.toFloatArray()
                    mem[uri] = arr
                    val bb = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    bb.asFloatBuffer().put(arr)
                    runCatching { f.writeBytes(bb.array()) }
                    arr
                }
            } catch (e: Exception) { null }
        }
    }
}
