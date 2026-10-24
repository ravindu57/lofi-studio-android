package com.lofiautopilot.studio.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/** ITU-R BS.1770 style integrated loudness (K-weighted, gated), computed on 16-bit stereo at 44.1 kHz. */
class LoudnessMeter(private val sampleRate: Int = OUT_RATE) {
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        var z1 = 0.0; var z2 = 0.0
        fun p(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    private fun shelf(): Biquad {
        val f0 = 1681.974450955533; val g = 3.999843853973347; val q = 0.7071752369554196
        val k = tan(PI * f0 / sampleRate); val vh = 10.0.pow(g / 20.0); val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + k / q + k * k
        return Biquad((vh + vb * k / q + k * k) / a0, 2.0 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
            2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
    }

    private fun highpass(): Biquad {
        val f0 = 38.13547087602444; val q = 0.5003270373238773
        val k = tan(PI * f0 / sampleRate)
        val a0 = 1.0 + k / q + k * k
        return Biquad(1.0, -2.0, 1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
    }

    private val sL = shelf(); private val hL = highpass()
    private val sR = shelf(); private val hR = highpass()
    private val blockLen = sampleRate / 10 // 100 ms sub-blocks
    private var acc = 0.0
    private var cnt = 0
    private val blocks = ArrayList<Double>()

    fun add(pcm: ShortArray, frames: Int) {
        for (i in 0 until frames) {
            val l = hL.p(sL.p(pcm[i * 2] / 32768.0))
            val r = hR.p(sR.p(pcm[i * 2 + 1] / 32768.0))
            acc += l * l + r * r
            if (++cnt >= blockLen) { blocks.add(acc / cnt); acc = 0.0; cnt = 0 }
        }
    }

    /** Integrated loudness in LUFS, or null if there was too little audio. */
    fun lufs(): Double? {
        if (blocks.size < 4) return null
        // 400 ms windows (4 sub-blocks), 75% overlap
        val win = ArrayList<Double>()
        for (i in 0..blocks.size - 4) win.add((blocks[i] + blocks[i + 1] + blocks[i + 2] + blocks[i + 3]) / 4.0)
        fun l(ms: Double) = -0.691 + 10.0 * log10(ms.coerceAtLeast(1e-12))
        val abs = win.filter { l(it) > -70.0 }
        if (abs.isEmpty()) return null
        val rel = l(abs.average()) - 10.0
        val gated = abs.filter { l(it) > rel }
        if (gated.isEmpty()) return null
        return l(gated.average())
    }
}
