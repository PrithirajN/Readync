package io.github.prithirajn.readync

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * When the app closes on its own (an uncaught error), what went wrong is written to a file in its
 * own storage before Android closes it. Next time the app opens it offers the report, to share
 * so the cause can be fixed. Nothing leaves the phone unless you share it.
 */
object CrashLog {

    private const val FILE = "last-crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            // Then Android's own handling (which closes the app), as before.
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val version = runCatching {
            val p = context.packageManager.getPackageInfo(context.packageName, 0)
            "${p.versionName} (${if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()})"
        }.getOrDefault("?")
        val text = buildString {
            appendLine("Readync $version · ${Edition.name}")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("When: ${java.time.ZonedDateTime.now()}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(trace.take(12_000))
        }
        File(context.filesDir, FILE).writeText(text)
    }

    private const val EXIT_FILE = "last-exit.txt"

    /**
     * Closes the error handler above never sees, from Android's own record of how the app's process
     * ended (Android 11 on): a crash in native code (video encoding, the camera, text recognition),
     * "not responding", or Android ending it for memory or battery. Each one since last time is
     * written to the sync log through [log] (with when, and whether the app was open or in the
     * background); a native crash's or ANR's details are kept as the report offered on opening.
     * The first time, the closes Android still remembers (several days' worth) come in too.
     */
    fun recordExits(context: Context, log: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) return
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return
        val prefs = context.getSharedPreferences("crashlog", Context.MODE_PRIVATE)
        val seen = prefs.getLong("exitSeen", 0L)
        val exits = runCatching { am.getHistoricalProcessExitReasons(null, 0, 32) }.getOrNull().orEmpty()
            .filter { it.timestamp > seen }.sortedBy { it.timestamp }
        if (exits.isEmpty()) return
        prefs.edit().putLong("exitSeen", exits.maxOf { it.timestamp }).apply()
        val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm")
        for (e in exits) {
            val why = when (e.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "a crash in native code"
                android.app.ApplicationExitInfo.REASON_ANR -> "it stopped responding (ANR)"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Android needed the memory"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Android said it used too much battery or CPU"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "it was killed (signal ${e.status})"
                android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "it failed to start"
                // A Java crash is already reported by the handler above; the rest are normal ends.
                else -> null
            } ?: continue
            val whenText = java.time.Instant.ofEpochMilli(e.timestamp).atZone(java.time.ZoneId.systemDefault()).format(fmt)
            val where = if (e.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) "while open" else "in the background"
            val mem = if (e.rss > 0) " · memory ${e.rss / 1024} MB" else ""
            log("Readync closed unexpectedly ($whenText, $where): $why" + (e.description?.takeIf { it.isNotBlank() }?.let { " · ${it.take(140)}" } ?: "") + mem)
            if (e.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE || e.reason == android.app.ApplicationExitInfo.REASON_ANR) runCatching {
                val trace = e.traceInputStream?.use { s ->
                    val buf = java.io.ByteArrayOutputStream(); val chunk = ByteArray(16_384)
                    while (buf.size() < 200_000) { val n = s.read(chunk); if (n < 0) break; buf.write(chunk, 0, n) }
                    String(buf.toByteArray(), Charsets.ISO_8859_1)
                }.orEmpty()
                // An ANR's trace is text; a native crash's (a tombstone) is mostly binary, so its readable lines only.
                val readable = if (e.reason == android.app.ApplicationExitInfo.REASON_ANR) trace.take(14_000)
                    else Regex("[\\x20-\\x7E]{6,}").findAll(trace).map { it.value }.filter { "/" in it || "(" in it || "#" in it }.take(160).joinToString("\n")
                val text = buildString {
                    appendLine("Readync closed: $why ($whenText, $where)")
                    appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                    e.description?.let { appendLine("Android says: $it") }
                    if (e.rss > 0) appendLine("Memory: ${e.rss / 1024} MB (PSS ${e.pss / 1024} MB)")
                    appendLine()
                    append(readable)
                }
                File(context.filesDir, EXIT_FILE).writeText(text)
            }
        }
    }

    /** The report from the last time the app closed on its own, until it's dismissed. */
    fun pending(context: Context): String? = runCatching {
        File(context.filesDir, FILE).takeIf { it.isFile }?.readText() ?: File(context.filesDir, EXIT_FILE).takeIf { it.isFile }?.readText()
    }.getOrNull()

    /** The error in a line ("NullPointerException in CatalogsScreen.kt"), for the log. */
    fun headline(report: String): String {
        val lines = report.lines()
        if (lines.firstOrNull()?.startsWith("Readync closed:") == true) return lines.first().removePrefix("Readync closed: ")
        val error = lines.firstOrNull { it.contains("Exception") || it.contains("Error") }?.trim()?.take(160) ?: "an error"
        val where = lines.firstOrNull { it.trim().startsWith("at io.github.prithirajn") }?.trim()?.removePrefix("at ")
            ?.let { Regex("\\(([^)]+)\\)").find(it)?.groupValues?.get(1) }
        return error + (where?.let { " ($it)" } ?: "")
    }

    fun dismiss(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
        runCatching { File(context.filesDir, EXIT_FILE).delete() }
    }
}
