package io.github.prithirajn.readync.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpShapes
import io.github.prithirajn.readync.ui.theme.CpType

// Compose versions of the companion app's ui.jsx / Settings.jsx building blocks.

/** A slip of paper laid on the page: flat, its corners cut unevenly, the page's grain faintly through it. */
@Composable
fun CpCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Cp.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(CpShapes.card)
            .background(c.surface.copy(alpha = if (c.isDark) 0.86f else 0.9f))
            .border(1.dp, c.hairline, CpShapes.card)
            // Rows appearing or disappearing inside a card resize it smoothly.
            .animateContentSize(Motion.medium()),
        content = content,
    )
}

/** divide-y divide-stone-100 between card rows */
@Composable
fun RowDivider() = HorizontalDivider(thickness = 1.dp, color = Cp.colors.divider)

/** A small-caps label above a block, printed in the section's ink. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text, style = CpType.eyebrow, color = Cp.colors.accentText, modifier = modifier)
}

/**
 * A page's title, printed in three passes: two inks slide in from either side and settle just off
 * register behind the text, like a riso print. Once, when the page first appears.
 */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier) {
    RegisteredText(text, CpType.pageTitle, modifier)
}

@Composable
fun RegisteredText(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier, color: Color = Cp.colors.text, replay: Any? = Unit) {
    val c = Cp.colors
    val on = LocalAnimationsOn.current
    // Prints itself in again whenever [replay] changes (each time its tab comes back on screen), but
    // not when its row was only scrolled away and back (what it last printed for is saved).
    val replayKey = replay.toString()
    var printedFor by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val t = remember { androidx.compose.animation.core.Animatable(if (on && printedFor != replayKey) 0f else 1f) }
    LaunchedEffect(replay) {
        if (printedFor == replayKey) { t.snapTo(1f); return@LaunchedEffect }
        printedFor = replayKey
        if (on && t.value >= 1f) t.snapTo(0f)
        t.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 140f))
    }
    Box(modifier) {
        Text(text, style = style, color = c.accent, modifier = Modifier.graphicsLayer {
            val k = 1f - t.value
            translationX = -(1.5f + 10f * k) * density; translationY = -(1f + 6f * k) * density
            alpha = (if (c.isDark) 0.75f else 0.55f) * t.value.coerceIn(0f, 1f)
        })
        Text(text, style = style, color = c.signalLedge, modifier = Modifier.graphicsLayer {
            val k = 1f - t.value
            translationX = (1.5f + 11f * k) * density; translationY = (1f + 7f * k) * density
            alpha = (if (c.isDark) 0.7f else 0.5f) * t.value.coerceIn(0f, 1f)
        })
        FigureText(text, style, color)
    }
}

/**
 * Big figures in the display face, drawn once more as an outline over themselves: every stroke a
 * little heavier, the hairlines most of all (at these sizes, through the grain, a 4's thin diagonal
 * used to vanish and 43 read as 13). The outline is about 2.4% of the type size.
 */
@Composable
fun FigureText(text: String, style: androidx.compose.ui.text.TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { (if (style.fontSize.isSp) style.fontSize else 40.sp).toPx() }
    Box(modifier) {
        Text(text, style = style, color = color, maxLines = maxLines)
        Text(
            text, style = style.copy(drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = px * 0.024f, join = androidx.compose.ui.graphics.StrokeJoin.Round)),
            color = color, maxLines = maxLines,
        )
    }
}

/**
 * The top of every tab, the same everywhere: a small-caps line in the tab's ink, the tab's title
 * set big in Bodoni and printed into register, and its keys together on a little tray to the right.
 * Each time the tab comes back on screen the line slides in, the title prints itself again and the
 * keys pop up one after another.
 */
