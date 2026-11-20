package io.github.prithirajn.readync.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.AnimatedVisibility
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpShapes
import io.github.prithirajn.readync.ui.theme.CpType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/*
 * Gadget: the parts of the little reading device the app is drawn as. Keys that sink onto their
 * ledge with a click you can feel, a dark screen with pixel numbers that roll, segment bars that
 * light one by one, switches whose knob glows. Each one answers your finger, so the app feels
 * like something in your hand.
 */

/** A feelable click (keys down and up, switches, tabs), following the phone's touch-feedback setting. */
object Haptics {
    fun press(v: View) = v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    fun release(v: View) {
        if (Build.VERSION.SDK_INT >= 27) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_RELEASE)
    }
    fun toggle(v: View, on: Boolean) {
        if (Build.VERSION.SDK_INT >= 34) v.performHapticFeedback(if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF)
        else v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
    fun tick(v: View) = v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    fun confirm(v: View) {
        if (Build.VERSION.SDK_INT >= 30) v.performHapticFeedback(HapticFeedbackConstants.CONFIRM) else tick(v)
    }
}

/** How a key looks: a plain light key, the orange signal key, or a quiet one with red writing. */
enum class KeyKind { PLAIN, SIGNAL, DANGER, INK }

/**
 * A physical key: its face sits [ledge] above a darker ledge and sinks onto it while pressed, with
 * a click on the way down and up. [content] is drawn on the face.
 */
