package com.lofiautopilot.studio.export

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.GLES30
import android.util.Log
import com.lofiautopilot.studio.audio.AnalysisResult
import com.lofiautopilot.studio.audio.Cancelled
import com.lofiautopilot.studio.audio.FrameFeatures
import com.lofiautopilot.studio.audio.TimelineMix
import com.lofiautopilot.studio.vfx.AudioClip
import com.lofiautopilot.studio.audio.OUT_RATE
import com.lofiautopilot.studio.gl.EglCore
import com.lofiautopilot.studio.gl.VfxRenderer
import com.lofiautopilot.studio.vfx.Look
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

enum class Codec(val label: String, val mime: String) {
    H264("H.264 (works everywhere)", "video/avc"),
    HEVC("HEVC (smaller files)", "video/hevc"),
}

enum class Aspect(val label: String, val w: Int, val h: Int) {
    WIDE("16:9 YouTube", 16, 9), TALL("9:16 Shorts / Reels", 9, 16), SQUARE("1:1 Square", 1, 1), PORTRAIT("4:5 Feed", 4, 5);

    /** Output size for a given "quality" line count (the short side, e.g. 1080). */
    fun size(shortSide: Int): Pair<Int, Int> {
        fun even(v: Double) = (Math.round(v / 2.0) * 2).toInt()
        return if (w >= h) even(shortSide * w.toDouble() / h) to even(shortSide.toDouble())
        else even(shortSide.toDouble()) to even(shortSide * h.toDouble() / w)
    }
}

class ExportRequest(
    /** The picture track: pictures, videos and built-in intros, with their transitions. */
    val media: List<com.lofiautopilot.studio.vfx.MediaClip>,
    val look: Look,
    val audio: List<AudioClip>,
    val normalize: Boolean,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val codec: Codec,
    val out: File,
    val maxSeconds: Double? = null,
)

class ExportResult(val file: File, val seconds: Double, val frames: Int)

class ExportException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

/**
 * Pass 2: renders every video frame with the effect stack and encodes picture + mixed audio into an MP4,
 * using the phone's hardware encoders.
 */
class VideoExporter(private val context: Context) {

    private class Mux(path: String) {
        private val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private var vTrack = -1
        private var aTrack = -1
        private var started = false
        private class Pending(val track: Int, val data: ByteArray, val info: MediaCodec.BufferInfo)
        private val pending = ArrayList<Pending>()
        var videoSamples = 0
        var lastVideoUs = 0L

        fun addVideo(f: MediaFormat) { vTrack = muxer.addTrack(f); maybeStart() }
        fun addAudio(f: MediaFormat) { aTrack = muxer.addTrack(f); maybeStart() }
        private fun maybeStart() {
            if (!started && vTrack >= 0 && aTrack >= 0) {
                muxer.start(); started = true
                for (p in pending) muxer.writeSampleData(p.track, ByteBuffer.wrap(p.data), p.info)
                pending.clear()
            }
        }
        fun write(video: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
            val tr = if (video) vTrack else aTrack
            if (video) { videoSamples++; lastVideoUs = info.presentationTimeUs }
            if (started) {
                muxer.writeSampleData(tr, buf, info)
            } else {
                val b = ByteArray(info.size)
                buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(b)
                val i = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                pending.add(Pending(tr, b, i))
            }
        }
        fun stop() { if (started) runCatching { muxer.stop() }; runCatching { muxer.release() } }
    }

