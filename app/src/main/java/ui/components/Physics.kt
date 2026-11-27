package io.github.prithirajn.readync.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/*
 * Small physical behaviours, in the spirit of the Library's ribbon: things that can be held, that
 * lift and tilt and land, and that you can feel through the phone (Haptics' finer effects). Each
 * stays still when the phone's animations are off, and is felt only when touch feedback is on.
 */

/**
 * Hold it and it lifts off the page: it grows a little, casts a deeper shadow, and tilts toward
 * your finger as you move it around, with a grain of texture as it turns. Let go and it drops back
 * with a soft landing you can feel. A tap still reaches whatever it sits in; once lifted, the
 * gesture is its own (so letting go doesn't also open the row).
 */
fun Modifier.liftable(maxTilt: Float = 14f, lift: Float = 1.1f): Modifier = composed {
    val view = LocalView.current
    val animate = LocalAnimationsOn.current
    val scale = remember { Animatable(1f) }
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    val shadow = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    this
        .pointerInput(animate) {
            if (!animate) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Up, or taken by a scroll, before the hold: a tap or a scroll, not ours.
                val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation(); Unit }
                if (early != null) return@awaitEachGesture
                Haptics.rise(view, 0.45f)
                scope.launch { scale.animateTo(lift, spring(dampingRatio = 0.5f, stiffness = 500f)) }
                scope.launch { shadow.animateTo(1f, tween(160)) }
                var lastAngle = 0f
                val w = size.width.toFloat().coerceAtLeast(1f)
                val h = size.height.toFloat().coerceAtLeast(1f)
                var at = down.position
                while (true) {
                    val e = awaitPointerEvent()
                    val ch = e.changes.firstOrNull { it.id == down.id } ?: e.changes.first()
                    e.changes.forEach { it.consume() }
                    if (!ch.pressed) break
                    at = ch.position
                    // Tilt toward the finger: from the middle, up to maxTilt at the edges.
                    val tx = (((at.y / h) - 0.5f) * -2f).coerceIn(-1.2f, 1.2f) * maxTilt
                    val ty = (((at.x / w) - 0.5f) * 2f).coerceIn(-1.2f, 1.2f) * maxTilt
                    scope.launch { tiltX.animateTo(tx, spring(dampingRatio = 0.8f, stiffness = 900f)) }
                    scope.launch { tiltY.animateTo(ty, spring(dampingRatio = 0.8f, stiffness = 900f)) }
                    // A grain each few degrees it turns, firmer the further over it leans.
                    val angle = abs(tx) + abs(ty)
                    if (abs(angle - lastAngle) > 3f) {
                        Haptics.texture(view, 0.12f + 0.4f * (angle / (2 * maxTilt)).coerceIn(0f, 1f))
                        lastAngle = angle
                    }
                }
                Haptics.thud(view, 0.32f)
                scope.launch { scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 420f)) }
                scope.launch { tiltX.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 300f)) }
                scope.launch { tiltY.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 300f)) }
                scope.launch { shadow.animateTo(0f, tween(260)) }
            }
        }
        .graphicsLayer {
            scaleX = scale.value; scaleY = scale.value
            rotationX = tiltX.value; rotationY = tiltY.value
            cameraDistance = 10f * density
            shadowElevation = shadow.value * 18.dp.toPx()
            transformOrigin = TransformOrigin.Center
        }
}

/**
 * Comes in after the ones before it: fades up from a little smaller and lower, [index] × 35 ms
 * after it first appears. For a row of buttons opening, so they arrive one after another.
 */
fun Modifier.staggerIn(index: Int): Modifier = composed {
    val animate = LocalAnimationsOn.current
    val t = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!animate) return@LaunchedEffect
        kotlinx.coroutines.delay(index * 35L)
        t.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 520f))
    }
    graphicsLayer {
        val v = t.value
        alpha = v.coerceIn(0f, 1f)
        val s = 0.82f + 0.18f * v
        scaleX = s; scaleY = s
        translationY = (1f - v) * 8.dp.toPx()
    }
}

private object StampClock { var last = 0L }

/**
 * Stamped onto the page: it comes down from big and askew, lands with a little bounce, and (once
 * every half second at most, so a page of them isn't a drumroll) is felt landing.
 */
