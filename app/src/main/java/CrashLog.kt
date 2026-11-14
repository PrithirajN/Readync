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

    /** The report from the last time the app closed on its own, until it's dismissed. */
    fun pending(context: Context): String? = runCatching { File(context.filesDir, FILE).takeIf { it.isFile }?.readText() }.getOrNull()

    /** The error in a line ("NullPointerException in CatalogsScreen.kt"), for the log. */
    fun headline(report: String): String {
        val lines = report.lines()
        val error = lines.firstOrNull { it.contains("Exception") || it.contains("Error") }?.trim()?.take(160) ?: "an error"
        val where = lines.firstOrNull { it.trim().startsWith("at io.github.prithirajn") }?.trim()?.removePrefix("at ")
            ?.let { Regex("\\(([^)]+)\\)").find(it)?.groupValues?.get(1) }
        return error + (where?.let { " ($it)" } ?: "")
    }

    fun dismiss(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }
}
