package io.github.prithirajn.trackersync.source

import android.content.Context
import io.github.prithirajn.trackersync.net.Http
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A public list of ad and tracker addresses (StevenBlack's hosts, about 80,000 of them, gathered
 * from the lists ad blockers use), for the article's Web page view: any request to one of them is
 * answered with nothing. Fetched once and kept in the app's storage, then refreshed weekly; the
 * app's own short list still applies before it arrives.
 */
object AdBlockList {

    private const val URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val FILE = "ad-hosts.txt"
    private const val WEEK_MS = 7L * 24 * 3600 * 1000

    @Volatile private var hosts: Set<String> = emptySet()
    private val loading = AtomicBoolean(false)

    /** Loads the kept list (and fetches a fresh one when it's missing or a week old), off the main thread. */
    fun warm(context: Context) {
        if (!loading.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread {
            try {
                val f = File(app.filesDir, FILE)
                if (hosts.isEmpty() && f.isFile) hosts = read(f.readText())
                if (!f.isFile || System.currentTimeMillis() - f.lastModified() > WEEK_MS) {
                    val r = runCatching { Http.request("GET", URL, timeoutMs = 30_000) }.getOrNull()
                    if (r != null && r.ok && r.body.length > 100_000) {
                        val list = read(r.body)
                        if (list.size > 10_000) {
                            // Kept as plain names, one a line (smaller than the hosts file).
                            f.writeText(list.joinToString("\n"))
                            hosts = list
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                loading.set(false)
            }
        }.apply { isDaemon = true; name = "ad-hosts" }.start()
    }

    /** "0.0.0.0 ads.example.com" lines (or bare names), as a set of names. */
    private fun read(text: String): Set<String> {
        val out = HashSet<String>(100_000)
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val name = if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) line.substringAfter(' ').trim() else line
            if (name.isEmpty() || name == "localhost" || name.startsWith("0.0.0.0") || ' ' in name) continue
            out += name.lowercase()
        }
        return out
    }

    /** Whether [host] (or a domain it's under: ads.x.com under x.com) is on the list. */
    fun blocks(host: String): Boolean {
        val set = hosts
        if (set.isEmpty()) return false
        var h = host.lowercase().trimEnd('.')
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false // stop at the registrable domain
            h = h.substring(dot + 1)
        }
    }

    val size: Int get() = hosts.size
}