fun Modifier.stampIn(play: Boolean): Modifier = composed {
    val view = LocalView.current
    val animate = LocalAnimationsOn.current && play
    val t = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        t.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 650f)) {
            // Felt the moment it touches down, not after it settles.
            if (value >= 0.98f && velocity > 0f) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - StampClock.last > 500) { StampClock.last = now; Haptics.stamp(view) }
            }
        }
    }
    graphicsLayer {
        val v = t.value
        alpha = (v * 2f).coerceIn(0f, 1f)
        val s = 1.9f - 0.9f * v
        scaleX = s; scaleY = s
        rotationZ = -14f + 10f * v
    }
}

/**
 * A list you can feel the ends of: flung into its top or bottom, it gives a bump as hard as it
 * hit. Put it on the list itself (Modifier.nestedScroll goes inside).
 */
fun Modifier.edgeBump(): Modifier = composed {
    val view = LocalView.current
    val connection = remember {
        object : NestedScrollConnection {
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // Speed left over after the list stopped: it hit an end at that speed.
                if (abs(available.y) > 600f) Haptics.bump(view, (abs(available.y) / 9000f).coerceIn(0f, 1f))
                return Velocity.Zero
            }
        }
    }
    nestedScroll(connection)
}

/**
 * Pull past the top of a list to do something (look for new releases): the list stretches down
 * with a rubber band's give, a grain of texture every few dp that firms up as it nears the point,
 * a rise when it's far enough, and a firm click when let go there. [progress] (0 to past 1) is how
 * far, for drawing what's being pulled.
 */
@Stable
class PullToAct internal constructor(private val threshold: Float, private val view: android.view.View, private val scope: kotlinx.coroutines.CoroutineScope) {
    var offset by mutableFloatStateOf(0f)
        private set
    var armed by mutableStateOf(false)
        private set
    val progress: Float get() = offset / threshold
    internal var onAct: () -> Unit = {}
    private var lastGrain = 0f
    private val settle = Animatable(0f)

    private fun pullBy(dy: Float): Float {
        val before = offset
        // Each dp further takes more: a band that tightens toward twice its point.
        val give = (1f - (offset / (threshold * 2.2f))).coerceIn(0.08f, 1f)
        offset = (offset + dy * 0.55f * give).coerceIn(0f, threshold * 2.2f)
        if (abs(offset - lastGrain) > threshold / 9f) {
            Haptics.texture(view, 0.1f + 0.45f * progress.coerceIn(0f, 1f))
            lastGrain = offset
        }
        val nowArmed = offset >= threshold
        if (nowArmed != armed) {
            armed = nowArmed
            if (nowArmed) Haptics.rise(view, 0.6f) else Haptics.fall(view, 0.3f)
        }
        return offset - before
    }

    private fun letGo() {
        val act = armed
        armed = false
        if (act) { Haptics.done(view, 0.85f); onAct() }
        val from = offset
        scope.launch {
            settle.snapTo(from)
            settle.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) { offset = value }
            lastGrain = 0f
        }
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Pushing back up while stretched: the stretch gives way first.
            if (offset > 0f && available.y < 0f && source == NestedScrollSource.UserInput) {
                val before = offset
                offset = (offset + available.y * 0.8f).coerceAtLeast(0f)
                if (offset < threshold && armed) { armed = false; Haptics.fall(view, 0.3f) }
                return Offset(0f, offset - before)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // Dragging down at the top: what the list couldn't use stretches it.
            if (available.y > 0f && source == NestedScrollSource.UserInput) { pullBy(available.y); return Offset(0f, available.y) }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (offset > 0f) { letGo(); return available }
            return Velocity.Zero
        }
    }
}

/** A [PullToAct] that, pulled [threshold] dp and let go, does [onAct]. */
@Composable
fun rememberPullToAct(threshold: Float = 96f, onAct: () -> Unit): PullToAct {
    val view = LocalView.current
    val px = with(LocalDensity.current) { threshold.dp.toPx() }
    val scope = rememberCoroutineScope()
    val state = remember(px) { PullToAct(px, view, scope) }
    state.onAct = onAct
    return state
}

/**
 * A page you can pull down to do something (sync, look again, bring the news in): [content] gets
 * the modifier for its list (it carries the stretch), and a short ink ribbon drops from the top as
 * far as the page has come, swinging a little with the pull, with a line saying what letting go
 * will do. While [busy], the ribbon stays down and bobs.
 */
