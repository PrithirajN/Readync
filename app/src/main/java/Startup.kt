package io.github.prithirajn.readync

import android.os.Process
import android.os.SystemClock

/**
 * How long the app took to start, part by part (debug builds show it on the frame reading):
 * from the process starting to the first frame, with the slow steps on the way timed.
 */
object Startup {
    private val parts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Milliseconds since this process started. */
    fun sinceStart(): Long =
        if (android.os.Build.VERSION.SDK_INT >= 24) SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() else 0L

    /** Runs [block], noting how long it took as [name]. */
    inline fun <T> time(name: String, block: () -> T): T {
        val t0 = SystemClock.elapsedRealtime()
        try { return block() } finally { note(name, SystemClock.elapsedRealtime() - t0) }
    }

    fun note(name: String, ms: Long) { parts[name] = ms }

    /** When the first frame was drawn (ms since the process started), once. */
    @Volatile var firstFrame = 0L
        private set

    fun firstFrameDrawn() { if (firstFrame == 0L) firstFrame = sinceStart() }

    /** "start 1240ms (store 410 · app 600 · activity 150)": the slowest parts first. */
    fun summary(): String {
        if (firstFrame == 0L) return ""
        val top = parts.entries.sortedByDescending { it.value }.take(4).joinToString(" · ") { "${it.key} ${it.value}" }
        return "start ${firstFrame}ms" + if (top.isNotEmpty()) " ($top)" else ""
    }
}