@Composable
fun TabHeader(eyebrow: String, title: String, modifier: Modifier = Modifier, actions: (@Composable RowScope.() -> Unit)? = null) {
    val c = Cp.colors
    val visible = LocalTabVisible.current
    val on = LocalAnimationsOn.current
    val slide = remember { androidx.compose.animation.core.Animatable(if (on) 0f else 1f) }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        if (on) slide.snapTo(0f)
        slide.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.75f, stiffness = 220f))
    }
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow, style = CpType.eyebrow, color = c.accentText, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { translationX = (1f - slide.value) * -24.dp.toPx(); alpha = slide.value.coerceIn(0f, 1f) })
            RegisteredText(title, CpType.pageTitle, Modifier.padding(top = 2.dp), replay = visible)
        }
        if (actions != null) {
            Row(
                Modifier
                    .padding(bottom = 4.dp)
                    .graphicsLayer { val k = slide.value.coerceIn(0f, 1.1f); scaleX = 0.7f + 0.3f * k; scaleY = 0.7f + 0.3f * k; alpha = slide.value.coerceIn(0f, 1f) }
                    .clip(CpShapes.pill)
                    .background(c.surface.copy(alpha = if (c.isDark) 0.55f else 0.7f))
                    .border(1.dp, c.hairline, CpShapes.pill)
                    .padding(horizontal = 5.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = CpType.section, color = Cp.colors.text, modifier = modifier.padding(top = 32.dp))
}

@Composable
fun LucideIcon(res: Int, modifier: Modifier = Modifier.size(16.dp), tint: Color = Cp.colors.quiet) {
    Icon(painterResource(res), contentDescription = null, modifier = modifier, tint = tint)
}

/** A book's progress: a bar of ink growing to the place, in the colour of its cover when [coverUrl] is given. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, fillIn: Boolean = false, coverUrl: String? = null) {
    SegmentBar(fraction, modifier, height = 6.dp, on = if (coverUrl != null) rememberBookInk(coverUrl) else Cp.colors.wall)
}

/**
 * A hardware slide switch: a recessed track, and a knob that slides across with a click. When
 * it's on, the knob is the signal orange and glows (with a brief flare as it turns on).
 */
@Composable
fun CpToggle(checked: Boolean, onChange: (Boolean) -> Unit, label: String, enabled: Boolean = true) {
    val c = Cp.colors
    val view = androidx.compose.ui.platform.LocalView.current
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // Held down, the knob stretches toward where it's about to go, like a drop of something.
    val stretch by animateDpAsState(if (pressed && enabled) 6.dp else 0.dp, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 800f), label = "knobStretch")
    // Off: an empty outline with a small hollow-looking knob at rest on the left. On: the track
    // inks in the section's colour over its second ink, and the knob grows dark and solid.
    val on by animateFloatAsState(if (checked) 1f else 0f, Motion.bouncy(), label = "toggle")
    val track by animateColorAsState(if (checked) c.accent else c.accent.copy(alpha = 0f), Motion.medium(), label = "track")
    val knobColor by animateColorAsState(if (checked) c.onAccent else c.quiet, Motion.medium(), label = "knob")
    // Switched on, a ring of ink pops out from the knob and fades.
    val flare = remember { androidx.compose.animation.core.Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked) {
        if (checked) { flare.snapTo(2.2f); flare.animateTo(1f, tween(650)) } else flare.animateTo(0f, tween(200))
    }
    Box(
        Modifier
            .size(width = 50.dp, height = 30.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .drawBehind {
                val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
                if (on > 0.01f) drawRoundRect(c.signalLedge.copy(alpha = on.coerceIn(0f, 1f)), topLeft = androidx.compose.ui.geometry.Offset(2.dp.toPx(), 2.5.dp.toPx()), size = size, cornerRadius = r)
            }
            .clip(CpShapes.pill)
            .clickable(source, indication = null, enabled = enabled, role = Role.Switch, onClickLabel = label) {
                Haptics.toggle(view, !checked)
                onChange(!checked)
            }
            .background(track)
            .drawBehind {
                val a = (1f - on).coerceIn(0f, 1f)
                if (a > 0.01f) drawRoundRect(c.textMid.copy(alpha = 0.5f * a), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val knobSize = 14.dp + 8.dp * on.coerceIn(0f, 1.1f)
        Box(
            Modifier
                .offset(x = 5.dp + (50.dp - 10.dp - 22.dp) * on + (if (checked) -stretch else 0.dp) + (22.dp - knobSize) / 2)
                .size(width = knobSize + stretch, height = knobSize)
                .drawBehind {
                    val f = flare.value
                    if (f > 1.01f) drawCircle(c.accent.copy(alpha = ((f - 1f) / 1.2f).coerceIn(0f, 1f)), radius = size.minDimension * (0.5f + 0.5f * (2.2f - f)), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                }
                .clip(CpShapes.pill)
                .background(knobColor)
        )
    }
}

/** A big key: the signal orange when [filled], a light key otherwise (red writing for [danger]). */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    danger: Boolean = false,
    busy: Boolean = false,
    enabled: Boolean = true,
    /** Overrides [filled] and [danger]: the dark ink key, say. */
    look: KeyKind? = null,
) {
    val kind = when {
        look != null -> look
        filled && !danger -> KeyKind.SIGNAL
        danger -> KeyKind.DANGER
        else -> KeyKind.PLAIN
    }
    val fg = keyText(kind)
    Key(onClick, modifier.fillMaxWidth().height(52.dp), kind = kind, enabled = enabled && !busy, label = text) {
        Crossfade(busy, animationSpec = Motion.fast(), label = "busy") { working ->
            if (working) {
                CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
            } else {
                Text(text, style = CpType.bodyStrong, color = fg)
            }
        }
    }
}

/** A small key: a light one, the orange signal key when not [outlined], or red writing for [danger]. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false, outlined: Boolean = true) {
    val kind = when {
        danger -> KeyKind.DANGER
        !outlined -> KeyKind.SIGNAL
        else -> KeyKind.PLAIN
    }
    Key(onClick, modifier.height(38.dp), kind = kind, shape = CpShapes.pill, ledge = 2.dp, label = text) {
        Text(
            text,
            style = CpType.bodyStrong,
            color = keyText(kind),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 15.dp),
        )
    }
}

/** A small round key with an icon (sync now, settings, matches): sinks with a click. */
@Composable
fun IconCircleButton(res: Int, label: String, onClick: () -> Unit, busy: Boolean = false, tint: Color = Cp.colors.textMid) {
    Key(onClick, Modifier.size(width = 38.dp, height = 40.dp), shape = CircleShape, ledge = 2.dp, enabled = !busy, label = label) {
        // The sync ribbon moves its arrows while busy; any other icon turns.
        if (res == io.github.prithirajn.readync.R.drawable.ic_ribbon_sync) SyncRibbon(18.dp, if (busy) Cp.colors.accent else tint, moving = busy)
        else if (busy) SpinningIcon(res, 16.dp, Cp.colors.accent)
        else LucideIcon(res, Modifier.size(17.dp), tint)
    }
}

/** Small rounded-full stone-100 chip (e.g. "Beta"). */
@Composable
fun Chip(text: String, color: Color = Cp.colors.quiet, background: Color = Cp.colors.divider) {
    Text(
        text,
        style = CpType.chip,
        color = color,
        modifier = Modifier
            .clip(CpShapes.pill)
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A label/control row inside a card: px-4 py-3 text-sm font-medium. */
@Composable
fun SettingRow(label: String, modifier: Modifier = Modifier, control: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = CpType.label, color = Cp.colors.text, modifier = Modifier.weight(1f))
        control()
    }
}

/** The companion app's select: h-9 rounded-lg stone-50 field with a ring, as a dropdown. */
@Composable
fun <T> CpSelect(
    value: T,
    options: List<T>,
    label: (T) -> String,
    onChange: (T) -> Unit,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val c = Cp.colors
    val view = androidx.compose.ui.platform.LocalView.current
    val flip by androidx.compose.animation.core.animateFloatAsState(if (open) 90f else -90f, Motion.bouncy(), label = "selectFlip")
    Box {
        Key({ open = true }, Modifier.height(40.dp), shape = CpShapes.field, ledge = 2.dp, enabled = enabled) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.animation.AnimatedContent(label(value), transitionSpec = {
                    (androidx.compose.animation.slideInVertically { it / 2 } + androidx.compose.animation.fadeIn()) togetherWith (androidx.compose.animation.slideOutVertically { -it / 2 } + androidx.compose.animation.fadeOut())
                }, label = "selectValue") { t -> Text(t, style = CpType.body, color = if (enabled) c.text else c.faint) }
                LucideIcon(io.github.prithirajn.readync.R.drawable.lucide_chevron_left, Modifier.size(14.dp).rotate(flip), if (open) c.accentText else c.quiet)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.surface, shape = CpShapes.card) {
            for (o in options) {
                val chosen = o == value
                DropdownMenuItem(
                    text = { Text(label(o), style = if (chosen) CpType.bodyStrong else CpType.body, color = if (chosen) c.accentText else c.text) },
                    leadingIcon = { Led(chosen, size = 6.dp) },
                    onClick = { Haptics.tick(view); open = false; onChange(o) },
                )
            }
        }
    }
}

/** A labelled text field in the companion app's style: rounded-2xl surface with a ring. */
@Composable
fun CpTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    mono: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Cp.colors
    val focus = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    val ring by animateColorAsState(if (focused) c.accent else c.hairline, Motion.medium(), label = "fieldRing")
    val glow by androidx.compose.animation.core.animateFloatAsState(if (focused) 1f else 0f, Motion.springy(), label = "fieldGlow")
    val labelColor by animateColorAsState(if (focused) c.accentText else c.textMid, Motion.medium(), label = "fieldLabel")
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = CpType.label, color = labelColor)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                // A soft orange glow grows around the well while you type in it.
                .drawBehind {
                    if (glow > 0f) drawRoundRect(c.accent.copy(alpha = 0.22f * glow), topLeft = androidx.compose.ui.geometry.Offset(-4.dp.toPx() * glow, -4.dp.toPx() * glow),
                        size = androidx.compose.ui.geometry.Size(size.width + 8.dp.toPx() * glow, size.height + 8.dp.toPx() * glow), cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()))
                }
                .clip(CpShapes.control)
                .background(c.field)
                // Set into the surface: a shade along the top inside edge.
                .drawBehind { drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = if (c.isDark) 0.3f else 0.06f), Color.Transparent), endY = 6.dp.toPx())) }
                .border(if (focused) 1.5.dp else 1.dp, ring, CpShapes.control)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            val style: TextStyle = CpType.body.copy(
                color = c.text,
                fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else CpType.body.fontFamily,
            )
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, style = style.copy(color = c.faint))
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(c.accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboard,
                    autoCorrectEnabled = false,
                ),
                interactionSource = focus,
                modifier = Modifier.fillMaxWidth().padding(end = if (trailing != null) 40.dp else 0.dp),
            )
            trailing?.let { Box(Modifier.align(Alignment.CenterEnd)) { it() } }
        }
    }
}