@Composable
fun PullFrame(
    pull: PullToAct,
    busy: Boolean,
    idle: String,
    armed: String,
    working: String,
    content: @Composable (Modifier) -> Unit,
) {
    // While it works, the page stays down far enough for the ribbon and its words to sit above the
    // heading (it slid back to the top before, and the ribbon hung over "Good afternoon").
    val density = LocalDensity.current
    val hold by androidx.compose.animation.core.animateFloatAsState(
        if (busy) with(density) { 86.dp.toPx() } else 0f, spring(dampingRatio = 0.8f, stiffness = 300f), label = "pullHold",
    )
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().nestedScroll(pull.connection)) {
        PullRibbon(pull, busy, idle, armed, working)
        content(Modifier.graphicsLayer { translationY = maxOf(pull.offset, hold) })
    }
}

@Composable
fun PullRibbon(pull: PullToAct, busy: Boolean, idle: String, armedText: String, working: String) {
    val c = io.github.prithirajn.readync.ui.theme.Cp.colors
    val bob = if (busy && LocalAnimationsOn.current) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "pullBob")
        val v by t.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "bob")
        v
    } else 0f
    val shown = if (busy) 56f + 10f * bob else 0f
    val drop = with(LocalDensity.current) { pull.offset.toDp() }.coerceAtLeast(shown.dp)
    if (drop <= 1.dp) return
    val p = pull.progress.coerceIn(0f, 1.4f)
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        // The Sync button's own icon, drawn long, as a ribbon of cloth: hung from the top, its sides
        // sway and ripple (more toward its foot), it lags behind your finger and swings on when you
        // stop or let go, narrows a touch as it's stretched, and its corners are soft. The two arrows
        // near its foot ride the cloth; they draw apart as it tightens and pass each other while it works.
        val ink = if (pull.armed || busy) c.accent else c.text.copy(alpha = 0.55f + 0.45f * p.coerceAtMost(1f))
        val shadow = c.inks[2].ink.copy(alpha = 0.5f)
        val animate = LocalAnimationsOn.current
        // A follower that trails the pull on a loose spring: the gap between them is the swing.
        val follow = remember { Animatable(pull.offset) }
        LaunchedEffect(pull.offset, animate) {
            if (animate) follow.animateTo(pull.offset, spring(dampingRatio = 0.32f, stiffness = 170f)) else follow.snapTo(pull.offset)
        }
        val lag = ((pull.offset - follow.value) / 140f).coerceIn(-1f, 1f)
        // A slow flutter, as cloth has even when still.
        val wave = if (animate) {
            val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "cloth")
            val v by t.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(if (busy) 900 else 1900, easing = androidx.compose.animation.core.LinearEasing)), label = "clothT")
            v
        } else 0f
        androidx.compose.foundation.Canvas(
            Modifier.width(48.dp).height((drop * 0.62f).coerceAtLeast(36.dp))
                .graphicsLayer { rotationZ = if (busy) (bob - 0.5f) * 4f else (p - 0.5f) * 4f + lag * 3f; transformOrigin = TransformOrigin(0.5f, 0f) },
        ) {
            val bw = 26.dp.toPx()
            val u = bw / 13f // one unit of the 24-unit icon (its ribbon is 12 wide), stroke room included
            val cx0 = size.width / 2f
            val w = bw - u
            val h = size.height - u * 0.5f
            val notch = w / 3f
            val tension = ((h / w) / 7f - 0.5f).coerceIn(0f, 1f)
            val phase = wave * 2f * kotlin.math.PI.toFloat()
            // How far across the cloth sits at depth y (0 top … h foot): hung from the top, so the
            // swing and the ripple grow toward the foot; the ripple travels down it.
            // How much cloth there is to move: a short ribbon stays almost straight (bent, it looked broken).
            val room = ((h / w) / 6f).coerceIn(0f, 1f)
            fun dx(y: Float): Float {
                val f = (y / h).coerceIn(0f, 1f)
                val swing = lag * 0.4f * w * f * f * room
                // Less ripple on a short ribbon: there's not enough cloth to wave.
                val ripple = (0.9f + 0.4f * lag.let { it * it } + (if (busy) 0.3f else 0f)) * u *
                    kotlin.math.sin(f * 6f - phase) * f * room
                return swing + ripple
            }
            // Half its width at depth y: a little narrower in the middle when stretched long.
            fun half(y: Float): Float {
                val f = (y / h).coerceIn(0f, 1f)
                // And a slow twist: where the cloth turns away it looks narrower.
                return w / 2f * (1f - 0.12f * tension * kotlin.math.sin(f * kotlin.math.PI.toFloat())) *
                    (1f - 0.08f * room * f * (0.5f + 0.5f * kotlin.math.sin(f * 6.5f - phase + 1.2f)))
            }
            val n = 36
            val tipY = h
            val ribbon = androidx.compose.ui.graphics.Path().apply {
                // Down the left side, from just above the top (it comes from under the edge).
                moveTo(cx0 + dx(0f) - half(0f), -u)
                for (i in 0..n) { val y = (tipY - u * 0.8f) * i / n; lineTo(cx0 + dx(y) - half(y), y) }
                // A soft tip, then up into the notch and down to the other tip.
                val lx = cx0 + dx(tipY) - half(tipY); val rx = cx0 + dx(tipY) + half(tipY)
                val nx = cx0 + dx(tipY - notch); val ny = tipY - notch
                quadraticTo(lx, tipY + u * 0.2f, lx + u * 0.9f, tipY - u * 0.5f)
                quadraticTo((lx + nx) / 2f, (tipY + ny) / 2f + u * 0.6f, nx, ny)
                quadraticTo((nx + rx) / 2f, (tipY + ny) / 2f + u * 0.6f, rx - u * 0.9f, tipY - u * 0.5f)
                quadraticTo(rx, tipY + u * 0.2f, rx, tipY - u * 0.8f)
                for (i in n downTo 0) { val y = (tipY - u * 0.8f) * i / n; lineTo(cx0 + dx(y) + half(y), y) }
                lineTo(cx0 + dx(0f) + half(0f), -u)
                close()
            }
            val outline = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f * u, join = androidx.compose.ui.graphics.StrokeJoin.Round, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            val arrows = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            // Off register, like the rest of the zine: a second ink peeking out behind.
            translate(left = 1.5.dp.toPx(), top = 1.dp.toPx()) { drawPath(ribbon, shadow, style = outline) }
            // A faint fold where the cloth turns, when it swings.
            if (kotlin.math.abs(lag) > 0.15f && room > 0.6f) {
                val fy = h * 0.55f
                drawLine(ink.copy(alpha = (kotlin.math.abs(lag) * 0.35f).coerceAtMost(0.35f)),
                    androidx.compose.ui.geometry.Offset(cx0 + dx(fy) - half(fy) * 0.6f, fy - lag * u * 3f),
                    androidx.compose.ui.geometry.Offset(cx0 + dx(fy + 4f * u) + half(fy) * 0.5f, fy + 4f * u + lag * u * 3f), 1.2f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            drawPath(ribbon, ink, style = outline)
            // The arrows, in the icon's units (x 6–18, its arrows' middle at y 11.5), sat above the
            // notch, moved and turned with the cloth where they are.
            val ay = h - notch - 4f * u
            val ox = cx0 + dx(ay) - w / 2f - 6f * u - u * 0.5f
            val oy = ay - 11.5f * u
            val slide = if (busy) (bob - 0.5f) * 2.4f * u else (p.coerceAtMost(1f) - 1f) * 1.2f * u
            fun pt(x: Float, y: Float, dx0: Float) = androidx.compose.ui.geometry.Offset(ox + x * u + dx0, oy + y * u)
            val top = androidx.compose.ui.graphics.Path().apply {
                moveTo(pt(8.5f, 9.5f, slide).x, pt(8.5f, 9.5f, slide).y); lineTo(pt(15f, 9.5f, slide).x, pt(15f, 9.5f, slide).y)
                moveTo(pt(13f, 7.5f, slide).x, pt(13f, 7.5f, slide).y); lineTo(pt(15f, 9.5f, slide).x, pt(15f, 9.5f, slide).y); lineTo(pt(13f, 11.5f, slide).x, pt(13f, 11.5f, slide).y)
            }
            val bottom = androidx.compose.ui.graphics.Path().apply {
                moveTo(pt(15.5f, 13.5f, -slide).x, pt(15.5f, 13.5f, -slide).y); lineTo(pt(9f, 13.5f, -slide).x, pt(9f, 13.5f, -slide).y)
                moveTo(pt(11f, 11.5f, -slide).x, pt(11f, 11.5f, -slide).y); lineTo(pt(9f, 13.5f, -slide).x, pt(9f, 13.5f, -slide).y); lineTo(pt(11f, 15.5f, -slide).x, pt(11f, 15.5f, -slide).y)
            }
            clipPath(ribbon) {
                // Upright, as on the Sync button: only carried sideways with the cloth.
                drawPath(top, ink, style = arrows)
                drawPath(bottom, ink, style = arrows)
            }
        }
        // On a backing of the page's own colour, so it reads over a heading it's pulled across.
        androidx.compose.material3.Text(
            when { busy -> working; pull.armed -> armedText; else -> idle },
            style = io.github.prithirajn.readync.ui.theme.CpType.small,
            color = if (pull.armed || busy) c.accentText else c.textMid,
            modifier = Modifier.padding(top = 4.dp)
                .background(c.page.copy(alpha = 0.92f), androidx.compose.foundation.shape.RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

/**
 * Hung from its top like a tag on a string: it comes in swinging and settles, the swing dying away
 * as a pendulum's would. [index] staggers a column of them and sets which way each starts.
 */
fun Modifier.swingIn(index: Int = 0): Modifier = composed {
    val animate = LocalAnimationsOn.current
    val a = remember { Animatable(if (animate) (if (index % 2 == 0) 11f else -9f) else 0f) }
    LaunchedEffect(Unit) {
        if (!animate) return@LaunchedEffect
        kotlinx.coroutines.delay(60L + index.coerceAtMost(12) * 45L)
        a.animateTo(0f, spring(dampingRatio = 0.18f, stiffness = 120f))
    }
    graphicsLayer { rotationZ = a.value; transformOrigin = TransformOrigin(0.5f, 0f) }
}

/**
 * Grows up from its base (a bar of a chart), a little past its height and back, [index] × 50 ms
 * after the one before it.
 */
fun Modifier.growIn(index: Int = 0): Modifier = composed {
    val animate = LocalAnimationsOn.current
    val t = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!animate) return@LaunchedEffect
        kotlinx.coroutines.delay(80L + index * 50L)
        t.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 260f))
    }
    graphicsLayer { scaleY = t.value.coerceAtLeast(0f); transformOrigin = TransformOrigin(0.5f, 1f) }
}

