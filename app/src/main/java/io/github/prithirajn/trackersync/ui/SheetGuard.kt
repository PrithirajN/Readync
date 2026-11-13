package io.github.prithirajn.trackersync.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView

/**
 * Every sheet's state: a sheet pulled down only a little springs back instead of closing. Pulled
 * past the line (about a third of the sheet, at most 150 dp) the phone gives a tick, and letting go
 * then closes it; pulled back above the line, it stays. Tapping outside and Back still close it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberSheetState(skipPartiallyExpanded: Boolean = true): SheetState {
    val view = LocalView.current
    val density = LocalDensity.current
    val guard = remember { SheetGuard(view, with(density) { 150f * this.density }, with(density) { 64f * this.density }, with(density) { 3f * this.density }) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded, confirmValueChange = { v -> guard.allow(v) })
    LaunchedEffect(state) {
        snapshotFlow { (runCatching { state.requireOffset() }.getOrNull()) to state.currentValue }
            .collect { (offset, current) -> guard.track(offset, current) }
    }
    return state
}

@OptIn(ExperimentalMaterial3Api::class)
private class SheetGuard(private val view: View, private val most: Float, private val least: Float, private val still: Float) {
    /** Where the sheet's top rests (on screen, px) at the place it last settled. */
    private var rest: Float? = null
    private var settledAt: SheetValue? = null
    private var pulled = 0f
    private var armed = false
    /** Closing for another reason (a tap outside, Back): no tick on the way down. */
    private var closing = false

    fun track(offset: Float?, current: SheetValue) {
        if (offset == null) return
        if (current != settledAt) { settledAt = current; rest = offset; pulled = 0f; armed = false; closing = false; return }
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
        // Not being pulled at all: a tap outside or Back.
        if (pulled <= still) { closing = true; return true }
        // Pulled past the line (the tick said so): close. A little pull: spring back.
        if (armed) { closing = true; return true }
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
