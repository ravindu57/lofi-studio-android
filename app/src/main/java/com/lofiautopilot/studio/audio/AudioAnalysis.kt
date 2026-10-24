package com.lofiautopilot.studio.audio

import android.content.Context
import android.net.Uri
import com.lofiautopilot.studio.vfx.AudioClip
import kotlin.math.log10
import kotlin.math.pow

class Cancelled : RuntimeException("Cancelled")

class AnalysisResult(
    val features: FeatureTrack,
    /** Measured loudness per clip id (null if unknown). */
    val lufs: Map<String, Double?>,
    val totalFrames: Long,
)

object AudioAnalysis {
    const val TARGET_LUFS = -14.0

    fun gainFor(lufs: Double?, target: Double = TARGET_LUFS): Double {
        if (lufs == null) return 1.0
        val db = (target - lufs).coerceIn(-12.0, 9.0)
        return 10.0.pow(db / 20.0)
    }

    /** Pass 1 over the audio: builds the feature track (spectrum, beats, tempo, envelopes) and measures loudness of clips that lack it. */
    fun run(
        context: Context,
        clips: List<AudioClip>,
        normalize: Boolean = true,
        maxSeconds: Double? = null,
        onProgress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): AnalysisResult {
        val meters = HashMap<String, LoudnessMeter>()
        val need = clips.filter { it.lufs == null }.map { it.id }.toSet()
        val src = TimelineMix(context, clips, normalize, 0.0, maxSeconds ?: Double.MAX_VALUE)
        src.onRaw = { c, pcm, n -> if (c.id in need) meters.getOrPut(c.id) { LoudnessMeter() }.add(pcm, n) }
        val an = FeatureAnalyzer(OUT_RATE, 30)
        val endS = clips.maxOfOrNull { it.endS } ?: 0f
        val totalEstimate = (minOf(endS.toDouble(), maxSeconds ?: Double.MAX_VALUE) * OUT_RATE).toLong().coerceAtLeast(1)
        val block = ShortArray(4096 * 2)
        val mono = FloatArray(4096)
        var done = 0L
        var lastReport = 0f
        try {
            while (true) {
                if (cancelled()) throw Cancelled()
                val n = src.read(block, 4096)
                if (n <= 0) break
                for (i in 0 until n) mono[i] = FeatureAnalyzer.mix(block[i * 2], block[i * 2 + 1])
                an.push(mono, n)
                done += n
                val p = (done.toFloat() / totalEstimate).coerceIn(0f, 1f)
                if (p - lastReport >= 0.01f) { lastReport = p; onProgress(p) }
            }
        } finally {
            src.close()
        }
        val ft = an.finish()
        ft.marks = clips.sortedBy { it.startS }.map { TrackMark(it.startS, it.title) }
        val lufs = clips.associate { it.id to (if (it.id in need) meters[it.id]?.lufs() else it.lufs) }
        onProgress(1f)
        return AnalysisResult(ft, lufs, done)
    }

    /** Measures loudness of a track from a short excerpt (used when importing music; cheap). */
    fun quickLufs(context: Context, uri: Uri, seconds: Double = 75.0): Double? {
        return try {
            PcmDecoder(context, uri).use { dec ->
                val meter = LoudnessMeter()
                val buf = ShortArray(4096 * 2)
                // skip the first 10% (intros are often quiet) by reading and discarding
                val skip = (dec.durationUs / 1_000_000.0 * 0.15).coerceAtMost(60.0)
                var skipFrames = (skip * OUT_RATE).toLong()
                while (skipFrames > 0) {
                    val n = dec.read(buf, minOf(4096L, skipFrames).toInt())
                    if (n <= 0) break
                    skipFrames -= n
                }
                var frames = (seconds * OUT_RATE).toLong()
                while (frames > 0) {
                    val n = dec.read(buf, minOf(4096L, frames).toInt())
                    if (n <= 0) break
                    meter.add(buf, n)
                    frames -= n
                }
                meter.lufs()
            }
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("unused")
    private fun db(x: Double) = 20 * log10(x)
}
