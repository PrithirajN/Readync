package io.github.prithirajn.trackersync.source

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.sync.Isbn
import io.github.prithirajn.trackersync.sync.Matcher
import io.github.prithirajn.trackersync.trackers.RemoteBook
import io.github.prithirajn.trackersync.util.arr
import io.github.prithirajn.trackersync.util.obj
import io.github.prithirajn.trackersync.util.objects
import io.github.prithirajn.trackersync.util.str
import org.json.JSONArray
import org.json.JSONObject

/** Everything public about a book, for adding it to a tracker that doesn't have it. */
data class BookDetails(
    val title: String,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val isbn13: String? = null,
    val isbn10: String? = null,
    val pages: Int? = null,
    val publisher: String? = null,
    /** "2024", "2024-05" or "2024-05-14". */
    val published: String? = null,
    /** Language name in English ("English", "Spanish"). */
    val language: String? = null,
    val description: String? = null,
    val coverUrl: String? = null,
    /** "Paperback", "Hardcover", "Ebook", "Audiobook". */
    val format: String? = null,
    val series: String? = null,
    val seriesIndex: String? = null,
    /** The title romanised ("Pratihingsa Yaunata Yuddha Draupadi"), for a title in an Indian script. */
    val altTitle: String? = null,
    /** Where the details came from first ("KalpaDB"), shown with them. */
    val source: String? = null,
    val genres: List<String> = emptyList(),
    /** Every source that had the book, and those that were asked but didn't. */
    val found: List<String> = emptyList(),
    val notFound: List<String> = emptyList(),
    /** For a web novel or fan fiction: the story's site, link, status and chapters. */
    val web: io.github.prithirajn.trackersync.data.WebNovel? = null,
    val goodreadsUrl: String? = null,
    val asin: String? = null,
) {
    /** "Found on Seekquel and Google Books" / "Not found on Seekquel, Google Books or Open Library: …". */
    fun sourceLine(): String = when {
        found.isEmpty() -> "Not found on ${list(notFound, "or")}; these are this app's own details."
        notFound.isEmpty() -> "Found on ${list(found, "and")}."
        else -> "Found on ${list(found, "and")}; not on ${list(notFound, "or")}."
    }

    private fun list(l: List<String>, last: String): String =
        if (l.size <= 1) l.joinToString() else l.dropLast(1).joinToString(", ") + " $last " + l.last()

    val author: String? get() = authors.firstOrNull()
    val year: Int? get() = published?.take(4)?.toIntOrNull()

    /** What's known, for showing before it's added: "ISBN …", "352 pages", "Tor, 2021". */
    fun summary(): List<String> = listOfNotNull(
        altTitle,
        web?.line(),
        web?.takeIf { it.fanfic }?.let { "Fan fiction" },
        (isbn13 ?: isbn10)?.let { "ISBN $it" },
        pages?.let { "$it pages" },
        listOfNotNull(publisher, published).joinToString(", ").ifEmpty { null },
        listOfNotNull(format, language).joinToString(" · ").ifEmpty { null },
        genres.takeIf { it.isNotEmpty() }?.take(4)?.joinToString(", "),
        series?.let { s -> s + (seriesIndex?.let { " #$it" } ?: "") },
        description?.let { "Description (${it.length} characters)" },
        coverUrl?.let { "Cover" },
    )
}

/**
 * Gathers a book's public details from Open Library and Google Books (no keys needed), by ISBN
 * when known, else by title and author, merged with what the app already has (its pages, cover
 * and description). The more filled in, the better the new entry on the tracker.
 */
object BookInfo {
    var openLibrary = "https://openlibrary.org"
    var googleBooks = "https://www.googleapis.com/books/v1"

    private fun json(url: String): JSONObject? = runCatching {
        val r = Http.request("GET", url, mapOf("Accept" to "application/json"), timeoutMs = 15_000)
        if (r.ok) JSONObject(r.body) else null
    }.getOrNull()