/** A service's colour, the same every time (its badge, its light). */
fun serviceColor(name: String): Color = when (name.lowercase().substringBefore(' ')) {
    "hardcover" -> Color(0xFF5B4BE0)
    "goodreads" -> Color(0xFF5A4632)
    "storygraph" -> Color(0xFF1F8A8A)
    "fable" -> Color(0xFF9BC53D)
    "pagebound" -> Color(0xFFE0703B)
    "seekquel" -> Color(0xFF9A3D2A)
    "librarything" -> Color(0xFF8B5E34)
    "readwise" -> Color(0xFFC9A227)
    "notion" -> Color(0xFF6E6E6B)
    "obsidian" -> Color(0xFF7C3AED)
    "bookfusion" -> Color(0xFF2E6FD8)
    "audiobookshelf" -> Color(0xFFB38A2E)
    "jellyfin" -> Color(0xFF8E5BE0)
    "bookorbit" -> Color(0xFF3A3A3A)
    "spotify" -> Color(0xFF1DB954)
    "readest" -> Color(0xFFD9822B)
    "micro.blog" -> Color(0xFF3B7DD8)
    "kindle" -> Color(0xFF1F7FA6)
    "libby" -> Color(0xFFB0285A)
    "audible" -> Color(0xFFE88A12)
    "hoopla" -> Color(0xFF1B8FD1)
    "everand" -> Color(0xFF1E7B85)
    "mihon" -> Color(0xFF3D6FD8)
    "anilist" -> Color(0xFF0B8FD9)
    "mangaupdates" -> Color(0xFFD9542B)
    else -> {
        val h = (name.hashCode() and 0x7fffffff) % 360
        Color.hsl(h.toFloat(), 0.45f, 0.45f)
    }
}

