package io.github.prithirajn.trackersync.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.ui.theme.Lora
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What happens when a book is set aside (Did not finish), as you choose on the DNF sheet: a short
 * scene in the book's genre, the book taken off your bookcases, and whether it counts toward this
 * year's books. Kept with the app's other data (Snapshot.extras).
 */
object DnfPrefs {
    private const val SCENE = "dnf.scene"
    private const val SHELF = "dnf.offShelves"
    private const val COUNT = "dnf.counts"

    fun scene(s: Snapshot) = s.extras[SCENE] != "off"
    fun offShelves(s: Snapshot) = s.extras[SHELF] == "on"
    fun counts(s: Snapshot) = s.extras[COUNT] == "on"

    fun withScene(s: Snapshot, on: Boolean) = s.copy(extras = s.extras + (SCENE to if (on) "on" else "off"))
    fun withOffShelves(s: Snapshot, on: Boolean) = s.copy(extras = s.extras + (SHELF to if (on) "on" else "off"))
    fun withCounts(s: Snapshot, on: Boolean) = s.copy(extras = s.extras + (COUNT to if (on) "on" else "off"))
}

/** The scene for a book just set aside: shown over everything, once. */
object DnfScenes {
    data class Shown(val title: String, val cover: String?, val percent: Int, val style: Style, val at: Long = System.nanoTime())

    val show = MutableStateFlow<Shown?>(null)

    enum class Style(val line: String) {
        /** Fantasy: the pages smoulder and embers drift up. */
        EMBERS("Back to the dragon's hoard"),
        /** Science fiction: it drifts off among the stars. */
        STARS("Drifting back into the dark"),
        /** Romance: petals fall. It wasn't meant to be. */
        PETALS("Not every story is a love story"),
        /** Horror, thrillers, crime and mystery: rain on the window. */
        RAIN("Case closed, for now"),
        /** Non-fiction: loose pages flutter away. */
        PAGES("Filed under maybe later"),
        /** Everything else: autumn leaves. */
        LEAVES("Back to the shelf, no hard feelings"),
    }

    /** The scene for a book, from its genres (Hardcover, StoryGraph, Seekquel) and title tags. */
    fun styleFor(s: Snapshot, b: Book): Style {
        val words = (s.facts[b.document]?.genres.orEmpty() + b.titleTags).joinToString(" ").lowercase()
        return when {
            Regex("fantasy|litrpg|cultivation|myth|fairy|dragon|progression").containsMatchIn(words) -> Style.EMBERS
            Regex("science fiction|sci-fi|scifi|space|dystop|cyberpunk|alien").containsMatchIn(words) -> Style.STARS
            Regex("romance|romantic|love|romcom").containsMatchIn(words) -> Style.PETALS
            Regex("horror|thriller|crime|mystery|suspense|noir|gothic|detective").containsMatchIn(words) -> Style.RAIN
            Regex("non-fiction|nonfiction|memoir|biography|history|self-help|science|essay|business|philosophy|religio|spiritual").containsMatchIn(words) -> Style.PAGES
            else -> Style.LEAVES
        }
    }
}

/**
 * The scene itself: the cover in the middle tipping back and fading, with the genre's weather
 * around it (embers, stars, petals, rain, pages or leaves), and where you stopped. About three
 * seconds; a tap ends it sooner.
 */
