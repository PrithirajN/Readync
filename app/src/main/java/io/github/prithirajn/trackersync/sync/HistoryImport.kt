package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.ImportedQuote
import io.github.prithirajn.trackersync.data.ReadDay
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.source.Clipping
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.trackers.LibraryBook
import io.github.prithirajn.trackersync.trackers.Trackers
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reading history brought in from elsewhere: a tracker's export (Goodreads, StoryGraph, Hardcover,
 * or any CSV with title and author columns), KOReader's reading statistics, or a linked tracker's
 * own shelves.
 *
 * Nothing is sent to your trackers. Books already here keep their progress, status, dates and
 * stats; only what they're missing is filled in (a rating, a review, start and finish days), and
 * those count as already sent. Books new here come in held: shown in the library, sent nowhere
 * until you choose to.
 */
object HistoryImport {

    /** One book in someone's history. Times are unix seconds. */
    data class Entry(
        val title: String,
        val author: String?,
        val isbn: String? = null,
        val status: ReadStatus? = null,
        val startedAt: Long? = null,
        val finishedAt: Long? = null,
        /** Stars out of 5. */
        val rating: Double? = null,
        val review: String? = null,
        val pageCount: Int? = null,
        /** 0–1. */
        val fraction: Double? = null,
        val coverUrl: String? = null,
        /** When it last changed there, for the library's order. */
        val updatedAt: Long? = null,
        /** Day by day, when the history has it ("2024-03-09" -> that day). */
        val days: Map<String, ReadDay> = emptyMap(),
        /** Highlights, when the history has them. */
        val quotes: List<ImportedQuote> = emptyList(),
    )

    /** Where a history came from: an id for the books' origin ("history:goodreads") and a name to show. */
    data class Source(val id: String, val name: String)

    val GOODREADS = Source("goodreads", "Goodreads")
    val STORYGRAPH = Source("storygraph", "StoryGraph")
    val HARDCOVER = Source("hardcover", "Hardcover")
    val KOREADER = Source("koreader", "KOReader")
    val CSV = Source("csv", "CSV file")

    const val ORIGIN_PREFIX = "history:"

    /** "Goodreads history" for a book's "From your … history" line. */
    fun originName(origin: String): String? {
        if (!origin.startsWith(ORIGIN_PREFIX)) return null
        return when (val id = origin.removePrefix(ORIGIN_PREFIX)) {
            GOODREADS.id -> GOODREADS.name
            STORYGRAPH.id -> STORYGRAPH.name
            HARDCOVER.id -> HARDCOVER.name
            KOREADER.id -> KOREADER.name
            CSV.id -> "imported"
            else -> io.github.prithirajn.trackersync.trackers.Trackers.byId(id)?.name ?: id
        }
    }

    // ---- CSV ----

