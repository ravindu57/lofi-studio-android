package com.lofiautopilot.studio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lofiautopilot.studio.R

val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)
val GeistMono = FontFamily(
    Font(R.font.geistmono_regular, FontWeight.Normal),
    Font(R.font.geistmono_medium, FontWeight.Medium),
)

/**
 * Vercel / Geist look: pure black or white, hairline borders, one neutral accent.
 * The old field names are kept (amber/rose/violet/sky) but now map to neutrals and a single blue.
 */
@Immutable
class Palette(
    val bg: Color, val bg2: Color, val surface: Color, val surfaceHi: Color, val line: Color, val lineStrong: Color,
    val ink: Color, val muted: Color, val faint: Color,
    val amber: Color, val rose: Color, val violet: Color, val sky: Color,
    val ok: Color, val warn: Color, val crit: Color, val dark: Boolean,
    // timeline colours
    val clipAudio: Color, val clipVisual: Color, val clipFx: Color,
) {
    val brand = Brush.linearGradient(listOf(ink, ink))
    val brandSoft = Brush.linearGradient(listOf(ink.copy(alpha = .08f), ink.copy(alpha = .05f)))
}

val DarkPalette = Palette(
    bg = Color(0xFF000000), bg2 = Color(0xFF0A0A0A), surface = Color(0xFF0A0A0A), surfaceHi = Color(0xFF161616),
    line = Color(0xFF2E2E2E), lineStrong = Color(0xFF454545),
    ink = Color(0xFFEDEDED), muted = Color(0xFFA1A1A1), faint = Color(0xFF6E6E6E),
    amber = Color(0xFFEDEDED), rose = Color(0xFF52A8FF), violet = Color(0xFFA1A1A1), sky = Color(0xFF52A8FF),
    ok = Color(0xFF46C281), warn = Color(0xFFF5A623), crit = Color(0xFFFF5C5C), dark = true,
    clipAudio = Color(0xFF46C281), clipVisual = Color(0xFF8E8E8E), clipFx = Color(0xFF52A8FF),
)

val LightPalette = Palette(
    bg = Color(0xFFFFFFFF), bg2 = Color(0xFFFAFAFA), surface = Color(0xFFFFFFFF), surfaceHi = Color(0xFFF5F5F5),
    line = Color(0xFFEAEAEA), lineStrong = Color(0xFFD4D4D4),
    ink = Color(0xFF000000), muted = Color(0xFF666666), faint = Color(0xFF8F8F8F),
    amber = Color(0xFF000000), rose = Color(0xFF0070F3), violet = Color(0xFF666666), sky = Color(0xFF0070F3),
    ok = Color(0xFF16A34A), warn = Color(0xFFB45309), crit = Color(0xFFD92D20), dark = false,
    clipAudio = Color(0xFF16A34A), clipVisual = Color(0xFF666666), clipFx = Color(0xFF0070F3),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

object Lofi {
    val c: Palette @Composable get() = LocalPalette.current
}

private fun ts(size: Int, weight: FontWeight = FontWeight.Normal, spacing: Double = 0.0, line: Int = 0, family: FontFamily = Geist) =
    TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, letterSpacing = spacing.sp, lineHeight = if (line > 0) line.sp else TextStyle.Default.lineHeight)

private val type = Typography(
    displayLarge = ts(40, FontWeight.Bold, -1.6),
    headlineMedium = ts(28, FontWeight.Bold, -0.9),
    titleLarge = ts(20, FontWeight.SemiBold, -0.4),
    titleMedium = ts(16, FontWeight.SemiBold, -0.2),
    bodyLarge = ts(16, line = 24),
    bodyMedium = ts(14, line = 20),
    bodySmall = ts(12, line = 16),
    labelLarge = ts(14, FontWeight.Medium),
    labelMedium = ts(12, FontWeight.Medium),
    labelSmall = ts(11, FontWeight.Medium, 0.2),
)

@Composable
fun LofiTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.ink, onPrimary = p.bg, secondary = p.ink, onSecondary = p.bg, tertiary = p.sky,
        background = p.bg, onBackground = p.ink, surface = p.surface, onSurface = p.ink,
        surfaceVariant = p.surfaceHi, onSurfaceVariant = p.muted, outline = p.lineStrong, outlineVariant = p.line,
        error = p.crit, surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceHi, surfaceContainerHighest = p.surfaceHi,
        surfaceContainerLow = p.surface, inverseSurface = p.ink, inverseOnSurface = p.bg, scrim = Color.Black,
    )
    val shapes = Shapes(small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(16.dp))
    CompositionLocalProvider(LocalPalette provides p) { MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content) }
}
