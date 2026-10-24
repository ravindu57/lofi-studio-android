package com.lofiautopilot.studio.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Streaming music analyser. Feed mono samples; it emits one feature frame per hop (default 30 per second):
 * a 64-band log spectrum, a coarse waveform, bass/mid/treble/loudness envelopes and an onset (flux) signal.
 * Beat picking and tempo are done once at [finish], when the whole signal is known.
 */
class FeatureAnalyzer(private val sampleRate: Int = 44100, val fps: Int = 30) {
    private val n = 2048
    private val hop = sampleRate / fps
    private val fft = Fft(n)
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / (n - 1))).toFloat() }
    private val ring = FloatArray(n)
    private var ringPos = 0
    private var sinceHop = 0
    private var filled = 0
    private val re = FloatArray(n)
    private val im = FloatArray(n)

    private val bandLo = IntArray(BANDS)
    private val bandHi = IntArray(BANDS)
    private val bandFrac = FloatArray(BANDS)
    private val bandCentre = FloatArray(BANDS)

    private var specBuf = ByteArray(1 shl 20)
    private var waveBuf = ByteArray(1 shl 20)
    private var frames = 0
    private var bassL = FloatArray(4096)
    private var midL = FloatArray(4096)
    private var highL = FloatArray(4096)
    private var energyL = FloatArray(4096)
    private var fluxL = FloatArray(4096)
    private var centL = FloatArray(4096)

    private val prevLevel = FloatArray(BANDS)
    private val smooth = FloatArray(BANDS)
    private var peakAll = 0.4f
    private var peakBass = 0.4f
    private var peakMid = 0.4f
    private var peakHigh = 0.4f
    private var sBass = 0f
    private var sMid = 0f
    private var sHigh = 0f
    private var sEnergy = 0f
    private var wavePeak = 0.25f
    private val tmpWave = FloatArray(WAVE_N)

    init {
        val binHz = sampleRate.toFloat() / n
        val f0 = 35f
        val f1 = min(16000f, sampleRate / 2f - 200f)
        for (b in 0 until BANDS) {
            val a = f0 * (f1 / f0).pow(b.toFloat() / BANDS)
            val c = f0 * (f1 / f0).pow((b + 1f) / BANDS)
            bandCentre[b] = sqrt(a * c)
            val lo = a / binHz
            val hi = c / binHz
            bandLo[b] = lo.toInt().coerceIn(1, n / 2 - 1)
            bandHi[b] = max(bandLo[b], hi.toInt()).coerceIn(1, n / 2 - 1)
            bandFrac[b] = ((bandCentre[b] / binHz) - bandLo[b]).coerceIn(0f, 1f)
        }
    }

    fun push(mono: FloatArray, count: Int) {
        for (i in 0 until count) {
            ring[ringPos] = mono[i]
            ringPos = (ringPos + 1) and (n - 1)
            if (filled < n) filled++
            if (++sinceHop >= hop) {
                sinceHop = 0
                if (filled >= n) emit()
            }
        }
    }

    private fun grow(a: FloatArray): FloatArray = a.copyOf(a.size * 2)

    private fun emit() {
        // Copy the window (oldest first) and apply a Hann window.
        var energySum = 0f
        for (i in 0 until n) {
            val s = ring[(ringPos + i) and (n - 1)]
            re[i] = s * window[i]
            im[i] = 0f
            energySum += s * s
        }
        fft.transform(re, im)
        val rms = sqrt(energySum / n)
        val rmsDb = 20f * log10(rms + 1e-6f)
        val loud = ((rmsDb + 52f) / 44f).coerceIn(0f, 1f)

        val level = FloatArray(BANDS)
        val norm = 4f / n // a full-scale sine gives ~1.0
        for (b in 0 until BANDS) {
            var m = 0f
            val lo = bandLo[b]
            val hi = bandHi[b]
            if (hi > lo) {
                for (k in lo..hi) m += sqrt(re[k] * re[k] + im[k] * im[k])
                m /= (hi - lo + 1)
            } else {
                val a = sqrt(re[lo] * re[lo] + im[lo] * im[lo])
                val k2 = min(lo + 1, n / 2 - 1)
                val c = sqrt(re[k2] * re[k2] + im[k2] * im[k2])
                m = a + (c - a) * bandFrac[b]
            }
            val db = 20f * log10(m * norm + 1e-7f)
            // +3 dB/octave tilt so treble is visible next to bass
            val tilt = 3f * (ln(bandCentre[b] / 200f) / ln(2f))
            level[b] = ((db + tilt + 78f) / 62f).coerceIn(0f, 1.2f)
        }

        // Onset flux: positive change in the level, weighting low bands more (kick/snare).
        var flux = 0f
        var lowFlux = 0f
        for (b in 0 until BANDS) {
            val d = max(0f, level[b] - prevLevel[b])
            flux += d
            if (bandCentre[b] < 220f) lowFlux += d * 2.2f
            prevLevel[b] = level[b]
        }
        flux = (flux + lowFlux) / BANDS * 6f

        // Attack/release smoothing for display, with adaptive gain so quiet songs still move.
        var mx = 0f
        var bSum = 0f; var bCnt = 0
        var mSum = 0f; var mCnt = 0
        var hSum = 0f; var hCnt = 0
        var cNum = 0f; var cDen = 0f
        for (b in 0 until BANDS) {
            val prev = smooth[b]
            val v = level[b]
            smooth[b] = if (v > prev) prev + (v - prev) * 0.7f else prev + (v - prev) * 0.22f
            mx = max(mx, smooth[b])
            val fc = bandCentre[b]
            if (fc < 160f) { bSum += level[b]; bCnt++ } else if (fc < 2500f) { mSum += level[b]; mCnt++ } else { hSum += level[b]; hCnt++ }
            cNum += b * level[b]; cDen += level[b]
        }
        peakAll = max(peakAll * 0.9993f, mx).coerceAtLeast(0.35f)
        val gain = 0.92f / peakAll
        grow()
        val sb = frames * BANDS
        for (b in 0 until BANDS) specBuf[sb + b] = (min(1f, smooth[b] * gain) * 255f).toInt().toByte()

        val bass = bSum / max(1, bCnt)
        val mid = mSum / max(1, mCnt)
        val high = hSum / max(1, hCnt)
        peakBass = max(peakBass * 0.9993f, bass).coerceAtLeast(0.3f)
        peakMid = max(peakMid * 0.9993f, mid).coerceAtLeast(0.3f)
        peakHigh = max(peakHigh * 0.9993f, high).coerceAtLeast(0.2f)
        sBass = env(sBass, (bass / peakBass).coerceIn(0f, 1f))
        sMid = env(sMid, (mid / peakMid).coerceIn(0f, 1f))
        sHigh = env(sHigh, (high / peakHigh).coerceIn(0f, 1f))
        sEnergy = env(sEnergy, loud)

        // Coarse waveform: peak-signed value per 16-sample chunk of the newest 1024 samples.
        val base = (ringPos - 1024 + n) and (n - 1)
        var wmax = 0f
        for (w in 0 until WAVE_N) {
            var best = 0f
            for (k in 0 until 16) {
                val s = ring[(base + w * 16 + k) and (n - 1)]
                if (abs(s) > abs(best)) best = s
            }
            tmpWave[w] = best
            wmax = max(wmax, abs(best))
        }
        wavePeak = max(wavePeak * 0.998f, wmax).coerceAtLeast(0.12f)
        val wg = 0.95f / wavePeak
        for (w in 0 until WAVE_N) {
            waveBuf[frames * WAVE_N + w] = ((tmpWave[w] * wg * 0.5f + 0.5f).coerceIn(0f, 1f) * 255f).toInt().toByte()
        }

        if (frames >= bassL.size) {
            bassL = grow(bassL); midL = grow(midL); highL = grow(highL); energyL = grow(energyL); fluxL = grow(fluxL); centL = grow(centL)
        }
        bassL[frames] = sBass; midL[frames] = sMid; highL[frames] = sHigh; energyL[frames] = sEnergy
        fluxL[frames] = flux
        centL[frames] = if (cDen > 1e-4f) cNum / cDen / BANDS else 0f
        frames++
    }

    private fun env(prev: Float, v: Float) = if (v > prev) prev + (v - prev) * 0.65f else prev + (v - prev) * 0.18f

    private fun grow() {
        if ((frames + 1) * BANDS > specBuf.size) specBuf = specBuf.copyOf(specBuf.size * 2)
        if ((frames + 1) * WAVE_N > waveBuf.size) waveBuf = waveBuf.copyOf(waveBuf.size * 2)
    }

    fun finish(): FeatureTrack {
        val f = frames
        val flux = fluxL.copyOf(f)
        val beats = pickBeats(flux)
        val bpm = estimateBpm(flux)
        val pulse = FloatArray(f)
        val phase = FloatArray(f)
        var bi = 0
        var last = -10f
        var interval = 0.5f
        var p = 0f
        val recent = ArrayList<Float>()
        for (k in 0 until f) {
            val t = k.toFloat() / fps
            while (bi < beats.size && beats[bi] <= t + 0.5f / fps) {
                if (last > 0f) {
                    recent.add(beats[bi] - last)
                    if (recent.size > 6) recent.removeAt(0)
                    interval = recent.sorted()[recent.size / 2].coerceIn(0.28f, 1.2f)
                }
                last = beats[bi]
                p = 1f
                bi++
            }
            p *= 0.80f
            pulse[k] = p
            phase[k] = ((t - max(last, 0f)) / interval).coerceIn(0f, 1f)
        }
        return FeatureTrack(
            fps, f, specBuf.copyOf(f * BANDS), waveBuf.copyOf(f * WAVE_N),
            bassL.copyOf(f), midL.copyOf(f), highL.copyOf(f), energyL.copyOf(f), flux, centL.copyOf(f),
            pulse, phase, beats, bpm,
        )
    }

    /** Peak picking on the onset envelope with an adaptive threshold. */
    private fun pickBeats(flux: FloatArray): FloatArray {
        val n = flux.size
        if (n < 8) return FloatArray(0)
        val w = fps
        val out = ArrayList<Float>()
        var lastIdx = -100
        val minGap = (0.27f * fps).toInt()
        val cum = DoubleArray(n + 1)
        val cum2 = DoubleArray(n + 1)
        for (i in 0 until n) { cum[i + 1] = cum[i] + flux[i]; cum2[i + 1] = cum2[i] + flux[i].toDouble() * flux[i] }
        for (i in 1 until n - 1) {
            val a = max(0, i - w)
            val b = min(n, i + w)
            val cnt = (b - a).toDouble()
            val mean = (cum[b] - cum[a]) / cnt
            val sd = sqrt(max(0.0, (cum2[b] - cum2[a]) / cnt - mean * mean))
            val thr = mean + 0.9 * sd + 0.04
            if (flux[i] > thr && flux[i] >= flux[i - 1] && flux[i] > flux[i + 1] && i - lastIdx >= minGap) {
                out.add(i.toFloat() / fps)
                lastIdx = i
            }
        }
        return out.toFloatArray()
    }

    private fun estimateBpm(flux: FloatArray): Float = Tempo.estimate(flux, fps)

    companion object {
        fun mix(l: Short, r: Short) = (l + r) * (0.5f / 32768f)
    }
}

