package com.lofiautopilot.studio.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Five-band equaliser (low shelf, three peaking bands, high shelf) for one clip. Gains are in dB, ±12. */
class ClipEq(gainsDb: List<Float>) {
    companion object {
        val FREQS = floatArrayOf(60f, 230f, 910f, 3600f, 12000f)
        val LABELS = listOf("60", "230", "910", "3.6k", "12k")
        val PRESETS: List<Pair<String, List<Float>>> = listOf(
            "Flat" to listOf(0f, 0f, 0f, 0f, 0f),
            "Bass boost" to listOf(6f, 3f, 0f, 0f, 0f),
            "Warm lofi" to listOf(3f, 2f, 0f, -3f, -8f),
            "Muffled tape" to listOf(2f, 1f, -1f, -6f, -12f),
            "Slowed night" to listOf(4f, 2f, -1f, -2f, -4f),
            "Vocal" to listOf(-3f, -1f, 3f, 4f, 1f),
            "Bright" to listOf(-1f, 0f, 0f, 3f, 6f),
            "Loudness" to listOf(5f, 2f, -1f, 2f, 5f),
        )
        fun isFlat(g: List<Float>) = g.isEmpty() || g.all { kotlin.math.abs(it) < 0.05f }
    }

    private class Band(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        var l1 = 0.0; var l2 = 0.0; var r1 = 0.0; var r2 = 0.0
        fun left(x: Double): Double { val y = b0 * x + l1; l1 = b1 * x - a1 * y + l2; l2 = b2 * x - a2 * y; return y }
        fun right(x: Double): Double { val y = b0 * x + r1; r1 = b1 * x - a1 * y + r2; r2 = b2 * x - a2 * y; return y }
    }

    private val bands: List<Band> = (0 until 5).mapNotNull { i ->
        val g = gainsDb.getOrElse(i) { 0f }.coerceIn(-12f, 12f)
        if (kotlin.math.abs(g) < 0.05f) null else design(i, g.toDouble())
    }

    val active: Boolean get() = bands.isNotEmpty()

    private fun design(i: Int, db: Double): Band {
        val fs = OUT_RATE.toDouble()
        val w0 = 2 * PI * FREQS[i] / fs
        val cw = cos(w0); val sw = sin(w0)
        val a = 10.0.pow(db / 40.0)
        val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
        when (i) {
            0 -> { // low shelf
                val alpha = sw / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * alpha
                b0 = a * ((a + 1) - (a - 1) * cw + sa); b1 = 2 * a * ((a - 1) - (a + 1) * cw); b2 = a * ((a + 1) - (a - 1) * cw - sa)
                a0 = (a + 1) + (a - 1) * cw + sa; a1 = -2 * ((a - 1) + (a + 1) * cw); a2 = (a + 1) + (a - 1) * cw - sa
            }
            4 -> { // high shelf
                val alpha = sw / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * alpha
                b0 = a * ((a + 1) + (a - 1) * cw + sa); b1 = -2 * a * ((a - 1) + (a + 1) * cw); b2 = a * ((a + 1) + (a - 1) * cw - sa)
                a0 = (a + 1) - (a - 1) * cw + sa; a1 = 2 * ((a - 1) - (a + 1) * cw); a2 = (a + 1) - (a - 1) * cw - sa
            }
            else -> { // peaking, Q = 0.9
                val alpha = sw / (2 * 0.9)
                b0 = 1 + alpha * a; b1 = -2 * cw; b2 = 1 - alpha * a
                a0 = 1 + alpha / a; a1 = -2 * cw; a2 = 1 - alpha / a
            }
        }
        return Band(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /** Filters [n] stereo frames of [src] into [dst] (float, same scale as 16-bit samples). */
    fun process(src: ShortArray, n: Int, dst: FloatArray) {
        for (k in 0 until n) {
            var l = src[k * 2].toDouble(); var r = src[k * 2 + 1].toDouble()
            for (b in bands) { l = b.left(l); r = b.right(r) }
            dst[k * 2] = l.toFloat(); dst[k * 2 + 1] = r.toFloat()
        }
    }
}
