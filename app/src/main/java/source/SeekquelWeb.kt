package io.github.prithirajn.readync.source

import io.github.prithirajn.readync.data.BookFacts
import io.github.prithirajn.readync.net.Http
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Seekquel's website (seekquel.app), signed in the way you sign in in a browser: a book's own
 * page there, with the moods, tropes, pace and content warnings readers gave it. Read like
 * Goodreads and StoryGraph are, through the app's unseen browser (it keeps the website's sign-in).
 * The page's data is found wherever it sits in it (the page's own JSON), by its names.
 */
object SeekquelWeb {
    var site = "https://seekquel.app"

    /** Opens a page in the app's unseen browser and hands back its HTML (set by the app). */
    @Volatile var browser: ((String) -> String?)? = null

    fun bookUrl(workId: String) = "$site/works/${Http.enc(workId)}"

    /** Why the last [facts] found nothing, for the sync log. */
    @Volatile var why: String? = null

    /** The book's tags from its page on the website, or null (with [why] saying what happened). */
    fun facts(workId: String, now: Long = System.currentTimeMillis() / 1000): BookFacts? {
        val url = bookUrl(workId)
        // The plain page first (it may carry its data already); the browser when it needs one.
        val plain = runCatching {
            Http.request("GET", url, mapOf("Accept" to "text/html", "User-Agent" to Http.defaultUserAgent), timeoutMs = 12_000)
        }.getOrNull()?.takeIf { it.ok }?.body
        plain?.let { parse(it, now) }?.takeIf { !it.isEmpty }?.let { why = null; return it }
        val b = browser ?: run { why = "the website's page had no tags without a browser"; return null }
        val html = runCatching { b(url) }.getOrNull() ?: run { why = "the website's page ($url) didn't load"; return null }
        val f = parse(html, now)
        if (!f.isEmpty) { why = null; return f }
        val title = Regex("<title>([^<]*)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim()
        why = "the website's page ($url, “${title ?: "no title"}”) showed no tags" +
            (if (html.contains("auth/login", true) || title?.contains("log in", true) == true) "; it asked to sign in" else "")
        return null
    }

    private val KEYS = listOf("moods", "mood", "tropes", "themes", "pace", "pacing", "content_warnings", "contentWarnings", "warnings", "genres")

    /** Reads the tags from a page: from its own data (JSON in the page), wherever it's kept. */
    fun parse(html: String, now: Long): BookFacts {
        // Data streamed into the page is often escaped ("\"moods\":[…]"): unescaped once to read it.
        val text = html.replace("\\\"", "\"").replace("\\u0026", "&").replace("\\/", "/")
        val found = HashMap<String, Any?>()
        for (k in KEYS) {
            if (k in found) continue
            for (m in Regex("\"" + Regex.escape(k) + "\"\\s*:\\s*").findAll(text)) {
                val v = runCatching { JSONTokener(text.substring(m.range.last + 1)).nextValue() }.getOrNull() ?: continue
                if (v is JSONArray && v.length() > 0 || v is String && v.isNotBlank() || v is JSONObject && v.length() > 0) { found[k] = v; break }
            }
        }
        fun label(v: Any?): String? {
            val raw = when (v) {
                is String -> v
                is JSONObject -> listOf("name", "label", "title", "slug").firstNotNullOfOrNull { k -> v.optString(k).takeIf { it.isNotBlank() && it != "null" } }
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() && it.length < 60 } ?: return null
            val spaced = if (raw.contains(' ')) raw else raw.replace('_', ' ').replace('-', ' ')
            return spaced.replaceFirstChar { it.uppercase() }
        }
        fun labels(v: Any?, max: Int): List<String> = when (v) {
            is JSONArray -> (0 until v.length()).mapNotNull { label(v.opt(it)) }
            is JSONObject -> v.keys().asSequence().toList().sortedByDescending { v.optDouble(it, 0.0) }.mapNotNull { label(it) } // {"dark": 0.6, …}
            is String -> v.split(',').mapNotNull { label(it) }
            else -> emptyList()
        }.distinctBy { it.lowercase() }.take(max)
        val pace = (found["pace"] ?: found["pacing"])?.let { p -> (if (p is JSONArray) p.opt(0) else p)?.let { label(it) } }?.let { p ->
            val l = p.lowercase()
            when {
                l.endsWith("paced") -> p.replace(" paced", "-paced")
                l in setOf("fast", "medium", "slow") -> "$p-paced"
                else -> p
            }
        }
        return BookFacts(
            pace = pace,
            moods = labels(found["moods"] ?: found["mood"], 6),
            genres = labels(found["genres"], 6),
            tropes = labels(found["tropes"] ?: found["themes"], 8),
            warnings = labels(found["content_warnings"] ?: found["contentWarnings"] ?: found["warnings"], 16),
            sources = listOf("Seekquel"),
            at = now,
        )
    }
}