    /** Hardware encoder limits: clamps the size/fps so the request can't fail on this phone. */
    fun supports(codec: Codec, w: Int, h: Int, fps: Int): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && codec.mime in it.supportedTypes }
        return list.any {
            val v = it.getCapabilitiesForType(codec.mime).videoCapabilities
            v.isSizeSupported(w, h) && v.areSizeAndRateSupported(w, h, fps.toDouble())
        }
    }

    fun export(
        req: ExportRequest,
        analysis: AnalysisResult,
        onProgress: (Float) -> Unit,
        cancelled: () -> Boolean,
    ): ExportResult {
        val features = analysis.features
        val totalS = minOf(features.durationS.toDouble(), req.maxSeconds ?: Double.MAX_VALUE)
        val totalFrames = ceil(totalS * req.fps).toInt()
        if (totalFrames <= 0) throw ExportException("There is no audio to build a video from.")
        req.out.parentFile?.mkdirs()
        if (req.out.exists()) req.out.delete()

        var venc: MediaCodec? = null
        var aenc: MediaCodec? = null
        var egl: EglCore? = null
        var renderer: VfxRenderer? = null
        var mux: Mux? = null
        var mix: TimelineMix? = null
        var source: FrameSource? = null
        val window = Array<android.opengl.EGLSurface?>(1) { null }
        var inputSurface: android.view.Surface? = null
        try {
            // ---------- encoders ----------
            val vfmt = MediaFormat.createVideoFormat(req.codec.mime, req.width, req.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, req.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, req.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                if (req.codec == Codec.H264) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                }
            }
            venc = try {
                MediaCodec.createEncoderByType(req.codec.mime).also {
                    it.configure(vfmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
            } catch (e: Exception) {
                // Some encoders refuse the High profile; retry with defaults.
                vfmt.removeKey(MediaFormat.KEY_PROFILE)
                MediaCodec.createEncoderByType(req.codec.mime).also {
                    it.configure(vfmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
            }
            inputSurface = venc.createInputSurface()
            venc.start()

            val afmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, OUT_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            aenc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                it.configure(afmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
            mux = Mux(req.out.absolutePath)

            // ---------- GL ----------
            egl = EglCore(null, recordable = true)
            val surf = egl.createWindowSurface(inputSurface)
            window[0] = surf
            egl.makeCurrent(surf)
            renderer = VfxRenderer(req.width, req.height).also { it.init() }
            renderer.look = req.look
            if (req.media.isEmpty()) throw ExportException("Choose a picture or a video first.")
            source = MediaSequenceSource(context, renderer, req.media)
            source.prepare()

            // ---------- audio source ----------
            val clips = req.audio.map { it.copy(lufs = analysis.lufs[it.id] ?: it.lufs) }
            mix = TimelineMix(context, clips, req.normalize, 0.0, totalS + 1.0)

            // ---------- main loop ----------
            val vInfo = MediaCodec.BufferInfo()
            val aInfo = MediaCodec.BufferInfo()
            val pcm = ShortArray(1024 * 2)
            val pcmBytes = ByteBuffer.allocateDirect(pcm.size * 2).order(ByteOrder.nativeOrder())
            var audioFrames = 0L
            var audioEos = false
            val ff = FrameFeatures()
            val audioFramesTotal = (totalS * OUT_RATE).toLong()
            var vEosSent = false

            fun drainAudio() {
                val enc = aenc ?: return
                while (true) {
                    val oi = enc.dequeueOutputBuffer(aInfo, 0)
                    when {
                        oi == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> mux!!.addAudio(enc.outputFormat)
                        oi >= 0 -> {
                            val buf = enc.getOutputBuffer(oi)!!
                            if (aInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) aInfo.size = 0
                            if (aInfo.size > 0) mux!!.write(false, buf, aInfo)
                            enc.releaseOutputBuffer(oi, false)
                            if (aInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            fun drainVideo(untilEos: Boolean) {
                val enc = venc ?: return
                while (true) {
                    val oi = enc.dequeueOutputBuffer(vInfo, if (untilEos) 10_000 else 0)
                    when {
                        oi == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return
                        oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> mux!!.addVideo(enc.outputFormat)
                        oi >= 0 -> {
                            val buf = enc.getOutputBuffer(oi)!!
                            if (vInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) vInfo.size = 0
                            if (vInfo.size > 0) mux!!.write(true, buf, vInfo)
                            enc.releaseOutputBuffer(oi, false)
                            if (vInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                    if (cancelled()) throw Cancelled()
                }
            }

            fun feedAudioUpTo(targetFrames: Long) {
                val enc = aenc!!
                while (!audioEos && audioFrames < targetFrames) {
                    val ii = enc.dequeueInputBuffer(5_000)
                    if (ii < 0) { drainAudio(); continue }
                    val n = if (audioFrames >= audioFramesTotal) 0 else mix.read(pcm, minOf(1024L, audioFramesTotal - audioFrames).toInt())
                    val inb = enc.getInputBuffer(ii)!!
                    inb.clear()
                    if (n <= 0) {
                        enc.queueInputBuffer(ii, 0, 0, audioFrames * 1_000_000L / OUT_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        audioEos = true
                    } else {
                        pcmBytes.clear()
                        pcmBytes.asShortBuffer().put(pcm, 0, n * 2)
                        pcmBytes.limit(n * 4)
                        inb.put(pcmBytes)
                        enc.queueInputBuffer(ii, 0, n * 4, audioFrames * 1_000_000L / OUT_RATE, 0)
                        audioFrames += n
                    }
                    drainAudio()
                }
            }

            val nsPerFrame = 1_000_000_000L / req.fps
            var lastPct = -1
            for (k in 0 until totalFrames) {
                if (cancelled()) throw Cancelled()
                val t = k.toFloat() / req.fps
                feedAudioUpTo(((t + 0.7) * OUT_RATE).toLong())
                features.at(t, ff)
                source.update(t)
                renderer.render(t, ff, 0, req.width, req.height) { it.text }
                egl.setPresentationTime(surf, k * nsPerFrame)
                if (!egl.swap(surf)) throw ExportException("The graphics driver lost the render surface.")
                drainVideo(false)
                val pct = (k * 1000L / totalFrames).toInt()
                if (pct != lastPct) { lastPct = pct; onProgress(k.toFloat() / totalFrames) }
            }
            // finish: remaining audio, end of video stream
            feedAudioUpTo(Long.MAX_VALUE)
            venc.signalEndOfInputStream()
            vEosSent = true
            drainVideo(true)
            // wait for the audio encoder's EOS
            run {
                val enc = aenc!!
                var guard = 0
                while (guard++ < 2000) {
                    val oi = enc.dequeueOutputBuffer(aInfo, 10_000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) mux.addAudio(enc.outputFormat)
                    else if (oi >= 0) {
                        val buf = enc.getOutputBuffer(oi)!!
                        if (aInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) aInfo.size = 0
                        if (aInfo.size > 0) mux.write(false, buf, aInfo)
                        enc.releaseOutputBuffer(oi, false)
                        if (aInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            check(vEosSent)
            onProgress(1f)
            mux.stop()
            mux = null
            return ExportResult(req.out, totalS, totalFrames)
        } catch (e: Cancelled) {
            req.out.delete()
            throw e
        } catch (e: ExportException) {
            req.out.delete()
            throw e
        } catch (e: Exception) {
            req.out.delete()
            Log.e("VideoExporter", "export failed", e)
            throw ExportException(friendly(e), e)
        } finally {
            runCatching { source?.close() }
            runCatching { mix?.close() }
            runCatching { venc?.stop() }; runCatching { venc?.release() }
            runCatching { aenc?.stop() }; runCatching { aenc?.release() }
            runCatching { mux?.stop() }
            runCatching { renderer?.release() }
            runCatching { window[0]?.let { egl?.releaseSurface(it) } }
            runCatching { inputSurface?.release() }
            runCatching { egl?.release() }
        }
    }

    private fun friendly(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            e is java.io.IOException && m.contains("ENOSPC") -> "The phone ran out of storage space."
            m.contains("No space", ignoreCase = true) -> "The phone ran out of storage space."
            e is OutOfMemoryError -> "The phone ran out of memory. Try 720p."
            else -> "Rendering stopped: ${m.ifBlank { e.javaClass.simpleName }}"
        }
    }

    companion object {
        /** A sensible bitrate for lofi-style content (mostly still, gentle motion). */
        fun bitrateFor(w: Int, h: Int, fps: Int, codec: Codec, busy: Boolean): Int {
            val base = w.toLong() * h * fps * (if (busy) 0.11 else 0.07)
            val v = if (codec == Codec.HEVC) base * 0.65 else base
            return v.toLong().coerceIn(450_000L, 40_000_000L).toInt()
        }
    }
}

/** Where the picture comes from for each frame. */
interface FrameSource : AutoCloseable {
    /** Which renderer slot this source feeds (changes when slots are swapped). */
    var slot: Int
    fun prepare()
    /** Called before every rendered frame with the time inside this source (seconds). */
    fun update(timeS: Float)
}

class ImageFrameSource(private val context: Context, private val uri: Uri, private val renderer: VfxRenderer, override var slot: Int = 0) : FrameSource {
    private var bmp: Bitmap? = null
    override fun prepare() {
        val b = ImageLoader.load(context, uri, 2560)
        bmp = b
        renderer.setImage(b, slot)
        GLES30.glFinish()
        b.recycle(); bmp = null
    }
    override fun update(timeS: Float) {}
    override fun close() { bmp?.recycle(); bmp = null }
}
