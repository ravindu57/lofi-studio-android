package com.lofiautopilot.studio.audio

import kotlin.math.floor

const val BANDS = 64
const val WAVE_N = 64

/** What the shaders see at one instant. */
class FrameFeatures {
    val spec = ByteArray(BANDS)
    val wave = ByteArray(WAVE_N) { 127.toByte() }
    var bass = 0f; var mid = 0f; var high = 0f; var energy = 0f
    var beat = 0f; var phase = 0f
    /** Director curve at this instant (1 = neutral). */
    var direction = 1f
}

data class TrackMark(val startS: Float, val title: String)

/** Per-frame music description for a whole mix, produced once by [FeatureAnalyzer] and then replayed by the renderer. */
class FeatureTrack(
    val fps: Int,
    val frames: Int,
    val spec: ByteArray,
    val wave: ByteArray,
    val bass: FloatArray,
    val mid: FloatArray,
    val high: FloatArray,
    val energy: FloatArray,
    val flux: FloatArray,
    val centroid: FloatArray,
    val beat: FloatArray,
    val phase: FloatArray,
    val beatTimes: FloatArray,
    val bpm: Float,
    val direction: FloatArray = FloatArray(frames) { 1f },
    var marks: List<TrackMark> = emptyList(),
) {
    val durationS: Float get() = frames.toFloat() / fps

    /** Fills [out] with the interpolated features at time [t] seconds. */
    fun at(t: Float, out: FrameFeatures) {
        if (frames == 0) return
        val x = (t * fps).coerceIn(0f, (frames - 1).toFloat())
        val i = floor(x).toInt()
        val j = minOf(i + 1, frames - 1)
        val f = x - i
        for (b in 0 until BANDS) {
            val a = spec[i * BANDS + b].toInt() and 0xFF
            val c = spec[j * BANDS + b].toInt() and 0xFF
            out.spec[b] = (a + (c - a) * f).toInt().toByte()
        }
        for (b in 0 until WAVE_N) {
            val a = wave[i * WAVE_N + b].toInt() and 0xFF
            val c = wave[j * WAVE_N + b].toInt() and 0xFF
            out.wave[b] = (a + (c - a) * f).toInt().toByte()
        }
        out.bass = bass[i] + (bass[j] - bass[i]) * f
        out.mid = mid[i] + (mid[j] - mid[i]) * f
        out.high = high[i] + (high[j] - high[i]) * f
        out.energy = energy[i] + (energy[j] - energy[i]) * f
        out.beat = beat[i] + (beat[j] - beat[i]) * f
        out.phase = phase[i]
        out.direction = direction[i] + (direction[j] - direction[i]) * f
    }

    fun titleAt(t: Float): String = marks.lastOrNull { it.startS <= t }?.title ?: ""
}