@Composable
fun Key(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: KeyKind = KeyKind.PLAIN,
    shape: Shape = CpShapes.control,
    ledge: Dp = 3.dp,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    label: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = Cp.colors
    val view = LocalView.current
    val source = remember { MutableInteractionSource() }
    var down by remember { mutableStateOf(false) }
    LaunchedEffect(source, enabled) {
        source.interactions.collectLatest { i ->
            when (i) {
                is PressInteraction.Press -> if (enabled) { down = true; Haptics.press(view) }
                is PressInteraction.Release -> {
                    // A quick tap still shows the whole travel.
                    delay(60); down = false; Haptics.release(view)
                }
                is PressInteraction.Cancel -> down = false
            }
        }
    }
    val travel by animateDpAsState(
        if (down) ledge else 0.dp,
        if (down) spring(dampingRatio = 1f, stiffness = Spring.StiffnessHigh) else spring(dampingRatio = 0.45f, stiffness = 700f),
        label = "keyTravel",
    )
    // Riso stamps: flat ink, printed a little off register over a second ink (or a shadow for plain
    // keys). Pressing sinks the face onto it, and a splash of light spreads from your finger.
    val (face, ledgeColor) = when (kind) {
        KeyKind.SIGNAL -> c.accent to c.signalLedge
        KeyKind.INK -> c.ink to (if (c.isDark) c.accent else Color.Black)
        else -> c.keyTop to c.keyLedge
    }
    val splash = remember { Animatable(1f) }
    var at by remember { mutableStateOf(Offset.Unspecified) }
    val animate = LocalAnimationsOn.current
    val splashScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(source, enabled) {
        source.interactions.collect { i ->
            if (i is PressInteraction.Press && enabled && animate) {
                at = i.pressPosition
                // Its own coroutine, so the finger lifting never cuts it off half-spread (it used to
                // stay on the key as a pale circle).
                splashScope.launch {
                    splash.snapTo(0f)
                    splash.animateTo(1f, tween(durationMillis = 520, easing = FastOutSlowInEasing))
                }
            }
        }
    }
    val misX = if (kind == KeyKind.SIGNAL) 2.dp else 0.dp
    Box(
        modifier
            .clickable(source, indication = null, enabled = enabled, role = role, onClickLabel = label, onClick = onClick)
            .padding(bottom = ledge)
            // The second ink (or shadow): the key's own shape, nudged down and a touch across.
            .drawBehind {
                drawRoundRect(ledgeColor, topLeft = Offset(misX.toPx(), ledge.toPx()), size = size, cornerRadius = cornerOf(shape, size, this))
            }
            .offset(y = travel)
            .clip(shape)
            .background(face)
            .then(if (kind == KeyKind.PLAIN || kind == KeyKind.DANGER) Modifier.drawBehind {
                drawRect(c.hairline, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx() * 2))
            } else Modifier)
            .drawBehind {
                val t = splash.value
                if (t < 1f && at.isSpecified) {
                    val r = size.maxDimension * (0.15f + 1.1f * t)
                    drawCircle(Color.White.copy(alpha = (if (kind == KeyKind.SIGNAL) 0.42f else 0.2f) * (1f - t)), r, at)
                }
            }
            .then(if (enabled) Modifier else Modifier.background(c.page.copy(alpha = 0.45f))),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

private fun cornerOf(shape: Shape, size: Size, d: androidx.compose.ui.unit.Density): CornerRadius {
    val outline = shape.createOutline(size, androidx.compose.ui.unit.LayoutDirection.Ltr, d)
    return if (outline is androidx.compose.ui.graphics.Outline.Rounded) CornerRadius(outline.roundRect.topLeftCornerRadius.x, outline.roundRect.topLeftCornerRadius.y)
    else CornerRadius.Zero
}

internal fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

/** Text on a key in the colour its kind writes in. */
@Composable
fun keyText(kind: KeyKind): Color = when (kind) {
    KeyKind.SIGNAL -> Cp.colors.onAccent
    KeyKind.DANGER -> Cp.colors.error
    KeyKind.PLAIN -> Cp.colors.text
    KeyKind.INK -> Cp.colors.onInk
}

/**
 * The device's dark screen: rounded, a soft inner shadow at the top, and a faint scan of pixel
 * rows, so it reads as a display and not a dark card.
 */
@Composable
fun Screen(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Cp.colors
    Column(
        modifier
            .fillMaxWidth()
            // Printed a little off register over the section's second ink.
            .drawBehind {
                val o = cornerOf(CpShapes.screen, size, this)
                drawRoundRect(c.signalLedge.copy(alpha = 0.8f), topLeft = Offset(3.dp.toPx(), 4.dp.toPx()), size = size, cornerRadius = CornerRadius(maxOf(o.x, 16.dp.toPx())))
            }
            .clip(CpShapes.screen)
            .background(c.screen)
            // A halftone corner in the section's ink, dots shrinking away from the top right. Drawn once
            // into a picture (redrawn only when the panel's size or ink changes), so a frame just pastes it.
            .drawWithCache {
                val reach = size.minDimension * 0.9f
                val bmp = halftone(androidx.compose.ui.geometry.Size(reach, reach), c.accent.copy(alpha = 0.32f), 7.dp.toPx(), 1.3.dp.toPx())
                onDrawBehind { drawImage(bmp, topLeft = Offset(size.width - reach, 0f)) }
            }
            .padding(14.dp),
        content = content,
    )
}

/** A tiny printed label, like the writing above a knob. */
@Composable
fun DeviceLabel(text: String, modifier: Modifier = Modifier, color: Color = Cp.colors.quiet) {
    Text(text.uppercase(), style = CpType.mono, color = color, modifier = modifier, maxLines = 1)
}

/**
 * A number whose digits roll into place like an odometer: counts up from zero the first time,
 * and each digit that changes later slides up into its new value.
 */
@Composable
fun RollingNumber(value: Int, style: TextStyle, color: Color, modifier: Modifier = Modifier, countUp: Boolean = true) {
    // Kept across being scrolled away in a list and back (rememberSaveable), so a number only counts
    // up again when its tab comes back on screen, not each time its row scrolls into view.
    var started by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(!countUp) }
    var seen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val visible = LocalTabVisible.current
    val animOn = LocalAnimationsOn.current
    LaunchedEffect(visible) {
        if (!visible) { seen = false; return@LaunchedEffect }
        if (seen) return@LaunchedEffect
        seen = true
        if (countUp && started && animOn) { started = false; kotlinx.coroutines.delay(16) }
        started = true
    }
    val target = if (started) value else 0
    val shown by animateIntAsState(target, tween(durationMillis = 900, easing = FastOutSlowInEasing), label = "count")
    // Plain figures: the count itself is the motion. (A rolling digit for each place, kept even at
    // rest, was most of the work of showing a row of numbers, every time it scrolled into view.)
    Text(shown.toString(), style = style, color = color, modifier = modifier)
}

/**
 * Progress as a riso bar: a bar of ink grows to [fraction] with a spring, its ends cut a little
 * unevenly, over a faint track. ([segments] is kept for older callers; the bar is one stroke now.)
 */
