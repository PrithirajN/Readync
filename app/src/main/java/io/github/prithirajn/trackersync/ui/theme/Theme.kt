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
 * Gadget: Readync as a little reading device. A warm light-grey body (graphite at night),
 * panels, a dark screen, pressable keys, ink for what's chosen and one signal orange for what's
 * on or happening. The cover of the book you're reading colours the wallpaper behind it.
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
    // ---- Gadget: the device's own parts ----
    /** The wallpaper's colour: the cover of the book you're reading (the accent stays the signal orange). */
    val wall: Color = Color(0xFFFFB08A),
    /** The dark screen on the device (book page, stats) and what's written on it. */
    val screen: Color = Color(0xFF141513),
    val screenText: Color = Color(0xFFF4F2EA),
    val screenDim: Color = Color(0xFF8B8A82),
    val screenOff: Color = Color(0xFF2B2C29),
    /** A key's face (top to bottom) and the ledge it sits on. */
    val keyTop: Color = Color(0xFFF6F5F1),
    val keyBottom: Color = Color(0xFFE6E3DB),
    val keyLedge: Color = Color(0xFFCFCBC0),
    /** The signal key's ledge (under the orange). */
    val signalLedge: Color = Color(0xFFC24618),
    /** Ink: the dock, a chosen tab, and what's written on them. */
    val ink: Color = Color(0xFF2A2620),
    val onInk: Color = Color(0xFFFFFFFF),
    /** Shadow colour under the device's panels. */
    val shade: Color = Color(0x40783214),
)

/** Gadget, by day: warm light-grey body, white panels, ink and one signal orange. */
private val Light = CpColors(
    page = Color(0xFFEEECE6),
    surface = Color(0xFFF8F7F3),
    raised = Color(0xFFFFFFFF),
    text = Color(0xFF221E19),
    textMid = Color(0xFF4A453D),
    quiet = Color(0xFF77716A),
    faint = Color(0xFFA7A198),
    divider = Color(0xFFE4E1D9),
    track = Color(0xFFE2DFD7),
    toggleOff = Color(0xFFD8D4CA),
    hairline = Color(0x0F000000),
    field = Color(0xFFEAE7E0),
    accent = Color(0xFFFF6A3D),
    accentText = Color(0xFFC94A1C),
    accentTint = Color(0xFFFFE6DC),
    accentRing = Color(0xFFFFC2AA),
    error = Color(0xFFD23C2A),
    errorText = Color(0xFFB8311F),
    good = Color(0xFF2F9E5B),
    warn = Color(0xFFE07A12),
    cover = Color(0xFFE6E3DB),
    isDark = false,
)

