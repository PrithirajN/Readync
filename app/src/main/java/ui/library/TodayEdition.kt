package io.github.prithirajn.readync.ui

import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.prithirajn.readync.data.Book
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.sync.Shelves
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpShapes
import io.github.prithirajn.readync.ui.theme.CpType
import io.github.prithirajn.readync.ui.theme.Display
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

/** The book you're reading now: the latest one on the Reading shelf. */
internal fun currentBook(state: Snapshot): Book? =
    state.books.values.filter { Shelves.tabOf(it) == ReadStatus.READING }.maxByOrNull { it.timestamp }

/**
 * Today, printed like the front of a riso zine: today's folio (the day of the year) in big
 * Bodoni sliding into register, a line about where you are in your book with its title swept in
 * the cover's colour, the page you're on, and a ribbon down the side whose length is
 * your place, swaying a little. Tapping it opens the book.
 */
@Composable
internal fun TodayEdition(state: Snapshot, book: Book, modifier: Modifier = Modifier) {
    val c = Cp.colors
    val view = androidx.compose.ui.platform.LocalView.current
    val animate = LocalAnimationsOn.current
    val today = LocalDate.now()
    val ink = rememberBookInk(book.coverUrl)
    val pct = book.percentage.toFloat().coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .bouncyClickable(CpShapes.card, pressedScale = 0.985f, role = Role.Button, onClickLabel = "Open ${book.displayTitle}") {
                Haptics.tick(view)
                AppActions.pending.value = AppAction.OpenBook(book.document)
            },
    ) {
        Column(Modifier.fillMaxWidth().padding(end = 44.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                RegisteredText(
                    today.dayOfYear.toString(),
                    CpType.screenNumber.copy(fontSize = 64.sp, lineHeight = 60.sp), replay = LocalTabVisible.current,
                )
                Column(Modifier.padding(start = 12.dp, bottom = 8.dp)) {
                    Text(
                        "days into ${today.year}",
                        style = CpType.mono, color = c.accentText,
                    )
                    Text(
                        today.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())),
                        style = CpType.mono, color = c.quiet,
                    )
                }
            }
            Text(
                "In which you are ${phrase(pct)}",
                fontFamily = Display, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium,
                fontSize = 23.sp, lineHeight = 28.sp, color = c.text,
                modifier = Modifier.padding(top = 6.dp),
            )
            SweptTitle(book.displayTitle, ink, animate)
            // Where you are in it (today's pages and the streak are in the panel just below).
            val total = book.pageCount
            val big = androidx.compose.ui.text.SpanStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = c.text)
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    if (total != null && total > 0) {
                        append("page ")
                        pushStyle(big); append(((pct * total).toInt().coerceIn(0, total)).toString()); pop()
                        append(" of $total")
                    } else {
                        pushStyle(big); append("${book.percent}%"); pop()
                        append(" read")
                    }
                },
                style = CpType.body, color = c.textMid,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Ribbon(pct, ink, animate, Modifier.align(Alignment.TopEnd)) {
            AppActions.pending.value = AppAction.OpenBook(book.document)
        }
    }
}

/** The title, with a highlighter of the cover's colour sweeping across it line by line. */
@Composable
private fun SweptTitle(title: String, ink: androidx.compose.ui.graphics.Color, animate: Boolean) {
    val c = Cp.colors
    val sweep = remember(title) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(title) { sweep.animateTo(1f, tween(durationMillis = 900, delayMillis = 350)) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        title,
        fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 27.sp, lineHeight = 33.sp,
        color = c.text,
        maxLines = 3,
        onTextLayout = { layout = it },
        modifier = Modifier
            .padding(top = 2.dp)
            .drawBehind {
                val l = layout ?: return@drawBehind
                val lines = l.lineCount
                val total = (0 until lines).sumOf { (l.getLineRight(it) - l.getLineLeft(it)).toDouble() }.toFloat()
                var budget = total * sweep.value
                for (i in 0 until lines) {
                    val left = l.getLineLeft(i)
                    val w = (l.getLineRight(i) - left)
                    val drawn = budget.coerceAtMost(w)
                    if (drawn <= 0f) break
                    val top = l.getLineTop(i) + (l.getLineBottom(i) - l.getLineTop(i)) * 0.42f
                    val bottom = l.getLineBottom(i) - 1.dp.toPx()
                    val path = Path().apply {
                        addRoundRect(androidx.compose.ui.geometry.RoundRect(
                            left - 3.dp.toPx(), top, left + drawn + 3.dp.toPx(), bottom,
                            topLeftCornerRadius = CornerRadius(3.dp.toPx()), topRightCornerRadius = CornerRadius(9.dp.toPx()),
                            bottomRightCornerRadius = CornerRadius(4.dp.toPx()), bottomLeftCornerRadius = CornerRadius(10.dp.toPx()),
                        ))
                    }
                    drawPath(path, ink.copy(alpha = if (c.isDark) 0.62f else 0.5f))
                    budget -= w
                }
            },
    )
}

