package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Store
import io.github.prithirajn.trackersync.net.ServiceException
import java.util.UUID

/**
 * Books you track by hand (a physical book, a library copy): added from Hardcover or Seekquel
 * (or typed in), kept only on this phone, and updated by page, percentage, status or a photo
 * of the page. They go to every linked tracker like any other book.
 */
object ManualBooks {
    /** [Book.origin] of a book added by hand. Not a tracker, so it's sent to all of them. */
    const val ORIGIN = "manual"

    fun isManual(b: Book?) = b?.origin == ORIGIN

    /** Letters, digits and dashes only, so every tracker (and Seekquel's document ids) accept it. */
    fun newDocument(): String = "manual-" + UUID.randomUUID().toString().replace("-", "").take(24)

    /** What the Add sheet picked: the book's details, and where it's already at, if known. */
    data class Pick(
        val title: String,
        val author: String?,
        val coverUrl: String? = null,
        val pages: Int? = null,
        val fraction: Double? = null,
        val status: ReadStatus? = null,
        val isbn: String? = null,
    )

    /** Adds the book and returns its document id. Linking to the tracker it came from is the caller's. */
    fun add(store: Store, pick: Pick, now: Long = System.currentTimeMillis()): String {
        val title = pick.title.trim()
        if (title.isEmpty()) throw ServiceException("A title is needed")
        val pages = pick.pages?.takeIf { it > 0 }
        val fraction = (pick.fraction ?: 0.0).coerceIn(0.0, 1.0)
        val status = when (pick.status) {
            null, ReadStatus.TO_READ -> ReadStatus.READING
            else -> pick.status
        }
        val doc = newDocument()
        val book = Book(
            document = doc,
            title = title,
            author = pick.author?.trim()?.ifEmpty { null },
            percentage = if (status == ReadStatus.FINISHED) 1.0 else fraction,
            status = status,
            timestamp = now / 1000,
            coverUrl = pick.coverUrl,
            origin = ORIGIN,
            pageCount = pages,
            currentPage = pages?.let { Math.round(fraction * it).toInt() }?.takeIf { it > 0 },
            startedAt = if (status == ReadStatus.TO_READ) null else now / 1000,
            finishedAt = if (status == ReadStatus.FINISHED) now / 1000 else null,
            isbn = pick.isbn,
        )
        val autoEnable = store.snapshot.settings.autoEnableNewBooks
        store.update { s ->
            val links = HashMap(s.links)
            if (!autoEnable) {
                for (t in io.github.prithirajn.trackersync.trackers.Trackers.all) {
                    links[io.github.prithirajn.trackersync.data.Link.key(t.id, doc)] =
                        io.github.prithirajn.trackersync.data.Link(t.id, doc, null, source = io.github.prithirajn.trackersync.data.LinkSource.NONE, note = "Not synced")
                }
            }
            s.copy(books = s.books + (doc to book), links = links)
        }
        if (autoEnable) {
            queueEverywhere(store, doc)
        }
        return doc
    }

    private fun book(store: Store, document: String): Book {
        val b = store.snapshot.books[document]
        if (!isManual(b)) throw ServiceException("That isn't a book you added by hand")
        return b!!
    }

    fun queueEverywhere(store: Store, document: String) {
        for (svc in store.snapshot.services.values.filter { it.linked && it.enabled }) {
            SyncEngine.queue(store, svc.id, listOf(document))
        }
    }

    /** You're on [page]. Moves the book to Reading, or Finished at the last pages. */
    fun setPage(store: Store, document: String, page: Int, now: Long = System.currentTimeMillis()) {
        val b = book(store, document)
        val pages = b.pageCount ?: throw ServiceException("Set the book's page count first")
        if (page < 0 || page > pages) throw ServiceException("Page $page isn't in a $pages-page book")
        val fraction = page.toDouble() / pages
        store.update { s ->
            val finishing = fraction >= 0.98
            s.copy(books = s.books + (document to b.copy(
                currentPage = page,
                percentage = fraction,
                status = if (finishing) ReadStatus.FINISHED else ReadStatus.READING,
                timestamp = now / 1000,
                startedAt = b.startedAt ?: now / 1000,
                finishedAt = if (finishing) (b.finishedAt ?: now / 1000) else b.finishedAt,
            )))
        }
        queueEverywhere(store, document)
    }

