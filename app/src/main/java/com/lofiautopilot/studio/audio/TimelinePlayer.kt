package com.lofiautopilot.studio.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import com.lofiautopilot.studio.vfx.AudioClip

/**
 * Plays the project's audio mix in real time through AudioTrack so the editor preview is audible and in sync.
 * Rebuilds the mix from the current position whenever the clips change.
 */
class TimelinePlayer(private val ctx: Context) {
    @Volatile private var thread: Thread? = null
    @Volatile private var stop = false
    private var track: AudioTrack? = null
    @Volatile private var baseS = 0.0
    @Volatile var playing = false
        private set
    private val ts = AudioTimestamp()
    @Volatile private var pausedAt = 0.0
    @Volatile var endS = 0.0

    /** Current timeline position in seconds (smoothly interpolated while playing). */
    fun position(): Double {
        val t = track ?: return pausedAt
        if (!playing) return pausedAt
        val frames = try {
            if (t.getTimestamp(ts)) ts.framePosition + (System.nanoTime() - ts.nanoTime) * OUT_RATE / 1e9 else t.playbackHeadPosition.toDouble()
        } catch (_: Exception) { t.playbackHeadPosition.toDouble() }
        return (baseS + frames / OUT_RATE).coerceAtMost(endS)
    }

    fun start(clips: List<AudioClip>, normalize: Boolean, fromS: Double) {
        halt()
        endS = clips.maxOfOrNull { (it.startS + it.lenS).toDouble() } ?: 0.0
        pausedAt = fromS
        if (clips.isEmpty() || fromS >= endS - 0.05) { return }
        stop = false
        baseS = fromS
        val min = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(OUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(min, 8192 * 4)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t
        playing = true
        thread = Thread({
            val mix = TimelineMix(ctx, clips, normalize, fromS)
            val buf = ShortArray(2048 * 2)
            try {
                t.play()
                while (!stop) {
                    val n = mix.read(buf, 2048)
                    if (n <= 0) break
                    var off = 0
                    while (off < n * 2 && !stop) { val w = t.write(buf, off, n * 2 - off); if (w <= 0) break; off += w }
                }
                if (!stop) { Thread.sleep(250); playing = false; pausedAt = endS }
            } catch (_: Exception) {
            } finally {
                mix.close()
            }
        }, "timeline-audio").also { it.priority = Thread.MAX_PRIORITY; it.start() }
    }

    /** Stops playback and returns the position it stopped at. */
    fun halt(): Double {
        val p = if (playing) position() else pausedAt
        stop = true
        playing = false
        thread?.let { runCatching { it.join(400) } }
        thread = null
        runCatching { track?.pause(); track?.flush(); track?.release() }
        track = null
        pausedAt = p
        return p
    }

    fun release() { halt() }
}