    /**
     * [kalpaId] (a KalpaDB work you picked) or a title in an Indian script puts KalpaDB first;
     * it's also tried when Google Books and Open Library know nothing.
     */
    fun lookup(
        book: Book, isbn: String? = book.isbn, kalpaId: String? = null, seekquel: BookDetails? = null, askedSeekquel: Boolean = false,
        crosspoint: BookDetails? = null, askedCrossPoint: Boolean = false,
        /** A web novel's own page (Royal Road, AO3…), pasted or remembered for this book. */
        webUrl: String? = null,
    ): BookDetails {
        val raw = seekquel?.title ?: book.title ?: "Untitled"
        // Web-novel tags ("[LitRPG, …]") only get in the way of a search.
        val title = Matcher.plainTitle(raw)
        // A web novel: its link, else tags in the title ("[LitRPG, …]") say to look on Royal Road and AO3 first.
        val tagged = Regex("\\[[^\\]]+\\]").containsMatchIn(raw)
        var web = webUrl?.let { runCatching { WebNovels.fetch(it) }.getOrNull() }
        var askedWeb = webUrl != null || tagged
        if (web == null && tagged) web = runCatching { WebNovels.search(title, book.author) }.getOrNull()
        if (web != null) return lookupWeb(book, title, web, seekquel, askedSeekquel, crosspoint, askedCrossPoint)

        var kalpa = kalpaId?.let { runCatching { KalpaDB.work(it) }.getOrNull() }
            ?: if (KalpaDB.isIndic(title)) runCatching { KalpaDB.search(title, book.author, isbn) }.getOrNull() else null
        val google = runCatching { google(title, book.author, isbn ?: kalpa?.isbn13 ?: kalpa?.isbn10) }.getOrNull()
        val ol = runCatching { openLibrary(title, book.author, isbn ?: kalpa?.isbn13 ?: kalpa?.isbn10 ?: google?.isbn13 ?: google?.isbn10) }.getOrNull()
        val askedKalpa = kalpaId != null || KalpaDB.isIndic(title) || (google == null && ol == null)
        if (kalpa == null && google == null && ol == null) kalpa = runCatching { KalpaDB.search(title, book.author, isbn) }.getOrNull()
        // Nothing anywhere, and it isn't an Indian book: perhaps a web novel without tags.
        if (kalpa == null && google == null && ol == null && isbn == null && !askedWeb) {
            askedWeb = true
            web = runCatching { WebNovels.search(title, book.author) }.getOrNull()
            if (web != null) return lookupWeb(book, title, web, seekquel, askedSeekquel, crosspoint, askedCrossPoint)
        }
        // crosspoint-sync's details (the book file's own, plus what the server found) come right
        // after Seekquel's, ahead of the public catalogues.
        val withCp = when {
            crosspoint == null -> kalpa
            kalpa == null -> crosspoint
            else -> fill(crosspoint, kalpa)
        }
        val merged = merge(book.copy(title = title), isbn, google, ol, withCp, seekquel).let { m ->
            m.copy(altTitle = kalpa?.altTitle ?: m.altTitle, genres = (crosspoint?.genres.orEmpty() + kalpa?.genres.orEmpty()).distinct())
        }
        val sources = listOfNotNull(
            if (askedSeekquel) "Seekquel" to (seekquel != null) else null,
            if (askedCrossPoint) "crosspoint-sync" to (crosspoint != null) else null,
            if (askedKalpa) "KalpaDB" to (kalpa != null) else null,
            "Google Books" to (google != null),
            "Open Library" to (ol != null),
            if (askedWeb) "Royal Road" to false else null,
            if (askedWeb) "AO3" to false else null,
        )
        val found = sources.filter { it.second }.map { it.first }
        return merged.copy(found = found, notFound = sources.filter { !it.second }.map { it.first }, source = found.firstOrNull())
    }

