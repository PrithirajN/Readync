package io.github.prithirajn.readync.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.prithirajn.readync.util.Palette
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
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
import io.github.prithirajn.readync.R

/**
 * Riso: Readync printed like a risograph. Bold spot inks on paper (dark plum by night, warm white
 * by day), each section of the app in its own ink, a little misregistered, the paper a fine still
 * grain. One small colour comes from the cover of the book you're reading ([CpColors.wall]): the
 * ribbon, progress, a book's spine. The older device words (key, screen, ledge) still name the parts.
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
    /** Cover ink: one colour from the cover of the book you're reading, used in small places. */
    val wall: Color = Color(0xFFD7263D),
    /** The deep panel (stats, the services strip) and what's printed on it. */
    val screen: Color = Color(0xFF0E0509),
    val screenText: Color = Color(0xFFF7EAD9),
    val screenDim: Color = Color(0xFF9C8577),
    val screenOff: Color = Color(0xFF24131B),
    /** A plain key's face and the shadow it's printed over. */
    val keyTop: Color = Color(0xFFFFFFFF),
    val keyBottom: Color = Color(0xFFFFFFFF),
    val keyLedge: Color = Color(0xFFD9D5C6),
    /** The second ink a stamped key is misregistered over. */
    val signalLedge: Color = Color(0xFF19C2B2),
    /** Ink: a chosen chip or tab, and what's written on it. */
    val ink: Color = Color(0xFF1E1A16),
    val onInk: Color = Color(0xFFFFFFFF),
    /** Shadow colour under panels. */
    val shade: Color = Color(0x40000000),
    /** What's written on anything filled with an ink (always the dark ink: riso inks are bright). */
    val onAccent: Color = Color(0xFF14080B),
    /** The five spot inks, one per section: red, yellow, teal, pink, blue. */
    val inks: List<RisoInk> = DarkInks,
)

/** A spot ink: the ink itself, and the shade of it that reads as text on the paper. */
@Immutable
data class RisoInk(val ink: Color, val text: Color)

val DarkInks = listOf(
    RisoInk(Color(0xFFFF4152), Color(0xFFFF5C6B)),
    RisoInk(Color(0xFFFFB511), Color(0xFFFFB511)),
    RisoInk(Color(0xFF19C2B2), Color(0xFF2DD4C3)),
    RisoInk(Color(0xFFFF6FB5), Color(0xFFFF7EBD)),
    RisoInk(Color(0xFF7D96FF), Color(0xFF93A7FF)),
)

val LightInks = listOf(
    RisoInk(Color(0xFFE8323F), Color(0xFFB81E2B)),
    RisoInk(Color(0xFFF5A800), Color(0xFF8F5D00)),
    RisoInk(Color(0xFF12A493), Color(0xFF0A7268)),
    RisoInk(Color(0xFFF2559D), Color(0xFFB0286A)),
    RisoInk(Color(0xFF5B73F2), Color(0xFF3A50C4)),
)

/** By day: warm white paper, dark ink for text. */
private val Light = CpColors(
    page = Color(0xFFF2F1E6),
    surface = Color(0xFFFAF9F0),
    raised = Color(0xFFFFFFFF),
    text = Color(0xFF1E1A16),
    textMid = Color(0xFF3E3830),
    quiet = Color(0xFF6E665C),
    faint = Color(0xFFA39B90),
    divider = Color(0xFFE2DFD2),
    track = Color(0xFFE0DDCF),
    toggleOff = Color(0xFFD3CFC1),
    hairline = Color(0x14000000),
    field = Color(0xFFE9E7DA),
    accent = LightInks[0].ink,
    accentText = LightInks[0].text,
    accentTint = Color(0xFFFADADB),
    accentRing = Color(0xFFF3A0A6),
    error = Color(0xFFD23C2A),
    errorText = Color(0xFFB8311F),
    good = Color(0xFF0F9A8C),
    warn = Color(0xFFD98A00),
    cover = Color(0xFFE6E3D6),
    isDark = false,
    screen = Color(0xFF170A10),
    screenText = Color(0xFFF7EAD9),
    screenDim = Color(0xFFB39A8B),
    screenOff = Color(0xFF2B1820),
    keyTop = Color(0xFFFFFFFF),
    keyBottom = Color(0xFFFFFFFF),
    keyLedge = Color(0xFFD9D5C6),
    signalLedge = LightInks[2].ink,
    ink = Color(0xFF1E1A16),
    onInk = Color(0xFFFFFFFF),
    shade = Color(0x30000000),
    inks = LightInks,
)

