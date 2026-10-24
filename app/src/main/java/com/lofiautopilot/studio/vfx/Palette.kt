package com.lofiautopilot.studio.vfx

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Three glow-friendly colours pulled from the picture so effects match it automatically. */
class Palette(val accent: Int, val secondary: Int, val light: Int, val dark: Int) {
    companion object {
        val DEFAULT = Palette(0xFFFFB46B.toInt(), 0xFF8E7CFF.toInt(), 0xFFFFF1D6.toInt(), 0xFF14121F.toInt())

        fun from(bmp: Bitmap): Palette {
            val small = Bitmap.createScaledBitmap(bmp, 48, 27, true)
            val px = IntArray(48 * 27)
            small.getPixels(px, 0, 48, 0, 0, 48, 27)
            if (small !== bmp) small.recycle()
            val k = 5
            val cent = Array(k) { FloatArray(3) }
            // seed from evenly spaced pixels sorted by brightness
            val sorted = px.sortedBy { Color.red(it) + Color.green(it) + Color.blue(it) }
            for (i in 0 until k) {
                val c = sorted[((i + 0.5f) / k * (sorted.size - 1)).toInt()]
                cent[i][0] = Color.red(c).toFloat(); cent[i][1] = Color.green(c).toFloat(); cent[i][2] = Color.blue(c).toFloat()
            }
            val count = IntArray(k)
            repeat(8) {
                val sum = Array(k) { FloatArray(3) }
                count.fill(0)
                for (p in px) {
                    val r = Color.red(p).toFloat(); val g = Color.green(p).toFloat(); val b = Color.blue(p).toFloat()
                    var best = 0; var bd = Float.MAX_VALUE
                    for (i in 0 until k) {
                        val dr = r - cent[i][0]; val dg = g - cent[i][1]; val db = b - cent[i][2]
                        val d = dr * dr + dg * dg + db * db
                        if (d < bd) { bd = d; best = i }
                    }
                    sum[best][0] += r; sum[best][1] += g; sum[best][2] += b; count[best]++
                }
                for (i in 0 until k) if (count[i] > 0) for (c in 0..2) cent[i][c] = sum[i][c] / count[i]
            }
            data class C(val rgb: Int, val pop: Int, val sat: Float, val v: Float, val h: Float)
            val cs = (0 until k).filter { count[it] > 0 }.map {
                val rgb = Color.rgb(cent[it][0].toInt(), cent[it][1].toInt(), cent[it][2].toInt())
                val hsv = FloatArray(3)
                Color.colorToHSV(rgb, hsv)
                C(rgb, count[it], hsv[1], hsv[2], hsv[0])
            }
            val accent = cs.maxByOrNull { it.sat * (0.35f + it.v) * sqrt(it.pop.toFloat()) } ?: cs.first()
            val second = cs.filter { it !== accent }.maxByOrNull {
                val dh = Math.abs(it.h - accent.h).let { d -> min(d, 360f - d) }
                (dh / 180f + 0.3f) * (0.2f + it.sat) * (0.3f + it.v)
            } ?: accent
            val light = cs.maxByOrNull { it.v } ?: accent
            val dark = cs.minByOrNull { it.v } ?: accent
            return Palette(glow(accent.rgb), glow(second.rgb), glow(light.rgb, 0.35f), dark.rgb)
        }

        /** Push a colour toward something that reads as light on a dark picture. */
        private fun glow(rgb: Int, minSat: Float = 0.5f): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(rgb, hsv)
            hsv[1] = max(hsv[1], minSat).coerceAtMost(1f)
            hsv[2] = max(hsv[2], 0.82f)
            return Color.HSVToColor(hsv)
        }
    }
}