/** A grain of texture each time a row of things scrolls past the next one, while it's moving. */
@Composable
fun ScrollTicks(list: androidx.compose.foundation.lazy.LazyListState, strength: Float = 0.14f) {
    val view = LocalView.current
    LaunchedEffect(list) {
        var first = true
        androidx.compose.runtime.snapshotFlow { list.firstVisibleItemIndex }.collect {
            if (first) { first = false; return@collect }
            if (list.isScrollInProgress) Haptics.texture(view, strength)
        }
    }
}

/**
 * Draws [shape] itself, filled or as an even edge of [stroke] px, nudged by [offset]. A stamp's second
 * ink and its outline have to follow its own cut corners; a stand-in rounded box drawn under or inside
 * a differently cornered clip shows as a mismatched shadow and an edge that thins and breaks at the
 * tight corners. The edge is drawn inset by half its width, so the clip doesn't shave it.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShape(
    shape: androidx.compose.ui.graphics.Shape,
    color: androidx.compose.ui.graphics.Color,
    offset: Offset = Offset.Zero,
    stroke: Float = 0f,
    dash: FloatArray? = null,
) {
    val inset = stroke / 2f
    // Nothing to draw while collapsed (a shape smaller than its edge would have negative corners, which throws).
    if (size.width - stroke <= 0f || size.height - stroke <= 0f) return
    val outline = shape.createOutline(androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke), layoutDirection, this)
    translate(offset.x + inset, offset.y + inset) {
        if (stroke > 0f) drawOutline(outline, color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, pathEffect = dash?.let { androidx.compose.ui.graphics.PathEffect.dashPathEffect(it) }))
        else drawOutline(outline, color)
    }
}