    /**
     * A web novel's details: Seekquel's lead where it has the book, then the story's own page,
     * then crosspoint-sync's. Book catalogues aren't asked: a published edition of a web novel
     * (its Kindle release, with an ISBN) isn't the story you're reading.
     */
    private fun lookupWeb(
        book: Book, title: String, web: BookDetails, seekquel: BookDetails?, askedSeekquel: Boolean,
        crosspoint: BookDetails?, askedCrossPoint: Boolean,
    ): BookDetails {
        val lead = fill(web, crosspoint?.copy(description = null, coverUrl = crosspoint.coverUrl))
        // The story's own title and author (a pasted link may be for a book saved under another name).
        val merged = merge(book.copy(title = web.title.ifBlank { title }, author = web.author ?: book.author, description = null, pageCount = null), null, null, null, lead, seekquel)
        val sources = listOfNotNull(
            if (askedSeekquel) "Seekquel" to (seekquel != null) else null,
            web.source!! to true,
            if (askedCrossPoint) "crosspoint-sync" to (crosspoint != null) else null,
        )
        val found = sources.filter { it.second }.map { it.first }
        return merged.copy(
            // The story's own description (with its link) and its tags, its length as the site counts it.
            description = web.description ?: merged.description,
            pages = seekquel?.pages ?: web.pages ?: book.pageCount ?: merged.pages,
            genres = (web.genres + book.titleTags).distinctBy { it.lowercase() },
            publisher = web.publisher, format = "Ebook", web = web.web,
            found = found, notFound = sources.filter { !it.second }.map { it.first }, source = found.firstOrNull(),
        )
    }

    /**
     * The app's own details first where it has them (they match the copy you read), then
     * KalpaDB (for Indian books), Google Books, then Open Library.
     */
    fun merge(book: Book, isbn: String?, google0: BookDetails?, ol0: BookDetails?, kalpa: BookDetails? = null, seekquel: BookDetails? = null): BookDetails {
        // Seekquel (your main tracker) leads for what it has: its title, authors, pages, year and cover.
        val lead = when {
            seekquel != null && kalpa != null -> fill(seekquel, kalpa)
            else -> seekquel ?: kalpa
        }
        val base = merge0(
            book.copy(
                title = seekquel?.title ?: book.title,
                author = seekquel?.author ?: book.author,
                coverUrl = book.coverUrl ?: seekquel?.coverUrl,
                pageCount = book.pageCount ?: seekquel?.pages,
            ),
            isbn, google0, ol0, lead,
        )
        return base.copy(
            authors = (seekquel?.authors.orEmpty() + base.authors).distinctBy { n -> n.lowercase().replace(Regex("[^\\p{L}]"), "").ifEmpty { n } },
            altTitle = kalpa?.altTitle ?: base.altTitle,
            source = if (seekquel != null) "Seekquel" else if (kalpa != null) "KalpaDB" else null,
        )
    }

    private fun merge0(book: Book, isbn: String?, google0: BookDetails?, ol0: BookDetails?, kalpa: BookDetails?): BookDetails {
        // KalpaDB's details lead; the others fill what it lacks.
        val google = if (kalpa == null) google0 else fill(kalpa, google0)
        val ol = if (kalpa == null) ol0 else ol0?.let { fill(it, null) }
        val (mainTitle, subtitleFromTitle) = splitTitle(book.title ?: google?.title ?: ol?.title ?: "Untitled")
        val isbn13 = isbn?.takeIf { it.length == 13 } ?: isbn?.takeIf { it.length == 10 }?.let { Isbn.to13(it) }
            ?: google?.isbn13 ?: ol?.isbn13
        val isbn10 = isbn?.takeIf { it.length == 10 } ?: google?.isbn10 ?: ol?.isbn10
        val authors = (listOfNotNull(book.author) + (google?.authors ?: emptyList()) + (ol?.authors ?: emptyList()))
            .flatMap { it.split(Regex("\\s*,\\s*(?=[A-Z])|\\s+&\\s+|\\s+and\\s+")) }
            .map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { n -> n.lowercase().replace(Regex("[^\\p{L}]"), "").ifEmpty { n } }
        // "…: Dungeon Crawler Carl Book 4" is the series, not a subtitle.
        val seriesFromTitle = subtitleFromTitle?.let { Regex("^(.+?)[,\\s]+(?:Book|Vol\\.?|Volume|#|No\\.?)\\s*(\\d+(?:\\.\\d+)?)$", RegexOption.IGNORE_CASE).find(it) }
        return BookDetails(
            title = mainTitle,
            subtitle = (if (seriesFromTitle != null) null else subtitleFromTitle) ?: google?.subtitle ?: ol?.subtitle,
            authors = authors,
            isbn13 = isbn13,
            isbn10 = isbn10,
            pages = book.pageCount ?: google?.pages ?: ol?.pages,
            publisher = google?.publisher ?: ol?.publisher,
            published = if (kalpa?.published != null) google?.published else longest(google?.published, ol?.published),
            language = google?.language ?: ol?.language,
            description = book.description ?: google?.description ?: ol?.description,
            coverUrl = book.coverUrl?.takeIf { it.startsWith("http") } ?: kalpa?.coverUrl ?: ol?.coverUrl ?: google?.coverUrl,
            format = google?.format ?: ol?.format,
            series = seriesFromTitle?.groupValues?.get(1)?.trim() ?: kalpa?.series ?: ol?.series,
            seriesIndex = seriesFromTitle?.groupValues?.get(2) ?: kalpa?.seriesIndex ?: ol?.seriesIndex,
        )
    }

