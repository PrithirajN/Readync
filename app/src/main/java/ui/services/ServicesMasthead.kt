package io.github.prithirajn.readync.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpType
import io.github.prithirajn.readync.ui.theme.Display

/**
 * The top of Services, printed like a masthead for the tab you're on: how many are on as one huge
 * Bodoni figure sliding into register, what it counts in italics beside it with when it last did
 * something, every service as a printed tag with a dot of ink (teal when fine, red when it wants
 * you, hollow when off), a stamp slapped on the corner when some need you, a band of ink running
 * under it while a sync goes, and (under Share & export) the next posts as a little timetable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ServicesMasthead(
    lights: List<Pair<String, Int>>,
    label: String,
    lastLine: String,
    running: Boolean,
    next: List<UpcomingPosts.Upcoming>,
    modifier: Modifier = Modifier,
    onOpenPost: (UpcomingPosts.Upcoming) -> Unit = {},
) {
    val c = Cp.colors
    val on = lights.count { it.second > 0 }
    val needYou = lights.count { it.second == 2 }
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Bottom) {
                // Keyed on the figure, so a new count prints itself in again.
                androidx.compose.runtime.key(label, on) {
                    RegisteredText(on.toString(), CpType.screenNumber.copy(fontSize = 96.sp, lineHeight = 92.sp), replay = LocalTabVisible.current)
                }
                Column(Modifier.padding(start = 14.dp, bottom = 12.dp)) {
                    Text(label, fontFamily = Display, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 28.sp, color = c.text)
                    Text(if (running) "syncing now" else lastLine.lowercase(), style = CpType.mono, color = if (running) c.accentText else c.quiet)
                }
            }
            if (needYou > 0) NeedStamp(needYou, Modifier.align(Alignment.TopEnd).padding(top = 6.dp))
        }
        RunningBand(running)
        if (next.isNotEmpty()) {
            Text("next up", style = CpType.eyebrow, color = c.accentText, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
            val now = System.currentTimeMillis() / 1000
            next.forEachIndexed { k, u ->
                // Each a slip you can open: the passage itself, a look at it, and what you can change.
                Row(
                    Modifier.fillMaxWidth().staggerIn(k)
                        .bouncyClickable(RoundedCornerShape(8.dp), pressedScale = 0.985f, onClickLabel = "Open the post to ${u.where}") { onOpenPost(u) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.width(124.dp).padding(end = 8.dp)) {
                        Text(UpcomingPosts.whenText(u, now), fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 18.sp, color = c.text, maxLines = 2)
                        Text(u.where, style = CpType.mono, color = c.accentText, maxLines = 2)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(u.what, style = CpType.small, color = c.textMid, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        u.snippet?.trim()?.takeIf { it.isNotBlank() }?.let { q ->
                            Row(Modifier.padding(top = 4.dp)) {
                                Box(Modifier.padding(top = 3.dp).width(2.dp).height(28.dp).background(c.accent.copy(alpha = 0.7f)))
                                Spacer(Modifier.width(8.dp))
                                Text("“" + q.removeSurrounding("“", "”") + "”", style = CpType.small.copy(fontStyle = FontStyle.Italic), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Text("›", fontFamily = Display, fontSize = 22.sp, color = c.faint, modifier = Modifier.padding(start = 6.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.text.copy(alpha = 0.1f)))
            }
        }
    }
}

/** A service as a printed tag: its name with a dot of ink for how it's doing. */
@Composable
private fun ServiceTag(name: String, state: Int, modifier: Modifier = Modifier) {
    val c = Cp.colors
    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 5.dp, bottomEnd = 12.dp, bottomStart = 5.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (state == 2) c.error.copy(alpha = 0.16f) else c.surface.copy(alpha = 0.8f))
            .drawBehind {
                drawShape(shape, (if (state == 2) c.error else c.text).copy(alpha = if (state == 2) 0.7f else 0.14f), stroke = 1.2.dp.toPx())
            }
            .padding(start = 9.dp, end = 11.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).drawBehind {
                when (state) {
                    0 -> drawCircle(c.faint, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                    2 -> drawCircle(c.error)
                    else -> drawCircle(c.good)
                }
            },
        )
        Spacer(Modifier.width(7.dp))
        Text(name, style = CpType.small, color = if (state == 0) c.quiet else c.text, maxLines = 1)
    }
}

/** "2 need you", stamped crooked onto the corner: it lands with a thump when it first appears. */
@Composable
private fun NeedStamp(count: Int, modifier: Modifier) {
    val c = Cp.colors
    val animate = LocalAnimationsOn.current
    val land = remember(count) { Animatable(if (animate) 1.6f else 1f) }
    LaunchedEffect(count) { land.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 420f)) }
    Column(
        modifier
            .graphicsLayer { rotationZ = -6f; scaleX = land.value; scaleY = land.value; alpha = (2.2f - land.value).coerceIn(0f, 1f) }
            .drawBehind { drawRoundRect(c.signalLedge, topLeft = Offset(3.dp.toPx(), 4.dp.toPx()), size = size, cornerRadius = CornerRadius(10.dp.toPx())) }
            .clip(RoundedCornerShape(10.dp))
            .background(c.error)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FigureText(count.toString(), CpType.screenNumber.copy(fontSize = 26.sp, lineHeight = 26.sp), c.onAccent)
        Text("need you", style = CpType.mono, color = c.onAccent)
    }
}

/** While a sync runs, a band of ink runs along under the figure; otherwise a plain printed rule. */
@Composable
private fun RunningBand(running: Boolean) {
    val c = Cp.colors
    val move = if (running && LocalAnimationsOn.current && LocalTabVisible.current) {
        rememberInfiniteTransition(label = "band").animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "bandAt")
    } else null
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(3.dp)
            .drawBehind {
                drawRect(c.text.copy(alpha = 0.22f), size = Size(size.width, 1.dp.toPx()), topLeft = Offset(0f, size.height / 2f))
                val t = move?.value
                if (t != null) {
                    val w = size.width * 0.35f
                    val x = -w + (size.width + w) * t
                    drawRoundRect(c.accent, topLeft = Offset(x, 0f), size = Size(w, size.height), cornerRadius = CornerRadius(size.height / 2f))
                }
            },
    )
}
