package io.github.prithirajn.readync

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window

/**
 * Watches how long each frame takes to draw and every two seconds writes a
 * line to the log (tag "FrameWatch"): frames a second, the share that missed the screen's slot,
 * how many took the app over 16.7 ms of its own work (too slow for 60 fps), and average times.
 * Nothing is kept or sent anywhere.
 */
object FrameWatch {
    private const val BUDGET_NS = 16_666_667L
    private var thread: HandlerThread? = null
    /** The latest reading, shown small at the top of the screen in debug builds. */
    val line = androidx.compose.runtime.mutableStateOf("")
    /** Whether the reading shows (debug builds; a tap on the Activity tab's title shows or hides it). */
    val showBadge = androidx.compose.runtime.mutableStateOf(false)
    /** Tapping the reading bumps this: the tab on screen then scrolls itself down and back, smoothly, to be measured. */
    val bench = androidx.compose.runtime.mutableIntStateOf(0)
    private val main = Handler(android.os.Looper.getMainLooper())
    /** The slowest part this window (debug sections timed by DebugTimed): name and ms. */
    @Volatile private var slowName = ""
    @Volatile private var slowMs = 0.0

    /** A timed part of the screen took [ms] to make or lay out: kept if it's the slowest this window. */
    fun slow(name: String, ms: Double) {
        if (ms > slowMs) { slowMs = ms; slowName = name }
    }

    fun start(activity: Activity) {
        // Watched in every build (cheap: a callback per frame, on its own thread); written to the log
        // only in debug builds, and shown only when asked for (three taps on the Activity title).
        if (Build.VERSION.SDK_INT < 26) return
        val t = thread ?: HandlerThread("FrameWatch").also { it.start(); thread = it }
        val handler = Handler(t.looper)
        var frames = 0
        var janky = 0
        var over60 = 0
        var worst = 0L
        var ui = 0L
        var gpu = 0L
        var since = System.nanoTime()
        val listener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            if (m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
            val total = m.getMetric(FrameMetrics.TOTAL_DURATION)
            // The app's own work for the frame: the main thread, then the render thread's commands.
            val work = m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) + m.getMetric(FrameMetrics.ANIMATION_DURATION) +
                m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) + m.getMetric(FrameMetrics.DRAW_DURATION) +
                m.getMetric(FrameMetrics.SYNC_DURATION) + m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
            frames++
            ui += work
            if (Build.VERSION.SDK_INT >= 31) {
                gpu += m.getMetric(FrameMetrics.GPU_DURATION)
                // Missed its slot on this screen (the phone's own refresh rate).
                if (total > m.getMetric(FrameMetrics.DEADLINE)) janky++
            }
            // Couldn't have made 60 fps: the app's own work took longer than a 60 fps frame.
            if (work > BUDGET_NS) over60++
            if (work > worst) worst = work
            val now = System.nanoTime()
            if (now - since > 2_000_000_000L && frames > 0) {
                val secs = (now - since) / 1e9
                val slowest = if (slowMs >= 2.0) " · $slowName ${"%.0f".format(slowMs)}ms" else ""
                slowMs = 0.0; slowName = ""
                val shown = "fps ${"%.0f".format(frames / secs)} · >16ms ${over60 * 100 / frames}% · work ${"%.1f".format(ui / frames / 1e6)}ms · worst ${worst / 1_000_000}ms$slowest"
                val start = Startup.summary()
                main.post { line.value = if (start.isEmpty()) shown else "$shown\n$start" }
                if (BuildConfig.DEBUG) Log.i(
                    "FrameWatch",
                    "fps=${"%.0f".format(frames / secs)} missed=${janky * 100 / frames}% over16ms=$over60 (${over60 * 100 / frames}%) " +
                        "work=${"%.1f".format(ui / frames / 1e6)}ms gpu=${"%.1f".format(gpu / frames / 1e6)}ms worst=${worst / 1_000_000}ms",
                )
                frames = 0; janky = 0; over60 = 0; worst = 0; ui = 0; gpu = 0; since = now
            }
        }
        activity.window.addOnFrameMetricsAvailableListener(listener, handler)
    }
}