@Composable
fun SegmentBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    segments: Int = 10,
    height: Dp = 6.dp,
    on: Color = Cp.colors.accent,
    off: Color = Cp.colors.track,
) {
    val target = fraction.coerceIn(0f, 1f)
    val animate = LocalAnimationsOn.current
    val shown = remember { Animatable(if (animate) 0f else target) }
    // Grows to its place, and grows again each time its tab comes back on screen.
    val visible = LocalTabVisible.current
    LaunchedEffect(target, visible) {
        if (animate && visible && shown.value == target) shown.snapTo(0f)
        shown.animateTo(target, spring(dampingRatio = 0.75f, stiffness = 120f))
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                val h = size.height
                drawRoundRect(off, size = size, cornerRadius = CornerRadius(h / 2f))
                val w = (size.width * shown.value.coerceIn(0f, 1f))
                if (w > 0.5f) {
                    // Uneven ends, like ink pulled across by hand.
                    val path = androidx.compose.ui.graphics.Path().apply {
                        addRoundRect(androidx.compose.ui.geometry.RoundRect(
                            0f, 0f, w, h,
                            topLeftCornerRadius = CornerRadius(h * 0.3f), topRightCornerRadius = CornerRadius(h * 0.9f),
                            bottomRightCornerRadius = CornerRadius(h * 0.45f), bottomLeftCornerRadius = CornerRadius(h),
                        ))
                    }
                    drawPath(path, on)
                }
            },
    )
}

/** The colour of a book's cover, for its spine and progress: the section's ink until it's known. */
@Composable
fun rememberBookInk(url: String?): Color {
    val c = Cp.colors
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val seed by androidx.compose.runtime.produceState<Int?>(url?.let { CoverAccent.knownInk(it) }, url) {
        if (url != null && value == null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CoverAccent.inkOf(context, url) }
    }
    val target = seed?.let { io.github.prithirajn.readync.util.Palette.signalFrom(it, c.isDark) }?.let { Color(it.accent) } ?: c.accent
    return androidx.compose.animation.animateColorAsState(target, tween(500), label = "bookInk").value
}

/**
 * The paper every page is printed on: a fine, still grain of two inks' dots over a little noise.
 * Drawn once into a tile and repeated, so it costs nothing while you scroll.
 */
@Composable
fun RisoPaper(modifier: Modifier = Modifier, inkA: Color = Cp.colors.accent, inkB: Color = Cp.colors.signalLedge) {
    val c = Cp.colors
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val brush = remember(inkA, inkB, c.isDark, density) { paperBrush(inkA, inkB, c.isDark, density) }
    Box(modifier.graphicsLayer().drawBehind { drawRect(brush) })
}

private fun paperBrush(inkA: Color, inkB: Color, dark: Boolean, density: Float): Brush {
    val a = (7 * density).toInt().coerceAtLeast(6)
    val b = (9 * density).toInt().coerceAtLeast(8)
    val tile = a * b
    val bmp = androidx.compose.ui.graphics.ImageBitmap(tile, tile)
    val canvas = androidx.compose.ui.graphics.Canvas(bmp)
    val paint = androidx.compose.ui.graphics.Paint().apply { isAntiAlias = true }
    val r = 1.05f * density
    paint.color = inkA.copy(alpha = if (dark) 0.10f else 0.15f)
    var y = 0
    while (y < tile) { var x = 0; while (x < tile) { canvas.drawCircle(Offset(x + a / 2f, y + a / 2f), r, paint); x += a }; y += a }
    paint.color = inkB.copy(alpha = if (dark) 0.08f else 0.12f)
    y = 0
    while (y < tile) { var x = 0; while (x < tile) { canvas.drawCircle(Offset(x + b / 2f + 3 * density, y + b / 2f + 5 * density), r, paint); x += b }; y += b }
    // Paper noise: a sprinkle of light and dark specks.
    val rnd = java.util.Random(7)
    paint.isAntiAlias = false
    repeat(tile * tile / 60) {
        val light = rnd.nextBoolean()
        paint.color = (if (light) Color.White else Color.Black).copy(alpha = if (dark) (if (light) 0.035f else 0.06f) else (if (light) 0.12f else 0.035f))
        val s = if (rnd.nextInt(5) == 0) 2f else 1f
        canvas.drawRect(androidx.compose.ui.geometry.Rect(rnd.nextInt(tile).toFloat(), rnd.nextInt(tile).toFloat(), 0f, 0f).let { androidx.compose.ui.geometry.Rect(it.left, it.top, it.left + s, it.top + s) }, paint)
    }
    return androidx.compose.ui.graphics.ShaderBrush(androidx.compose.ui.graphics.ImageShader(bmp, androidx.compose.ui.graphics.TileMode.Repeated, androidx.compose.ui.graphics.TileMode.Repeated))
}

