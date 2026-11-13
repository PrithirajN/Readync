package io.github.prithirajn.trackersync.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.max
import androidx.compose.ui.window.DialogProperties

/**
 * Full-screen pages (the studios, Wrapped, recaps, reviews…) as dialogs that reach every edge,
 * with their content kept clear of the status bar, the camera cut-out and the navigation bar.
 *
 * A dialog's own window doesn't always hear about the system bars (on Android 15, edge to edge, it
 * can be drawn under the navigation bar while being told the bar isn't there), so the app's own
 * insets, measured where they're always right, are handed down and the larger of the two is used.
 */
internal val LocalAppInsets = compositionLocalOf { PaddingValues() }

/** At the root of the app: the system bars as the activity sees them, for full-screen dialogs. */
@Composable
fun ProvideAppInsets(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppInsets provides WindowInsets.safeDrawing.asPaddingValues(), content = content)
}

/** For a full-screen page: the whole window, drawn under the bars (kept clear with [fullScreenSafe]). */
internal val FullScreen = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)

/** Padding that keeps a full-screen page's content clear of the bars and cut-outs. */
internal fun Modifier.fullScreenSafe(): Modifier = composed {
    // Some phones size a full-screen dialog's window to the whole display but place it under the
    // status bar, so its bottom runs off the screen (buttons cut in half). The window is made to
    // fill the screen from its very top, drawn under both bars, so the insets below line up.
    val view = androidx.compose.ui.platform.LocalView.current
    // Once per page (doing it on every redraw kept relaying the window out).
    androidx.compose.runtime.LaunchedEffect(view) { fillDialogWindow(view) }
    val own = WindowInsets.safeDrawing.asPaddingValues()
    val app = LocalAppInsets.current
    val dir = LocalLayoutDirection.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Whatever of the page still lies past the screen's edges, measured where it really is on the
    // screen: some phones keep the dialog's window the display's full height but start it below the
    // status bar (or ignore the resize above), so its bottom runs off the screen whatever the
    // insets say. That much more room is kept at the bottom (and top), so nothing is cut off.
    var offBottom by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    var offTop by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    // Where the window's top is on the screen: started below the status bar already, it needs no room for it.
    var winTop by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    this
        .then(androidx.compose.ui.Modifier.onGloballyPositioned {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val screen = screenHeightPx(view.context)
            if (screen > 0) {
                val below = (loc[1] + view.height - screen).coerceAtLeast(0)
                val above = (-loc[1]).coerceAtLeast(0)
                if (below != offBottom) offBottom = below
                if (above != offTop) offTop = above
                val top = loc[1].coerceAtLeast(0)
                if (top != winTop) winTop = top
            }
        })
        .padding(
            start = max(own.calculateStartPadding(dir), app.calculateStartPadding(dir)),
            top = max(max(own.calculateTopPadding(), app.calculateTopPadding()) - with(density) { winTop.toDp() }, androidx.compose.ui.unit.Dp(0f)) + with(density) { offTop.toDp() },
            end = max(own.calculateEndPadding(dir), app.calculateEndPadding(dir)),
            bottom = max(own.calculateBottomPadding(), app.calculateBottomPadding()) + with(density) { offBottom.toDp() },
        )
}

/** The whole screen's height in pixels (bars included), or 0 when it can't be told. */
private fun screenHeightPx(context: android.content.Context): Int = runCatching {
    val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
    if (android.os.Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.height()
    else android.util.DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }.heightPixels
}.getOrDefault(0)

/** The dialog's window made to cover the whole screen from its top edge, drawn under the bars. */
private fun fillDialogWindow(view: android.view.View) {
    var v: android.view.ViewParent? = view as? android.view.ViewParent ?: view.parent
    var window: android.view.Window? = (view as? androidx.compose.ui.window.DialogWindowProvider)?.window
    while (window == null && v != null) {
        window = (v as? androidx.compose.ui.window.DialogWindowProvider)?.window
        v = v.parent
    }
    val w = window ?: return
    runCatching {
        w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        w.setGravity(android.view.Gravity.TOP or android.view.Gravity.START)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val a = w.attributes
            a.layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 30)
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            w.attributes = a
        }
        @Suppress("DEPRECATION")
        run { w.statusBarColor = android.graphics.Color.TRANSPARENT; w.navigationBarColor = android.graphics.Color.TRANSPARENT }
    }
}