@Composable
fun DnfSceneOverlay() {
    val shown by DnfScenes.show.collectAsState()
    val s = shown ?: return
    val t = remember(s.at) { Animatable(0f) }
    LaunchedEffect(s.at) {
        t.snapTo(0f)
        t.animateTo(1f, tween(3200, easing = LinearEasing))
        DnfScenes.show.value = null
    }
    Dialog(onDismissRequest = { DnfScenes.show.value = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val p = t.value
        // In for the first 15%, out for the last 15%.
        val fade = when { p < 0.15f -> p / 0.15f; p > 0.85f -> (1f - p) / 0.15f; else -> 1f }.coerceIn(0f, 1f)
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = fade }.background(sky(s.style))
                .clickable(MutableInteractionSource(), indication = null) { DnfScenes.show.value = null },
            contentAlignment = Alignment.Center,
        ) {
            Weather(s.style, p, Modifier.fillMaxSize())
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                // The cover tips back and sinks a little as the scene goes on.
                Box(Modifier.graphicsLayer {
                    rotationX = 26f * p
                    translationY = 40f * p
                    alpha = 1f - 0.45f * p
                    cameraDistance = 14f * density
                }) {
                    BookCover(s.cover, s.title, 132.dp, 198.dp)
                }
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 22.dp))
                Text("Set aside", fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, color = Color.White, textAlign = TextAlign.Center)
                Text(s.style.line, fontSize = 15.sp, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                Text("Stopped at ${s.percent}%", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun sky(style: DnfScenes.Style) = androidx.compose.ui.graphics.Brush.verticalGradient(
    when (style) {
        DnfScenes.Style.EMBERS -> listOf(Color(0xFF1A0E08), Color(0xFF3B1608), Color(0xFF6A2A0C))
        DnfScenes.Style.STARS -> listOf(Color(0xFF02030A), Color(0xFF0A1030), Color(0xFF1B1446))
        DnfScenes.Style.PETALS -> listOf(Color(0xFF2A1220), Color(0xFF4A1E34), Color(0xFF6E2E48))
        DnfScenes.Style.RAIN -> listOf(Color(0xFF07090C), Color(0xFF141B22), Color(0xFF1E2830))
        DnfScenes.Style.PAGES -> listOf(Color(0xFF1C1A16), Color(0xFF2C2822), Color(0xFF3A342B))
        DnfScenes.Style.LEAVES -> listOf(Color(0xFF1B140C), Color(0xFF33230F), Color(0xFF4A3214))
    }
)

/** The particles: each with a start place, speed and size from a fixed seed, so a scene plays the same. */
@Composable
private fun Weather(style: DnfScenes.Style, p: Float, modifier: Modifier) {
    val bits = remember(style) {
        val r = java.util.Random(style.ordinal * 104729L + 7)
        val n = when (style) { DnfScenes.Style.RAIN -> 90; DnfScenes.Style.STARS -> 120; else -> 46 }
        List(n) { FloatArray(6) { r.nextFloat() } }
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sec = p * 3.2f
        for (b in bits) {
            val (x0, y0, speed, sz, spin, tint) = Six(b)
            when (style) {
                DnfScenes.Style.EMBERS -> {
                    // Rising from below, swaying, glowing then going out.
                    val y = h * (1.05f - ((y0 * 0.4f + sec * (0.18f + speed * 0.25f)) % 1.15f))
                    val x = w * x0 + kotlin.math.sin((sec * 2f + spin * 6f).toDouble()).toFloat() * 18f
                    val life = (1f - (h - y) / h).coerceIn(0f, 1f)
                    val col = if (tint > 0.5f) Color(0xFFFFB347) else Color(0xFFFF6A2B)
                    drawCircle(col.copy(alpha = 0.25f * life), radius = 7f + sz * 9f, center = Offset(x, y))
                    drawCircle(col.copy(alpha = 0.9f * life), radius = 1.5f + sz * 3f, center = Offset(x, y))
                }
                DnfScenes.Style.STARS -> {
                    // A field of stars sliding slowly past, twinkling.
                    val x = ((x0 * w) - sec * (20f + speed * 60f)).let { ((it % w) + w) % w }
                    val tw = 0.4f + 0.6f * kotlin.math.abs(kotlin.math.sin((sec * (1f + spin * 3f) + tint * 6f).toDouble())).toFloat()
                    drawCircle(Color.White.copy(alpha = 0.85f * tw), radius = 0.8f + sz * 2.2f, center = Offset(x, y0 * h))
                }
                DnfScenes.Style.PETALS -> fallingShape(w, h, sec, x0, y0, speed, sz, spin, if (tint > 0.5f) Color(0xFFF7A1B5) else Color(0xFFE56B8A), petal = true)
                DnfScenes.Style.LEAVES -> fallingShape(w, h, sec, x0, y0, speed, sz, spin,
                    listOf(Color(0xFFD9822B), Color(0xFFB5541C), Color(0xFFE3B23C))[(tint * 2.99f).toInt()], petal = false)
                DnfScenes.Style.PAGES -> {
                    // Loose pages fluttering down and across.
                    val y = h * (((y0 - 0.2f) + sec * (0.12f + speed * 0.14f)) % 1.2f) - h * 0.1f
                    val x = w * x0 + sec * 40f * (spin - 0.5f) + kotlin.math.sin((sec * 2.4f + tint * 6f).toDouble()).toFloat() * 30f
                    val pw = 22f + sz * 26f
                    rotate(((spin - 0.5f) * 140f * sec + tint * 60f), Offset(x, y)) {
                        drawRect(Color(0xFFF1E9D8).copy(alpha = 0.85f), Offset(x - pw / 2, y - pw * 0.65f), Size(pw, pw * 1.3f))
                        for (i in 1..4) drawRect(Color(0xFF9C8E75).copy(alpha = 0.5f), Offset(x - pw / 2 + pw * 0.15f, y - pw * 0.65f + i * pw * 0.24f), Size(pw * 0.7f, 1.4f))
                    }
                }
                DnfScenes.Style.RAIN -> {
                    // Streaks of rain, slanting, and now and then a flash.
                    val len = 18f + sz * 26f
                    val y = h * ((y0 + sec * (0.9f + speed * 0.8f)) % 1.1f) - h * 0.05f
                    val x = w * x0 + (y / h) * 40f
                    drawLine(Color(0xFF9FB4C8).copy(alpha = 0.45f), Offset(x, y), Offset(x - 6f, y + len), strokeWidth = 1.6f)
                }
            }
        }
        if (style == DnfScenes.Style.RAIN) {
            val flash = listOf(0.9f, 1.05f).minOf { kotlin.math.abs(sec - it) }
            if (flash < 0.06f) drawRect(Color.White.copy(alpha = 0.18f * (1f - flash / 0.06f)))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.fallingShape(
    w: Float, h: Float, sec: Float, x0: Float, y0: Float, speed: Float, sz: Float, spin: Float, col: Color, petal: Boolean,
) {
    val y = h * (((y0 - 0.2f) + sec * (0.14f + speed * 0.18f)) % 1.25f) - h * 0.1f
    val x = w * x0 + kotlin.math.sin((sec * 1.8f + spin * 6f).toDouble()).toFloat() * 36f
    val s = (if (petal) 8f else 11f) + sz * 10f
    rotate((spin - 0.5f) * 300f * sec + spin * 90f, Offset(x, y)) {
        if (petal) {
            drawOval(col.copy(alpha = 0.85f), Offset(x - s * 0.5f, y - s * 0.3f), Size(s, s * 0.6f))
        } else {
            drawOval(col.copy(alpha = 0.9f), Offset(x - s * 0.5f, y - s * 0.35f), Size(s, s * 0.7f))
            drawLine(Color(0xFF5A3410).copy(alpha = 0.8f), Offset(x - s * 0.5f, y), Offset(x + s * 0.5f, y), strokeWidth = 1.2f)
        }
    }
}

private data class Six(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    constructor(v: FloatArray) : this(v[0], v[1], v[2], v[3], v[4], v[5])
}
