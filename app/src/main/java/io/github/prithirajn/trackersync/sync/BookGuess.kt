package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.net.Http
import org.json.JSONObject

/**
 * Which book some highlights are from, guessed by searching Google Books for their exact words
 * (it searches inside books, not just their titles). A few short phrases from different highlights
 * are looked up; the books that turn up for more of them come first. Only a suggestion: you pick
 * the right one, or type the name yourself.
 */
object BookGuess {
    var googleBooks = "https://www.googleapis.com/books/v1"

    data class Candidate(
        val title: String,
        val author: String?,
        /** How many of the phrases searched were found in it. */
        val hits: Int,
        /** How many phrases were searched. */
        val asked: Int,
        val year: String? = null,
        val cover: String? = null,
        /** Where it was found, when looked up by name ("Hardcover", "Royal Road", "Google Books"). */
        val source: String? = null,
    )

    /**
     * A distinctive run of about [words] words from [text]: from its middle (a highlight's first
     * and last words are where it's most often cut differently), without quote marks.
     */
    internal fun phrase(text: String, words: Int = 8): String? {
        val w = text.replace(Regex("[\"“”«»]"), " ").replace('’', '\'').replace('‘', '\'').split(Regex("\\s+"))
            .map { it.trim(',', ';', ':', '.', '!', '?', '(', ')', '—', '–', '-', '…') }.filter { it.isNotEmpty() }
        if (w.size < 5) return null
        val n = minOf(words, w.size)
        val start = ((w.size - n) / 2).coerceAtLeast(0)
        return w.subList(start, start + n).joinToString(" ")
    }

    private fun key(title: String, author: String?) =
        Matcher.baseTitle(title) + "|" + (author?.lowercase()?.filter { it.isLetter() }?.take(12) ?: "")

    /**
     * Books with a name like [query], to pick the right title and author when naming a book by hand:
     * from Hardcover (when [hardcover] is a session; it has most self-published and LitRPG series),
     * Royal Road (web novels) and Google Books, a few from each. Blocking; network.
     */
    fun byName(query: String, hardcover: io.github.prithirajn.trackersync.trackers.Session?): List<Candidate> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val out = ArrayList<Candidate>()
        hardcover?.let { hc ->
            runCatching { io.github.prithirajn.trackersync.trackers.HardcoverTracker.search(hc, q, null) }.getOrNull().orEmpty().take(4)
                .forEach { out += Candidate(it.title, it.author, 1, 1, cover = it.coverUrl, source = "Hardcover") }
        }
        runCatching { io.github.prithirajn.trackersync.source.WebNovels.searchRoyalRoad(q, null) }.getOrNull().orEmpty().take(3)
            .forEach { (t, a, _) -> out += Candidate(io.github.prithirajn.trackersync.sync.Matcher.plainTitle(t).ifBlank { t }, a, 1, 1, source = "Royal Road") }
        runCatching {
            val r = Http.request("GET", "$googleBooks/volumes?q=${Http.enc(q)}&maxResults=4&printType=books", mapOf("Accept" to "application/json"), timeoutMs = 15_000)
            if (r.ok) JSONObject(r.body).optJSONArray("items") else null
        }.getOrNull()?.let { items ->
            for (i in 0 until items.length()) {
                val v = items.optJSONObject(i)?.optJSONObject("volumeInfo") ?: continue
                val t = v.optString("title").trim().takeIf { it.isNotEmpty() } ?: continue
                val a = v.optJSONArray("authors")?.optString(0)?.takeIf { it.isNotBlank() }
                out += Candidate(t, a, 1, 1, v.optString("publishedDate").take(4).takeIf { it.length == 4 }, source = "Google Books")
            }
        }
        // The same book from two places once (the first place found keeps it).
        return out.distinctBy { key(it.title, it.author) }.take(8)
    }

    /** Guesses for highlights [texts], best first (at most [limit]). Blocking; network. Empty when nothing turned up. */
    fun guess(texts: List<String>, limit: Int = 4): List<Candidate> {
        val phrases = texts.mapNotNull { phrase(it) }.distinct().take(4)
        if (phrases.isEmpty()) return emptyList()
        data class Tally(var title: String, var author: String?, var hits: Int, var rank: Int, var year: String?, var cover: String?)
        val tally = LinkedHashMap<String, Tally>()
        for (p in phrases) {
            val o = runCatching {
                val r = Http.request("GET", "$googleBooks/volumes?q=${Http.enc("\"$p\"")}&maxResults=10&printType=books", mapOf("Accept" to "application/json"), timeoutMs = 15_000)
                if (r.ok) JSONObject(r.body) else null
            }.getOrNull() ?: continue
            val items = o.optJSONArray("items") ?: continue
            val seen = HashSet<String>()
            for (i in 0 until items.length()) {
                val v = items.optJSONObject(i)?.optJSONObject("volumeInfo") ?: continue
                val title = v.optString("title").trim().takeIf { it.isNotEmpty() } ?: continue
                val sub = v.optString("subtitle").trim()
                val authors = v.optJSONArray("authors")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
                // Collections of quotes and study guides quote books; they aren't the book.
                val all = (title + " " + sub).lowercase()
                if (listOf("quotes", "quotations", "sparknotes", "study guide", "summary of", "cliffsnotes", "analysis of").any { it in all }) continue
                val author = authors.firstOrNull()
                val k = key(title, author)
                if (!seen.add(k)) continue
                val t = tally.getOrPut(k) { Tally(title, author, 0, 0, null, null) }
                t.hits++
                t.rank += i
                if (t.year == null) t.year = v.optString("publishedDate").take(4).takeIf { it.length == 4 }
                if (t.cover == null) t.cover = v.optJSONObject("imageLinks")?.optString("thumbnail")?.takeIf { it.isNotBlank() }?.replace("http://", "https://")
            }
        }
        return tally.values
            .sortedWith(compareByDescending<Tally> { it.hits }.thenBy { it.rank })
            .take(limit)
            .map { Candidate(it.title, it.author, it.hits, phrases.size, it.year, it.cover) }
    }
}