/** The ribbon's cloth: a chain of points hanging from its top, moved by gravity, a pull or a jolt. */
private class Cloth(val n: Int) {
    val x = FloatArray(n); val y = FloatArray(n)
    val px = FloatArray(n); val py = FloatArray(n)
    var seg = 0f
    fun hang(seg: Float) { this.seg = seg; for (i in 0 until n) { x[i] = 0f; y[i] = seg * i; px[i] = 0f; py[i] = seg * i } }
    /** A push sideways, more at the free end than near the pin (as a breeze catches cloth). */
    fun gust(v: Float) { for (i in 1 until n) px[i] -= v * (i.toFloat() / (n - 1)) }
    /** One step: moves each point on by its own speed plus gravity, then pulls the chain back to its length. */
    /** How far either side of the pin the cloth may go: it stays inside its box. */
    var maxX = Float.MAX_VALUE
    var maxY = Float.MAX_VALUE
    fun step(dt: Float, gravity: Float, grab: Int, gx: Float, gy: Float): Float {
        var energy = 0f
        val damp = 0.965f
        for (i in 1 until n) {
            val vx = (x[i] - px[i]) * damp; val vy = (y[i] - py[i]) * damp
            px[i] = x[i]; py[i] = y[i]
            x[i] += vx; y[i] += vy + gravity * dt * dt
            energy += vx * vx + vy * vy
        }
        repeat(10) {
            x[0] = 0f; y[0] = 0f
            if (grab > 0) { x[grab] = gx; y[grab] = gy }
            for (i in 0 until n - 1) {
                val dx = x[i + 1] - x[i]; val dy = y[i + 1] - y[i]
                val d = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
                val diff = (d - seg) / d
                val aPinned = i == 0 || i == grab
                val bPinned = i + 1 == grab
                when {
                    aPinned && bPinned -> Unit
                    aPinned -> { x[i + 1] -= dx * diff; y[i + 1] -= dy * diff }
                    bPinned -> { x[i] += dx * diff; y[i] += dy * diff }
                    else -> { x[i] += dx * diff * 0.5f; y[i] += dy * diff * 0.5f; x[i + 1] -= dx * diff * 0.5f; y[i + 1] -= dy * diff * 0.5f }
                }
            }
            // The walls of its box: a swing or a pull sideways stops at the edge, as against a page.
            for (i in 1 until n) {
                if (x[i] > maxX) { x[i] = maxX; px[i] = x[i] } else if (x[i] < -maxX) { x[i] = -maxX; px[i] = x[i] }
                if (y[i] > maxY) { y[i] = maxY; py[i] = y[i] }
            }
        }
        return energy
    }
}

/**
 * A ribbon bookmark that behaves like one: real cloth hanging from the top of the page, as long as
 * how far you are. Pull it anywhere and it bends from where you hold it, swings and curls when let
 * go, and settles hanging straight; pull it well down and it opens the book (with a click you can
 * feel). Shake the phone and it stirs as if in a breeze. It only moves (and only draws new frames)
 * while it has somewhere to go, and rests when the Library isn't on screen.
 */
