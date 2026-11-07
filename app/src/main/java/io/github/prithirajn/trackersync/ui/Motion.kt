package io.github.prithirajn.trackersync.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.sync.Health
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import io.github.prithirajn.trackersync.ui.theme.Cp

/** One set of timings for the whole app, so every movement feels like the same hand made it. */
object Motion {
    /** Material's emphasized curve: quick to start, gentle to settle. */
    val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> fast() = tween<T>(durationMillis = 180, easing = emphasized)
    fun <T> medium() = tween<T>(durationMillis = 320, easing = emphasized)
    fun <T> slow() = tween<T>(durationMillis = 700, easing = emphasized)

    /** A lively spring that overshoots and settles, for presses and switches. */
    fun <T> bouncy() = spring<T>(dampingRatio = 0.42f, stiffness = 450f)

    /** A looser spring for things that travel: rows, slides, pills. */
    fun <T> springy() = spring<T>(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)

    /** Progress bars: fill with a small overshoot, like a rubber band. */
    fun <T> fill() = spring<T>(dampingRatio = 0.5f, stiffness = 90f)
}

/**
 * Clickable that sinks slightly under the finger and springs back, with the ripple clipped to
 * [shape]. Put it before background/border so the whole control moves together.
 */
@Composable
fun Modifier.bouncyClickable(
    shape: Shape,
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
    role: Role? = null,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val scale = remember { Animatable(1f) }
    val view = androidx.compose.ui.platform.LocalView.current
    // Sink under the finger, then spring back past full size and settle. A quick tap still
    // gets the whole dip and bounce, so every press feels answered.
    LaunchedEffect(source, enabled) {
        source.interactions.collectLatest { i ->
            when (i) {
                is PressInteraction.Press -> if (enabled) scale.animateTo(pressedScale, spring(dampingRatio = 1f, stiffness = Spring.StiffnessHigh))
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    if (enabled && i is PressInteraction.Release && scale.value > pressedScale + 0.005f) {
                        scale.animateTo(pressedScale, tween(durationMillis = 70))
                    }
                    scale.animateTo(1f, Motion.bouncy())
                }
            }
        }
    }
    return this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .clip(shape)
        .clickable(
            interactionSource = source,
            indication = LocalIndication.current,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = {
                // A light tick under the finger (follows the phone's touch-feedback setting).
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            },
        )
}

/** A Lucide icon turning steadily: the sync icon while a sync runs. */
@Composable
fun SpinningIcon(res: Int, size: Dp, tint: Color = Cp.colors.quiet) {
    val turn = rememberInfiniteTransition(label = "spin")
    val angle by turn.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "angle",
    )
    LucideIcon(res, Modifier.size(size).graphicsLayer { rotationZ = angle }, tint)
}

/** Quiet text that cross-fades when it changes, instead of jumping. */
@Composable
fun AnimatedQuiet(text: String, modifier: Modifier = Modifier, color: Color = Cp.colors.quiet, maxLines: Int = Int.MAX_VALUE) {
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(Motion.medium()) togetherWith fadeOut(Motion.fast()) },
        modifier = modifier,
        label = "text",
    ) { t ->
        Quiet(t, color = color, maxLines = maxLines)
    }
}


/**
 * A health light: green fine, amber wants a look, red broken, grey off. Amber and red pulse
 * softly so they're noticed; tapping says what it means.
 */
@Composable
fun HealthLight(status: Health.Status, onClick: () -> Unit) {
    val c = Cp.colors
    val level = status.level
    val target = when (level) {
        Health.Level.GOOD -> c.good
        Health.Level.WARN -> c.warn
        Health.Level.BAD -> c.error
        Health.Level.OFF -> c.faint
    }
    val color by animateColorAsState(target, Motion.medium(), label = "health")
    Box(
        Modifier
            .size(32.dp)
            .bouncyClickable(CircleShape, pressedScale = 0.85f, onClickLabel = status.title, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (level == Health.Level.WARN || level == Health.Level.BAD) {
            val pulse = rememberInfiniteTransition(label = "pulse")
            val p by pulse.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing)),
                label = "halo",
            )
            Box(
                Modifier
                    .size(10.dp)
                    .graphicsLayer {
                        scaleX = 1f + p * 1.6f
                        scaleY = 1f + p * 1.6f
                        alpha = (1f - p) * 0.55f
                    }
                    .clip(CircleShape)
                    .background(color)
            )
        } else if (level == Health.Level.GOOD) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)))
        }
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    }
}


/** False when the phone's animations are switched off: then nothing in the background moves. */
val LocalAnimationsOn = androidx.compose.runtime.staticCompositionLocalOf { true }

@Composable
fun rememberAnimationsOn(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
}