    /** [lead] with gaps filled from [other]. */
    private fun fill(lead: BookDetails, other: BookDetails?): BookDetails {
        if (other == null) return lead
        return lead.copy(
            subtitle = lead.subtitle ?: other.subtitle,
            authors = lead.authors.ifEmpty { other.authors },
            isbn13 = lead.isbn13 ?: other.isbn13, isbn10 = lead.isbn10 ?: other.isbn10,
            pages = lead.pages ?: other.pages, publisher = lead.publisher ?: other.publisher,
            published = lead.published ?: other.published, language = lead.language ?: other.language,
            description = lead.description ?: other.description, coverUrl = lead.coverUrl ?: other.coverUrl,
            format = lead.format ?: other.format, series = lead.series ?: other.series, seriesIndex = lead.seriesIndex ?: other.seriesIndex,
            genres = lead.genres.ifEmpty { other.genres },
        )
    }

    /** "Gideon the Ninth: The Locked Tomb" -> ("Gideon the Ninth", "The Locked Tomb"). */
    fun splitTitle(t: String): Pair<String, String?> {
        val i = t.indexOf(": ")
        return if (i in 2 until t.length - 2) t.substring(0, i).trim() to t.substring(i + 2).trim() else t.trim() to null
    }

    private fun longest(a: String?, b: String?): String? = listOfNotNull(a, b).maxByOrNull { it.length }

    /**
     * A description for the book from Google Books: by ISBN when known, else by title and author,
     * trying the title without tags, subtitle or series too ("The Will of the Many (Hierarchy
     * Book 1)" -> "The Will of the Many"). Only a clear match by title (and author) counts.
     */
    fun description(title: String, author: String?, isbn: String?): String? {
        val a = author?.let { cleanAuthor(it) }
        if (!isbn.isNullOrBlank()) runCatching { google(title, a, isbn) }.getOrNull()?.description?.let { return it }
        for (t in titleVariants(title)) {
            runCatching { google(t, a, null) }.getOrNull()?.description?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /** The title as given, without [tags] or (asides), before its subtitle, and without a series suffix. */
    fun titleVariants(title: String): List<String> {
        val plain = Matcher.plainTitle(title)
        return listOf(title.trim(), plain, splitTitle(plain).first, Matcher.coreTitle(title))
            .map { it.trim() }.filter { it.length >= 2 }.distinct()
    }

    /** "Writer, A:" -> "A Writer"; "Bennett, Robert Jackson" -> "Robert Jackson Bennett". */
    fun cleanAuthor(author: String): String {
        val t = author.trim().removeSuffix(":").trim()
        val first = t.split(Regex("\\s*(?:;|&| and )\\s*")).first().trim()
        val comma = first.indexOf(',')
        return if (comma > 0 && comma == first.lastIndexOf(',')) (first.substring(comma + 1).trim() + " " + first.substring(0, comma).trim()).trim() else first
    }

    // ---- Google Books ----

    private fun google(title: String, author: String?, isbn: String?): BookDetails? {
        val q = if (isbn != null) "isbn:$isbn"
        else "intitle:" + splitTitle(title).first + (author?.let { " inauthor:" + it.substringBefore(",") } ?: "")
        val o = json("$googleBooks/volumes?q=${Http.enc(q)}&maxResults=5&printType=books") ?: return null
        val items = o.arr("items")?.objects() ?: return null
        // The closest by title and author, among the first few.
        val best = items.map { it.obj("volumeInfo") ?: JSONObject() }.maxByOrNull { v ->
            val t = v.str("title") ?: ""
            val a = v.arr("authors")?.optString(0)
            Matcher.score(title, author, RemoteBook("", t, a))
        } ?: return null
        if (isbn == null && Matcher.score(title, author, RemoteBook("", best.str("title") ?: "", best.arr("authors")?.optString(0))) < 0.6) return null
        return googleDetails(best)
    }

    internal fun googleDetails(v: JSONObject): BookDetails? {
        val ids = v.arr("industryIdentifiers")?.objects() ?: emptyList()
        val images = v.obj("imageLinks")
        val cover = (images?.str("extraLarge") ?: images?.str("large") ?: images?.str("medium") ?: images?.str("thumbnail"))
            ?.replace("http://", "https://")?.replace("&edge=curl", "")
        return BookDetails(
            title = v.str("title") ?: return null,
            subtitle = v.str("subtitle"),
            authors = v.arr("authors")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } } ?: emptyList(),
            isbn13 = ids.firstOrNull { it.str("type") == "ISBN_13" }?.str("identifier"),
            isbn10 = ids.firstOrNull { it.str("type") == "ISBN_10" }?.str("identifier"),
            pages = v.optInt("pageCount").takeIf { it > 0 },
            publisher = v.str("publisher"),
            published = v.str("publishedDate"),
            language = v.str("language")?.let { languageName(it) },
            description = v.str("description")?.let { stripTags(it) },
            coverUrl = cover,
            format = if (v.str("printType") == "BOOK" && v.obj("readingModes")?.optBoolean("text") == true && ids.isEmpty()) "Ebook" else null,
        )
    }

