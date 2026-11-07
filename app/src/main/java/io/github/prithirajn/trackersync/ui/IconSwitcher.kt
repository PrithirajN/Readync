package io.github.prithirajn.trackersync.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.prithirajn.trackersync.util.IconTint

/**
 * Turns on the home-screen icon in the colour nearest the latest book's cover (or the usual
 * green), and the others off. Only when the colour actually changes, and only as the app goes to
 * the background, so the switch is never seen mid-use.
 */
object IconSwitcher {
    private const val PREFS = "theme"
    private const val FOLLOW = "iconFollowsBook"
    private const val CURRENT = "icon"

    private fun component(context: Context, key: String) = ComponentName(
        context.packageName,
        "io.github.prithirajn.trackersync." + if (key == IconTint.DEFAULT) "Launcher" else "Launcher" + key.replaceFirstChar { it.uppercase() },
    )

    /** Kept beside the cover colour so the switch needs nothing else loaded. */
    fun setFollow(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(FOLLOW, on).apply()
    }

    fun apply(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val want = if (prefs.getBoolean(FOLLOW, false)) IconTint.nearest(CoverAccent.last(context)) else IconTint.DEFAULT
        val now = prefs.getString(CURRENT, IconTint.DEFAULT) ?: IconTint.DEFAULT
        if (want == now) return
        val pm = context.packageManager
        runCatching {
            // The new one on first, so there's never a moment with no icon at all.
            pm.setComponentEnabledSetting(component(context, want), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            for (t in IconTint.all) {
                if (t.key == want) continue
                pm.setComponentEnabledSetting(component(context, t.key), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
            prefs.edit().putString(CURRENT, want).apply()
        }
    }
}