/**
 * A book's page header: a still halftone in its cover's colour, the dots biggest at the top and
 * shrinking down into the paper. Its own layer, so it's drawn once.
 */
@Composable
fun Wallpaper(modifier: Modifier = Modifier, height: Dp = 340.dp, color: Color = Cp.colors.wall) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds()
            .drawWithCache {
                val bmp = stripes(size, color.copy(alpha = 0.55f), 8.dp.toPx(), 2.4.dp.toPx())
                onDrawBehind { drawImage(bmp) }
            },
    )
}

/** The Screen's halftone corner, drawn once into a picture: dots shrinking with distance from the top right. */
private fun halftone(area: Size, color: Color, step: Float, maxR: Float): androidx.compose.ui.graphics.ImageBitmap {
    val w = area.width.toInt().coerceAtLeast(1)
    val bmp = androidx.compose.ui.graphics.ImageBitmap(w, w)
    val canvas = androidx.compose.ui.graphics.Canvas(bmp)
    val paint = androidx.compose.ui.graphics.Paint().apply { this.color = color; isAntiAlias = true }
    val reach = area.width
    var y = step / 2
    while (y < reach) {
        var x = reach - step / 2
        while (x > 0f) {
            val d = kotlin.math.hypot(reach - x, y) / reach
            if (d < 1f) canvas.drawCircle(Offset(x, y), maxR * (1f - d), paint)
            x -= step
        }
        y += step
    }
    return bmp
}

/** A book page header's halftone, drawn once into a picture: rows of dots shrinking down the page. */
private fun stripes(area: Size, color: Color, step: Float, maxR: Float): androidx.compose.ui.graphics.ImageBitmap {
    val bmp = androidx.compose.ui.graphics.ImageBitmap(area.width.toInt().coerceAtLeast(1), area.height.toInt().coerceAtLeast(1))
    val canvas = androidx.compose.ui.graphics.Canvas(bmp)
    val paint = androidx.compose.ui.graphics.Paint().apply { this.color = color; isAntiAlias = true }
    val reach = area.height * 0.85f
    var y = step / 2
    var row = 0
    while (y < reach) {
        val t = 1f - y / reach
        val rad = maxR * t * t
        if (rad > 0.3f) {
            var x = if (row % 2 == 0) step / 2 else step
            while (x < area.width) { canvas.drawCircle(Offset(x, y), rad, paint); x += step }
        }
        y += step * 0.87f
        row++
    }
    return bmp
}

/** A small light that glows when on (a status LED). */
@Composable
fun Led(on: Boolean, modifier: Modifier = Modifier, color: Color = Cp.colors.accent, size: Dp = 7.dp) {
    val c = Cp.colors
    val a by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "led")
    Box(
        modifier
            .width(size).height(size)
            .drawBehind {
                drawCircle(c.track)
                if (a > 0.01f) drawCircle(color, radius = this.size.minDimension / 2f * a.coerceIn(0f, 1.15f))
            },
    )
}

/**
 * Works something out off the main thread (so scrolling to it never stutters), showing [initial]
 * for the moment until it's ready, and again whenever a key changes.
 */
@Composable
fun <T> rememberWorked(vararg keys: Any?, initial: T, work: () -> T): T =
    androidx.compose.runtime.produceState(initial, *keys) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { work() }.getOrDefault(value) }
    }.value

/**
 * Debug builds only: times how long [content] takes to make and to lay out, and tells FrameWatch
 * (the slowest part shows on the frame reading). Release builds just show [content].
 */
@Composable
fun DebugTimed(name: String, content: @Composable () -> Unit) {
    if (!io.github.prithirajn.readync.BuildConfig.DEBUG) { content(); return }
    val t0 = System.nanoTime()
    Box(Modifier.layout { m, c ->
        val t = System.nanoTime()
        val p = m.measure(c)
        io.github.prithirajn.readync.FrameWatch.slow("$name layout", (System.nanoTime() - t) / 1e6)
        layout(p.width, p.height) { p.place(0, 0) }
    }) { content() }
    io.github.prithirajn.readync.FrameWatch.slow(name, (System.nanoTime() - t0) / 1e6)
}

/** Space that takes the rest of a row. */
@Composable
fun androidx.compose.foundation.layout.RowScope.Fill() = Spacer(Modifier.weight(1f))

