package io.github.prithirajn.trackersync.ui

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

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
    val (top, bottom, ledgeColor) = when (kind) {
        KeyKind.SIGNAL -> Triple(c.accent.copy(alpha = 1f).let { lerpColor(it, Color.White, 0.18f) }, c.accent, c.signalLedge)
        KeyKind.INK -> Triple(lerpColor(c.ink, Color.White, 0.1f), c.ink, Color.Black)
        else -> Triple(c.keyTop, c.keyBottom, c.keyLedge)
    }
    Box(
        modifier
            .clickable(source, indication = null, enabled = enabled, role = role, onClickLabel = label, onClick = onClick)
            .padding(bottom = ledge)
            // The ledge: the key's own shape, a little lower, darker.
            .drawBehind {
                drawRoundRect(ledgeColor, topLeft = Offset(0f, ledge.toPx()), size = size, cornerRadius = cornerOf(shape, size, this))
            }
            .offset(y = travel)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            // A thin light edge along the top, as on a moulded key.
            .drawBehind { drawRect(Color.White.copy(alpha = if (c.isDark) 0.06f else 0.55f), size = Size(size.width, 1.dp.toPx())) }
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
    KeyKind.SIGNAL -> Color.White
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
            .clip(CpShapes.screen)
            .background(c.screen)
            .drawBehind {
                // Inner shade at the top edge.
                drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent), endY = 14.dp.toPx()))
                // Faint pixel rows.
                val step = 3.dp.toPx()
                var y = 0f
                while (y < size.height) {
                    drawRect(Color.White.copy(alpha = 0.018f), topLeft = Offset(0f, y), size = Size(size.width, 1f))
                    y += step
                }
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
    var started by remember { mutableStateOf(!countUp) }
    LaunchedEffect(Unit) { started = true }
    val shown by animateIntAsState(if (started) value else 0, tween(durationMillis = 900, easing = FastOutSlowInEasing), label = "count")
    Row(modifier) {
        for ((i, ch) in shown.toString().withIndex()) {
            androidx.compose.runtime.key(shown.toString().length - i) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        // Rolls up when the number grows, down when it shrinks, like a real odometer.
                        val up = if (targetState > initialState) 1 else -1
                        (slideInVertically(spring(dampingRatio = 0.7f, stiffness = 500f)) { it * up } + fadeIn(tween(120))) togetherWith
                            (slideOutVertically(tween(160)) { -it * up } + fadeOut(tween(120)))
                    },
                    label = "digit",
                ) { d -> Text(d.toString(), style = style, color = color) }
            }
        }
    }
}

/**
 * Progress as a row of lit segments: they light one after another up to [fraction] (the last one
 * partly), the first time and whenever it moves.
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
    val shown = remember { Animatable(0f) }
    LaunchedEffect(target) { shown.animateTo(target, tween(durationMillis = 260 + (target * 700).toInt(), easing = FastOutSlowInEasing)) }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                val gap = 3.dp.toPx()
                val w = (size.width - gap * (segments - 1)) / segments
                val r = CornerRadius(size.height / 2f)
                for (i in 0 until segments) {
                    val x = i * (w + gap)
                    drawRoundRect(off, topLeft = Offset(x, 0f), size = Size(w, size.height), cornerRadius = r)
                    val lit = (shown.value * segments - i).coerceIn(0f, 1f)
                    if (lit > 0f) drawRoundRect(on, topLeft = Offset(x, 0f), size = Size(w * lit, size.height), cornerRadius = r)
                }
            },
    )
}

/**
 * The wallpaper behind the device: soft blooms in the colour of the book you're reading, drifting
 * slowly. Strongest at the top; the device's body takes over lower down.
 */