/** By night: dark plum paper, bone-coloured text, the inks bright. */
private val Dark = CpColors(
    page = Color(0xFF170A10),
    surface = Color(0xFF1F1017),
    raised = Color(0xFF29151E),
    text = Color(0xFFF7EAD9),
    textMid = Color(0xFFE6D0BD),
    quiet = Color(0xFFC4A994),
    faint = Color(0xFF8A7466),
    divider = Color(0xFF33202A),
    track = Color(0xFF3A2530),
    toggleOff = Color(0xFF4A3340),
    hairline = Color(0x17FFFFFF),
    field = Color(0xFF24131B),
    accent = DarkInks[0].ink,
    accentText = DarkInks[0].text,
    accentTint = Color(0xFF3D1219),
    accentRing = Color(0xFF7A2230),
    error = Color(0xFFFF6B6B),
    errorText = Color(0xFFFFA3A3),
    good = Color(0xFF2DD4C3),
    warn = Color(0xFFFFB511),
    cover = Color(0xFF2B1820),
    isDark = true,
    wall = Color(0xFFFF4152),
    screen = Color(0xFF0E0509),
    screenText = Color(0xFFF7EAD9),
    screenDim = Color(0xFF9C8577),
    screenOff = Color(0xFF24131B),
    keyTop = Color(0xFF2E1922),
    keyBottom = Color(0xFF2E1922),
    keyLedge = Color(0xFF0A0306),
    signalLedge = DarkInks[2].ink,
    ink = Color(0xFFF7EAD9),
    onInk = Color(0xFF170A10),
    shade = Color(0x80000000),
    inks = DarkInks,
)

/**
 * Prints [content] in one of the five spot inks: its keys, chosen chips, lights and links. Each
 * tab is printed in its own, so the colour says where you are. The second ink a stamp is
 * misregistered over is two along the list.
 */
@Composable
fun SectionInk(index: Int, content: @Composable () -> Unit) {
    val base = LocalCp.current
    val c = remember(base, index) {
        val i = base.inks[index.mod(base.inks.size)]
        base.copy(
            accent = i.ink,
            accentText = i.text,
            accentTint = androidx.compose.ui.graphics.lerp(base.page, i.ink, if (base.isDark) 0.2f else 0.16f),
            accentRing = androidx.compose.ui.graphics.lerp(base.page, i.ink, 0.5f),
            signalLedge = base.inks[(index + 2).mod(base.inks.size)].ink,
        )
    }
    CompositionLocalProvider(LocalCp provides c) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = c.accent), content = content)
    }
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