/**
 * The grip at the top of every sheet: three short ribs, like the grip moulded into a device, that
 * spread out as the sheet arrives.
 */
@Composable
fun SheetGrip() {
    val c = Cp.colors
    val spread = remember { Animatable(0f) }
    LaunchedEffect(Unit) { spread.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 260f)) }
    // A sheet pulled up to the top of the screen: its grip (and so everything under it) moved down
    // clear of the status bar and the camera, instead of the title sitting under the clock. Measured
    // where the sheet really is on the screen, so a sheet that stops lower gets no extra room.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val view = androidx.compose.ui.platform.LocalView.current
    val barDp = androidx.compose.ui.unit.max(
        androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
        LocalAppInsets.current.calculateTopPadding(),
    )
    val barPx = with(density) { barDp.roundToPx() }
    var clear by remember { androidx.compose.runtime.mutableStateOf(0) }
    Row(
        Modifier
            .onGloballyPositioned { co ->
                // Decided by how tall the sheet is, not where it is right now: a sheet tall enough to reach
                // the top of the screen keeps the bar's room the whole time, even while it's dragged
                // (deciding by position made everything in it jump up and down as it moved).
                // The sheet: the first box around the grip that's taller than the grip itself.
                var up = co.parentLayoutCoordinates
                while (up != null && up.size.height <= co.size.height + 1) up = up.parentLayoutCoordinates
                val sheetHeight = up?.size?.height ?: return@onGloballyPositioned
                val screen = view.rootView.height
                val need = if (sheetHeight - clear + barPx >= screen - 2) barPx else 0
                if (need != clear) clear = need
            }
            .padding(top = 12.dp + with(density) { clear.toDp() }, bottom = 10.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val w = if (i == 1) 26.dp else 10.dp
            Box(
                Modifier
                    .width(w * (0.4f + 0.6f * spread.value))
                    .height(5.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                    .background(if (i == 1) c.keyLedge else c.keyLedge.copy(alpha = 0.6f)),
            )
        }
    }
}

/**
 * Brings a block in once, a beat after the one before it: up a few dots and into view. Used down
 * a sheet or a page so its parts arrive in order instead of all at once. Still when the phone's
 * animations are off.
 */
@Composable
fun Modifier.riseIn(index: Int): Modifier {
    if (!LocalAnimationsOn.current) return this
    // Rises once: a row scrolled away in a list and back is just there (whether it rose is saved).
    var risen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val t = remember { Animatable(if (risen) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (risen) return@LaunchedEffect
        delay(40L * index.coerceAtMost(8))
        t.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 240f))
        risen = true
    }
    return this.graphicsLayer {
        alpha = t.value.coerceIn(0f, 1f)
        translationY = (1f - t.value) * 14.dp.toPx()
    }
}

/**
 * Options most people never touch, folded under one row. The chevron turns as it opens, the
 * contents slide down into place, and the fold clicks under your finger.
 */
@Composable
fun MoreOptions(title: String = "More options", summary: String? = null, startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = Cp.colors
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(startOpen) }
    val turn by animateFloatAsState(if (open) 90f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "foldTurn")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .bouncyClickable(CpShapes.control, pressedScale = 0.985f, role = Role.Button, onClickLabel = if (open) "Hide $title" else "Show $title") { open = !open }
                .padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = CpType.bodyStrong, color = c.text)
                if (!open && summary != null) Text(summary, style = CpType.small, color = c.quiet, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            LucideIcon(io.github.prithirajn.readync.R.drawable.lucide_chevron_right, Modifier.size(16.dp).graphicsLayer { rotationZ = turn }, if (open) c.accentText else c.quiet)
        }
        AnimatedVisibility(
            visible = open,
            enter = androidx.compose.animation.expandVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) + fadeIn(tween(220, delayMillis = 60)),
            exit = androidx.compose.animation.shrinkVertically(tween(220)) + fadeOut(tween(120)),
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

/**
 * The strip of screen at the top of Services: how many services are on, how many want you, and
 * when it last synced, with a light per service that blinks while a sync runs.
 */
