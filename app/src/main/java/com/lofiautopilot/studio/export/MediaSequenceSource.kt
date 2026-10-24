package com.lofiautopilot.studio.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import com.lofiautopilot.studio.gl.VfxRenderer
import com.lofiautopilot.studio.vfx.Intros
import com.lofiautopilot.studio.vfx.MediaClip
import com.lofiautopilot.studio.vfx.VisualTimeline

/** White text on a transparent 4:1 canvas, used as the title of a built-in intro. */
object TitleBitmap {
    fun make(text: String): Bitmap {
        val bmp = Bitmap.createBitmap(1024, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = Typeface.create("sans-serif-light", Typeface.BOLD); textAlign = Paint.Align.CENTER
            letterSpacing = 0.12f
        }
        val t = text.ifBlank { "SE" }.take(40).uppercase()
        var size = 120f
        p.textSize = size
        while (p.measureText(t) > 940f && size > 28f) { size -= 4f; p.textSize = size }
        c.drawText(t, 512f, 128f + size * 0.35f, p)
        return bmp
    }
}

class IntroFrameSource(private val clip: MediaClip, private val renderer: VfxRenderer, override var slot: Int = 0) : FrameSource {
    private var lastT = 0f
    override fun prepare() {
        val def = Intros.forUri(clip.uri)
        renderer.useGenerated(def?.id ?: "pulse", clip.lenS, slot)
        renderer.setTitle(slot, TitleBitmap.make(clip.title))
    }
    override fun update(timeS: Float) { lastT = timeS; renderer.setGenTime(slot, timeS) }
    override fun close() {}
}

/**
 * Feeds the renderer from the project's picture track. It keeps the current clip in slot 0 and, while a transition
 * is running, the outgoing clip in slot 1.
 */
class MediaSequenceSource(private val context: Context, private val renderer: VfxRenderer, private val media: List<MediaClip>) : FrameSource {
    override var slot: Int = 0
    private class Live(val clip: MediaClip, val src: FrameSource)
    private val live = arrayOfNulls<Live>(2)

    override fun prepare() {
        update(0f)
    }

    private fun release(i: Int) {
        live[i]?.let { runCatching { it.src.close() } }
        live[i] = null
        renderer.clearSlot(i)
    }

    private fun swap() {
        val t = live[0]; live[0] = live[1]; live[1] = t
        live[0]?.src?.slot = 0
        live[1]?.src?.slot = 1
        renderer.swapSlots()
    }

    private fun open(i: Int, clip: MediaClip, t: Float) {
        release(i)
        val src: FrameSource = when {
            clip.isIntro -> IntroFrameSource(clip, renderer, i)
            clip.video -> VideoFrameSource(context, Uri.parse(clip.uri), renderer, i, t)
            else -> ImageFrameSource(context, Uri.parse(clip.uri), renderer, i)
        }
        src.prepare()
        live[i] = Live(clip, src)
    }

    private fun ensure(i: Int, clip: MediaClip?, t: Float) {
        if (clip == null) { if (live[i] != null) release(i); return }
        if (live[i]?.clip?.id == clip.id) return
        open(i, clip, t)
    }

    override fun update(timeS: Float) {
        val st = VisualTimeline.at(media, timeS) ?: return
        if (live[0]?.clip?.id != st.cur.id && live[1]?.clip?.id == st.cur.id) swap()
        else if (st.prev != null && live[0]?.clip?.id == st.prev.id && live[1]?.clip?.id != st.prev.id) swap()
        ensure(0, st.cur, st.curT)
        ensure(1, st.prev, st.prevT)
        live[0]?.src?.update(st.curT)
        if (st.prev != null) live[1]?.src?.update(st.prevT)
        renderer.transition = if (st.prev != null && live[1] != null) st.transition to st.progress else null
    }

    override fun close() {
        release(0); release(1)
    }
}
