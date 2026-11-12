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

    /** The icon colour the app should wear now: the book's nearest, or the default green. */
    fun wanted(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getBoolean(FOLLOW, false)) IconTint.nearest(CoverAccent.last(context)) else IconTint.DEFAULT
    }

    /** The launcher icon picture for [key], for the recent-apps card. */
    fun iconRes(key: String): Int = when (key) {
        "teal" -> io.github.prithirajn.trackersync.R.mipmap.ic_launcher_teal
        "blue" -> io.github.prithirajn.trackersync.R.mipmap.ic_launcher_blue
        "indigo" -> io.github.prithirajn.trackersync.R.mipmap.ic_launcher_indigo
        "purple" -> io.github.prithirajn.trackersync.R.mipmap.ic_launcher_purple
        "rose" -> io.github.prithirajn.trackersync.R.mipmap.ic_launcher_rose
        "red" -> io.github.prithirajn.trackersync.R.mipmap.ic_launcher_red
        "orange" -> io.github.prithirajn.trackersync.R.mipmap.ic_launcher_orange
        "amber" -> io.github.prithirajn.trackersync.R.mipmap.ic_launcher_amber
        "slate" -> io.github.prithirajn.trackersync.R.mipmap.ic_launcher_slate
        else -> io.github.prithirajn.trackersync.R.mipmap.ic_launcher_green
    }

    /**
     * The recent-apps card shows the icon of whichever launcher entry opened the app, which can be
     * an older colour after a switch (the switch itself waits until you leave the app). This sets
     * the card to the icon the app wears now.
     */
    fun applyToTask(activity: android.app.Activity) {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        val key = wanted(activity)
        val color = IconTint.all.firstOrNull { it.key == key }?.argb ?: 0
        runCatching {
            val label = activity.getString(io.github.prithirajn.trackersync.R.string.app_name)
            val td = if (android.os.Build.VERSION.SDK_INT >= 33) {
                android.app.ActivityManager.TaskDescription.Builder().setLabel(label).setIcon(iconRes(key)).setPrimaryColor(color).build()
            } else {
                @Suppress("DEPRECATION")
                android.app.ActivityManager.TaskDescription(label, iconRes(key), color)
            }
            activity.setTaskDescription(td)
        }
    }

    fun apply(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val want = wanted(context)
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
