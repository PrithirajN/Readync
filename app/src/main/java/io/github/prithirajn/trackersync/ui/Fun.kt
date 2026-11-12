package io.github.prithirajn.trackersync.ui

import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType

/** A light tick under the finger, for the moments that should feel physical (a toggle, a star, a save). */
@Composable
fun rememberTick(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) { { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
}

// ---- The book's actions: one row of icon tiles, each the same size ----

data class BookActionItem(val icon: Int, val label: String, val onClick: () -> Unit, val highlighted: Boolean = false)

/**
 * The book's main actions as a row of tiles (an icon in a soft circle, a short label under it),
 * like a phone's quick settings: every action visible at once, nothing cut off or wrapped.
 */
@Composable
fun ActionBar(items: List<BookActionItem>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (item in items) ActionTile(item, Modifier.weight(1f))
    }
}

@Composable
private fun ActionTile(item: BookActionItem, modifier: Modifier) {
    val c = Cp.colors
    Column(
        modifier
            .clip(CpShapes.control)
            .bouncyClickable(CpShapes.control, pressedScale = 0.9f, role = Role.Button, onClickLabel = item.label, onClick = item.onClick)
            .heightIn(min = 72.dp)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (item.highlighted) c.accent else c.accentTint),
            contentAlignment = Alignment.Center,
        ) {
            LucideIcon(item.icon, Modifier.size(20.dp), if (item.highlighted) Color.White else c.accentText)
        }
        Text(
            item.label,
            style = CpType.small,
            color = c.textMid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// ---- Trackers at a glance: a dot each ----

enum class Dot { SYNCED, WAITING, NEEDS_YOU, OFF }

/**
 * One small lettered dot per tracker: filled when it's in step, ringed while waiting, amber when
 * it needs you (a match to pick), faint when off. Reads at a glance without a wall of chips.
 */
@Composable
fun TrackerDots(dots: List<Pair<String, Dot>>, modifier: Modifier = Modifier) {
    val c = Cp.colors
    val spoken = dots.joinToString(", ") { (name, d) ->
        name + when (d) { Dot.SYNCED -> " synced"; Dot.WAITING -> " waiting"; Dot.NEEDS_YOU -> " needs a match"; Dot.OFF -> " off" }
    }
    val marks = trackerMarks(dots.map { it.first })
    Row(modifier.semantics { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((i, pair) in dots.withIndex()) {
            val d = pair.second
            val (bg, fg, ring) = when (d) {
                Dot.SYNCED -> Triple(c.accent, Color.White, Color.Transparent)
                Dot.WAITING -> Triple(Color.Transparent, c.accentText, c.accentRing)
                Dot.NEEDS_YOU -> Triple(c.warn.copy(alpha = 0.18f), c.warn, c.warn)
                Dot.OFF -> Triple(c.divider, c.faint, Color.Transparent)
            }
            val mark = marks[i]
            Box(
                Modifier
                    .heightIn(min = 22.dp)
                    .widthIn(min = 22.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .border(1.dp, ring, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    mark,
                    style = CpType.chip.copy(fontSize = if (mark.length > 1) 10.sp else 11.sp),
                    color = fg,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = if (mark.length > 1) 5.dp else 0.dp),
                )
            }
        }
    }
}

/**
 * A short mark per tracker that no other tracker shares: its first letter when that's unique,
 * else its own short name (StoryGraph "SG", Seekquel "Sq"), else its first two letters.
 */
fun trackerMarks(names: List<String>): List<String> {
    val known = mapOf("StoryGraph" to "SG", "Seekquel" to "Sq")
    val firsts = names.groupingBy { it.take(1).uppercase() }.eachCount()
    return names.map { n ->
        val first = n.take(1).uppercase()
        when {
            (firsts[first] ?: 0) <= 1 -> first
            known[n] != null -> known.getValue(n)
            else -> n.filter { it.isUpperCase() }.takeIf { it.length >= 2 }?.take(2)
                ?: n.take(2).replaceFirstChar { it.uppercase() }
        }
    }
}

// ---- A settings row that opens its own page ----

/** An icon, a title with a one-line summary, and a chevron: tap to open that setting's own sheet. */
@Composable
fun NavRow(icon: Int, title: String, summary: String?, summaryWarn: Boolean = false, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val c = Cp.colors
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // The chevron slides along as if the row were about to open, and the icon key dips.
    val nudge by androidx.compose.animation.core.animateDpAsState(if (pressed) 5.dp else 0.dp, Motion.bouncy(), label = "navNudge")
    val dip by androidx.compose.animation.core.animateDpAsState(if (pressed) 2.dp else 0.dp, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 900f), label = "navDip")
    val chevron by androidx.compose.animation.animateColorAsState(if (pressed) c.accentText else c.faint, Motion.fast(), label = "navChevron")
    Row(
        Modifier
            .fillMaxWidth()
            .pressRow(source, title, onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A little moulded key with the icon on it, sitting on its ledge.
        Box(Modifier.size(38.dp)) {
            Box(Modifier.matchParentSize().padding(top = 2.dp).clip(CpShapes.control).background(c.keyLedge))
            Box(
                Modifier
                    .size(38.dp, 36.dp)
                    .offset(y = dip)
                    .clip(CpShapes.control)
                    .background(Brush.verticalGradient(listOf(c.keyTop, c.keyBottom)))
                    .drawBehind { drawRect(Color.White.copy(alpha = if (c.isDark) 0.06f else 0.6f), size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) },
                contentAlignment = Alignment.Center,
            ) { LucideIcon(icon, Modifier.size(18.dp), if (summaryWarn) c.errorText else c.text) }
        }
        HSpace(12)
        Column(Modifier.weight(1f)) {
            Text(title, style = CpType.bodyStrong, color = c.text, maxLines = 1)
            summary?.let { Text(it, style = CpType.small, color = if (summaryWarn) c.errorText else c.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        trailing?.let { HSpace(8); it() }
        HSpace(4)
        LucideIcon(io.github.prithirajn.trackersync.R.drawable.lucide_chevron_right, Modifier.size(16.dp).offset(x = nudge), chevron)
    }
}

/**
 * A whole row as a touch target: an ink wash comes up under the finger, a click is felt on the
 * way down, and it hands its press state to [source] so the row's parts can answer too.
 */
@Composable
fun Modifier.pressRow(source: androidx.compose.foundation.interaction.MutableInteractionSource, label: String?, onClick: () -> Unit, enabled: Boolean = true): Modifier {
    val c = Cp.colors
    val view = androidx.compose.ui.platform.LocalView.current
    val pressed by source.collectIsPressedAsState()
    LaunchedEffect(pressed) { if (pressed && enabled) Haptics.tick(view) }
    val wash by androidx.compose.animation.core.animateFloatAsState(if (pressed) 1f else 0f, if (pressed) tween(90) else tween(380), label = "rowWash")
    return this
        .drawBehind { if (wash > 0f) drawRect(c.text.copy(alpha = 0.06f * wash)) }
        .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
}

// ---- A little celebration ----

/**
 * A burst of confetti from the middle of its box, each time [burst] goes up (a book finished, a
 * high rating). Drawn over whatever is around it; it doesn't take any space or block taps.
 */
@Composable
fun Confetti(burst: Int, modifier: Modifier = Modifier, height: Dp = 0.dp) {
    if (burst <= 0) return
    val c = Cp.colors
    val colors = remember(c.isDark) { listOf(c.accent, Color(0xFFF2B84B), Color(0xFFE76F51), Color(0xFF6CC3D5), Color(0xFFB38CF2), c.accentText) }
    val t = remember(burst) { Animatable(0f) }
    LaunchedEffect(burst) { t.snapTo(0f); t.animateTo(1f, tween(1500, easing = LinearEasing)) }
    val pieces = remember(burst) {
        val r = java.util.Random(burst * 7919L)
        List(46) {
            val angle = Math.toRadians(-90.0 + (r.nextDouble() - 0.5) * 150.0)
            val speed = 700f + r.nextFloat() * 700f
            Piece(
                vx = (Math.cos(angle) * speed).toFloat(),
                vy = (Math.sin(angle) * speed).toFloat(),
                spin = (r.nextFloat() - 0.5f) * 900f,
                w = 6f + r.nextFloat() * 6f,
                h = 10f + r.nextFloat() * 8f,
                color = colors[r.nextInt(colors.size)],
            )
        }
    }
    if (t.value >= 1f) return
    Canvas(modifier) {
        val s = t.value * 1.5f // seconds
        val origin = Offset(size.width / 2f, size.height / 2f)
        val alpha = (1f - t.value).coerceIn(0f, 1f)
        for (p in pieces) {
            val x = origin.x + p.vx * s * 0.6f
            val y = origin.y + p.vy * s * 0.6f + 900f * s * s
            rotate(p.spin * s, Offset(x, y)) {
                drawRect(p.color.copy(alpha = alpha), topLeft = Offset(x - p.w / 2, y - p.h / 2), size = Size(p.w, p.h))
            }
        }
    }
}

private class Piece(val vx: Float, val vy: Float, val spin: Float, val w: Float, val h: Float, val color: Color)

/**
 * Lets a scrolling page melt away at its top edge instead of being sliced off by a hard line
 * under the status bar: the first [height] fades from clear to fully drawn. Only while
 * [active] (scrolled down), so the page title is never dimmed at the top.
 */
fun Modifier.fadeTopEdge(active: Boolean, height: Dp = 28.dp): Modifier = if (!active) this else this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = height.toPx().coerceAtMost(size.height)
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = h,
            ),
            size = Size(size.width, h),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }
