package io.github.prithirajn.trackersync.source

import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.util.arr
import io.github.prithirajn.trackersync.util.obj
import io.github.prithirajn.trackersync.util.objects
import io.github.prithirajn.trackersync.util.str
import org.json.JSONObject

/**
 * Open Library's public API (no key): the fallback for a book description when
 * crosspoint-sync has none, and for a cover when the server can't find one.
 */
object OpenLibrary {
    var base = "https://openlibrary.org"

    private fun json(url: String): JSONObject? = runCatching {
        val r = Http.request("GET", url, mapOf("Accept" to "application/json"), timeoutMs = 15_000)
        if (r.ok) JSONObject(r.body) else null
    }.getOrNull()

    /**
     * The matching work: (work key like "/works/OL123W", cover id). The title is tried as given,
     * then without tags, subtitle or series; a result only counts when it is the same book by
     * title (and author), so another book's description is never shown.
     */
    private fun work(title: String, author: String?): Pair<String, Long?>? {
        val a = author?.let { BookInfo.cleanAuthor(it) }
        for (t in BookInfo.titleVariants(title)) {
            for (withAuthor in listOf(true, false)) {
                if (!withAuthor && a.isNullOrBlank()) continue
                val q = StringBuilder("$base/search.json?title=${Http.enc(t)}")
                if (withAuthor && !a.isNullOrBlank()) q.append("&author=").append(Http.enc(a))
                q.append("&limit=5&fields=key,title,author_name,cover_i")
                val docs = json(q.toString())?.arr("docs")?.objects() ?: continue
                val doc = docs.firstOrNull { d ->
                    val dt = d.str("title") ?: return@firstOrNull false
                    val da = d.arr("author_name")?.optString(0)
                    io.github.prithirajn.trackersync.sync.Matcher.sameBook(t, a, dt, da) ||
                        io.github.prithirajn.trackersync.sync.Matcher.score(t, a, io.github.prithirajn.trackersync.trackers.RemoteBook("", dt, da)) >= 0.75
                } ?: continue
                val key = doc.str("key") ?: continue
                val cover = if (doc.isNull("cover_i")) null else doc.optLong("cover_i").takeIf { it > 0 }
                return key to cover
            }
        }
        return null
    }

    /** The edition with this ISBN: title, author, cover and pages. For adding a physical book by barcode. */
    fun byIsbn(isbn: String): io.github.prithirajn.trackersync.trackers.RemoteBook? {
        val e = json("$base/isbn/$isbn.json") ?: return null
        val title = e.str("title") ?: return null
        val author = e.arr("authors")?.objects()?.firstOrNull()?.str("key")?.let { json("$base$it.json")?.str("name") }
        val cover = e.arr("covers")?.optLong(0)?.takeIf { it > 0 }?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" }
        val year = e.str("publish_date")?.let { Regex("(1[5-9]\\d\\d|20\\d\\d)").find(it)?.value?.toInt() }
        return io.github.prithirajn.trackersync.trackers.RemoteBook(
            id = isbn, title = title, author = author, coverUrl = cover,
            pages = e.opt("number_of_pages").let { (it as? Number)?.toInt() }, year = year,
        )
    }

    fun description(title: String, author: String?): String? {
        val (key, _) = work(title, author) ?: return null
        val w = json("$base$key.json") ?: return null
        val raw = w.obj("description")?.str("value") ?: w.str("description") ?: return null
        return clean(raw)
    }

    fun cover(title: String, author: String?): String? {
        val (_, id) = work(title, author) ?: return null
        return id?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" }
    }

    /** Drops the "----------" source/see-also tail and markdown links Open Library descriptions carry. */
    fun clean(raw: String): String? {
        var t = raw.replace("\r\n", "\n")
        val cut = Regex("\\n\\s*-{5,}").find(t)
        if (cut != null) t = t.substring(0, cut.range.first)
        t = Regex("\\[([^\\]]+)]\\([^)]*\\)").replace(t) { it.groupValues[1] }
        t = Regex("\\n{3,}").replace(t, "\n\n").trim()
        return t.takeIf { it.isNotEmpty() }
    }

    /**
     * An ISBN for the Kindle book [asin], from Open Library's record that lists that ASIN
     * (its id_amazon field), only when that record is the same book by title (and author).
     */
    fun isbnForAsin(asin: String, title: String?, author: String?): String? {
        if (!Regex("^[A-Z0-9]{10}$").matches(asin) || title.isNullOrBlank()) return null
        val q = "$base/search.json?q=" + Http.enc("id_amazon:$asin") + "&limit=3&fields=isbn,title,author_name"
        val docs = json(q)?.arr("docs")?.objects() ?: return null
        val doc = docs.firstOrNull { d ->
            val t = d.str("title") ?: return@firstOrNull false
            val a = d.arr("author_name")?.optString(0)
            io.github.prithirajn.trackersync.sync.Matcher.sameBook(title, author, t, a) ||
                io.github.prithirajn.trackersync.sync.Matcher.score(title, author, io.github.prithirajn.trackersync.trackers.RemoteBook("", t, a)) >= 0.8
        } ?: return null
        val isbns = doc.arr("isbn")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } } ?: return null
        return isbns.firstOrNull { it.length == 13 } ?: isbns.firstOrNull { it.length == 10 }
    }
}