@Composable
fun ServicesScreenStrip(
    lights: List<Pair<String, Int>>, on: Int, needYou: Int, lastSync: String, running: Boolean, modifier: Modifier = Modifier,
    /** The next posts to go up: (when, where, what). */
    next: List<Triple<String, String, String>> = emptyList(),
    /** What the first number counts ("Trackers on", "Connected"…). */
    onLabel: String = "Services on",
) {
    val c = Cp.colors
    Screen(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                DeviceLabel(onLabel, color = c.screenDim)
                RollingNumber(on, CpType.screenNumber.copy(fontSize = 40.sp, lineHeight = 40.sp), c.screenText)
            }
            Column(Modifier.weight(1f)) {
                DeviceLabel("Need you", color = c.screenDim)
                RollingNumber(needYou, CpType.screenNumber.copy(fontSize = 40.sp, lineHeight = 40.sp), if (needYou > 0) c.accent else c.screenText)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 0 = off, 1 = fine, 2 = wants you. While a sync runs the fine ones chase along.
            val chase = if (running && LocalAnimationsOn.current) {
                val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "chase")
                tr.animateFloat(initialValue = 0f, targetValue = lights.size.coerceAtLeast(1).toFloat(), animationSpec = androidx.compose.animation.core.infiniteRepeatable(tween(900 + 120 * lights.size, easing = androidx.compose.animation.core.LinearEasing)), label = "chaseAt").value
            } else -1f
            lights.forEachIndexed { i, (_, state) ->
                val lit = when (state) { 0 -> false; else -> true }
                val near = chase >= 0f && kotlin.math.abs(chase - i) < 0.8f
                Led(
                    on = lit && (chase < 0f || near || state == 2),
                    color = if (state == 2) c.accent else Color(0xFF7BE0A0),
                    size = 7.dp,
                )
                Spacer(Modifier.width(7.dp))
            }
            Spacer(Modifier.weight(1f))
            DeviceLabel(if (running) "Syncing" else lastSync, color = if (running) c.accent else c.screenDim)
        }
        // What goes up next on your social networks: when, where, what.
        if (next.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.screenDim.copy(alpha = 0.25f)))
            Spacer(Modifier.height(12.dp))
            DeviceLabel("Next posts", color = c.screenDim)
            next.forEach { (whenAt, where, what) ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.width(118.dp)) {
                        DeviceLabel(whenAt, color = c.accent)
                        DeviceLabel(where, color = c.screenDim)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(what, style = CpType.small, color = c.screenText, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * A few choices side by side in a recessed strip, the chosen one under a pill that slides across
 * to whichever you tap, with a click. [leading] can draw a little picture before each label.
 */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable (T, Boolean) -> Unit)? = null,
) {
    val c = Cp.colors
    val view = LocalView.current
    val index = options.indexOf(selected).coerceAtLeast(0)
    val at by animateFloatAsState(index.toFloat(), spring(dampingRatio = 0.72f, stiffness = 520f), label = "segAt")
    Box(
        modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(CpShapes.control)
            .background(c.field)
            .padding(3.dp),
    ) {
        // The pill, drawn under the labels and slid between them.
        Box(
            Modifier
                .fillMaxWidth(1f / options.size)
                .fillMaxHeight()
                .graphicsLayer { translationX = at * size.width }
                .drawBehind { drawRoundRect(c.signalLedge, topLeft = Offset(1.5.dp.toPx(), 2.dp.toPx()), size = size, cornerRadius = CornerRadius(11.dp.toPx())) }
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 13.dp, topEnd = 6.dp, bottomEnd = 13.dp, bottomStart = 6.dp))
                .background(c.accent),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, o ->
                val on = i == index
                val fg by androidx.compose.animation.animateColorAsState(if (on) c.onAccent else c.textMid, tween(180), label = "segText")
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(11.dp))
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton, onClickLabel = label(o)) {
                            if (!on) { Haptics.tick(view); onPick(o) }
                        },
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    leading?.let { it(o, on); Spacer(Modifier.width(6.dp)) }
                    Text(label(o), style = if (on) CpType.bodyStrong else CpType.label, color = fg, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The light that marks what's chosen, as under the dock's tabs: a short orange bar with a glow,
 * growing out from its middle when chosen and shrinking away when not.
 */
@Composable
fun GlowBar(on: Boolean, modifier: Modifier = Modifier, width: Dp = 24.dp) {
    val c = Cp.colors
    val grow by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 520f), label = "glowBar")
    Box(modifier.width(width).height(4.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .width(width * grow.coerceAtLeast(0f))
                .height(4.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 1.dp, topEnd = 4.dp, bottomEnd = 2.dp, bottomStart = 4.dp))
                .background(c.accent),
        )
    }
}
