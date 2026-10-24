package com.lofiautopilot.studio.audio

import android.content.Context
import android.net.Uri
import java.io.Closeable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

data class MixTrack(val uri: Uri, val title: String, val durationUs: Long)

/**
 * Plays a list of tracks back to back with equal-power crossfades and per-track gain, as 16-bit stereo at 44.1 kHz.
 * Deterministic: reading it twice gives the same samples, so analysis and rendering line up exactly.
 */
class MixSource(
    private val context: Context,
    private val tracks: List<MixTrack>,
    crossfadeS: Double,
    private val gains: DoubleArray? = null,
    private val maxFrames: Long = Long.MAX_VALUE,
) : AudioSource {

    private class Active(val idx: Int, val dec: PcmDecoder, val declared: Long) {
        var pos = 0L
        var eof = false
    }

    private var nextIdx = 0
    private var cur: Active? = null
    private var next: Active? = null
    private val xf: Long = (crossfadeS * OUT_RATE).toLong().coerceAtLeast(0)
    private val a = ShortArray(2048 * 2)
    private val b = ShortArray(2048 * 2)
    var framesOut = 0L
        private set

    /** Start time (seconds) of every track as it actually played. */
    val starts = ArrayList<Pair<Int, Double>>()
    var onRaw: ((track: Int, pcm: ShortArray, frames: Int) -> Unit)? = null
    var failed: String? = null
        private set

    private fun open(): Active? {
        while (nextIdx < tracks.size) {
            val i = nextIdx++
            try {
                val t = tracks[i]
                val dec = PcmDecoder(context, t.uri)
                val declared = (if (dec.durationUs > 0) dec.durationUs else t.durationUs) * OUT_RATE / 1_000_000L
                return Active(i, dec, if (declared > 0) declared else 4L * 60 * OUT_RATE)
            } catch (e: AudioDecodeException) {
                failed = e.message // skip unreadable tracks but remember why
            }
        }
        return null
    }

    private fun fadeLen(t: Active): Long = minOf(xf, t.declared / 3)

    private fun gain(i: Int): Float = (gains?.getOrNull(i) ?: 1.0).toFloat()

    override fun read(dst: ShortArray, frames: Int): Int {
        var produced = 0
        while (produced < frames && framesOut < maxFrames) {
            if (cur == null) {
                cur = next ?: open()
                next = null
                val c = cur ?: break
                starts.add(c.idx to framesOut.toDouble() / OUT_RATE)
            }
            val c = cur!!
            val fl = fadeLen(c)
            val fadeStart = c.declared - fl
            if (next == null && c.pos >= fadeStart && nextIdx < tracks.size && fl > 0) {
                next = open()
                if (next != null) starts.add(next!!.idx to (framesOut.toDouble() / OUT_RATE))
            }
            val want = minOf(2048, frames - produced).toLong().coerceAtMost(maxFrames - framesOut).toInt()
            val n1 = if (c.eof) 0 else c.dec.read(a, want)
            if (n1 > 0) onRaw?.invoke(c.idx, a, n1)
            if (n1 == 0) {
                c.eof = true
                c.dec.close()
                val nx = next
                if (nx != null) {
                    cur = nx; next = null
                } else {
                    cur = null
                }
                continue
            }
            val nx = next
            if (nx != null) {
                val n2 = nx.dec.read(b, n1)
                if (n2 > 0) onRaw?.invoke(nx.idx, b, n2)
                for (k in 0 until n1) {
                    val pos = c.pos + k
                    val w = ((pos - fadeStart).toDouble() / fl).coerceIn(0.0, 1.0)
                    val ga = cos(w * PI / 2).toFloat() * gain(c.idx)
                    val gb = sin(w * PI / 2).toFloat() * gain(nx.idx)
                    val l = a[k * 2] * ga + (if (k < n2) b[k * 2] * gb else 0f)
                    val r = a[k * 2 + 1] * ga + (if (k < n2) b[k * 2 + 1] * gb else 0f)
                    dst[(produced + k) * 2] = limit(l)
                    dst[(produced + k) * 2 + 1] = limit(r)
                }
                nx.pos += n2
                c.pos += n1
                if (c.pos >= c.declared) {
                    c.dec.close()
                    cur = nx; next = null
                }
            } else {
                val g = gain(c.idx)
                for (k in 0 until n1) {
                    dst[(produced + k) * 2] = limit(a[k * 2] * g)
                    dst[(produced + k) * 2 + 1] = limit(a[k * 2 + 1] * g)
                }
                c.pos += n1
            }
            produced += n1
            framesOut += n1
        }
        return produced
    }

    /** Soft limiter: transparent below ~-3 dBFS, rounds off peaks above it. */
    private fun limit(x: Float): Short {
        val ax = abs(x)
        val knee = 23000f
        val y = if (ax <= knee) x else {
            val over = (ax - knee) / (32767f - knee)
            val s = knee + (32767f - knee) * tanh(over.toDouble()).toFloat()
            if (x < 0) -s else s
        }
        return y.toInt().coerceIn(-32768, 32767).toShort()
    }

    override fun close() {
        cur?.dec?.close(); next?.dec?.close(); cur = null; next = null
    }
}