    // ---- Open Library ----

    private fun openLibrary(title: String, author: String?, isbn: String?): BookDetails? {
        val edition = isbn?.let { json("$openLibrary/isbn/$it.json") }
            ?: run {
                val q = StringBuilder("$openLibrary/search.json?title=${Http.enc(splitTitle(title).first)}")
                if (!author.isNullOrBlank()) q.append("&author=").append(Http.enc(author))
                q.append("&limit=1&fields=key,title,author_name,isbn,number_of_pages_median,publisher,first_publish_year,language,cover_i,edition_key")
                val doc = json(q.toString())?.arr("docs")?.objects()?.firstOrNull() ?: return null
                if (Matcher.score(title, author, RemoteBook("", doc.str("title") ?: "", doc.arr("author_name")?.optString(0))) < 0.6) return null
                // The first edition with an ISBN, else what the search gave.
                val ed = doc.arr("edition_key")?.optString(0)?.let { json("$openLibrary/books/$it.json") }
                if (ed == null) return searchDetails(doc)
                ed
            }
        return editionDetails(edition, author)
    }

    private fun searchDetails(doc: JSONObject): BookDetails {
        val isbns = doc.arr("isbn")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        return BookDetails(
            title = doc.str("title") ?: "",
            authors = doc.arr("author_name")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            isbn13 = isbns.firstOrNull { it.length == 13 },
            isbn10 = isbns.firstOrNull { it.length == 10 },
            pages = doc.optInt("number_of_pages_median").takeIf { it > 0 },
            publisher = doc.arr("publisher")?.optString(0)?.takeIf { it.isNotBlank() },
            published = doc.optInt("first_publish_year").takeIf { it > 0 }?.toString(),
            coverUrl = doc.optLong("cover_i").takeIf { it > 0 }?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" },
        )
    }

