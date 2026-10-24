package com.lofiautopilot.studio.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.Closeable
import java.nio.ByteOrder

const val OUT_RATE = 44100

class AudioDecodeException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

/** Decodes any audio file Android can play into 16-bit stereo at 44.1 kHz, on demand. */
class PcmDecoder(context: Context, val uri: Uri) : Closeable {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var srcRate = OUT_RATE
    private var srcCh = 2
    private var pcmFloat = false
    val durationUs: Long
    val title: String

    private var inputDone = false
    private var outputDone = false
    private var buf = ShortArray(1 shl 16)   // interleaved stereo source frames
    private var avail = 0                     // frames in buf
    private var srcPos = 0.0                  // fractional read position in buf (frames)
    private val info = MediaCodec.BufferInfo()
    private var skipTargetUs = -1L   // after a seek: decoded audio before this time is dropped
    private var firstAfterSeek = false
    private var dropPending = 0

    init {
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            throw AudioDecodeException("Can't open ${uri.lastPathSegment ?: uri}", e)
        }
        var idx = -1
        var fmt: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { idx = i; fmt = f; break }
        }
        if (idx < 0 || fmt == null) throw AudioDecodeException("No audio in ${uri.lastPathSegment ?: uri}")
        extractor.selectTrack(idx)
        durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        title = fmt.getString("title") ?: ""
        try {
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!).also {
                it.configure(fmt, null, null, 0)
                it.start()
            }
        } catch (e: Exception) {
            extractor.release()
            throw AudioDecodeException("This audio format can't be decoded on the phone", e)
        }
        if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) srcCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    }

    /** Reads up to [frames] stereo frames into [dst]. Returns the number produced; 0 means end of file. */
    fun read(dst: ShortArray, frames: Int): Int {
        val step = srcRate.toDouble() / OUT_RATE
        var produced = 0
        while (produced < frames) {
            if (!ensure(2)) {
                // Flush the last frame once.
                val i = srcPos.toInt()
                if (i < avail && outputDone) {
                    dst[produced * 2] = buf[i * 2]; dst[produced * 2 + 1] = buf[i * 2 + 1]
                    produced++; srcPos += step
                    continue
                }
                break
            }
            val i = srcPos.toInt()
            val f = (srcPos - i).toFloat()
            val l0 = buf[i * 2]; val r0 = buf[i * 2 + 1]
            val l1 = buf[i * 2 + 2]; val r1 = buf[i * 2 + 3]
            dst[produced * 2] = (l0 + (l1 - l0) * f).toInt().toShort()
            dst[produced * 2 + 1] = (r0 + (r1 - r0) * f).toInt().toShort()
            produced++
            srcPos += step
        }
        return produced
    }

    /** Makes sure at least [n] source frames are available past the read position. */
    private fun ensure(n: Int): Boolean {
        while (true) {
            val i = srcPos.toInt()
            if (avail - i >= n) return true
            if (outputDone) return false
            // compact consumed frames
            if (i > 8192) {
                System.arraycopy(buf, i * 2, buf, 0, (avail - i) * 2)
                avail -= i
                srcPos -= i
            }
            decodeSome()
        }
    }

    /** Jumps to [us] in the source (sample accurate). */
    fun seekTo(us: Long) {
        if (us <= 0L) { if (avail == 0 && srcPos == 0.0 && !outputDone) return }
        extractor.seekTo(us.coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec?.flush()
        inputDone = false; outputDone = false
        avail = 0; srcPos = 0.0
        skipTargetUs = us.coerceAtLeast(0); firstAfterSeek = true
    }

    private fun append(frames: Int, src: java.nio.ShortBuffer) {
        if ((avail + frames) * 2 > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, (avail + frames) * 2 + 8))
        var o = avail * 2
        if (srcCh == 1) {
            for (k in 0 until frames) { val s = src.get(); buf[o++] = s; buf[o++] = s }
        } else {
            for (k in 0 until frames) {
                val l = src.get(); val r = src.get()
                for (c in 2 until srcCh) src.get()
                buf[o++] = l; buf[o++] = r
            }
        }
        avail += frames
    }

    private fun decodeSome() {
        val c = codec ?: run { outputDone = true; return }
        var progressed = false
        var guard = 0
        while (!progressed && !outputDone && guard++ < 400) {
            if (!inputDone) {
                val ii = c.dequeueInputBuffer(2000)
                if (ii >= 0) {
                    val inb = c.getInputBuffer(ii)!!
                    val sz = extractor.readSampleData(inb, 0)
                    if (sz < 0) {
                        c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        c.queueInputBuffer(ii, 0, sz, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val oi = c.dequeueOutputBuffer(info, 2000)
            when {
                oi >= 0 -> {
                    if (info.size > 0) {
                        val ob = c.getOutputBuffer(oi)!!
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        var drop = 0
                        if (firstAfterSeek) {
                            firstAfterSeek = false
                            if (skipTargetUs > info.presentationTimeUs) drop = ((skipTargetUs - info.presentationTimeUs) * srcRate / 1_000_000L).toInt()
                        } else if (dropPending > 0) { drop = dropPending }
                        dropPending = 0
                        if (pcmFloat) {
                            val fb = ob.order(ByteOrder.nativeOrder()).asFloatBuffer()
                            if (drop > 0) fb.position(minOf(fb.limit(), drop * srcCh))
                            val frames = fb.remaining() / srcCh
                            val tmp = java.nio.ShortBuffer.allocate(fb.remaining())
                            while (fb.hasRemaining()) tmp.put((fb.get() * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                            tmp.flip()
                            append(frames, tmp)
                        } else {
                            val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                            if (drop > 0) sb.position(minOf(sb.limit(), drop * srcCh))
                            append(sb.remaining() / srcCh, sb)
                        }
                        progressed = true
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    c.releaseOutputBuffer(oi, false)
                }
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) srcCh = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4
                }
            }
        }
        if (guard >= 400 && !progressed) outputDone = true // decoder stalled; treat as the end of the track
    }

    override fun close() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { extractor.release() } catch (_: Exception) {}
    }

    companion object {
        /** Cheap duration probe without decoding. Returns 0 if unknown. */
        fun probe(context: Context, uri: Uri): Long {
            val ex = MediaExtractor()
            return try {
                ex.setDataSource(context, uri, null)
                var d = 0L
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true && f.containsKey(MediaFormat.KEY_DURATION)) {
                        d = f.getLong(MediaFormat.KEY_DURATION)
                        break
                    }
                }
                d
            } catch (_: Exception) {
                0L
            } finally {
                ex.release()
            }
        }
    }
}
