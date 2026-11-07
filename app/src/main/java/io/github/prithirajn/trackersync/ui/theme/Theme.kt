package io.github.prithirajn.trackersync.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import io.github.prithirajn.trackersync.util.Palette
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.prithirajn.trackersync.R

/**
 * Tokens from crosspoint-sync's app/src/index.css (the "Free-Ink" set): Tailwind stone
 * neutrals, one forest-green accent, white cards on a warm off-white page. Dark mode uses
 * the remapped values from the same file.
 */
@Immutable
data class CpColors(
    val page: Color,
    val surface: Color,
    val raised: Color,
    val text: Color,
    val textMid: Color,
    val quiet: Color,
    val faint: Color,
    val divider: Color,
    val track: Color,
    val toggleOff: Color,
    val hairline: Color,
    val field: Color,
    val accent: Color,
    val accentText: Color,
    val accentTint: Color,
    val accentRing: Color,
    val error: Color,
    val errorText: Color,
    /** Health lights: all good, needs a look. (Bad uses [error]; off uses [faint].) */
    val good: Color,
    val warn: Color,
    /** Placeholder cover paper for books without artwork (--color-cover). */
    val cover: Color,
    val isDark: Boolean,
)

private val Light = CpColors(
    page = Color(0xFFFAFAF9),        // stone-50
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFFFFFFF),
    text = Color(0xFF1C1917),        // stone-900
    textMid = Color(0xFF44403C),     // stone-700
    quiet = Color(0xFF78716C),       // stone-500
    faint = Color(0xFFA8A29E),       // stone-400
    divider = Color(0xFFF5F5F4),     // stone-100
    track = Color(0xFFE7E5E4),       // stone-200
    toggleOff = Color(0xFFD6D3D1),   // stone-300
    hairline = Color(0x0D0C0A09),    // stone-950 at 5%
    field = Color(0xFFF5F5F4),       // stone-100
    accent = Color(0xFF4A7A62),      // brand-500
    accentText = Color(0xFF3D6652),  // brand-600
    accentTint = Color(0xFFF0F5F3),  // brand-50
    accentRing = Color(0xFFB3CFC2),  // brand-200
    error = Color(0xFFDC2626),       // red-600
    errorText = Color(0xFFB91C1C),   // red-700
    good = Color(0xFF16A34A),        // green-600
    warn = Color(0xFFD97706),        // amber-600
    cover = Color(0xFFF5F4EF),
    isDark = false,
)

private val Dark = CpColors(
    page = Color(0xFF121110),
    surface = Color(0xFF1B1917),
    raised = Color(0xFF3A3531),
    text = Color(0xFFF3F0ED),
    textMid = Color(0xFFD3CEC9),
    quiet = Color(0xFFA09993),
    faint = Color(0xFF6F6963),
    divider = Color(0xFF221F1D),
    track = Color(0xFF2E2A27),
    toggleOff = Color(0xFF423D39),
    hairline = Color(0x1AFBF9F7),
    field = Color(0xFF221F1D),
    accent = Color(0xFF4F8268),
    accentText = Color(0xFF8FB9A6),
    accentTint = Color(0xFF16211C),
    accentRing = Color(0xFF284738),
    error = Color(0xFFF87171),
    errorText = Color(0xFFFCA5A5),
    good = Color(0xFF4ADE80),        // green-400
    warn = Color(0xFFFBBF24),        // amber-400
    cover = Color(0xFF26231F),
    isDark = true,
)

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Inter for UI and body text. */
val Inter = FontFamily(variable(R.font.inter, 400), variable(R.font.inter, 500), variable(R.font.inter, 600), variable(R.font.inter, 700))

/** Lora for page titles and section headings. */
val Lora = FontFamily(variable(R.font.lora, 500), variable(R.font.lora, 600), variable(R.font.lora, 700))

/** Caveat for the handwritten eyebrow. */
val Caveat = FontFamily(variable(R.font.caveat, 500), variable(R.font.caveat, 600))

private const val InterFeatures = "'cv02', 'cv03', 'cv04', 'cv11'"

object CpType {
    val pageTitle = TextStyle(fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp)
    val section = TextStyle(fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp)
    val eyebrow = TextStyle(fontFamily = Caveat, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 24.sp)
    val body = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = InterFeatures)
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val small = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = InterFeatures)
    val label = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = InterFeatures)
    val chip = TextStyle(fontFamily = Inter, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = InterFeatures)
}

object CpShapes {
    val card = RoundedCornerShape(12.dp)       // rounded-xl
    val control = RoundedCornerShape(16.dp)    // rounded-2xl, 48-56dp controls
    val field = RoundedCornerShape(8.dp)       // rounded-lg
    val pill = RoundedCornerShape(50)          // rounded-full
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}

val LocalCp = staticCompositionLocalOf { Light }

object Cp {
    val colors: CpColors @Composable get() = LocalCp.current
}

/**
 * Re-colours the app's accent (progress bars, buttons, tabs, highlights) from [seed], a book
 * cover's colour, easing from the old accent to the new. Null keeps the default green.
 */
@Composable
fun AccentTheme(seed: Int?, content: @Composable () -> Unit) {
    val base = LocalCp.current
    val set = seed?.let { Palette.accentFrom(it, base.isDark) }
    val spec = tween<Color>(durationMillis = 900)
    val accent by animateColorAsState(set?.let { Color(it.accent) } ?: base.accent, spec, label = "accent")
    val text by animateColorAsState(set?.let { Color(it.text) } ?: base.accentText, spec, label = "accentText")
    val tint by animateColorAsState(set?.let { Color(it.tint) } ?: base.accentTint, spec, label = "accentTint")
    val ring by animateColorAsState(set?.let { Color(it.ring) } ?: base.accentRing, spec, label = "accentRing")
    val c = base.copy(accent = accent, accentText = text, accentTint = tint, accentRing = ring)
    CompositionLocalProvider(LocalCp provides c) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = accent), content = content)
    }
}

@Composable
fun TrackerSyncTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(primary = c.accent, background = c.page, surface = c.surface, onSurface = c.text, onBackground = c.text, error = c.error)
    } else {
        lightColorScheme(primary = c.accent, background = c.page, surface = c.surface, onSurface = c.text, onBackground = c.text, error = c.error)
    }
    CompositionLocalProvider(LocalCp provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
