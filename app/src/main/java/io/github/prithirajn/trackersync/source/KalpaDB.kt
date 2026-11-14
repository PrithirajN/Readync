package io.github.prithirajn.trackersync.source

import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.sync.Matcher
import io.github.prithirajn.trackersync.trackers.RemoteBook
import io.github.prithirajn.trackersync.util.arr
import io.github.prithirajn.trackersync.util.obj
import io.github.prithirajn.trackersync.util.objects
import io.github.prithirajn.trackersync.util.str
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * KalpaDB (kalpadb.com), the community catalogue of Indian speculative fiction: the first source
 * of details for books in Indian languages. Its site reads a public JSON API; a book is
 * /api/v1/works/{id} (the number in a kalpadb.com/works/… link), with its title in the original
 * script and romanised, authors, publisher, formats (ISBN, pages, date, cover), series and
 * description.
 */
object KalpaDB {
    var base = "https://kalpa-api.onrender.com/api/v1"

    /** The work id in a KalpaDB link ("https://kalpadb.com/works/3858") or a bare number. */
    fun idFrom(text: String): String? =
        Regex("kalpadb\\.com/works/(\\d+)").find(text)?.groupValues?.get(1) ?: text.trim().takeIf { it.matches(Regex("\\d{1,9}")) }

    /** A title written in an Indian script (Bengali, Devanagari, Tamil, Telugu, Kannada, Malayalam, Gujarati, Gurmukhi, Odia). */
    fun isIndic(text: String?): Boolean = text?.any { it.code in 0x0900..0x0D7F } == true

    // Hosted on a free tier that sleeps: the first request can take most of a minute.
    private fun get(url: String): Any? = runCatching {
        val r = Http.request("GET", url, mapOf("Accept" to "application/json"), timeoutMs = 60_000)
        if (r.ok) JSONTokener(r.body).nextValue() else null
    }.getOrNull()

    fun work(id: String): BookDetails? = (get("$base/works/${Http.enc(id)}") as? JSONObject)?.let { parse(it) }

    /**
     * Finds the work by title (or ISBN) and author. The API's search isn't documented, so the
     * likely addresses are tried and any list of works in the answer is used; each hit is
     * then read in full.
     */
    fun search(title: String, author: String?, isbn: String? = null): BookDetails? {
        val queries = listOfNotNull(isbn, title)
        for (q in queries) {
            for (path in listOf("/search?q=", "/works?q=", "/works?search=", "/search/works?q=")) {
                val hits = works(get(base + path + Http.enc(q)) ?: continue)
                if (hits.isEmpty()) continue
                val best = hits.maxByOrNull { h -> score(title, author, h) } ?: continue
                if (q != isbn && score(title, author, best) < 0.6) continue
                val id = best.opt("id")?.toString() ?: continue
                return work(id) ?: parse(best)
            }
        }
        return null
    }

    private fun score(title: String, author: String?, w: JSONObject): Double {
        val names = w.arr("authors")?.objects()?.mapNotNull { it.str("name") } ?: emptyList()
        val titles = listOfNotNull(w.str("title"), w.obj("localised")?.obj("title")?.let { t -> t.keys().asSequence().mapNotNull { t.str(it) }.firstOrNull() })
        return titles.maxOfOrNull { Matcher.score(title, author, RemoteBook("", it, names.firstOrNull())) } ?: 0.0
    }

    /** Every object in [v] that looks like a work (an id and a title), wherever the list sits. */
    private fun works(v: Any?, depth: Int = 0): List<JSONObject> {
        if (depth > 4) return emptyList()
        return when (v) {
            is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }.let { list ->
                if (list.any { it.has("title") && it.has("id") }) list.filter { it.has("title") } else list.flatMap { works(it, depth + 1) }
            }
            is JSONObject -> v.keys().asSequence().toList().flatMap { k -> (v.opt(k) as? JSONArray)?.let { works(it, depth + 1) } ?: emptyList() }
            else -> emptyList()
        }
    }

    fun parse(o: JSONObject): BookDetails? {
        val title = o.str("title") ?: return null
        val book = o.obj("book")
        val formats = book?.arr("formats")?.objects() ?: emptyList()
        val isbns = formats.mapNotNull { it.str("isbn")?.filter { c -> c.isDigit() || c == 'X' } }
        val series = book?.opt("series").let { s ->
            when (s) {
                is JSONObject -> s.str("name") ?: s.str("title")
                is String -> s
                else -> null
            }
        }
        val lang = o.str("language")
        return BookDetails(
            title = title,
            altTitle = o.obj("localised")?.obj("title")?.let { t -> t.keys().asSequence().mapNotNull { t.str(it) }.firstOrNull() }
                ?.split(" ")?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } },
            authors = o.arr("authors")?.objects()?.mapNotNull { it.str("credited_as") ?: it.str("name") } ?: emptyList(),
            isbn13 = isbns.firstOrNull { it.length == 13 },
            isbn10 = isbns.firstOrNull { it.length == 10 },
            pages = formats.firstNotNullOfOrNull { f -> f.optInt("page_count").takeIf { it > 0 } },
            publisher = book?.arr("publishers")?.objects()?.firstOrNull()?.str("name"),
            published = (formats.firstNotNullOfOrNull { it.str("publication_date") } ?: book?.opt("publication_year")?.takeIf { it is Number }?.toString()
                ?: o.str("publication_date"))?.let { BookInfo.normaliseDate(it) ?: it },
            language = lang?.let { BookInfo.languageName(it) ?: it },
            description = o.str("description")?.lines()?.joinToString("\n") { it.trim() }?.replace(Regex("\\n{3,}"), "\n\n")?.trim(),
            coverUrl = formats.firstNotNullOfOrNull { it.str("cover_image_url") } ?: o.arr("image_urls")?.optString(0)?.takeIf { it.startsWith("http") },
            format = formats.firstNotNullOfOrNull { it.str("format_type")?.let { t -> BookInfo.formatName(t) } },
            series = series,
            seriesIndex = book?.opt("series_position")?.takeIf { it is Number || (it is String && it.isNotBlank()) }?.toString()?.removeSuffix(".0"),
            source = "KalpaDB",
        )
    }
}
