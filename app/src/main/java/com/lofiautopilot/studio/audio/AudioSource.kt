package com.lofiautopilot.studio.audio

import java.io.Closeable

/** Anything that produces 16-bit stereo 44.1 kHz audio on demand. */
interface AudioSource : Closeable {
    /** Fills [dst] with up to [frames] stereo frames. Returns the number produced; 0 = end. */
    fun read(dst: ShortArray, frames: Int): Int
}