/**
 * A service's badge, printed like a stamp: a block of its own colour with lopsided corners and its
 * initial in black Bodoni.
 */
@Composable
fun ServiceTile(name: String, size: Dp = 36.dp, muted: Boolean = false) {
    val c = Cp.colors
    // A paused service's stamp is printed faded into the paper (solid, so nothing shows through).
    val col by animateColorAsState(if (muted) androidx.compose.ui.graphics.lerp(serviceColor(name), c.track, 0.7f) else serviceColor(name), Motion.medium(), label = "tile")
    val ink by animateColorAsState(if (muted) c.quiet else Color.White, Motion.medium(), label = "tileInk")
    val shape = serviceTileShape(size)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(col),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(1).uppercase(),
            fontFamily = io.github.prithirajn.readync.ui.theme.Display, fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
            fontSize = (size.value * 0.58f).sp, lineHeight = (size.value * 0.6f).sp, color = ink,
        )
    }
}

/**
 * A list printed on the page instead of boxed in a card: a firm rule above, fine rules between
 * its rows (the rows' own dividers) and a light rule to close it.
 */
@Composable
fun RisoList(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Cp.colors
    Column(modifier.fillMaxWidth().animateContentSize(Motion.medium())) {
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(c.text.copy(alpha = 0.55f)))
        content()
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.text.copy(alpha = 0.18f)))
    }
}

@Composable
fun Quiet(text: String, modifier: Modifier = Modifier, color: Color = Cp.colors.quiet, maxLines: Int = Int.MAX_VALUE) {
    Text(text, style = CpType.small, color = color, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
fun HSpace(w: Int) = Spacer(Modifier.width(w.dp))

/** A service tile's cut: so a ring drawn around one (stacked tiles) follows its corners. */
internal fun serviceTileShape(size: androidx.compose.ui.unit.Dp) =
    RoundedCornerShape(topStart = size * 0.36f, topEnd = size * 0.14f, bottomEnd = size * 0.36f, bottomStart = size * 0.14f)

/** Keeps a word with an apostrophe in it (Cookbook's, Anarchist’s) on one line: a word joiner after the apostrophe. */
fun String.wholeWords(): String = replace(Regex("([’'])(?=\\p{L})"), "$1\u2060")