/**
 * Fluid Aurora Mesh Glow:
 * Large, luminous, ultra-soft color pools that slowly drift, breathe, and blend across the screen.
 * Seamless, non-distracting depth derived from the book's cover palette (accent and accentRing).
 * Still and elegant when animations are off.
 */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val c = Cp.colors
    val on = LocalAnimationsOn.current
    val drift = rememberInfiniteTransition(label = "aurora")
    fun loop(ms: Int) = infiniteRepeatable<Float>(tween(durationMillis = ms, easing = LinearEasing))
    val end = if (on) TWO_PI else 0f
    val a by drift.animateFloat(0f, end, loop(22_000), label = "a")
    val b by drift.animateFloat(0f, end, loop(28_000), label = "b")
    val d by drift.animateFloat(0f, end, loop(34_000), label = "d")
    val e by drift.animateFloat(0f, end, loop(19_000), label = "e")

    val strength = if (c.isDark) 0.28f else 0.18f

    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height

        // Multi-stop butter-smooth radial gradient falloff with zero banding
        fun auroraOrb(x: Float, y: Float, radius: Float, color: Color, alpha: Float) {
            val center = androidx.compose.ui.geometry.Offset(x, y)
            drawCircle(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = alpha),
                        color.copy(alpha = alpha * 0.55f),
                        color.copy(alpha = alpha * 0.18f),
                        color.copy(alpha = 0f),
                    ),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        // 1. Primary Aurora Core (Top-left / center pool - slowly drifts and pulses)
        val x1 = w * (0.28f + 0.16f * kotlin.math.cos(a))
        val y1 = h * (0.16f + 0.08f * kotlin.math.sin(b))
        val r1 = w * (1.05f + 0.10f * kotlin.math.sin(e))
        auroraOrb(x1, y1, r1, c.accent, strength)

        // 2. Secondary Radiant Harmonic (Mid-right pool in complementary accentRing)
        val x2 = w * (0.82f + 0.14f * kotlin.math.sin(b))
        val y2 = h * (0.45f + 0.12f * kotlin.math.cos(d))
        val r2 = w * (0.95f + 0.08f * kotlin.math.cos(a))
        auroraOrb(x2, y2, r2, c.accentRing, strength * 0.90f)

        // 3. Lower Horizon Glow (Soft base color wash near lower third)
        val x3 = w * (0.40f + 0.18f * kotlin.math.sin(d))
        val y3 = h * (0.85f + 0.07f * kotlin.math.cos(a))
        val r3 = w * (1.00f + 0.08f * kotlin.math.sin(b))
        auroraOrb(x3, y3, r3, c.accent, strength * 0.75f)

        // 4. Subtle Ambient Halo (Expansive top halo that frames headers and greeting)
        val x4 = w * (0.65f + 0.12f * kotlin.math.cos(e))
        val y4 = h * (0.05f + 0.05f * kotlin.math.sin(a))
        val r4 = w * (0.85f + 0.06f * kotlin.math.cos(d))
        auroraOrb(x4, y4, r4, c.accentRing, strength * 0.65f)
    }
}

private const val TWO_PI = (2 * Math.PI).toFloat()

/**
 * The app's ribbon while a sync runs: its two arrows slide past each other and back, the way
 * your place passes between the reader and your trackers. Still when [moving] is false.
 */
@Composable
fun SyncRibbon(size: Dp, tint: Color, moving: Boolean, modifier: Modifier = Modifier) {
    val turn = rememberInfiniteTransition(label = "ribbon")
    val phase by turn.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 700, easing = androidx.compose.animation.core.FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "phase",
    )
    val shift = if (moving) (phase - 0.5f) * 2f else 0f // −1…1
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val u = this.size.width / 24f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val ribbon = androidx.compose.ui.graphics.Path().apply {
            moveTo(6f * u, 3f * u); lineTo(18f * u, 3f * u); lineTo(18f * u, 21f * u); lineTo(12f * u, 17f * u); lineTo(6f * u, 21f * u); close()
        }
        drawPath(ribbon, tint, style = stroke)
        val a = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val dx = 0.9f * u * shift
        // Upper arrow, pointing right, moving right; lower one pointing left, moving left.
        val up = androidx.compose.ui.graphics.Path().apply {
            moveTo(9.3f * u + dx, 9.5f * u); lineTo(14.2f * u + dx, 9.5f * u)
            moveTo(12.6f * u + dx, 7.9f * u); lineTo(14.2f * u + dx, 9.5f * u); lineTo(12.6f * u + dx, 11.1f * u)
        }
        val down = androidx.compose.ui.graphics.Path().apply {
            moveTo(14.7f * u - dx, 13.7f * u); lineTo(9.8f * u - dx, 13.7f * u)
            moveTo(11.4f * u - dx, 12.1f * u); lineTo(9.8f * u - dx, 13.7f * u); lineTo(11.4f * u - dx, 15.3f * u)
        }
        drawPath(up, tint, style = a)
        drawPath(down, tint.copy(alpha = if (moving) 0.75f + 0.25f * (1f - kotlin.math.abs(shift)) else 1f), style = a)
    }
}