    internal fun editionDetails(e: JSONObject, knownAuthor: String?): BookDetails? {
        val title = e.str("title") ?: return null
        val authors = e.arr("authors")?.objects()?.mapNotNull { a -> a.str("key")?.let { json("$openLibrary$it.json")?.str("name") } }
            ?.takeIf { it.isNotEmpty() } ?: listOfNotNull(knownAuthor)
        val work = e.arr("works")?.objects()?.firstOrNull()?.str("key")?.let { json("$openLibrary$it.json") }
        val desc = (e.obj("description")?.str("value") ?: e.str("description") ?: work?.obj("description")?.str("value") ?: work?.str("description"))
            ?.let { OpenLibrary.clean(it) }
        val series = e.arr("series")?.optString(0)?.takeIf { it.isNotBlank() }
        return BookDetails(
            title = title,
            subtitle = e.str("subtitle"),
            authors = authors,
            isbn13 = e.arr("isbn_13")?.optString(0)?.takeIf { it.isNotBlank() },
            isbn10 = e.arr("isbn_10")?.optString(0)?.takeIf { it.isNotBlank() },
            pages = e.optInt("number_of_pages").takeIf { it > 0 },
            publisher = e.arr("publishers")?.optString(0)?.takeIf { it.isNotBlank() },
            published = e.str("publish_date")?.let { normaliseDate(it) },
            language = e.arr("languages")?.objects()?.firstOrNull()?.str("key")?.substringAfterLast('/')?.let { languageName(it) },
            description = desc,
            coverUrl = e.arr("covers")?.optLong(0)?.takeIf { it > 0 }?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" },
            format = e.str("physical_format")?.let { formatName(it) },
            series = series?.substringBefore(";")?.substringBefore(",")?.trim(),
            seriesIndex = series?.let { Regex("(?:#|no\\.?|book|vol\\.?)\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) },
        )
    }

    // ---- small helpers ----

    /** "May 14, 2024" / "2024" / "14 May 2024" -> "2024-05-14" / "2024" / "2024-05-14". */
    fun normaliseDate(s: String): String? {
        val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        Regex("^(\\d{4})-(\\d{2})(?:-(\\d{2}))?").find(s)?.let { return it.value }
        val year = Regex("(1[5-9]\\d\\d|20\\d\\d)").find(s)?.value ?: return null
        val m = months.indexOfFirst { s.lowercase().contains(it) }.takeIf { it >= 0 }?.plus(1) ?: return year
        val day = Regex("\\b(\\d{1,2})\\b").findAll(s).map { it.value.toInt() }.firstOrNull { it in 1..31 }
        return "%s-%02d".format(year, m) + (day?.let { "-%02d".format(it) } ?: "")
    }

    fun formatName(raw: String): String? = when {
        Regex("(?i)paper|soft").containsMatchIn(raw) -> "Paperback"
        Regex("(?i)hard|cloth").containsMatchIn(raw) -> "Hardcover"
        Regex("(?i)e-?book|kindle|epub|digital").containsMatchIn(raw) -> "Ebook"
        Regex("(?i)audio").containsMatchIn(raw) -> "Audiobook"
        else -> null
    }

    private val languages = mapOf(
        "en" to "English", "eng" to "English", "es" to "Spanish", "spa" to "Spanish", "fr" to "French", "fre" to "French", "fra" to "French",
        "de" to "German", "ger" to "German", "deu" to "German", "it" to "Italian", "ita" to "Italian", "pt" to "Portuguese", "por" to "Portuguese",
        "nl" to "Dutch", "dut" to "Dutch", "ja" to "Japanese", "jpn" to "Japanese", "zh" to "Chinese", "chi" to "Chinese", "ko" to "Korean", "kor" to "Korean",
        "ru" to "Russian", "rus" to "Russian", "hi" to "Hindi", "hin" to "Hindi", "bn" to "Bengali", "ben" to "Bengali", "ta" to "Tamil", "tam" to "Tamil",
        "ar" to "Arabic", "ara" to "Arabic", "sv" to "Swedish", "swe" to "Swedish", "pl" to "Polish", "pol" to "Polish", "tr" to "Turkish", "tur" to "Turkish",
    )

    fun languageName(code: String): String? = languages[code.lowercase().take(3)] ?: languages[code.lowercase().take(2)]

    private fun stripTags(s: String): String =
        s.replace(Regex("(?i)<br\\s*/?>|</p>"), "\n").replace(Regex("<[^>]+>"), "").replace("&quot;", "\"").replace("&amp;", "&").trim()

    /** For tests and trackers that want it as JSON. */
    fun toJson(d: BookDetails): JSONObject = JSONObject()
        .put("title", d.title).put("subtitle", d.subtitle ?: JSONObject.NULL)
        .put("authors", JSONArray(d.authors)).put("isbn13", d.isbn13 ?: JSONObject.NULL).put("isbn10", d.isbn10 ?: JSONObject.NULL)
        .put("pages", d.pages ?: JSONObject.NULL).put("publisher", d.publisher ?: JSONObject.NULL).put("published", d.published ?: JSONObject.NULL)
}
