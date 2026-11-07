package io.github.prithirajn.trackersync.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType

// Compose versions of the companion app's ui.jsx / Settings.jsx building blocks.

/** rounded-xl bg-surface ring-1 ring-stone-950/5 */
@Composable
fun CpCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CpShapes.card)
            // Very slightly see-through, so the moving background reads behind the cards.
            .background(Cp.colors.surface.copy(alpha = if (Cp.colors.isDark) 0.9f else 0.93f))
            .border(1.dp, Cp.colors.hairline, CpShapes.card)
            // Rows appearing or disappearing inside a card resize it smoothly.
            .animateContentSize(Motion.medium()),
        content = content,
    )
}

/** divide-y divide-stone-100 between card rows */
@Composable
fun RowDivider() = HorizontalDivider(thickness = 1.dp, color = Cp.colors.divider)

/** Handwritten margin-note eyebrow: Caveat, brand-600, rotated -1 degree. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text, style = CpType.eyebrow, color = Cp.colors.accentText, modifier = modifier.rotate(-1f))
}

@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = CpType.pageTitle, color = Cp.colors.text, modifier = modifier)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = CpType.section, color = Cp.colors.text, modifier = modifier.padding(top = 32.dp))
}

@Composable
fun LucideIcon(res: Int, modifier: Modifier = Modifier.size(16.dp), tint: Color = Cp.colors.quiet) {
    Icon(painterResource(res), contentDescription = null, modifier = modifier, tint = tint)
}

/**
 * h-1.5 rounded-full bg-stone-200 track, brand-500 fill. Glides to new values; with [fillIn]
 * it also fills from empty when it first appears.
 */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, fillIn: Boolean = false) {
    val target = fraction.coerceIn(0f, 1f)
    val shown = remember { Animatable(if (fillIn) 0f else target) }
    LaunchedEffect(target) { shown.animateTo(target, Motion.fill()) }
    val fill = Cp.colors.accent
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CpShapes.pill)
            .background(Cp.colors.track)
            // Drawn, not laid out, so a filling bar only repaints each frame.
            .drawBehind {
                val w = size.width * shown.value.coerceIn(0f, 1f)
                if (w > 0f) {
                    drawRoundRect(
                        color = fill,
                        size = androidx.compose.ui.geometry.Size(w, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                    )
                }
            }
    )
}

/** The companion app's switch: 48 x 28, brand-500 when on, stone-300 when off. */
@Composable
fun CpToggle(checked: Boolean, onChange: (Boolean) -> Unit, label: String, enabled: Boolean = true) {
    val knob by animateDpAsState(if (checked) 22.dp else 2.dp, Motion.bouncy(), label = "toggle")
    val track by animateColorAsState(
        when {
            !enabled -> Cp.colors.toggleOff.copy(alpha = 0.5f)
            checked -> Cp.colors.accent
            else -> Cp.colors.toggleOff
        },
        Motion.medium(),
        label = "track",
    )
    Box(
        Modifier
            .size(width = 48.dp, height = 28.dp)
            .bouncyClickable(CpShapes.pill, enabled = enabled, pressedScale = 0.92f, role = Role.Switch, onClickLabel = label) { onChange(!checked) }
            .background(track)
    ) {
        Box(
            Modifier
                .offset(x = knob, y = 2.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Cp.colors.surface)
        )
    }
}

/** Large touch control: h-12 rounded-2xl. Filled = brand button, else a surface button with a ring. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    danger: Boolean = false,
    busy: Boolean = false,
    enabled: Boolean = true,
) {
    val c = Cp.colors
    val bg = if (filled) c.accent else c.surface
    val fg = when {
        filled -> Color.White
        danger -> c.error
        else -> c.text
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .bouncyClickable(CpShapes.control, enabled = enabled && !busy, onClick = onClick)
            .background(if (enabled) bg else bg.copy(alpha = 0.5f))
            .then(if (filled) Modifier else Modifier.border(1.dp, c.hairline, CpShapes.control)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crossfade(busy, animationSpec = Motion.fast(), label = "busy") { working ->
            if (working) {
                CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
            } else {
                Text(text, style = CpType.bodyStrong, color = fg)
            }
        }
    }
}

/** Outlined pill: "Link" in brand-600 with a brand-200 ring. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false, outlined: Boolean = true) {
    val c = Cp.colors
    Box(
        modifier
            .height(36.dp)
            .bouncyClickable(CpShapes.pill, pressedScale = 0.94f, onClick = onClick)
            // Outlined: a ring. Not outlined: the main action of its group, on a tinted fill (it used to
            // be bare text, which read as less important than the outlined ones beside it).
            .then(
                when {
                    danger -> Modifier
                    outlined -> Modifier.border(1.dp, c.accentRing, CpShapes.pill)
                    else -> Modifier.background(c.accentTint, CpShapes.pill)
                }
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = if (danger) CpType.label else CpType.bodyStrong,
            color = if (danger) c.error else c.accentText,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** Round 36dp icon button (matches, sync now). */
@Composable
fun IconCircleButton(res: Int, label: String, onClick: () -> Unit, busy: Boolean = false, tint: Color = Cp.colors.quiet) {
    Box(
        Modifier
            .size(36.dp)
            .bouncyClickable(CircleShape, enabled = !busy, pressedScale = 0.88f, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The sync ribbon moves its arrows while busy; any other icon turns.
        if (res == io.github.prithirajn.trackersync.R.drawable.ic_ribbon_sync) SyncRibbon(18.dp, tint, moving = busy)
        else if (busy) SpinningIcon(res, 16.dp, tint)
        else LucideIcon(res, Modifier.size(16.dp), tint)
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
    Box {
        Row(
            Modifier
                .height(36.dp)
                .clip(CpShapes.field)
                .background(c.page)
                .border(1.dp, c.hairline, CpShapes.field)
                .clickable(enabled = enabled) { open = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label(value), style = CpType.body, color = if (enabled) c.text else c.faint)
            LucideIcon(io.github.prithirajn.trackersync.R.drawable.lucide_chevron_left, Modifier.size(14.dp).rotate(-90f), c.quiet)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.surface) {
            for (o in options) {
                DropdownMenuItem(
                    text = { Text(label(o), style = CpType.body, color = c.text) },
                    onClick = { open = false; onChange(o) },
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
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = CpType.label, color = c.textMid)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(CpShapes.control)
                .background(c.surface)
                .border(1.dp, c.hairline, CpShapes.control)
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
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Initial-letter tile standing in for a service logo (size-9 rounded-lg). */
@Composable
fun ServiceTile(name: String, size: Dp = 36.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CpShapes.field)
            .background(Cp.colors.accentTint),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1), style = CpType.section.copy(fontSize = CpType.section.fontSize * 0.9f), color = Cp.colors.accentText)
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
