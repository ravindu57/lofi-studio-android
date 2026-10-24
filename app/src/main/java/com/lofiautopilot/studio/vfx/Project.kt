package com.lofiautopilot.studio.vfx

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.max

@Serializable
data class AudioClip(
    val id: String = UUID.randomUUID().toString().take(8),
    val uri: String,
    val title: String,
    /** Full length of the source file in seconds. */
    val srcDurS: Float,
    /** Where the clip starts on the timeline. */
    val startS: Float = 0f,
    /** Where in the source the clip begins (trim from the start). */
    val inS: Float = 0f,
    /** Length on the timeline (after trimming). */
    val lenS: Float = srcDurS,
    val gain: Float = 1f,
    val fadeInS: Float = 0f,
    val fadeOutS: Float = 0f,
    val lufs: Double? = null,
    val track: Int = 0,
    /** Five-band EQ gains in dB (60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 12 kHz). Empty = flat. */
    val eq: List<Float> = emptyList(),
) {
    val endS: Float get() = startS + lenS
}

/** Legacy single-picture field; old projects are converted to [MediaClip]s by [Project.migrate]. */
@Serializable
data class Visual(val uri: String, val video: Boolean = false, val durS: Float = 0f)

/**
 * A picture, video or built-in intro on the media track. Clips sit back to back (the track ripples), and
 * [transition] describes how this clip comes in from the one before it.
 */
@Serializable
data class MediaClip(
    val id: String = UUID.randomUUID().toString().take(8),
    /** File uri, or `intro:<id>` for a built-in intro. */
    val uri: String,
    val video: Boolean = false,
    /** Length of the source video (0 for pictures and intros). */
    val srcDurS: Float = 0f,
    val startS: Float = 0f,
    /** Where in the source video this clip begins. */
    val inS: Float = 0f,
    val lenS: Float = 6f,
    val title: String = "",
    val transition: String = "none",
    val transS: Float = 0.9f,
) {
    val endS: Float get() = startS + lenS
    val isIntro: Boolean get() = uri.startsWith(INTRO_PREFIX)
    /** Transition length actually used: never longer than most of this clip. */
    val transLen: Float get() = if (transition == "none") 0f else minOf(transS, lenS * 0.6f).coerceAtLeast(0.15f)

    companion object {
        const val INTRO_PREFIX = "intro:"
    }
}

/** What the picture track shows at one instant: the current clip, and the clip it is dissolving from (if any). */
class VisualState(
    val cur: MediaClip, val curT: Float,
    val prev: MediaClip?, val prevT: Float,
    val progress: Float, val transition: String,
)

/** Everything in an editing session: a track of pictures/videos, any number of music clips and effect clips on a timeline. */
@Serializable
data class Project(
    val id: String = UUID.randomUUID().toString().take(10),
    val name: String = "Untitled",
    val visual: Visual? = null,
    val media: List<MediaClip> = emptyList(),
    val audio: List<AudioClip> = emptyList(),
    val effects: List<Layer> = emptyList(),
    val fit: Fit = Fit.COVER,
    val aspect: String = "WIDE",
    val quality: Int = 1080,
    val fps: Int = 24,
    val codec: String = "H264",
    val director: Boolean = true,
    val normalize: Boolean = true,
    val updatedAt: Long = 0L,
) {
    val audioEndS: Float get() = audio.maxOfOrNull { it.endS } ?: 0f
    val mediaEndS: Float get() = media.lastOrNull()?.endS ?: 0f

    /** Length of the finished video. The last picture holds (a video loops) if the music is longer than the media track. */
    val durationS: Float get() = if (audio.isNotEmpty()) audioEndS else mediaEndS

    /** Thumbnail source for lists. */
    val cover: String? get() = media.firstOrNull { !it.isIntro }?.uri ?: visual?.uri

    /** Converts the old single-picture form into a one-clip media track. */
    fun migrate(): Project {
        val v = visual ?: return this
        if (media.isNotEmpty()) return copy(visual = null)
        val len = when {
            audio.isNotEmpty() -> audioEndS
            v.video && v.durS > 0f -> v.durS
            else -> 8f
        }
        return copy(visual = null, media = listOf(MediaClip(uri = v.uri, video = v.video, srcDurS = v.durS, lenS = len)))
    }

    /** Puts the media clips back to back in list order. */
    fun relayout(): Project {
        var t = 0f
        return copy(media = media.map { c -> c.copy(startS = t).also { t += c.lenS } })
    }

    fun visualAt(t: Float): VisualState? = VisualTimeline.at(media, t)

    fun layerEnd(l: Layer): Float = if (l.to < 0f) max(durationS, l.from + 0.5f) else l.to

    /** The effect stack in the order the renderer applies it (lower lane first, then earlier start). */
    fun look(): Look = Look(name, effects.sortedWith(compareBy({ it.track }, { it.from })), fit)

    fun withEffects(f: (List<Layer>) -> List<Layer>) = copy(effects = f(effects))
    fun touch() = copy(updatedAt = System.currentTimeMillis())
}

object VisualTimeline {
    fun at(media: List<MediaClip>, t: Float): VisualState? {
        if (media.isEmpty()) return null
        val i = media.indexOfLast { it.startS <= t + 1e-4f }.coerceAtLeast(0)
        val c = media[i]
        val local = (t - c.startS).coerceAtLeast(0f)
        val curT = c.inS + local
        val tl = c.transLen
        if (i > 0 && tl > 0f && local < tl) {
            val p = media[i - 1]
            // the outgoing clip keeps going past its end for the length of the transition
            return VisualState(c, curT, p, p.inS + p.lenS + local, local / tl, c.transition)
        }
        return VisualState(c, curT, null, 0f, 0f, "none")
    }
}
