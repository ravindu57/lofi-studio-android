package com.lofiautopilot.studio.export

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.view.Surface
import com.lofiautopilot.studio.gl.VfxRenderer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Decodes a video file frame by frame into an external GL texture so the effect stack can be applied to it.
 * The clip loops if the audio is longer than the video.
 */
class VideoFrameSource(
    private val context: Context,
    private val uri: Uri,
    private val renderer: VfxRenderer,
    override var slot: Int = 0,
    /** Where to start in the file, in seconds (the first update asks for a time at or after this). */
    private val startAtS: Float = 0f,
) : FrameSource {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var st: SurfaceTexture? = null
    private var surface: Surface? = null
    private val frameReady = Semaphore(0)
    private val info = MediaCodec.BufferInfo()
    private val mat = FloatArray(16)
    private var inputDone = false
    private var curPts = Long.MIN_VALUE
    private var loopOffsetUs = 0L
    private var durationUs = 0L
    private var halfFrameUs = 20_000L
    private var lastPtsInLoop = 0L

    override fun prepare() {
        extractor.setDataSource(context, uri, null)
        var idx = -1
        var fmt: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { idx = i; fmt = f; break }
        }
        if (idx < 0 || fmt == null) throw ExportException("That file has no video track.")
        extractor.selectTrack(idx)
        var w = fmt.getInteger(MediaFormat.KEY_WIDTH)
        var h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
        val rot = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0
        if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
        durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val fps = if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) fmt.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
        halfFrameUs = (500_000L / fps.coerceIn(1, 120))

        val tex = renderer.createExternalTexture()
        st = SurfaceTexture(tex).also { it.setOnFrameAvailableListener { frameReady.release() } }
        surface = Surface(st)
        renderer.useExternal(tex, w, h, slot)
        if (startAtS > 0.05f && durationUs > 0) {
            val at = (startAtS * 1_000_000L).toLong()
            // start inside the file; anything past the end wraps, as it will in update()
            extractor.seekTo(if (at < durationUs) at else 0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            if (at >= durationUs) loopOffsetUs = 0L
        }
        codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!).also {
            it.configure(fmt, surface, null, 0)
            it.start()
        }
    }

    override fun update(timeS: Float) {
        val target = (timeS * 1_000_000L).toLong()
        var guard = 0
        while (curPts < target - halfFrameUs && guard++ < 10_000) {
            if (!decodeNext()) {
                // end of clip: loop
                loopOffsetUs += maxOf(lastPtsInLoop + halfFrameUs * 2, durationUs)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                codec!!.flush()
                inputDone = false
                lastPtsInLoop = 0
            }
        }
    }

    /** Decodes and displays the next frame. Returns false at the end of the stream. */
    private fun decodeNext(): Boolean {
        val c = codec!!
        var spins = 0
        while (spins++ < 4000) {
            if (!inputDone) {
                val ii = c.dequeueInputBuffer(0)
                if (ii >= 0) {
                    val b = c.getInputBuffer(ii)!!
                    val sz = extractor.readSampleData(b, 0)
                    if (sz < 0) {
                        c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        c.queueInputBuffer(ii, 0, sz, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val oi = c.dequeueOutputBuffer(info, 5_000)
            if (oi >= 0) {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (eos && info.size == 0) {
                    c.releaseOutputBuffer(oi, false)
                    return false
                }
                c.releaseOutputBuffer(oi, true)
                if (!frameReady.tryAcquire(2, TimeUnit.SECONDS)) throw ExportException("The video decoder stopped responding.")
                st!!.updateTexImage()
                st!!.getTransformMatrix(mat)
                renderer.setExternalMatrix(mat, slot)
                curPts = info.presentationTimeUs + loopOffsetUs
                lastPtsInLoop = info.presentationTimeUs
                return true
            }
        }
        throw ExportException("The video decoder stalled.")
    }

    override fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { surface?.release() }
        runCatching { st?.release() }
        runCatching { extractor.release() }
    }
}