    /** For a book without a page count: where you are, as a percentage. */
    fun setPercent(store: Store, document: String, percent: Int, now: Long = System.currentTimeMillis()) {
        val b = book(store, document)
        if (percent !in 0..100) throw ServiceException("Enter a percentage from 0 to 100")
        val fraction = percent / 100.0
        store.update { s ->
            s.copy(books = s.books + (document to b.copy(
                percentage = fraction,
                currentPage = b.pageCount?.let { Math.round(fraction * it).toInt() },
                status = if (percent >= 98) ReadStatus.FINISHED else ReadStatus.READING,
                timestamp = now / 1000,
            )))
        }
        queueEverywhere(store, document)
    }

    fun setStatus(store: Store, document: String, status: ReadStatus, now: Long = System.currentTimeMillis()) {
        val b = book(store, document)
        val finished = status == ReadStatus.FINISHED
        store.update { s ->
            s.copy(books = s.books + (document to b.copy(
                status = status,
                percentage = if (finished) 1.0 else b.percentage,
                currentPage = if (finished) b.pageCount ?: b.currentPage else b.currentPage,
                finishedAt = if (finished) now / 1000 else b.finishedAt,
                timestamp = now / 1000,
            )))
        }
        queueEverywhere(store, document)
    }

    /** New title, author or page count; the percentage follows the page you're on. */
    fun edit(store: Store, document: String, title: String, author: String?, pages: Int?) {
        val b = book(store, document)
        val t = title.trim()
        if (t.isEmpty()) throw ServiceException("A title is needed")
        val p = pages?.takeIf { it > 0 }
        val fraction = if (p != null && b.currentPage != null) (b.currentPage.toDouble() / p).coerceIn(0.0, 1.0) else b.percentage
        store.update { s ->
            s.copy(books = s.books + (document to b.copy(
                title = t,
                author = author?.trim()?.ifEmpty { null },
                pageCount = p,
                percentage = fraction,
                currentPage = b.currentPage?.let { cp -> if (p != null) cp.coerceAtMost(p) else cp },
            )))
        }
        if (fraction != b.percentage) queueEverywhere(store, document)
    }

    /** Removes the book from this app. Nothing changes on the trackers. */
    fun remove(store: Store, document: String) {
        val b = book(store, document)
        store.update { s ->
            s.copy(
                books = s.books - document,
                links = s.links.filterValues { it.document != document },
                pending = s.pending.filterValues { it.document != document },
                removedBooks = s.removedBooks + (document to b.percentage),
            )
        }
    }
}

/**
 * A book link shared into the app (from Goodreads, Hardcover, StoryGraph, Amazon…): the words
 * to search for, from the link's own title slug when it has one, else the shared text.
 */
