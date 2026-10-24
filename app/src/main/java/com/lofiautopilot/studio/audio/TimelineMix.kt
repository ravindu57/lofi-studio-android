package com.lofiautopilot.studio.audio

import android.content.Context
import android.net.Uri
import com.lofiautopilot.studio.vfx.AudioClip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Mixes audio clips placed anywhere on a timeline: trimmed, offset, with gain and equal-power fades, overlapping freely.
 * Deterministic, so analysis, preview and export all hear the same thing. Starts at [fromS] on the timeline.
 */
class TimelineMix(
    private val context: Context,
    clips: List<AudioClip>,
    private val normalize: Boolean = true,
    private val fromS: Double = 0.0,
    private val untilS: Double = Double.MAX_VALUE,
) : AudioSource {

    private class Active(val clip: AudioClip, val dec: PcmDecoder, var nextFrame: Long, val eq: ClipEq?)

    private val sorted = clips.filter { it.lenS > 0.02f }.sortedBy { it.startS }
    private val active = HashMap<String, Active>()
    private val tmp = ShortArray(2048 * 2)
    private val acc = FloatArray(2048 * 2)
    private val eqBuf = FloatArray(2048 * 2)
    private var pos: Long = (fromS * OUT_RATE).toLong()
    private val end: Long = if (untilS >= Double.MAX_VALUE / 2) Long.MAX_VALUE else (untilS * OUT_RATE).toLong()
    var failed: String? = null
        private set

    /** Timeline position (seconds) of the next sample that will be produced. */
    val positionS: Double get() = pos.toDouble() / OUT_RATE

    /** Called with raw (pre-gain) samples for every clip, used to measure loudness. */
    var onRaw: ((clip: AudioClip, pcm: ShortArray, frames: Int) -> Unit)? = null

    private fun startF(c: AudioClip) = (c.startS * OUT_RATE).toLong()

    private fun clipGain(c: AudioClip): Float {
        var g = c.gain
        if (normalize && c.lufs != null) g *= AudioAnalysis.gainFor(c.lufs).toFloat()
        return g
    }

    private fun open(c: AudioClip, atFrame: Long): Active? {
        return try {
            val dec = PcmDecoder(context, Uri.parse(c.uri))
            val intoClip = max(0L, atFrame - startF(c))
            val srcUs = ((c.inS.toDouble() + intoClip.toDouble() / OUT_RATE) * 1_000_000.0).toLong()
            if (srcUs > 0) dec.seekTo(srcUs)
            Active(c, dec, atFrame, if (ClipEq.isFlat(c.eq)) null else ClipEq(c.eq).takeIf { it.active })
        } catch (e: AudioDecodeException) {
            failed = e.message
            null
        }
    }

    private fun endFrames(c: AudioClip) = ((c.startS + c.lenS) * OUT_RATE).toLong()
    private fun lastEnd(): Long = sorted.maxOfOrNull { endFrames(it) } ?: 0L

    override fun read(dst: ShortArray, frames: Int): Int {
        var produced = 0
        val total = min(lastEnd(), end)
        while (produced < frames && pos < total) {
            val n = min(min(2048, frames - produced).toLong(), total - pos).toInt()
            java.util.Arrays.fill(acc, 0, n * 2, 0f)
            val blockEnd = pos + n
            for (c in sorted) {
                val cs = startF(c); val ce = endFrames(c)
                if (ce <= pos || cs >= blockEnd) { active.remove(c.id)?.dec?.close(); continue }
                var a = active[c.id]
                if (a == null) { a = open(c, max(pos, cs)) ?: continue; active[c.id] = a }
                val from = max(pos, cs)
                val to = min(blockEnd, ce)
                val cnt = (to - from).toInt()
                if (cnt <= 0) continue
                val got = a.dec.read(tmp, cnt)
                if (got > 0) onRaw?.invoke(c, tmp, got)
                val g = clipGain(c)
                val fi = (c.fadeInS * OUT_RATE).toLong().coerceAtLeast(0)
                val fo = (c.fadeOutS * OUT_RATE).toLong().coerceAtLeast(0)
                val off = (from - pos).toInt()
                val eq = a.eq
                if (eq != null) eq.process(tmp, got, eqBuf)
                for (k in 0 until got) {
                    val f = from + k
                    var w = g
                    if (fi > 0 && f - cs < fi) w *= sin(((f - cs).toDouble() / fi) * PI / 2).toFloat()
                    if (fo > 0 && ce - f < fo) w *= sin(((ce - f).toDouble() / fo) * PI / 2).toFloat()
                    acc[(off + k) * 2] += (if (eq != null) eqBuf[k * 2] else tmp[k * 2].toFloat()) * w
                    acc[(off + k) * 2 + 1] += (if (eq != null) eqBuf[k * 2 + 1] else tmp[k * 2 + 1].toFloat()) * w
                }
            }
            for (k in 0 until n * 2) dst[produced * 2 + k] = limit(acc[k])
            pos += n
            produced += n
        }
        return produced
    }

    private fun limit(x: Float): Short {
        val ax = abs(x)
        val knee = 23000f
        val y = if (ax <= knee) x else {
            val s = knee + (32767f - knee) * tanh(((ax - knee) / (32767f - knee)).toDouble()).toFloat()
            if (x < 0) -s else s
        }
        return y.toInt().coerceIn(-32768, 32767).toShort()
    }

    override fun close() {
        active.values.forEach { runCatching { it.dec.close() } }
        active.clear()
    }

}