    /** RFC 4180 rows: quoted fields with commas, doubled quotes and line breaks inside. */
    fun csvRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val f = StringBuilder()
        var quoted = false
        var i = 0
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val ch = s[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { f.append('"'); i++ } else quoted = false
                } else f.append(ch)
            } else when (ch) {
                '"' -> quoted = true
                ',' -> { row.add(f.toString()); f.setLength(0) }
                '\r' -> Unit
                '\n' -> { row.add(f.toString()); f.setLength(0); rows.add(row); row = ArrayList() }
                else -> f.append(ch)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    private fun key(h: String) = h.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private val TITLE = listOf("title", "book title", "name", "book name", "book")
    private val AUTHOR = listOf("author", "authors", "author s", "author name", "primary author", "author l f", "creators", "creator", "contributors")
    private val ISBN = listOf("isbn13", "isbn 13", "isbn", "isbn uid", "isbn10", "isbn 10", "ean", "asin")
    private val STATUS = listOf("exclusive shelf", "read status", "status", "reading status", "shelf", "state")
    private val RATING = listOf("my rating", "star rating", "rating", "user rating", "your rating", "stars")
    private val REVIEW = listOf("my review", "review", "review text", "your review", "notes")
    private val STARTED = listOf("date started", "started at", "started", "start date", "started reading", "date started reading", "first started")
    private val FINISHED = listOf("date read", "last date read", "date finished", "finished at", "finished", "finish date", "read date", "date completed", "completed at", "finished reading")
    private val DATES_READ = listOf("dates read")
    private val PAGES = listOf("number of pages", "pages", "page count", "num pages", "number pages", "length")
    private val PROGRESS = listOf("progress", "percent", "progress percent", "percentage", "progress pct")
    private val ADDED = listOf("date added", "added", "added at", "created at", "date created")
    private val COVER = listOf("cover", "cover url", "image", "image url", "cover image")

    /** What a column can hold. */
    enum class Field(val label: String) {
        TITLE("title"), AUTHOR("author"), ISBN("ISBN"), STATUS("reading status"), RATING("your rating"),
        REVIEW("your review"), STARTED("date started"), FINISHED("date finished"), DATES_READ("dates read (start-end)"),
        PAGES("page count"), PROGRESS("progress"), ADDED("date added"), COVER("cover image link"),
    }

    private val NAMES = mapOf(
        Field.TITLE to TITLE, Field.AUTHOR to AUTHOR, Field.ISBN to ISBN, Field.STATUS to STATUS, Field.RATING to RATING,
        Field.REVIEW to REVIEW, Field.STARTED to STARTED, Field.FINISHED to FINISHED, Field.DATES_READ to DATES_READ,
        Field.PAGES to PAGES, Field.PROGRESS to PROGRESS, Field.ADDED to ADDED, Field.COVER to COVER,
    )

    /** Which tracker an export came from, by its columns. */
    fun detect(headers: List<String>): Source {
        val h = headers.map(::key).toSet()
        return when {
            "exclusive shelf" in h && "bookshelves" in h -> GOODREADS
            "read status" in h && "star rating" in h -> STORYGRAPH
            h.any { it.contains("hardcover") } -> HARDCOVER
            else -> CSV
        }
    }

    /** A CSV's header row and up to [n] rows under it, for working out its columns. */
    fun preview(text: String, n: Int = 4): Pair<List<String>, List<List<String>>> {
        val rows = csvRows(text)
        return (rows.firstOrNull() ?: emptyList()) to rows.drop(1).take(n)
    }

    /** The columns known by their names (Goodreads', StoryGraph's and common ones). */
    fun columns(headers: List<String>): Map<Field, Int> {
        val keys = headers.map(::key)
        val out = LinkedHashMap<Field, Int>()
        for ((f, names) in NAMES) {
            names.firstNotNullOfOrNull { n -> keys.indexOf(n).takeIf { it >= 0 } }?.let { out[f] = it }
        }
        return out
    }

    /**
     * True when the names alone don't say enough to read the file: no title or author column, or
     * nothing about reading at all (no status, finish day, dates read, rating or progress).
     */
    fun needsHelp(cols: Map<Field, Int>): Boolean =
        Field.TITLE !in cols || Field.AUTHOR !in cols ||
            listOf(Field.STATUS, Field.FINISHED, Field.DATES_READ, Field.RATING, Field.PROGRESS).none { it in cols }

    /**
     * [suggested] columns (from the on-device model) kept only where the file's own values bear
     * them out: titles mostly filled, ratings numbers, dates dates, statuses statuses. A column
     * can't be two things, and the columns known by name stay as they are.
     */
    fun checkColumns(known: Map<Field, Int>, suggested: Map<Field, Int>, header: List<String>, rows: List<List<String>>, zone: ZoneId = ZoneId.systemDefault()): Map<Field, Int> {
        val out = LinkedHashMap(known)
        val used = known.values.toMutableSet()
        for ((f, c) in suggested) {
            if (f in out || c !in header.indices || c in used) continue
            val vals = rows.mapNotNull { it.getOrNull(c)?.trim()?.takeIf { v -> v.isNotEmpty() } }
            fun share(ok: (String) -> Boolean) = if (vals.isEmpty()) 0.0 else vals.count(ok).toDouble() / vals.size
            val fits = when (f) {
                Field.TITLE -> vals.size * 2 >= rows.size && share { it.any(Char::isLetter) } >= 0.8
                Field.AUTHOR -> share { it.any(Char::isLetter) && it.length < 200 } >= 0.8
                Field.ISBN -> share { isbnOf(it) != null } >= 0.6
                Field.STATUS -> share { statusOf(it) != null } >= 0.6
                Field.RATING -> share { ratingOf(it) != null || it.trim() == "0" } >= 0.6
                Field.STARTED, Field.FINISHED, Field.ADDED -> share { day(it, zone) != null } >= 0.6
                Field.DATES_READ -> share { v -> day(v.split(Regex("\\s*[-–,]\\s*(?=\\d{4})")).first(), zone) != null } >= 0.6
                Field.PAGES -> share { v -> v.filter(Char::isDigit).toIntOrNull()?.let { it in 1..20000 } == true } >= 0.6
                Field.PROGRESS -> share { v -> v.removeSuffix("%").trim().toDoubleOrNull()?.let { it in 0.0..100.0 } == true } >= 0.6
                Field.COVER -> share { it.startsWith("http") } >= 0.6
                Field.REVIEW -> true
            }
            if (fits) { out[f] = c; used += c }
        }
        return out
    }

    /**
     * The books in a CSV export, with the source it looks like. Rows without a title are left
     * out. Dates are read as days in [zone]. [cols] says which column is which when the names
     * alone don't (worked out by the on-device model and checked).
     */
    fun fromCsv(text: String, zone: ZoneId = ZoneId.systemDefault(), cols: Map<Field, Int>? = null): Pair<Source, List<Entry>> {
        val rows = csvRows(text)
        if (rows.size < 2) return CSV to emptyList()
        val header = rows[0]
        val keys = header.map(::key)
        val map = cols ?: columns(header)
        val source = detect(header)
        val cTitle = map[Field.TITLE] ?: return source to emptyList()
        val cAuthor = map[Field.AUTHOR]; val cIsbn = map[Field.ISBN]; val cStatus = map[Field.STATUS]; val cRating = map[Field.RATING]
        val cReview = map[Field.REVIEW]; val cStarted = map[Field.STARTED]; val cFinished = map[Field.FINISHED]; val cDates = map[Field.DATES_READ]
        val cPages = map[Field.PAGES]; val cProgress = map[Field.PROGRESS]; val cAdded = map[Field.ADDED]; val cCover = map[Field.COVER]
        // Goodreads keeps a second ISBN column; the 13-digit one is preferred, the 10-digit kept as a fallback.
        val cIsbn10 = keys.indexOf("isbn").takeIf { it >= 0 && it != cIsbn }
        val out = ArrayList<Entry>()
        for (r in rows.drop(1)) {
            fun at(c: Int?): String? = c?.let { r.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
            val title = at(cTitle) ?: continue
            var started = day(at(cStarted), zone)
            var finished = day(at(cFinished), zone)
            // StoryGraph's "Dates Read": "2023/01/05-2023/01/20, 2024/03/01-2024/03/09"; the last read counts.
            at(cDates)?.split(',')?.lastOrNull()?.trim()?.let { last ->
                val parts = last.split(Regex("\\s*[-–]\\s*(?=\\d{4})"))
                if (started == null) started = day(parts.firstOrNull(), zone)
                if (finished == null && parts.size > 1) finished = day(parts.last(), zone)
            }
            val status = statusOf(at(cStatus)) ?: when {
                finished != null -> ReadStatus.FINISHED
                else -> null
            }
            val pct = at(cProgress)?.removeSuffix("%")?.trim()?.toDoubleOrNull()?.let { if (it > 1.0) it / 100.0 else it }?.coerceIn(0.0, 1.0)
            out += Entry(
                title = title,
                author = at(cAuthor)?.let { cleanAuthor(it) },
                isbn = isbnOf(at(cIsbn)) ?: isbnOf(at(cIsbn10)),
                status = status,
                startedAt = started,
                finishedAt = finished,
                rating = at(cRating)?.let(::ratingOf),
                review = at(cReview)?.let { cleanReview(it) },
                pageCount = at(cPages)?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it in 1..20000 },
                fraction = pct ?: if (status == ReadStatus.FINISHED) 1.0 else null,
                coverUrl = at(cCover)?.takeIf { it.startsWith("http") },
                updatedAt = finished ?: started ?: day(at(cAdded), zone),
            )
        }
        return source to out
    }

    internal fun statusOf(raw: String?): ReadStatus? {
        val s = raw?.lowercase()?.replace('_', '-')?.replace(' ', '-')?.trim() ?: return null
        return when (s) {
            "read", "finished", "completed", "complete", "done", "have-read" -> ReadStatus.FINISHED
            "currently-reading", "reading", "in-progress", "started" -> ReadStatus.READING
            "to-read", "want-to-read", "wanttoread", "plan-to-read", "planned", "tbr", "want" -> ReadStatus.TO_READ
            "did-not-finish", "dnf", "abandoned", "dropped", "gave-up" -> ReadStatus.DNF
            "paused", "on-hold", "onhold", "hold" -> ReadStatus.PAUSED
            else -> null
        }
    }

    /** "4", "4.5", "4/5", "★★★★" as stars; 0 or blank (unrated) as null; a 10-point scale halved. */
    internal fun ratingOf(raw: String): Double? {
        val t = raw.trim()
        if (t.contains('★')) return t.count { it == '★' }.toDouble().takeIf { it > 0 }
        val n = t.substringBefore('/').trim().toDoubleOrNull() ?: return null
        val outOf = t.substringAfter('/', "").trim().toDoubleOrNull()
        val stars = when {
            outOf != null && outOf > 0 -> n / outOf * 5.0
            n > 5.0 && n <= 10.0 -> n / 2.0
            else -> n
        }
        return stars.takeIf { it > 0.0 }?.coerceAtMost(5.0)?.let { Math.round(it * 10) / 10.0 }
    }

    /** Goodreads' ="0441013597" and spaces or dashes taken out; anything that isn't an ISBN as null. */
    internal fun isbnOf(raw: String?): String? {
        val d = raw?.uppercase()?.filter { it.isDigit() || it == 'X' } ?: return null
        return d.takeIf { it.length == 13 || it.length == 10 }
    }

    /** "Dinniman, Matt" as "Matt Dinniman"; StoryGraph's several authors kept as listed. */
    private fun cleanAuthor(a: String): String = Matcher.naturalAuthor(a) ?: a

    /** Goodreads reviews come with <br/> for line breaks. */
    private fun cleanReview(r: String): String? =
        r.replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "").trim().takeIf { it.isNotEmpty() }

    private val DAY_FORMATS = listOf("yyyy/MM/dd", "yyyy-MM-dd", "yyyy.MM.dd", "MM/dd/yyyy", "dd.MM.yyyy", "d MMM yyyy", "MMM d, yyyy", "yyyy/M/d")
        .map { DateTimeFormatter.ofPattern(it, java.util.Locale.ENGLISH) }

    /** A day ("2024/03/09", "2024-03-09T18:20:00Z"…) as unix seconds at noon there, so it stays that day. */
    internal fun day(raw: String?, zone: ZoneId): Long? {
        val t = raw?.trim()?.trim('"')?.takeIf { it.isNotEmpty() } ?: return null
        val date: LocalDate = runCatching { OffsetDateTime.parse(t).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(t).toLocalDate() }.getOrNull()
            ?: DAY_FORMATS.firstNotNullOfOrNull { f -> runCatching { LocalDate.parse(t, f) }.getOrNull() }
            // "2024/03/09 18:20" or "2024-03-09T18:20": the day part.
            ?: t.substringBefore(' ').substringBefore('T').let { d -> DAY_FORMATS.firstNotNullOfOrNull { f -> runCatching { LocalDate.parse(d, f) }.getOrNull() } }
            ?: return null
        if (date.year < 1900 || date.isAfter(LocalDate.now(zone).plusDays(1))) return null
        return date.atTime(12, 0).atZone(zone).toEpochSecond()
    }

    // ---- KOReader ----

    /** A book in KOReader's statistics.sqlite3 ("book" table, with its page reads). Times are unix seconds. */
    data class KoBook(
        val title: String,
        val authors: String?,
        val pages: Int?,
        val readPages: Int?,
        val firstRead: Long?,
        val lastRead: Long?,
        /** Each day it was read: pages turned and minutes spent ("2024-03-09" -> that day). */
        val days: Map<String, ReadDay> = emptyMap(),
    )

    /**
     * KOReader books as history: progress from pages read out of the book's pages, finished once
     * nearly all of it was read, paused when it hasn't been opened for three months.
     */
    fun fromKoreader(books: List<KoBook>, now: Long = System.currentTimeMillis() / 1000): List<Entry> = books.mapNotNull { k ->
        val title = k.title.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val frac = if ((k.pages ?: 0) > 0 && k.readPages != null) (k.readPages.toDouble() / k.pages!!).coerceIn(0.0, 1.0) else null
        val finished = frac != null && frac >= 0.97
        val status = when {
            finished -> ReadStatus.FINISHED
            k.lastRead != null && now - k.lastRead > 90L * 86400 -> ReadStatus.PAUSED
            else -> ReadStatus.READING
        }
        Entry(
            title = title,
            // KOReader keeps several authors on separate lines.
            author = k.authors?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(", ")?.takeIf { it.isNotEmpty() }?.let { Matcher.naturalAuthor(it) ?: it },
            status = status,
            startedAt = k.firstRead,
            finishedAt = if (finished) k.lastRead else null,
            pageCount = k.pages?.takeIf { it > 0 },
            fraction = if (finished) 1.0 else frac,
            updatedAt = k.lastRead,
            days = k.days,
        )
    }

    // ---- a linked tracker's shelves ----

    fun fromLibrary(items: List<LibraryBook>): List<Entry> = items.map { b ->
        Entry(
            title = b.title, author = b.author, status = b.status, startedAt = b.startedAt, finishedAt = b.finishedAt,
            rating = b.rating, review = b.review, pageCount = b.pageCount,
            fraction = b.fraction ?: if (b.status == ReadStatus.FINISHED) 1.0 else null,
            coverUrl = b.coverUrl, updatedAt = b.updatedAt.takeIf { it > 0 },
            days = b.days, quotes = b.quotes,
        )
    }

    // ---- planning and bringing it in ----

    /** A book already here that the history adds to, and what it adds. */
    data class Fill(val document: String, val entry: Entry, val adds: List<String>)

    data class Plan(
        val source: Source,
        /** Books not here yet, by status. */
        val fresh: List<Entry>,
        val fills: List<Fill>,
        /** Books already here with nothing to add. */
        val same: Int,
        /** Want to Read books, left out unless asked for. */
        val wantToRead: List<Entry>,
        /** Columns the on-device model worked out ("title" to the file's "Book Name"), to show before bringing it in. */
        val columnsByAi: List<Pair<String, String>> = emptyList(),
    ) {
        val total: Int get() = fresh.size + fills.size + same + wantToRead.size
    }

    /** The book here that [e] is, by ISBN, else by title and author. */
    internal fun match(s: Snapshot, e: Entry, books: Collection<Book> = s.books.values): Book? {
        e.isbn?.let { i -> books.firstOrNull { it.isbn != null && isbnOf(it.isbn) == i }?.let { return it } }
        return books.firstOrNull { b -> b.title != null && Matcher.sameBook(e.title, e.author, b.title, b.author) }
    }

    /** A highlight's text, for telling whether a book already has it. */
    private fun quoteKey(t: String) = t.lowercase().filter { it.isLetterOrDigit() }.take(120)

    /** Every highlight a book has here, whatever it came from. */
    internal fun clipsOf(s: Snapshot, doc: String): List<Clipping> =
        (s.cpClips + s.paperClips + s.kindleClips + s.libbyClips + s.moonClips + s.historyClips + s.koClips).filter { it.document == doc }

    /** [e]'s highlights that [doc] doesn't have yet. */
    internal fun newQuotes(s: Snapshot, doc: String, e: Entry): List<ImportedQuote> {
        if (e.quotes.isEmpty()) return emptyList()
        val have = clipsOf(s, doc).map { quoteKey(it.text) }.toHashSet()
        return e.quotes.filter { q -> quoteKey(q.text).let { it.isNotEmpty() && have.add(it) } }
    }

    /** [e]'s reading days that [doc] doesn't have yet (days already here are kept as they are). */
    internal fun newDays(s: Snapshot, doc: String, e: Entry): Map<String, ReadDay> {
        val have = s.readingDays[doc] ?: return e.days
        return e.days.filterKeys { it !in have }
    }

    /** What [e] would add to [b]: only what [b] doesn't have. */
    internal fun adds(s: Snapshot, b: Book, e: Entry): List<String> = listOfNotNull(
        newQuotes(s, b.document, e).size.takeIf { it > 0 }?.let { "highlights" },
        newDays(s, b.document, e).size.takeIf { it > 0 }?.let { "reading days" },
        "rating".takeIf { b.rating == null && e.rating != null },
        "review".takeIf { b.review.isNullOrBlank() && !e.review.isNullOrBlank() },
        "start day".takeIf { b.startedAt == null && e.startedAt != null },
        "finish day".takeIf { b.finishedAt == null && e.finishedAt != null && (b.status == ReadStatus.FINISHED || b.status == null) },
        "status".takeIf { b.status == null && e.status != null },
        "pages".takeIf { b.pageCount == null && e.pageCount != null },
        "cover".takeIf { b.coverUrl == null && e.coverUrl != null },
        "ISBN".takeIf { b.isbn == null && e.isbn != null },
    )

    fun plan(s: Snapshot, source: Source, entries: List<Entry>): Plan {
        val fresh = ArrayList<Entry>()
        val want = ArrayList<Entry>()
        val fills = ArrayList<Fill>()
        var same = 0
        val seen = HashSet<String>()
        for (e in entries) {
            // The same book twice in one file (a reread on its own row): the first one counts.
            val k = Matcher.baseTitle(e.title) + "|" + (e.author?.let { Matcher.normalizeAuthor(it) } ?: "")
            if (!seen.add(k)) continue
            val b = match(s, e)
            if (b == null) {
                if (e.status == ReadStatus.TO_READ) want += e else fresh += e
                continue
            }
            val a = adds(s, b, e)
            if (a.isEmpty()) same++ else fills += Fill(b.document, e, a)
        }
        return Plan(source, fresh, fills, same, want)
    }

    /** What [apply] did. */
    data class Result(val snapshot: Snapshot, val added: Int, val filled: Int, val highlights: Int = 0, val days: Int = 0)

    /** A stable document id for a book brought in from [source]. */
    internal fun documentFor(source: Source, e: Entry): String {
        val k = e.isbn ?: (Matcher.baseTitle(e.title) + "|" + (e.author?.let { Matcher.normalizeAuthor(it) } ?: ""))
        return ORIGIN_PREFIX + source.id + ":" + Integer.toHexString(k.hashCode()) + Integer.toHexString(k.reversed().hashCode())
    }

    /**
     * [plan] brought into [s]. Nothing is queued for any tracker: new books are held, and what's
     * filled in on books already matched to a tracker is marked as already sent there.
     */
    fun apply(s: Snapshot, plan: Plan, withWantToRead: Boolean, now: Long = System.currentTimeMillis()): Result {
        val books = HashMap(s.books)
        val links = HashMap(s.links)
        val days = HashMap(s.readingDays)
        val clips = ArrayList(s.historyClips)
        var sent = s.sentClippings
        var added = 0
        var newHighlights = 0
        var newDays = 0
        val nowSec = now / 1000
        /** [e]'s new highlights and days put on [doc]. Highlights count as already sent everywhere: nothing goes out. */
        fun extras(doc: String, e: Entry) {
            val cur = s.copy(readingDays = days, historyClips = clips)
            val qs = newQuotes(cur, doc, e)
            for (q in qs) {
                val id = "hist-${plan.source.id}-" + Integer.toHexString((doc + "|" + quoteKey(q.text)).hashCode())
                if (clips.any { it.id == id }) continue
                clips += Clipping(id, doc, q.text.trim(), q.note?.trim()?.ifEmpty { null }, null, q.createdAt ?: e.finishedAt ?: e.updatedAt ?: nowSec, percent = q.percent, page = q.page)
                sent = sent + Trackers.all.map { "${it.id}|$id" }
                newHighlights++
            }
            val nd = newDays(cur, doc, e)
            if (nd.isNotEmpty()) { days[doc] = (days[doc] ?: emptyMap()) + nd; newDays += nd.size }
        }
        for (e in plan.fresh + (if (withWantToRead) plan.wantToRead else emptyList())) {
            val doc = documentFor(plan.source, e)
            if (books.containsKey(doc)) continue
            val finished = e.status == ReadStatus.FINISHED
            books[doc] = Book(
                document = doc,
                title = e.title,
                author = e.author,
                percentage = (e.fraction ?: if (finished) 1.0 else 0.0).coerceIn(0.0, 1.0),
                status = e.status ?: ReadStatus.FINISHED.takeIf { finished },
                timestamp = e.updatedAt ?: e.finishedAt ?: e.startedAt ?: nowSec,
                coverUrl = e.coverUrl,
                origin = ORIGIN_PREFIX + plan.source.id,
                pageCount = e.pageCount,
                finishedAt = e.finishedAt,
                startedAt = e.startedAt,
                held = true,
                rating = e.rating,
                review = e.review,
                // Past reading: the "you finished it" prompt isn't offered for it.
                reviewAsked = true,
                isbn = e.isbn,
            )
            added++
            extras(doc, e)
        }
        var filled = 0
        for (f in plan.fills) {
            val b = books[f.document] ?: continue
            val e = f.entry
            val nb = b.copy(
                rating = b.rating ?: e.rating,
                review = b.review?.takeIf { it.isNotBlank() } ?: e.review,
                startedAt = b.startedAt ?: e.startedAt,
                finishedAt = b.finishedAt ?: e.finishedAt?.takeIf { b.status == ReadStatus.FINISHED || b.status == null },
                status = b.status ?: e.status,
                pageCount = b.pageCount ?: e.pageCount,
                coverUrl = b.coverUrl ?: e.coverUrl,
                isbn = b.isbn ?: e.isbn,
                reviewAsked = b.reviewAsked || e.rating != null,
            )
            val before = newHighlights + newDays
            extras(f.document, e)
            if (nb == b && newHighlights + newDays == before) continue
            books[f.document] = nb
            filled++
            // Filled in here, not news for the trackers: marked as sent where the book is matched.
            val started = day(nb.startedAt)
            val fin = if (nb.status == ReadStatus.FINISHED) day(nb.finishedAt) else null
            val datesKey = "${started ?: ""}|${fin ?: ""}"
            for ((k, l) in s.links) {
                if (l.document != f.document) continue
                links[k] = l.copy(
                    reviewSent = if (nb.rating != b.rating || nb.review != b.review) SyncEngine.reviewPrint(nb) else l.reviewSent,
                    datesSent = if (nb.startedAt != b.startedAt || nb.finishedAt != b.finishedAt) datesKey else l.datesSent,
                )
            }
        }
        return Result(s.copy(books = books, links = links, readingDays = days, historyClips = clips, sentClippings = sent), added, filled, newHighlights, newDays)
    }

    private fun day(unix: Long?): LocalDate? =
        unix?.takeIf { it > 0 }?.let { java.time.Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDate() }
}