@Composable
private fun Ribbon(pct: Float, ink: androidx.compose.ui.graphics.Color, animate: Boolean, modifier: Modifier, onPull: () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val visible = LocalTabVisible.current
    val pull by androidx.compose.runtime.rememberUpdatedState(onPull)
    val full = 170.dp
    val fullPx = with(density) { full.toPx() }
    val widthPx = with(density) { 20.dp.toPx() }
    val cloth = remember { Cloth(16) }
    val length = remember { Animatable(if (animate) 0.12f else (0.12f + 0.88f * pct)) }
    LaunchedEffect(pct) { length.animateTo(0.12f + 0.88f * pct, spring(dampingRatio = 0.7f, stiffness = 60f)) }
    // Redrawn from the cloth each frame it moves: the frame count is what the drawing reads.
    val frame = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val wake = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val grab = remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    val finger = remember { floatArrayOf(0f, 0f) }
    var armed by remember { androidx.compose.runtime.mutableStateOf(false) }
    // How far it's been stretched (0 to 1 of the way to opening), for the feel of it tightening.
    val stretched = remember { floatArrayOf(0f, 0f) }
    LaunchedEffect(Unit) { cloth.hang(fullPx * length.value / (cloth.n - 1)); if (animate) cloth.gust(3f * density.density) }
    // Let go whenever the Library goes off screen (a swipe to another tab mid-pull ends the gesture
    // without a word): a hold left set kept the cloth pinned where the finger was, stretched and stiff.
    LaunchedEffect(visible) {
        if (!visible && grab.intValue >= 0) { grab.intValue = -1; armed = false }
        if (visible) { if (grab.intValue >= 0) grab.intValue = -1; wake.intValue++ }
    }
    // The simulation runs only while there's motion (or a finger on it), then sleeps until woken.
    LaunchedEffect(wake.intValue, visible, animate) {
        if (!visible) return@LaunchedEffect
        var still = 0
        var last = 0L
        val gravity = with(density) { 2600.dp.toPx() }
        while (still < 20) {
            androidx.compose.runtime.withFrameNanos { t ->
                val dt = if (last == 0L) 1f / 60f else ((t - last) / 1e9f).coerceIn(1f / 240f, 1f / 30f)
                last = t
                cloth.seg = fullPx * length.value / (cloth.n - 1)
                cloth.maxX = with(density) { (24.dp - 10.dp).toPx() }
                cloth.maxY = fullPx - widthPx * 0.2f
                val g = grab.intValue
                val e = cloth.step(dt, gravity, g, finger[0], finger[1])
                frame.intValue++
                if (g < 0 && e < 0.02f * cloth.n) still++ else still = 0
            }
            if (!animate) break
        }
    }
    LaunchedEffect(Unit) { androidx.compose.runtime.snapshotFlow { length.value }.collect { wake.intValue++ } }
    // A jolt of the phone gives the cloth a little push.
    if (animate && visible) {
        androidx.compose.runtime.DisposableEffect(Unit) {
            val sm = context.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager
            val linear = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION)
            val sensor = linear ?: sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
            var gravityX = 0f
            var lastKick = 0L
            val listener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(e: android.hardware.SensorEvent) {
                    var ax = e.values[0]
                    if (linear == null) { gravityX += (ax - gravityX) * 0.1f; ax -= gravityX }
                    val now = android.os.SystemClock.uptimeMillis()
                    if (kotlin.math.abs(ax) < 1.4f || now - lastKick < 140 || grab.intValue >= 0) return
                    lastKick = now
                    cloth.gust((ax * 0.35f).coerceIn(-3f, 3f) * density.density)
                    wake.intValue++
                }
                override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
            }
            if (sensor != null) sm?.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
            onDispose { sm?.unregisterListener(listener) }
        }
    }
    val pullPx = with(density) { 64.dp.toPx() }
    Box(
        modifier
            .width(48.dp)
            .height(full)
            .pointerInput(Unit) {
                val cx = size.width / 2f
                try { detectDragGestures(
                    onDragStart = { o ->
                        // Held where the finger landed: the nearest point of the cloth (never the pin).
                        var best = 1; var bd = Float.MAX_VALUE
                        for (i in 1 until cloth.n) {
                            val dx = cloth.x[i] + cx - o.x; val dy = cloth.y[i] - o.y
                            val d = dx * dx + dy * dy
                            if (d < bd) { bd = d; best = i }
                        }
                        finger[0] = cloth.x[best]; finger[1] = cloth.y[best]
                        grab.intValue = best; armed = false; wake.intValue++
                        stretched[0] = 0f; stretched[1] = 0f
                        Haptics.texture(view, 0.25f) // the cloth taken between the fingers
                    },
                    onDragEnd = {
                        grab.intValue = -1
                        if (!animate) cloth.hang(fullPx * length.value / (cloth.n - 1))
                        // Let go past the point: it opens, with a firm click. Let go short of it after a
                        // good stretch: it springs back, and you feel it give.
                        if (armed) { Haptics.done(view, 0.9f); pull() }
                        else if (stretched[0] > 0.35f) Haptics.fall(view, 0.25f + 0.4f * stretched[0])
                        armed = false; wake.intValue++
                    },
                    onDragCancel = { grab.intValue = -1; armed = false; wake.intValue++ },
                ) { change, drag ->
                    change.consume()
                    val wall = with(density) { (24.dp - 10.dp).toPx() }
                    finger[0] = (finger[0] + drag.x).coerceIn(-wall, wall); finger[1] += drag.y
                    // Past its own length (stretched down well beyond where it hangs), letting go opens the book.
                    val reach = cloth.seg * grab.intValue.coerceAtLeast(1)
                    val past = finger[1] - reach > pullPx
                    // Tightening as it's drawn down: a grain every few dp, firmer the closer to opening.
                    val s = ((finger[1] - reach) / pullPx).coerceIn(0f, 1.3f)
                    stretched[0] = maxOf(stretched[0], s.coerceAtMost(1f))
                    if (kotlin.math.abs(s - stretched[1]) > 0.08f) { Haptics.texture(view, 0.08f + 0.5f * s.coerceAtMost(1f)); stretched[1] = s }
                    if (past != armed) { armed = past; if (past) Haptics.rise(view, 0.7f) else Haptics.fall(view, 0.3f) }
                } } finally {
                    // The gesture ended some other way (the screen left, the gesture taken over): let go.
                    if (grab.intValue >= 0) { grab.intValue = -1; armed = false; wake.intValue++ }
                }
            }
            .drawBehind {
                frame.intValue // read, so each step of the cloth redraws it
                val cx = size.width / 2f
                val n = cloth.n
                val half = widthPx / 2f
                val left = Path(); val right = Path()
                val lx = FloatArray(n); val ly = FloatArray(n); val rx = FloatArray(n); val ry = FloatArray(n)
                for (i in 0 until n) {
                    val a = (i - 1).coerceAtLeast(0); val b = (i + 1).coerceAtMost(n - 1)
                    var tx = cloth.x[b] - cloth.x[a]; var ty = cloth.y[b] - cloth.y[a]
                    val tl = kotlin.math.sqrt(tx * tx + ty * ty).coerceAtLeast(0.001f); tx /= tl; ty /= tl
                    // The cloth's edges either side of the chain.
                    lx[i] = cx + cloth.x[i] + ty * half; ly[i] = cloth.y[i] - tx * half
                    rx[i] = cx + cloth.x[i] - ty * half; ry[i] = cloth.y[i] + tx * half
                }
                val tipX = cx + cloth.x[n - 1]; val tipY = cloth.y[n - 1]
                val bx = cloth.x[n - 1] - cloth.x[n - 2]; val by = cloth.y[n - 1] - cloth.y[n - 2]
                val bl = kotlin.math.sqrt(bx * bx + by * by).coerceAtLeast(0.001f)
                val notch = widthPx * 0.55f
                val path = Path().apply {
                    moveTo(lx[0], ly[0])
                    for (i in 1 until n) lineTo(lx[i], ly[i])
                    lineTo(tipX - bx / bl * notch, tipY - by / bl * notch)
                    lineTo(rx[n - 1], ry[n - 1])
                    for (i in n - 2 downTo 0) lineTo(rx[i], ry[i])
                    close()
                }
                // Kept within its own space on the page: the cloth never hangs past the box it's pinned in.
                clipRect(left = 0f, top = -size.height, right = size.width, bottom = size.height) {
                drawPath(path, ink)
                // A fold of shade down one edge, like satin.
                val shade = Path().apply {
                    moveTo(lx[0], ly[0])
                    for (i in 1 until n - 1) lineTo(lx[i], ly[i])
                    for (i in n - 2 downTo 0) lineTo(lx[i] + (rx[i] - lx[i]) * 0.28f, ly[i] + (ry[i] - ly[i]) * 0.28f)
                    close()
                }
                drawPath(shade, androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.16f))
                }
            },
    )
}

private fun phrase(p: Float): String = when {
    p < 0.08f -> "just setting out in"
    p < 0.28f -> "a quarter of the way into"
    p < 0.42f -> "a third of the way into"
    p < 0.6f -> "halfway into"
    p < 0.85f -> "deep into"
    else -> "nearly done with"
}

private fun ordinal(n: Int): String {
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    return "$n$suffix"
}