@Composable
fun Wallpaper(modifier: Modifier = Modifier, height: Dp = 340.dp, color: Color = Cp.colors.wall) {
    val c = Cp.colors
    val on = LocalAnimationsOn.current
    // The drift is slow (once round in 26 s), so it's moved 15 times a second, not on every frame
    // (on a 120 Hz screen that's 8 times less drawing behind everything you scroll).
    val tState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    if (on) androidx.compose.runtime.LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameMillis { ms ->
                if (ms - last >= 66) { last = ms; tState.floatValue = ((ms % 26_000L) / 26_000f) * (Math.PI * 2).toFloat() }
            }
        }
    }
    val glow = if (c.isDark) 0.55f else 0.85f
    val second = lerpColor(color, if (c.isDark) Color(0xFF6B4BFF) else Color(0xFFFFC7E3), 0.55f)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            // The blooms are bigger than the box: keep them inside it, so the colour ends in the fade
            // instead of carrying on below it in a hard line.
            .clipToBounds()
            // Its own layer: its redraws don't make the screen above it redraw.
            .graphicsLayer()
            .drawBehind {
                val t = tState.floatValue
                val w = size.width
                val h = size.height
                fun bloom(x: Float, y: Float, r: Float, col: Color, a: Float) =
                    drawCircle(Brush.radialGradient(listOf(col.copy(alpha = a), col.copy(alpha = a * 0.4f), col.copy(alpha = 0f)), Offset(x, y), r), r, Offset(x, y))
                bloom(w * (0.25f + 0.08f * kotlin.math.cos(t)), h * (0.18f + 0.06f * kotlin.math.sin(t)), w * 0.95f, color, glow)
                bloom(w * (0.85f - 0.07f * kotlin.math.sin(t)), h * (0.45f + 0.05f * kotlin.math.cos(t)), w * 0.8f, second, glow * 0.8f)
                // Fades into the page at the bottom.
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, c.page.copy(alpha = 0.55f), c.page), startY = h * 0.3f, endY = h * 0.96f))
                drawRect(c.page, topLeft = Offset(0f, h * 0.96f))
            },
    )
}

/** A small light that glows when on (a status LED). */
@Composable
fun Led(on: Boolean, modifier: Modifier = Modifier, color: Color = Cp.colors.accent, size: Dp = 7.dp) {
    val c = Cp.colors
    val a by animateFloatAsState(if (on) 1f else 0f, tween(300), label = "led")
    Box(
        modifier
            .width(size).height(size)
            .drawBehind {
                if (a > 0f) drawCircle(color.copy(alpha = 0.35f * a), radius = this.size.minDimension * 1.1f)
                drawCircle(lerpColor(c.keyLedge, color, a))
            },
    )
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
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(40L * index.coerceAtMost(8))
        t.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 240f))
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
            LucideIcon(io.github.prithirajn.trackersync.R.drawable.lucide_chevron_right, Modifier.size(16.dp).graphicsLayer { rotationZ = turn }, if (open) c.accentText else c.quiet)
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
fun ServicesScreenStrip(lights: List<Pair<String, Int>>, on: Int, needYou: Int, lastSync: String, running: Boolean, modifier: Modifier = Modifier) {
    val c = Cp.colors
    Screen(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                DeviceLabel("Services on", color = c.screenDim)
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
            .drawBehind { drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = if (c.isDark) 0.35f else 0.08f), Color.Transparent), endY = 6.dp.toPx())) }
            .padding(3.dp),
    ) {
        // The pill, drawn under the labels and slid between them.
        Box(
            Modifier
                .fillMaxWidth(1f / options.size)
                .fillMaxHeight()
                .graphicsLayer { translationX = at * size.width }
                .shadow(5.dp, androidx.compose.foundation.shape.RoundedCornerShape(11.dp), ambientColor = c.accent, spotColor = c.accent)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(11.dp))
                .background(Brush.verticalGradient(listOf(lerpColor(c.accent, Color.White, 0.15f), c.accent))),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, o ->
                val on = i == index
                val fg by androidx.compose.animation.animateColorAsState(if (on) Color.White else c.textMid, tween(180), label = "segText")
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
                .drawBehind {
                    if (grow > 0.01f) drawRoundRect(c.accent.copy(alpha = 0.35f * grow.coerceAtMost(1f)), topLeft = Offset(-5.dp.toPx(), -3.dp.toPx()),
                        size = Size(size.width + 10.dp.toPx(), size.height + 6.dp.toPx()), cornerRadius = CornerRadius(6.dp.toPx()))
                }
                .clip(CpShapes.pill)
                .background(c.accent),
        )
    }
}
