package io.github.prithirajn.trackersync.ui

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay

/** Each guarded sheet's guard, by its state (so the sheet can hand it its way to close). */
private val guards = java.util.WeakHashMap<Any, SheetGuard>()

/**
 * Every sheet's state: a sheet pulled down only a little springs back instead of closing. Pulled
 * past the line (about a third of the sheet, at most 150 dp) the phone gives a tick, and letting go
 * then closes it; pulled back above the line, it stays. Tapping outside and Back still close it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberSheetState(skipPartiallyExpanded: Boolean = true): SheetState {
    val view = LocalView.current
    val d = LocalDensity.current.density
    val guard = remember { SheetGuard(view, 150f * d, 64f * d, 3f * d) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded, confirmValueChange = { v -> guard.allow(v) })
    remember(state) { synchronized(guards) { guards[state] = guard } }
    LaunchedEffect(state) {
        snapshotFlow { Pair<Float?, SheetValue>(runCatching { state.requireOffset() }.getOrNull(), state.currentValue) }
            .collect { p -> guard.track(p.first, p.second) }
    }
    // A safety net: a sheet that has slid all the way down (or says it's hidden) but is still
    // there, holding the screen, is closed properly.
    LaunchedEffect(state) {
        var stuck = 0
        while (true) {
            delay(250)
            val off = runCatching { state.requireOffset() }.getOrNull() ?: continue
            val gone = state.currentValue == SheetValue.Hidden || off >= view.rootView.height - 4 * d
            if (guard.shown && gone) {
                if (++stuck >= 2) { stuck = 0; guard.dismiss?.invoke() }
            } else stuck = 0
        }
    }
    return state
}

/**
 * A sheet with the guard above: Material's sheet, with the same arguments the app uses. Its close
 * is handed to the guard so a sheet can never be left invisible over the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardedSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberSheetState(),
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    val latest = rememberUpdatedState(onDismissRequest)
    val guard = synchronized(guards) { guards[sheetState] }
    DisposableEffect(guard) {
        guard?.dismiss = { latest.value() }
        onDispose { guard?.dismiss = null }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        dragHandle = dragHandle,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
private class SheetGuard(private val view: View, private val most: Float, private val least: Float, private val still: Float) {
    /** Closes the sheet for real (set by [GuardedSheet]). */
    @Volatile var dismiss: (() -> Unit)? = null
    /** Whether the sheet has been up on the screen. */
    @Volatile var shown = false

    /** Where the sheet's top rests (on screen, px) at the place it last settled. */
    private var rest: Float? = null
    private var last: Float? = null
    private var settledAt: SheetValue? = null
    private var pulled = 0f
    private var armed = false
    /** When a finger last moved it (not an animation): a release right after is a drag's end. */
    private var movedAt = 0L
    /** Closing for another reason (a tap outside, Back): no tick on the way down, and no "no". */
    private var closing = false

    fun track(offset: Float?, current: SheetValue) {
        if (offset == null) return
        if (current != SheetValue.Hidden) shown = true
        if (current != settledAt) {
            settledAt = current; rest = offset; last = offset; pulled = 0f; armed = false; closing = false
            return
        }
        val moved = last != null && offset != last
        last = offset
        if (moved && current != SheetValue.Hidden) movedAt = SystemClock.uptimeMillis()
        // A sheet whose content grew sits higher: that's its new resting place.
        val r = rest?.let { minOf(it, offset) } ?: offset
        rest = r
        pulled = offset - r
        val line = line(r)
        if (!armed && !closing && current != SheetValue.Hidden && pulled >= line) {
            armed = true
            tick()
        } else if (armed && pulled < line * 0.85f) {
            armed = false
        }
    }

    /** How far down to pull before letting go closes it: a third of the sheet, between 64 and 150 dp. */
    private fun line(rest: Float): Float {
        val height = (view.rootView.height - rest).coerceAtLeast(1f)
        return (height / 3f).coerceIn(least, most)
    }

    fun allow(target: SheetValue): Boolean {
        if (target != SheetValue.Hidden) return true
        // Already on its way out: Material asks again when the sheet lands, and a "no" then would
        // leave an invisible sheet holding the screen.
        if (closing) return true
        // Only a finger's pull that has just been let go is ever turned down: a tap outside, Back,
        // or a sheet whose content changed size closes as asked.
        val dragEnd = SystemClock.uptimeMillis() - movedAt < 500 && pulled > still
        if (!dragEnd || armed) { closing = true; return true }
        // Let go above the line: it springs back.
        return false
    }

    private fun tick() {
        val kind = when {
            Build.VERSION.SDK_INT >= 34 -> HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            Build.VERSION.SDK_INT >= 30 -> HapticFeedbackConstants.CONFIRM
            else -> HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(kind)
    }
}