object Tempo {
    /** Autocorrelation tempo estimate in BPM (0 if the signal is too short or flat). */
    fun estimate(flux: FloatArray, fps: Int): Float {
        val n = flux.size
        if (n < fps * 6) return 0f
        val mean = flux.average().toFloat()
        val x = FloatArray(n) { max(0f, flux[it] - mean) }
        val minLag = (fps * 60f / 190f).toInt().coerceAtLeast(3)
        val maxLag = (fps * 60f / 55f).toInt().coerceAtMost(n / 2)
        var best = 0f
        var bestLag = 0
        val ac = FloatArray(maxLag + 2)
        for (lag in minLag..maxLag + 1) {
            var s = 0f
            var i = lag
            while (i < n) { s += x[i] * x[i - lag]; i++ }
            ac[lag] = s / (n - lag)
        }
        for (lag in minLag..maxLag) {
            // mild preference for 70–120 BPM, where lofi lives
            val bpm = 60f * fps / lag
            val prior = exp(-0.5f * ((ln(bpm / 90f)) / 0.45f).pow(2))
            val sc = ac[lag] * (0.6f + 0.4f * prior)
            if (sc > best && ac[lag] >= ac[lag - 1] && ac[lag] >= ac[lag + 1]) { best = sc; bestLag = lag }
        }
        if (bestLag == 0) return 0f
        val a = ac[bestLag - 1]; val b = ac[bestLag]; val c = ac[bestLag + 1]
        val denom = a - 2 * b + c
        val shift = if (abs(denom) > 1e-9f) 0.5f * (a - c) / denom else 0f
        return 60f * fps / (bestLag + shift.coerceIn(-1f, 1f))
    }
}
