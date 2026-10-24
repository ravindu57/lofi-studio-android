package com.lofiautopilot.studio.vfx

import kotlinx.serialization.Serializable
import java.util.UUID

/** Which audio signal drives a layer. */
@Serializable
enum class React(val label: String) {
    OFF("Off"), BASS("Bass"), MID("Mids"), HIGH("Treble"), ENERGY("Loudness"), BEAT("Beat")
}

@Serializable
enum class Blend(val label: String) { NORMAL("Normal"), ADD("Add"), SCREEN("Screen"), OVERLAY("Overlay") }

/** How the picture is fitted to the output frame. */
@Serializable
enum class Fit(val label: String) { COVER("Fill"), CONTAIN_BLUR("Fit + blurred backdrop") }

enum class FxKind {
    /** Reads the picture and returns a new colour (grading, glitch, blur…). */
    FILTER,
    /** Returns a colour + alpha that is composited with a blend mode (visualizers, particles, light). */
    OVERLAY,
    /** Moves the virtual camera (zoom/pan/rotate). Evaluated on the CPU and merged into one transform. */
    CAMERA,
}

data class Param(
    val key: String,
    val label: String,
    val min: Float,
    val max: Float,
    val def: Float,
    /** 0 = continuous. */
    val step: Float = 0f,
)

data class FxDef(
    val id: String,
    val name: String,
    val category: String,
    val kind: FxKind,
    val blurb: String,
    val emoji: String,
    val params: List<Param> = emptyList(),
    val react: React = React.OFF,
    val reactAmt: Float = 0.6f,
    /** Number of colour pickers this effect uses (0, 1 or 2). 0xFF000000 sentinel = take from the picture's palette. */
    val colors: Int = 0,
    val glsl: String = "",
    val defBlend: Blend = Blend.NORMAL,
    val keywords: String = "",
)

/** One effect on the stack. All values are plain data so a Look can be saved, shared and edited by AI. */
@Serializable
data class Layer(
    val id: String = UUID.randomUUID().toString().take(8),
    val fx: String,
    val on: Boolean = true,
    val intensity: Float = 1f,
    val opacity: Float = 1f,
    val blend: Blend = Blend.NORMAL,
    val react: React = React.OFF,
    val reactAmt: Float = 0.6f,
    val p: Map<String, Float> = emptyMap(),
    /** ARGB. null = automatic, taken from the picture's palette. */
    val c1: Int? = null,
    val c2: Int? = null,
    /** Active window in seconds. to < 0 = until the end. */
    val from: Float = 0f,
    val to: Float = -1f,
    /** When true the Director's section curve scales this layer. */
    val follow: Boolean = true,
    val text: String = "",
    /** Timeline lane. Lower lanes are applied first (underneath). */
    val track: Int = 0,
    /** Placed by the AI arranger; re-arranging replaces these and keeps your own clips. */
    val auto: Boolean = false,
)

@Serializable
data class Look(
    val name: String = "Untitled",
    val layers: List<Layer> = emptyList(),
    val fit: Fit = Fit.COVER,
    val note: String = "",
)

object LayerFactory {
    fun create(def: FxDef): Layer = Layer(
        fx = def.id,
        react = def.react,
        reactAmt = def.reactAmt,
        blend = def.defBlend,
        p = def.params.associate { it.key to it.def },
    )
}

fun Layer.param(def: FxDef, key: String): Float =
    p[key] ?: def.params.firstOrNull { it.key == key }?.def ?: 0f