@OptIn(ExperimentalTextApi::class)
private fun axes(res: Int, weight: Int, opsz: Float, italic: Boolean = false) =
    Font(res, FontWeight(weight), if (italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", opsz)))

/** Literata: everything you read, set for text sizes. */
val Body = FontFamily(
    axes(R.font.riso_literata, 400, 12f), axes(R.font.riso_literata, 500, 12f), axes(R.font.riso_literata, 600, 12f),
    axes(R.font.riso_literata, 700, 12f), axes(R.font.riso_literata, 800, 12f),
    axes(R.font.riso_literata_italic, 400, 12f, true), axes(R.font.riso_literata_italic, 600, 12f, true),
)

/** Bodoni Moda at display size: titles, folios, big numbers. */
val Display = FontFamily(
    axes(R.font.riso_bodoni, 500, 72f), axes(R.font.riso_bodoni, 700, 72f), axes(R.font.riso_bodoni, 900, 72f),
    axes(R.font.riso_bodoni_italic, 500, 72f, true), axes(R.font.riso_bodoni_italic, 700, 72f, true),
)

/** Bodoni Moda cut for smaller headings, so its hairlines hold up. */
val DisplaySmall = FontFamily(
    axes(R.font.riso_bodoni, 600, 18f), axes(R.font.riso_bodoni, 700, 18f), axes(R.font.riso_bodoni, 800, 18f),
    axes(R.font.riso_bodoni_italic, 600, 18f, true),
)

/** Literata cut for display, its heaviest: the big figures (its 1 keeps its flag, unlike Bodoni's). */
val Figures = FontFamily(axes(R.font.riso_literata, 800, 72f), axes(R.font.riso_literata, 900, 72f))

/** The UI face (Literata now; the name is kept so older screens keep compiling). */
val Inter = Body

/** Lora: kept for share cards and posters, which have their own look. */
val Lora = FontFamily(variable(R.font.lora, 500), variable(R.font.lora, 600), variable(R.font.lora, 700))

/** Caveat for handwritten notes. */
val Caveat = FontFamily(variable(R.font.caveat, 500), variable(R.font.caveat, 600))

/** Jura, for the photo studio's display numbers. */
val Pixel = FontFamily(Font(R.font.book_jura, FontWeight.Medium))

val Mono = FontFamily(Font(R.font.book_jura, FontWeight.Medium))

/** Small capitals: capitals and lowercase both drawn as small caps, spaced a little. */
private const val SmallCaps = "'smcp', 'c2sc', 'lnum'"

object CpType {
    val pageTitle = TextStyle(fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.4).sp)
    val section = TextStyle(fontFamily = DisplaySmall, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 26.sp, letterSpacing = (-0.1).sp)
    /** A small-caps label above a block, in the section's ink. */
    val eyebrow = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.9.sp, fontFeatureSettings = SmallCaps)
    val body = TextStyle(fontFamily = Body, fontSize = 14.sp, lineHeight = 21.sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val small = TextStyle(fontFamily = Body, fontSize = 12.sp, lineHeight = 17.sp)
    val label = TextStyle(fontFamily = Body, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val chip = TextStyle(fontFamily = Body, fontSize = 10.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
    /** Printed labels: small capitals, spaced. */
    val mono = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, lineHeight = 14.sp, letterSpacing = 0.7.sp, fontFeatureSettings = SmallCaps)
    /** The big numbers: Bodoni at its blackest. */
    val screenNumber = TextStyle(fontFamily = Figures, fontWeight = FontWeight.Black, fontSize = 52.sp, lineHeight = 52.sp, letterSpacing = (-1).sp, fontFeatureSettings = "'lnum'")
    val screenSmall = TextStyle(fontFamily = Figures, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 24.sp, fontFeatureSettings = "'lnum'")
    /** Long reading text (descriptions): Literata, comfortable. */
    val reading = TextStyle(fontFamily = Body, fontSize = 15.sp, lineHeight = 24.sp)
}

object CpShapes {
    // Lopsided corners, like blocks cut by hand for printing.
    val card = RoundedCornerShape(topStart = 10.dp, topEnd = 24.dp, bottomEnd = 10.dp, bottomStart = 24.dp)
    val control = RoundedCornerShape(topStart = 16.dp, topEnd = 7.dp, bottomEnd = 16.dp, bottomStart = 7.dp)
    val screen = RoundedCornerShape(topStart = 10.dp, topEnd = 24.dp, bottomEnd = 10.dp, bottomStart = 24.dp)
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
    // Handed down too, so a page that turns itself dark (the studios) can take the same colour in its dark form.
    val outer = LocalAccentSeed.current
    CompositionLocalProvider(LocalAccentSeed provides (seed ?: outer)) { AccentThemeInner(seed, content) }
}

/** The cover colour the accent was taken from, where one was (null keeps the orange). */
val LocalAccentSeed = compositionLocalOf<Int?> { null }

@Composable
private fun AccentThemeInner(seed: Int?, content: @Composable () -> Unit) {
    val base = LocalCp.current
    // The cover gives one ink, bright enough to print on this paper; the sections keep their own.
    val signal = seed?.let { Palette.signalFrom(it, base.isDark) }
    val wall by animateColorAsState(signal?.let { Color(it.accent) } ?: base.wall, tween<Color>(durationMillis = 700), label = "coverInk")
    CompositionLocalProvider(LocalCp provides base.copy(wall = wall), content = content)
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

/** Always the dark paper, whatever the phone is set to: for the photo studio, where a dark room shows the picture best. */
@Composable
fun DarkGadget(content: @Composable () -> Unit) {
    val outer = LocalCp.current
    val signal = LocalAccentSeed.current?.let { Palette.signalFrom(it, true) }
    // Keeps the section's ink (in its dark-paper form) and the book's colour.
    val i = outer.inks.indexOfFirst { it.ink == outer.accent }.coerceAtLeast(0)
    val ink = DarkInks[i]
    val c = Dark.copy(
        wall = signal?.let { Color(it.accent) } ?: Dark.wall,
        accent = ink.ink, accentText = ink.text,
        accentTint = androidx.compose.ui.graphics.lerp(Dark.page, ink.ink, 0.2f),
        accentRing = androidx.compose.ui.graphics.lerp(Dark.page, ink.ink, 0.5f),
        signalLedge = DarkInks[(i + 2) % 5].ink,
    )
    CompositionLocalProvider(LocalCp provides c) {
        MaterialTheme(colorScheme = darkColorScheme(primary = c.accent, background = c.page, surface = c.surface, onSurface = c.text, onBackground = c.text, error = c.error), content = content)
    }
}