/** Gadget, by night: graphite body, a black screen, keys a shade lighter, the same orange glowing. */
private val Dark = CpColors(
    page = Color(0xFF111110),
    surface = Color(0xFF1C1B19),
    raised = Color(0xFF26251F),
    text = Color(0xFFF3F1EC),
    textMid = Color(0xFFD5D1C9),
    quiet = Color(0xFFA19C93),
    faint = Color(0xFF6D6961),
    divider = Color(0xFF2A2925),
    track = Color(0xFF2C2B27),
    toggleOff = Color(0xFF3A3833),
    hairline = Color(0x14FFFFFF),
    field = Color(0xFF23221F),
    accent = Color(0xFFFF7A4D),
    accentText = Color(0xFFFF9C78),
    accentTint = Color(0xFF3A1D12),
    accentRing = Color(0xFF6B2E18),
    error = Color(0xFFF87171),
    errorText = Color(0xFFFCA5A5),
    good = Color(0xFF4ADE80),        // green-400
    warn = Color(0xFFFBBF24),        // amber-400
    cover = Color(0xFF26231F),
    isDark = true,
    wall = Color(0xFF8A3A1E),
    screen = Color(0xFF060606),
    screenText = Color(0xFFF4F2EA),
    screenDim = Color(0xFF7D7A72),
    screenOff = Color(0xFF1E1E1C),
    keyTop = Color(0xFF2E2D29),
    keyBottom = Color(0xFF23221F),
    keyLedge = Color(0xFF0A0A09),
    signalLedge = Color(0xFF8E2C0C),
    ink = Color(0xFF2C2B27),
    onInk = Color(0xFFFFFFFF),
    shade = Color(0x80000000),
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

/** Pixelify Sans: the device screen's big numbers. */
val Pixel = FontFamily(Font(R.font.book_pixelify))

/** IBM Plex Mono: the device's tiny printed labels. */
val Mono = FontFamily(Font(R.font.book_plexmono))

private const val InterFeatures = "'cv02', 'cv03', 'cv04', 'cv11'"

object CpType {
    // Titles in Inter, set tight and heavy like the print on a device; Lora stays for long reading text.
    val pageTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-1.1).sp, fontFeatureSettings = InterFeatures)
    val section = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.4).sp, fontFeatureSettings = InterFeatures)
    val eyebrow = TextStyle(fontFamily = Caveat, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 24.sp)
    val body = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = InterFeatures)
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val small = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = InterFeatures)
    val label = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = InterFeatures)
    val chip = TextStyle(fontFamily = Inter, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = InterFeatures)
    /** The device's printed labels: tiny, spaced, monospaced. */
    val mono = TextStyle(fontFamily = Mono, fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.8.sp)
    /** The screen's big pixel number. */
    val screenNumber = TextStyle(fontFamily = Pixel, fontSize = 52.sp, lineHeight = 52.sp)
    val screenSmall = TextStyle(fontFamily = Pixel, fontSize = 22.sp, lineHeight = 24.sp)
    /** Long reading text (descriptions): Lora, comfortable. */
    val reading = TextStyle(fontFamily = Lora, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 24.sp)
}

object CpShapes {
    val card = RoundedCornerShape(22.dp)       // the device's panels
    val control = RoundedCornerShape(16.dp)    // keys
    val screen = RoundedCornerShape(16.dp)     // the dark screen
    val field = RoundedCornerShape(8.dp)       // rounded-lg
    val pill = RoundedCornerShape(50)          // rounded-full
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}

val LocalCp = staticCompositionLocalOf { Light }

object Cp {
    val colors: CpColors @Composable get() = LocalCp.current
}

/**
 * The book you're reading colours the device: the wallpaper behind it ([CpColors.wall]) and the
 * signal colour of its keys, lights and lit segments, both easing from the old colour to the new.
 * A cover with hardly any colour (black, white, grey) keeps the signal orange.
 */
@Composable
fun AccentTheme(seed: Int?, content: @Composable () -> Unit) {
    val base = LocalCp.current
    val set = seed?.let { Palette.accentFrom(it, base.isDark) }
    val signal = seed?.let { Palette.signalFrom(it, base.isDark) }
    val ease = tween<Color>(durationMillis = 900)
    val wall by animateColorAsState(set?.let { Color(it.accent) } ?: base.wall, ease, label = "wall")
    val accent by animateColorAsState(signal?.let { Color(it.accent) } ?: base.accent, ease, label = "accent")
    val accentText by animateColorAsState(signal?.let { Color(it.text) } ?: base.accentText, ease, label = "accentText")
    val accentTint by animateColorAsState(signal?.let { Color(it.tint) } ?: base.accentTint, ease, label = "accentTint")
    val accentRing by animateColorAsState(signal?.let { Color(it.ring) } ?: base.accentRing, ease, label = "accentRing")
    val ledge by animateColorAsState(signal?.let { Color(Palette.deeper(it.accent)) } ?: base.signalLedge, ease, label = "ledge")
    CompositionLocalProvider(
        LocalCp provides base.copy(wall = wall, accent = accent, accentText = accentText, accentTint = accentTint, accentRing = accentRing, signalLedge = ledge),
    ) {
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

/** Always the dark device, whatever the phone is set to: for the photo studio, where a dark room shows the picture best. */
@Composable
fun DarkGadget(content: @Composable () -> Unit) {
    val c = Dark.copy(wall = LocalCp.current.wall)
    CompositionLocalProvider(LocalCp provides c) {
        MaterialTheme(colorScheme = darkColorScheme(primary = c.accent, background = c.page, surface = c.surface, onSurface = c.text, onBackground = c.text, error = c.error), content = content)
    }
}