object ShareLinks {
    fun query(shared: String): String? {
        val url = Regex("https?://\\S+").find(shared)?.value
        val rest = shared.replace(Regex("https?://\\S+"), " ")
            .replace(Regex("(?i)\\b(check out|on goodreads|on storygraph|on the storygraph|on hardcover|via)\\b"), " ")
            .replace(Regex("[\"“”|]+"), " ").replace(Regex("\\s+"), " ").trim()
        val slug = url?.let { u ->
            val path = u.substringAfter("://").substringAfter('/').substringBefore('?').substringBefore('#')
            when {
                // goodreads.com/book/show/12345.The_Name_of_the_Wind or /12345-the-name-of-the-wind
                u.contains("goodreads.com") -> Regex("book/show/\\d+[.-]([^/]+)").find(path)?.groupValues?.get(1)
                // hardcover.app/books/the-name-of-the-wind
                u.contains("hardcover.app") -> Regex("books/([^/]+)").find(path)?.groupValues?.get(1)
                // amazon.*/The-Name-Wind-Kingkiller-Chronicle/dp/0756404746
                u.contains("amazon.") -> Regex("^([^/]+)/dp/").find(path)?.groupValues?.get(1)
                else -> null
            }
        }?.replace(Regex("[_-]+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
        return slug ?: rest.takeIf { it.length >= 2 }
    }
}

/** Book barcodes: an EAN-13 starting 978/979 is an ISBN-13; a valid 10-digit code is an ISBN-10. */
object Isbn {
    fun fromBarcode(raw: String): String? {
        val d = raw.filter { it.isDigit() || it == 'X' || it == 'x' }.uppercase()
        return when {
            d.length == 13 && (d.startsWith("978") || d.startsWith("979")) && valid13(d) -> d
            d.length == 10 && valid10(d) -> d
            else -> null
        }
    }

    private fun valid13(d: String): Boolean {
        if (!d.all { it.isDigit() }) return false
        val sum = d.take(12).mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return (10 - sum % 10) % 10 == d[12] - '0'
    }

    private fun valid10(d: String): Boolean {
        if (!d.take(9).all { it.isDigit() }) return false
        val check = if (d[9] == 'X') 10 else if (d[9].isDigit()) d[9] - '0' else return false
        val sum = d.take(9).mapIndexed { i, c -> (c - '0') * (10 - i) }.sum() + check
        return sum % 11 == 0
    }

    /** ISBN-10 → ISBN-13, so both trackers' searches can be tried. */
    fun to13(isbn10: String): String {
        val core = "978" + isbn10.take(9)
        val sum = core.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return core + ((10 - sum % 10) % 10)
    }
}

/**
 * Finds the page number in the text read off a photo of a page. Page numbers sit alone near
 * the top or bottom edge ("142", "— 142 —", "Page 142"), or at the end of a running head
 * ("142  THE NAME OF THE WIND"). Numbers in the body (footnotes, years) are pushed down, and
 * so are numbers that don't fit the book or the page you were on.
 */
object PageReader {
    /** One line of recognised text, with its box as fractions of the photo (0 top/left, 1 bottom/right). */
    data class Line(val text: String, val top: Float, val bottom: Float, val left: Float = 0f, val right: Float = 1f)

    /** The likely page, whether it's clear enough to just confirm, and other numbers seen. */
    data class Guess(val page: Int, val confident: Boolean, val others: List<Int>)

    private val alone = Regex("^[^\\p{L}\\d]{0,4}(?:p(?:age|g)?\\.?\\s*)?(\\d{1,4})[^\\p{L}\\d]{0,4}$", RegexOption.IGNORE_CASE)
    private val leading = Regex("^(\\d{1,4})\\s*[|·•—–-]?\\s+\\p{L}.{0,80}$")
    private val trailing = Regex("^\\p{L}.{0,80}?\\s+[|·•—–-]?\\s*(\\d{1,4})$")

    fun guess(lines: List<Line>, pageCount: Int?, lastPage: Int?): Guess? {
        data class Cand(val page: Int, val score: Float)
        val cands = ArrayList<Cand>()
        for (l in lines) {
            val text = l.text.trim()
            if (text.isEmpty()) continue
            val edge = minOf(l.top, 1f - l.bottom).coerceAtLeast(0f)
            val near = (1f - edge / 0.18f).coerceIn(0f, 1f) // 1 at the very edge, 0 from 18% in
            val (page, form) = alone.find(text)?.let { it.groupValues[1].toInt() to 0.6f }
                ?: leading.find(text)?.let { it.groupValues[1].toInt() to 0.3f }
                ?: trailing.find(text)?.let { it.groupValues[1].toInt() to 0.3f }
                ?: continue
            if (page <= 0) continue
            var score = near + form
            if (edge > 0.25f) score -= 0.8f // in the body: a footnote mark, a year, a list
            if (pageCount != null && page > pageCount + 10) continue
            if (lastPage != null) {
                when {
                    page < lastPage - 20 -> score -= 0.7f
                    page <= lastPage + 250 -> score += 0.3f
                    else -> score -= 0.2f
                }
            }
            cands.add(Cand(page, score))
        }
        if (cands.isEmpty()) return null
        val best = cands.groupBy { it.page }.map { (p, cs) -> Cand(p, cs.maxOf { it.score }) }.sortedByDescending { it.score }
        val top = best[0]
        val runner = best.getOrNull(1)?.score ?: Float.NEGATIVE_INFINITY
        return Guess(
            page = top.page,
            confident = top.score >= 1.0f && top.score - runner >= 0.3f,
            others = best.drop(1).filter { it.score > 0f }.map { it.page }.take(4),
        )
    }
}

/**
 * Setting a tracked book's progress by hand (a CrossPoint or Seekquel book read elsewhere for a
 * while, or a position a source got wrong). It goes to your trackers right away and wins over
 * CrossPoint, KOSync and Seekquel until one of them reports reading done after it.
 */
object ProgressOverride {
    /**
     * [page] (with the book's page count) or [percent]; [status] as chosen, else Finished from
     * 98% and Reading below it (Paused and DNF stay as they are).
     */
    fun set(
        store: Store, document: String, percent: Int? = null, page: Int? = null, status: ReadStatus? = null,
        now: Long = System.currentTimeMillis(), exact: Double? = null, writeBack: Boolean = false,
    ) {
        val b = store.snapshot.books[document] ?: throw ServiceException("That book isn't in your library any more")
        if (ManualBooks.isManual(b) && exact == null) throw ServiceException("Use the physical book's own progress card")
        val pages = b.pageCount
        val fraction = when {
            exact != null -> exact.coerceIn(0.0, 1.0)
            page != null -> {
                if (pages == null) throw ServiceException("This book has no page count; enter a percentage")
                if (page !in 0..pages) throw ServiceException("Page $page isn't in a $pages-page book")
                page.toDouble() / pages
            }
            percent != null -> {
                if (percent !in 0..100) throw ServiceException("Enter a percentage from 0 to 100")
                percent / 100.0
            }
            status == ReadStatus.FINISHED -> 1.0
            status != null -> b.percentage
            else -> throw ServiceException("Enter a page or a percentage")
        }
        val st = status ?: when {
            fraction >= 0.98 -> ReadStatus.FINISHED
            b.status == ReadStatus.PAUSED || b.status == ReadStatus.DNF -> b.status
            else -> ReadStatus.READING
        }
        val finished = st == ReadStatus.FINISHED
        val sec = now / 1000
        store.update { s ->
            s.copy(
                progressAsks = s.progressAsks - document,
                // Seekquel's position you chose goes back to crosspoint-sync and KOSync (and so to the reader).
                toCrossPoint = if (writeBack && b.origin == null) s.toCrossPoint + document else s.toCrossPoint,
                toKosync = if (writeBack && b.origin == null && s.kosync != null) s.toKosync + document else s.toKosync,
                books = s.books + (document to b.copy(
                percentage = if (finished) 1.0 else fraction,
                currentPage = if (finished) pages ?: page else page ?: pages?.let { Math.round(fraction * it).toInt() },
                status = st,
                // Later than any report already in, so the sources' older positions don't undo it.
                timestamp = maxOf(sec, b.timestamp + 1),
                manualAt = maxOf(sec, b.timestamp + 1),
                startedAt = b.startedAt ?: sec,
                finishedAt = if (finished) b.finishedAt ?: sec else b.finishedAt,
            )))
        }
        for (svc in store.snapshot.services.values.filter { it.linked && it.enabled && it.id != b.origin }) {
            SyncEngine.queue(store, svc.id, listOf(document))
        }
    }


    /**
     * The other source's position for the book, from its card ("Update"): used here, sent to your
     * trackers and back to CrossPoint and KOSync; that source moves up the book's ranking.
     */
    fun acceptSeekquel(store: Store, document: String, now: Long = System.currentTimeMillis()) {
        val ask = store.snapshot.progressAsks[document] ?: throw ServiceException("Nothing to update")
        val status = ask.status?.takeIf { it == ReadStatus.FINISHED || it == ReadStatus.PAUSED || it == ReadStatus.DNF }
        set(store, document, status = status, now = now, exact = ask.fraction, writeBack = true)
        store.update { Primary.promoted(it, document, ask.source) }
    }

    /** "Keep": the position here stays; that source's isn't offered again until it changes. */
    fun denySeekquel(store: Store, document: String) = Primary.keep(store, document)

    /** "Move it back": that source is set to the position here, where it can be written to. */
    fun moveBack(store: Store, document: String) = Primary.moveBack(store, document)

}
