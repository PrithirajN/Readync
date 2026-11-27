package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.data.Book
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.data.Release
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.data.Store
import io.github.prithirajn.readync.trackers.GoodreadsTracker
import io.github.prithirajn.readync.trackers.HardcoverTracker
import io.github.prithirajn.readync.trackers.Session

/**
 * New & upcoming in the series you read: the next book or volume after the one you're on (out
 * now, or coming and when), from Hardcover's and Goodreads' series lists; the newest manga chapter
 * on MangaDex past the one you've read; new chapters of the web novels you follow. Each series is
 * looked at about once a day, a few per sync, within a time limit.
 */
object Releases {
    const val CHECK_EVERY_S = 20 * 3600L
    const val PER_SYNC = 6
    const val BUDGET_MS = 40_000L
    private const val DAY = 86_400L

    /** A series to look into: its name, the furthest number you've reached, its kind, and a book of it (for its Goodreads id). */
    data class Series(val key: String, val name: String, val reached: Double, val kind: String, val book: Book, val author: String?)

    /** "book", "volume" (manga or comic volumes), from the books in your library and what the trackers say of their series. */
    fun candidates(s: Snapshot): List<Series> {
        val set = s.releaseSettings
        val bySeries = LinkedHashMap<String, Series>()
        for (b in s.books.values) {
            if (b.held || b.status == ReadStatus.DNF) continue
            val f = s.facts[b.document] ?: continue
            val name = f.series?.let { MangaSources.seriesTitleOf(it) }?.takeIf { it.isNotBlank() } ?: continue
            val n = f.seriesNumber?.toDoubleOrNull() ?: Matcher.volume(b.title ?: "")?.toDoubleOrNull() ?: continue
            val kind = when {
                (listOf(b.document) + b.aliases).any { it.startsWith("comic:") } -> "comic"
                (listOf(b.document) + b.aliases).any { it.startsWith("manga:") } -> "manga"
                else -> "book"
            }
            if (kind == "book" && !set.novels || kind == "manga" && !set.manga || kind == "comic" && !set.comics) continue
            val counts = set.which == "all" || b.status == ReadStatus.FINISHED || b.status == ReadStatus.READING || b.percent > 0
            if (!counts) continue
            val key = Matcher.baseTitle(name)
            if (key.isBlank() || key in s.releasesHidden) continue
            val had = bySeries[key]
            if (had == null || n > had.reached) bySeries[key] = Series(key, name, n, kind, b, b.author)
        }
        return bySeries.values.toList()
    }

    /** The next books after [reached] (whole numbers, and in-between ones if you want them), from a series list. */
    fun nextOnes(reached: Double, positions: List<Double>, inBetween: Boolean): List<Double> =
        positions.filter { it > reached + 1e-6 && (inBetween || it == Math.floor(it)) }.sorted().take(2)

    /**
     * Looks into a few series that are due, within the time limit, and keeps what it finds. Returns
     * how many new things were found.
     */
    fun check(store: Store, now: Long = System.currentTimeMillis() / 1000): Int {
        // First: what's out of date dropped (and, once, what the old way found), so it's looked for again now.
        tidy(store, now)
        val s0 = store.snapshot
        val set = s0.releaseSettings
        if (!set.enabled) return 0
        val deadline = System.currentTimeMillis() + BUDGET_MS
        var looked = 0
        var found = 0
        val hc = s0.service("hardcover").takeIf { set.hardcover && it.linked && !it.needsReauth }?.let { Session(it.credential) }
        val gr = s0.service("goodreads").takeIf { set.goodreads && it.linked && !it.needsReauth }?.let { Session(it.credential) }
        val due = candidates(s0).filter { now - (s0.releasesChecked["series:" + it.key] ?: 0L) >= CHECK_EVERY_S }
            .sortedByDescending { it.book.timestamp }
        for (c in due) {
            if (looked >= PER_SYNC || System.currentTimeMillis() > deadline) break
            if (hc == null && gr == null) break
            looked++
            val fromGr = gr?.let { session ->
                runCatching {
                    val id = s0.link("goodreads", c.book.document)?.externalId
                        ?: c.book.isbn?.let { GoodreadsTracker.bookIdFor(session, it) }
                        ?: GoodreadsTracker.bookUrlFor(session, c.book.title ?: return@runCatching emptyList(), c.author, c.book.isbn, null)
                            ?.let { Regex("""/book/show/(\d+)""").find(it)?.groupValues?.get(1) }
                        ?: return@runCatching emptyList()
                    val url = GoodreadsTracker.seriesUrlForBook(session, id) ?: return@runCatching emptyList()
                    GoodreadsTracker.seriesBooks(session, url)
                }.getOrNull()
            }.orEmpty()
            // Goodreads first: Hardcover's series lists and days are often wrong or missing, so it's
            // asked only when Goodreads has nothing for the series (or isn't linked).
            val fromHc = if (fromGr.isNotEmpty()) emptyList() else hc?.let { runCatching { HardcoverTracker.seriesBooks(it, c.name) }.getOrNull() }.orEmpty()
            val positions = (fromGr.map { it.position } + fromHc.map { it.position }).distinct()
            for (p in nextOnes(c.reached, positions, set.inBetween)) {
                val h = fromHc.firstOrNull { it.position == p }
                val g = fromGr.firstOrNull { it.position == p }
                var date = g?.date
                // Goodreads' list may not say when: the book's own page does (the day it shows).
                if (date == null && g != null && gr != null && System.currentTimeMillis() < deadline) date = runCatching { GoodreadsTracker.releaseDay(gr, g.id) }.getOrNull()
                if (date == null) date = h?.releaseDate
                if (date != null && date > now + set.aheadMonths * 31 * DAY) continue // further off than you want to see
                val label = ComicIssues.label(p)
                // In the language you read the series in: a translation's title (another alphabet, or
                // Spanish where yours is English) gives way to the other source's, else isn't news.
                val yours = c.book.title
                // A stand-in title ("The Devils book 3") gives way to a real one from the other source too.
                val gTitle = g?.title?.takeIf { !Matcher.otherLanguage(it, yours) }
                val hTitle = h?.title?.takeIf { !Matcher.otherLanguage(it, yours) }
                val title = gTitle ?: hTitle ?: continue
                // Already in your library: not news. By its number in the series too, so an edition under
                // another title (or in another language) of a book you have isn't listed as new.
                if (haveNumber(s0, c.key, p)) continue
                if (s0.books.values.any { b -> owned(b) && b.title != null && (Matcher.sameBook(title, c.author, b.title, b.author) || (Matcher.baseTitle(MangaSources.seriesTitleOf(b.title)) == c.key && Matcher.volume(b.title) == label)) }) continue
                val key = "${c.key}|$label"
                val r = Release(
                    key = key, kind = if (c.kind == "book") "book" else "volume", series = c.name, title = title, number = label, date = date,
                    source = listOfNotNull(g?.let { "Goodreads" }, h?.let { "Hardcover" }).joinToString(" and "),
                    coverUrl = g?.coverUrl ?: h?.coverUrl,
                    url = g?.let { "${GoodreadsTracker.BASE}/book/show/${it.id}" } ?: h?.let { "https://hardcover.app/books/${it.id}" },
                    author = g?.author ?: h?.author ?: c.author, seenAt = now,
                )
                found += keep(store, r)
            }
            store.update { it.copy(releasesChecked = it.releasesChecked + ("series:" + c.key to now)) }
        }
        // Manga chapters: the newest one out past where you are.
        if (set.manga && set.mangaDex) {
            for ((key, m) in s0.mangaMaps) {
                if (System.currentTimeMillis() > deadline) break
                if (m.source != "mangadex" || m.mangadexId.isBlank()) continue
                val name = MangaSources.seriesTitleOf(m.title)
                if (Matcher.baseTitle(name) in s0.releasesHidden) continue
                val read = s0.seriesRead[key] ?: continue
                val furthest = listOfNotNull(read.read.maxOfOrNull { it.second }, read.current).maxOrNull() ?: continue
                if (now - (s0.releasesChecked["md:$key"] ?: 0L) < CHECK_EVERY_S) continue
                val latest = runCatching { io.github.prithirajn.readync.source.MangaDex.latestChapter(m.mangadexId) }.getOrNull()
                store.update { it.copy(releasesChecked = it.releasesChecked + ("md:$key" to now)) }
                if (latest == null || latest.first <= furthest + 1e-6) continue
                val label = ComicIssues.label(latest.first)
                found += keep(store, Release(
                    key = "${Matcher.baseTitle(name)}|ch$label", kind = "chapter", series = name, title = "$name, Chapter $label",
                    number = "Chapter $label", date = latest.second, source = "MangaDex", url = "https://mangadex.org/title/${m.mangadexId}", seenAt = now,
                ))
            }
        }
        found += checkAuthors(store, now, deadline)
        found += checkMetron(store, now)
        checkSff(store, now)
        enrichSff(store, now, deadline)
        tidy(store)
        watchOnRelease(store, now)
        return found
    }

    /** Authors are looked at about once a week, a few per sync. */
    const val AUTHOR_EVERY_S = 7 * DAY
    const val AUTHORS_PER_SYNC = 4

    /**
     * The authors you've finished a book of, the most recently finished first (books from a tracker's
     * history count: you read them, even if they're not sent anywhere).
     */
    fun readAuthors(s: Snapshot): List<String> =
        s.books.values.filter { it.status == ReadStatus.FINISHED && !it.author.isNullOrBlank() && isNovel(it) }
            .sortedByDescending { it.finishedAt ?: it.timestamp }
            // The first-listed author only: a comic's or an illustrated book's later names are its artists.
            .map { b -> b.author!!.split(Regex("\\s*(?:,|&|;| and )\\s*")).first().trim() }
            .filter { it.length > 2 }
            .distinctBy { Matcher.baseTitle(it) }

    /**
     * A novel, as far as following its author goes: not a comic or manga (by where it came from, or
     * an issue or volume number in its title), which already have their own volume and chapter news.
     */
    fun isNovel(b: Book): Boolean {
        val ids = listOf(b.document) + b.aliases
        if (ids.any { it.startsWith("comic:") || it.startsWith("manga:") }) return false
        return !singleIssue(b.title.orEmpty())
    }

    /** "Guardians of the Galaxy #1", "Dandadan, Vol. 9", "Issue 4": one part of a comic or manga run. */
    fun singleIssue(title: String): Boolean =
        Regex("""(?i)#\s*\d|\bvol(?:ume)?\.?\s*\d|\bissue\s*\d|\bchapter\s*\d""").containsMatchIn(title)

    /**
     * New books by authors you've read: anything they have coming (or out in the last fortnight)
     * that isn't in your library and isn't already news as the next book of a series you follow.
     * From each author's Goodreads list (newest first), with the day from the book's own page;
     * Hardcover only when Goodreads isn't linked. Returns how many were new.
     */
    private fun checkAuthors(store: Store, now: Long, deadline: Long): Int {
        val s0 = store.snapshot
        val set = s0.releaseSettings
        if (!set.authors) return 0
        val gr = s0.service("goodreads").takeIf { set.goodreads && it.linked && !it.needsReauth }?.let { Session(it.credential) }
        val hc = if (gr != null) null else s0.service("hardcover").takeIf { set.hardcover && it.linked && !it.needsReauth }?.let { Session(it.credential) }
        if (gr == null && hc == null) return 0
        val followed = candidates(s0).map { it.key }.toSet()
        val thisYear = java.time.LocalDate.now().year
        var looked = 0
        var found = 0
        for (author in readAuthors(s0)) {
            if (looked >= AUTHORS_PER_SYNC || System.currentTimeMillis() > deadline) break
            val base = Matcher.baseTitle(author)
            if (base in s0.releasesHidden) continue
            if (now - (s0.releasesChecked["author:$base"] ?: 0L) < AUTHOR_EVERY_S) continue
            looked++
            val books: List<Found> = if (gr != null) fromGoodreads(store, gr, author, base, thisYear, deadline) else fromHardcover(hc!!, author)
            store.update { it.copy(releasesChecked = it.releasesChecked + ("author:$base" to now)) }
            for (b in books) {
                val date = b.date ?: continue // undated: too uncertain to be news
                if (date > now + set.aheadMonths * 31 * DAY || date < now - 14 * DAY) continue
                if (Release.placeholder(b.title)) continue
                // The next book of a series you follow is already news, under that series.
                val seriesKey = b.series?.let { Matcher.baseTitle(MangaSources.seriesTitleOf(it)) }
                if (seriesKey != null && (seriesKey in followed || seriesKey in s0.releasesHidden)) continue
                val st = store.snapshot
                if (st.books.values.any { owned(it) && it.title != null && Matcher.sameBook(b.title, author, it.title, it.author) }) continue
                if (st.releases.values.any { it.kind != "author" && Matcher.sameBook(b.title, author, it.title, it.author) }) continue
                found += keep(store, Release(
                    key = "author:$base|${Matcher.baseTitle(b.title)}", kind = "author", series = author, title = b.title,
                    number = b.position?.let { p -> ComicIssues.label(p) }?.let { n -> b.series?.let { "$it #$n" } } ?: b.series,
                    date = date, source = b.source, coverUrl = b.cover, url = b.url, author = author, seenAt = now,
                ))
            }
        }
        return found
    }

    /** An author's book as either source gives it. */
    private data class Found(val title: String, val series: String?, val position: Double?, val date: Long?, val cover: String?, val url: String, val source: String)

    /**
     * An author's recent and coming books from Goodreads. The author is found from a book of theirs
     * you've finished (its Goodreads link, else a search), and kept. The list says only the year, so
     * the day comes from each book's own page, for books from this year on (a few per author).
     */
    private fun fromGoodreads(store: Store, gr: Session, author: String, base: String, thisYear: Int, deadline: Long): List<Found> {
        val s = store.snapshot
        val id = s.extras["gr.author.$base"] ?: run {
            val book = s.books.values.filter { it.status == ReadStatus.FINISHED && it.author?.let { a -> Matcher.baseTitle(a).contains(base) } == true }
                .maxByOrNull { it.finishedAt ?: it.timestamp } ?: return emptyList()
            val bookId = s.link("goodreads", book.document)?.externalId
                ?: runCatching { GoodreadsTracker.bookUrlFor(gr, book.title ?: return emptyList(), author, book.isbn, null) }.getOrNull()
                    ?.let { Regex("""/book/show/(\d+)""").find(it)?.groupValues?.get(1) }
                ?: return emptyList()
            val found = runCatching { GoodreadsTracker.authorIdForBook(gr, bookId) }.getOrNull() ?: return emptyList()
            store.update { it.copy(extras = it.extras + ("gr.author.$base" to found)) }
            found
        }
        val list = runCatching { GoodreadsTracker.authorBooks(gr, id) }.getOrNull().orEmpty()
        // Novels only: a comic writer's list is mostly single issues and collected volumes.
        val recent = list.filter { ((it.year ?: 0) >= thisYear || it.expected) && !singleIssue(it.title) }.take(6)
        return recent.mapNotNull { b ->
            if (System.currentTimeMillis() > deadline) return@mapNotNull null
            val day = runCatching { GoodreadsTracker.releaseDay(gr, b.id) }.getOrNull()
            Found(b.title, b.series, b.position, day, b.coverUrl, "${GoodreadsTracker.BASE}/book/show/${b.id}", "Goodreads")
        }
    }

    private fun fromHardcover(hc: Session, author: String): List<Found> {
        val fromDay = java.time.LocalDate.now().minusDays(14).toString()
        return runCatching { HardcoverTracker.authorBooks(hc, author, fromDay) }.getOrNull().orEmpty().map { b ->
            Found(b.title, b.series, b.position, b.releaseDate, b.coverUrl, "https://hardcover.app/books/${b.id}", "Hardcover")
        }
    }

    /**
     * A book of yours as far as release news goes: one you've started, finished or put down. A book
     * only on your Want to Read (not started) doesn't stop the news; it's how you say you want it.
     */
    fun owned(b: Book): Boolean = b.status != ReadStatus.TO_READ || b.percentage > 0.0

    /**
     * On its release day, a book's price is tracked on Deals (when that's on): the first good drop is
     * then news too. Only books out in the last fortnight, so turning it on doesn't fill Deals with old ones.
     */
    fun watchOnRelease(store: Store, now: Long = System.currentTimeMillis() / 1000): Int {
        val s0 = store.snapshot
        if (!s0.releaseSettings.let { it.enabled && it.watchPrices }) return 0
        val due = s0.releases.values.filter { r ->
            !r.skipped && r.priceWatch == null && (r.kind == "book" || r.kind == "volume" || r.kind == "author") &&
                r.isOut(now) && (r.date ?: r.seenAt) >= now - 14 * DAY && !Release.placeholder(r.title)
        }
        if (due.isEmpty()) return 0
        store.update { st -> due.fold(st) { acc, r -> watchPrice(acc, r, now).first } }
        return due.size
    }

    /** Reactor's monthly lists are read about twice a day. */
    const val SFF_EVERY_S = 12 * 3600L

    /**
     * Coming in SFF: every book in Reactor's monthly lists (read about twice a day) and the books
     * announced in the news and on socials (from what the News tab already has, every sync), from a
     * fortnight ago to as far ahead as you want to see. Not a book you've started, and not one that's
     * already news as yours (the next in a series, an author you've read). Returns how many were new.
     */
    fun checkSff(store: Store, now: Long = System.currentTimeMillis() / 1000): Int {
        val s0 = store.snapshot
        val set = s0.releaseSettings
        if (!set.enabled || !set.sff) return 0
        val books = ArrayList<io.github.prithirajn.readync.source.UpcomingSff.Book>()
        if (now - (s0.releasesChecked["sff:reactor"] ?: 0L) >= SFF_EVERY_S) {
            val fromReactor = runCatching { io.github.prithirajn.readync.source.UpcomingSff.fetchReactor() }.getOrNull()
            if (fromReactor != null) {
                books += fromReactor
                store.update { it.copy(releasesChecked = it.releasesChecked + ("sff:reactor" to now)) }
            }
        }
        // Upcoming LitRPG from its publishers (Reactor lists traditional SFF), as often as Reactor.
        if (now - (s0.releasesChecked["sff:litrpg"] ?: 0L) >= SFF_EVERY_S) {
            val lit = runCatching { io.github.prithirajn.readync.source.UpcomingSff.fetchLitRpg(now) }.getOrNull()
            if (!lit.isNullOrEmpty()) {
                books += lit
                store.update { it.copy(releasesChecked = it.releasesChecked + ("sff:litrpg" to now)) }
            }
        }
        // Upcoming manga (VIZ, Seven Seas) and comics (Marvel, Dark Horse), as often.
        for ((key, fetch) in listOf<Pair<String, () -> List<io.github.prithirajn.readync.source.UpcomingSff.Book>>>(
            "sff:manga" to { io.github.prithirajn.readync.source.UpcomingSff.fetchManga(now) },
            "sff:comics" to {
                io.github.prithirajn.readync.source.UpcomingSff.fetchComics(now) +
                    s0.metronToken?.let { t -> runCatching { io.github.prithirajn.readync.source.UpcomingSff.fetchMetronFirsts(t, now) }.getOrDefault(emptyList()) }.orEmpty()
            },
        )) {
            if (now - (s0.releasesChecked[key] ?: 0L) < SFF_EVERY_S) continue
            val got = runCatching { fetch() }.getOrNull()
            if (!got.isNullOrEmpty()) {
                books += got
                store.update { it.copy(releasesChecked = it.releasesChecked + (key to now)) }
            }
        }
        books += runCatching { io.github.prithirajn.readync.source.UpcomingSff.fromNews(s0.sffItems, now) }.getOrDefault(emptyList())
        if (books.isEmpty()) return 0
        var found = 0
        // All in one change (a month's lists run to a few hundred books): one save, not hundreds.
        store.update { st ->
            // Indexed by title, so a few hundred books aren't each compared with the whole library.
            val mine = st.books.values.filter { owned(it) && it.title != null }.groupBy { Matcher.baseTitle(it.title!!) }
            val yours = st.releases.values.filter { it.kind != "sff" }.groupBy { Matcher.baseTitle(it.title) }
            val yoursBySeries = st.releases.values.filter { it.kind != "sff" }.groupBy { seriesKey(it.series, it.number) }
            val releases = collapseSff(LinkedHashMap(st.releases))
            // Coming in SFF books by title, so one book listed twice (in two genres' lists, or in a list
            // and the news, its author written two ways) is kept once, under the key it already has.
            val sffByBase = releases.values.filter { it.kind == "sff" }.groupByTo(HashMap()) { Matcher.baseTitle(it.title) }
            val sffBySeries = releases.values.filter { it.kind == "sff" }.mapNotNull { r -> seriesKeys(r).map { it to r } }.flatten()
                .groupByTo(HashMap(), { it.first }, { it.second })
            for (b in books) {
                if (b.day > now + set.aheadMonths * 31 * DAY || b.day < now - 14 * DAY) continue
                val authorBase = b.author?.let { Matcher.baseTitle(it) }
                if (authorBase != null && authorBase in st.releasesHidden) continue
                val base = Matcher.baseTitle(b.title)
                if (mine[base].orEmpty().any { Matcher.sameBook(b.title, b.author, it.title!!, it.author) }) continue
                if (yours[base].orEmpty().any { Matcher.sameBook(b.title, b.author, it.title, it.author) }) continue
                val sk = seriesKey(b.series, b.number)
                if (sk != null && yoursBySeries[sk].orEmpty().isNotEmpty()) continue
                // The same book from another source: by title and author, link, Kindle id, or series and number.
                val same = (sffByBase[base].orEmpty() + (sk?.let { sffBySeries[it] }.orEmpty())).firstOrNull { sameSff(b.title, b.author, b.series, b.number, b.url, it, b.day) }
                val key = same?.key ?: "sff:$base|${authorBase ?: ""}"
                val had = releases[key]
                // Reactor's copy wins over a news mention of the same book (it has the genre and the blurb).
                if (had != null && had.genre != null && b.genre == null) continue
                if (had == null) found++
                releases[key] = merged(had, Release(
                    key = key, kind = "sff", series = b.series ?: "", title = b.title, number = b.number, date = b.day,
                    source = listOfNotNull(b.source, b.publisher).joinToString(" · "), coverUrl = b.cover ?: had?.coverUrl, url = b.url,
                    author = b.author, seenAt = now, genre = b.genre ?: had?.genre, blurb = b.blurb ?: had?.blurb,
                    facts = b.translators.takeIf { it.isNotEmpty() }?.let { io.github.prithirajn.readync.data.ReleaseFacts(translators = it) },
                ), now)
                if (same == null) {
                    val saved = releases.getValue(key)
                    sffByBase.getOrPut(base) { ArrayList() } += saved
                    for (k in seriesKeys(saved)) sffBySeries.getOrPut(k) { ArrayList() } += saved
                }
            }
            st.copy(releases = releases)
        }
        return found
    }

    /**
     * One Coming in SFF book saved twice (before 4.12.7 a book in two genres' lists, or with its
     * author written two ways, got two keys and showed twice on a ledge) folded into one: the copy
     * with a genre, Goodreads facts and a cover is kept; skipped, seen and a price watch carry over.
     */
    internal fun collapseSff(releases: LinkedHashMap<String, Release>): LinkedHashMap<String, Release> {
        // Grouped by title, and by series and number (a list's "Hell Difficulty Tutorial 9: A LitRPG
        // Adventure" and a publisher's "Hell Difficulty Tutorial 9" share no title, but are #9 of one series).
        val sff = releases.values.filter { it.kind == "sff" }
        val groups = (sff.groupBy { Matcher.baseTitle(it.title) }.values +
            sff.flatMap { r -> seriesKeys(r).map { it to r } }.groupBy({ it.first }, { it.second }).values).filter { it.size > 1 }
        for (g in groups) {
            val left = g.toMutableList()
            while (left.isNotEmpty()) {
                val first = left.removeAt(0)
                if (first.key !in releases) continue
                val twins = left.filter { it.key in releases && sameSff(first.title, first.author, first.series, first.number, first.url, it, first.date) }
                if (twins.isEmpty()) continue
                left -= twins.toSet()
                val all = listOf(first) + twins
                val keep = all.maxBy { (if (it.genre != null) 4 else 0) + (if ((it.facts?.checkedAt ?: 0L) > 0) 2 else 0) + (if (it.coverUrl != null) 1 else 0) }
                for (r in all) if (r.key != keep.key) releases.remove(r.key)
                releases[keep.key] = keep.copy(
                    skipped = all.any { it.skipped }, seen = all.any { it.seen },
                    priceWatch = keep.priceWatch ?: all.firstNotNullOfOrNull { it.priceWatch },
                    seenAt = all.minOf { it.seenAt }, coverUrl = keep.coverUrl ?: all.firstNotNullOfOrNull { it.coverUrl },
                    facts = keep.facts ?: all.firstNotNullOfOrNull { it.facts },
                )
            }
        }
        return releases
    }

    /** "hell difficulty tutorial|9": a series and a whole number, for telling one book from two sources apart. */
    internal fun seriesKey(series: String?, number: String?): String? {
        val sr = series?.let { Matcher.baseTitle(it) }?.takeIf { it.isNotBlank() } ?: return null
        val n = number?.trim()?.toDoubleOrNull() ?: return null
        return "$sr|${if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()}"
    }

    /** Every series-and-number a saved SFF release goes by (its own, and Goodreads'). */
    internal fun seriesKeys(r: Release): List<String> =
        listOfNotNull(seriesKey(r.series, r.number), seriesKey(r.facts?.series, r.facts?.position)).distinct()

    /**
     * One book seen twice: the same title and author, the same link or Kindle id, or the same number in
     * the same series (with authors that don't disagree, and days within a month of each other, so a
     * reissue or a second printing years later stays apart).
     */
    internal fun sameSff(title: String, author: String?, series: String?, number: String?, url: String?, r: Release, day: Long? = null): Boolean {
        if (Matcher.sameBook(title, author, r.title, r.author)) return true
        if (url != null && url == r.url) return true
        val id = io.github.prithirajn.readync.source.UpcomingSff.amazonId(url)
        if (id != null && id == io.github.prithirajn.readync.source.UpcomingSff.amazonId(r.url)) return true
        val k = seriesKey(series, number) ?: return false
        if (k !in seriesKeys(r)) return false
        if (day != null && r.date != null && kotlin.math.abs(day - r.date) > 31 * DAY) return false
        val wa = author?.let { Matcher.normalizeAuthor(it) }.orEmpty()
        val wb = r.author?.let { Matcher.normalizeAuthor(it) }.orEmpty()
        return wa.isEmpty() || wb.isEmpty() || Matcher.overlap(wa, wb) > 0.0
    }

    /** Goodreads is asked about this many Coming in SFF books a sync (the nearest to their day first). */
    const val ENRICH_PER_SYNC = 24

    /**
     * Coming in SFF books (and your own releases) filled in from Goodreads (series or standalone, rating, pages, first
     * published, genres, translators): those not asked about yet, in the genres you show, the ones
     * nearest their day first; one it didn't have is asked again after a fortnight. All saved in one go.
     */
    fun enrichSff(store: Store, now: Long = System.currentTimeMillis() / 1000, deadline: Long = System.currentTimeMillis() + 30_000L): Int {
        val s0 = store.snapshot
        val set = s0.releaseSettings
        if (!set.enabled) return 0
        // Your own books (series, authors) too, for the waiting room's rating and pages.
        val due = s0.releases.values.filter { r ->
            !r.skipped && (
                (set.sff && r.kind == "sff" && r.genre != io.github.prithirajn.readync.source.UpcomingSff.COMICS &&
                    (r.genre ?: io.github.prithirajn.readync.source.UpcomingSff.NEWS) in set.sffGenres) ||
                    ((r.kind == "book" || r.kind == "volume" || r.kind == "author") && !r.announcedOnly)
                ) &&
                r.facts.let { f -> f == null || f.checkedAt == 0L || (f.missing && now - f.checkedAt > 14 * DAY) }
        }.sortedBy { kotlin.math.abs((it.date ?: it.seenAt) - now) }.take(ENRICH_PER_SYNC)
        val got = HashMap<String, io.github.prithirajn.readync.data.ReleaseFacts>()
        for (r in due) {
            if (System.currentTimeMillis() > deadline) break
            factsFor(r, now)?.let { got[r.key] = it }
            Thread.sleep(250)
        }
        if (got.isNotEmpty()) store.update { st ->
            st.copy(releases = st.releases + got.mapNotNull { (k, f) -> st.releases[k]?.let { k to it.copy(facts = f) } })
        }
        return got.size
    }

    /** One release's facts from Goodreads, keeping the translators a list named when Goodreads names none. Blocking. */
    fun factsFor(r: Release, now: Long = System.currentTimeMillis() / 1000): io.github.prithirajn.readync.data.ReleaseFacts? {
        // A release found on Goodreads already has its page.
        r.url?.takeIf { it.contains("goodreads.com/book/show/") }?.let { u ->
            io.github.prithirajn.readync.source.UpcomingSff.goodreadsPage(u, now)?.let { return it }
        }
        val id = io.github.prithirajn.readync.source.UpcomingSff.amazonId(r.url)
        val f = runCatching { io.github.prithirajn.readync.source.UpcomingSff.goodreadsFacts(id, r.title, r.author, now) }.getOrNull() ?: return null
        return if (f.translators.isEmpty() && !r.facts?.translators.isNullOrEmpty()) f.copy(translators = r.facts!!.translators) else f
    }

    /**
     * [s] with [r]'s price watched on Deals (one already there for the same book is used), and the
     * watch's key. The release remembers it, so the button says "Price watched".
     */
    fun watchPrice(s: Snapshot, r: Release, now: Long = System.currentTimeMillis() / 1000): Pair<Snapshot, String> {
        val lib = s.books.values.firstOrNull { it.title != null && Matcher.sameBook(r.title, r.author, it.title, it.author) }
        val key = lib?.document?.takeIf { it in s.priceWatch }
            ?: s.priceWatch.values.firstOrNull { w -> w.title != null && Matcher.sameBook(r.title, r.author, w.title, w.author) }?.document
            ?: lib?.document
            ?: ("price:" + java.util.UUID.randomUUID().toString())
        val watch = s.priceWatch[key] ?: io.github.prithirajn.readync.data.PriceWatch(
            key, since = now, title = lib?.displayTitle ?: r.title, author = lib?.author ?: r.author, isbn = lib?.isbn, cover = lib?.coverUrl ?: r.coverUrl,
        )
        val rel = s.releases[r.key]?.copy(priceWatch = key)
        return s.copy(priceWatch = s.priceWatch + (key to watch), releases = if (rel != null) s.releases + (r.key to rel) else s.releases) to key
    }

    /** New chapters of a web novel you follow, seen when its page was looked at again. */
    fun webNovelGrew(store: Store, document: String, title: String, from: Int, to: Int, url: String, site: String, latest: String? = null) {
        if (!store.snapshot.releaseSettings.let { it.enabled && it.webNovels } || to <= from) return
        val now = System.currentTimeMillis() / 1000
        // Chapters seen earlier and still listed fold into this one ("3 new chapters", not three rows).
        val base = Matcher.baseTitle(title)
        val earlier = store.snapshot.releases.filter { (k, r) -> r.kind == "webnovel" && k.startsWith("$base|web") && k != "$base|web$to" }
        var start = from
        for ((k, r) in earlier) {
            val upTo = k.substringAfter("|web").toIntOrNull() ?: continue
            val n = Regex("^(\\d+) new chapter").find(r.title)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            start = minOf(start, upTo - n)
        }
        if (earlier.isNotEmpty()) store.update { it.copy(releases = it.releases - earlier.keys) }
        val count = to - start
        keep(store, Release(
            key = "$base|web$to", kind = "webnovel", series = title,
            title = "$count new chapter${if (count == 1) "" else "s"} of $title", number = "Chapter $to", date = now,
            // The newest chapter's name, shown under it (and in the alert).
            source = site, url = url, seenAt = now, author = latest?.let { "Latest: $it" },
        ))
    }

    /**
     * The next published book of a web novel you follow (a Royal Road story out as books): news under
     * New & upcoming like the next book of any series, kept under the same key so it isn't listed twice.
     */
    fun publishedNext(store: Store, series: String, title: String, number: String?, date: Long?, url: String?, cover: String?, author: String?) {
        if (!store.snapshot.releaseSettings.let { it.enabled && it.novels }) return
        val now = System.currentTimeMillis() / 1000
        keep(store, Release(
            key = "${Matcher.baseTitle(series)}|${number ?: title}", kind = "book", series = series, title = title, number = number, date = date,
            source = "Hardcover", coverUrl = cover, url = url, author = author, seenAt = now,
        ))
    }

    /**
     * Drops what's no longer news: a book now in your library, chapters you've read since (or seen two
     * months ago), and a series you've hidden.
     */
    /** Whether a book of the series [key] numbered [n] is in your library (by its series details). */
    private fun haveNumber(s: io.github.prithirajn.readync.data.Snapshot, key: String, n: Double): Boolean =
        s.books.values.any { b ->
            if (!owned(b)) return@any false
            val f = s.facts[b.document] ?: return@any false
            f.series?.let { Matcher.baseTitle(MangaSources.seriesTitleOf(it)) } == key && f.seriesNumber?.toDoubleOrNull() == n
        }

    /** Version of how books are picked; ones found by an older way are looked for again (translations, stand-in titles). */
    private const val PICK_VERSION = "2"

    fun tidy(store: Store, now: Long = System.currentTimeMillis() / 1000) = store.update { st0 ->
        // Found by the old way (which could take a translation): dropped, and their series looked up again soon.
        val st = if (st0.extras["releasesPick"] == PICK_VERSION) st0 else st0.copy(
            releases = st0.releases.filterValues { it.kind != "book" && it.kind != "volume" },
            releasesChecked = st0.releasesChecked.filterKeys { !it.startsWith("series:") },
            extras = st0.extras + ("releasesPick" to PICK_VERSION),
        )
        // Your started books by title, for the few hundred Coming in SFF books.
        val ownedByTitle by lazy { st.books.values.filter { owned(it) && it.title != null }.groupBy { Matcher.baseTitle(it.title!!) } }
        val kept = st.releases.filterValues { r ->
            val base = Matcher.baseTitle(r.series)
            if (base in st.releasesHidden) return@filterValues false
            // "Not this one": kept a while so it isn't found again, then let go.
            if (r.skipped) return@filterValues r.seenAt >= now - 400 * DAY
            // Coming in SFF: until a month after it's out, unless its author is hidden or you've started it.
            if (r.kind == "sff") return@filterValues (r.date ?: r.seenAt) >= now - 30 * DAY &&
                r.author?.let { Matcher.baseTitle(it) in st.releasesHidden } != true &&
                ownedByTitle[Matcher.baseTitle(r.title)].orEmpty().none { b -> Matcher.sameBook(r.title, r.author, b.title!!, b.author) }
            if ((r.kind == "book" || r.kind == "volume") && r.number?.toDoubleOrNull()?.let { haveNumber(st, base, it) } == true) return@filterValues false
            // Found before this check: one in another language than the series as you read it.
            if (r.kind == "book" || r.kind == "volume") {
                val yours = st.books.values.firstOrNull { b -> st.facts[b.document]?.series?.let { Matcher.baseTitle(MangaSources.seriesTitleOf(it)) } == base }?.title
                if (Matcher.otherLanguage(r.title, yours)) return@filterValues false
            }
            // An issue from your Metron pull list: until two months after it's out.
            if (r.key.startsWith("metron:")) return@filterValues (r.date ?: r.seenAt) >= now - 60 * DAY
            // A chapter's news goes stale; the next book stays until you have it (or hide the series).
            if ((r.kind == "chapter" || r.kind == "webnovel") && r.seenAt < now - 60 * DAY) return@filterValues false
            when (r.kind) {
                "book", "volume" -> st.books.values.none { b ->
                    owned(b) && b.title != null && (Matcher.sameBook(r.title, r.author, b.title, b.author) ||
                        (Matcher.baseTitle(MangaSources.seriesTitleOf(b.title)) == base && Matcher.volume(b.title) == r.number))
                }
                // An author's new book: news until you've started it, and for a year after it's out.
                "author" -> (r.date ?: r.seenAt) >= now - 365 * DAY &&
                    st.books.values.none { b -> owned(b) && b.title != null && Matcher.sameBook(r.title, r.author, b.title, b.author) }
                "chapter" -> {
                    val n = r.number?.substringAfter("Chapter ")?.toDoubleOrNull()
                    val read = st.mangaMaps.entries.firstOrNull { Matcher.baseTitle(MangaSources.seriesTitleOf(it.value.title)) == base }?.key?.let { st.seriesRead[it] }
                    val furthest = read?.let { listOfNotNull(it.read.maxOfOrNull { p -> p.second }, it.current).maxOrNull() }
                    n == null || furthest == null || n > furthest + 1e-6
                }
                else -> true
            }
        }
        if (kept.size == st.releases.size) st else st.copy(releases = kept)
    }

    /**
     * Your Metron pull list: each series' issues from a fortnight ago to four months on, as your own
     * releases (counted down, notified on the day like your other series). Twice a day.
     */
    fun checkMetron(store: Store, now: Long = System.currentTimeMillis() / 1000): Int {
        val s0 = store.snapshot
        val token = s0.metronToken ?: return 0
        if (!s0.releaseSettings.enabled || !s0.releaseSettings.comics) return 0
        if (now - (s0.releasesChecked["metron:pull"] ?: 0L) < SFF_EVERY_S) return 0
        val issues = try {
            io.github.prithirajn.readync.source.UpcomingSff.fetchMetronPullList(token, now)
        } catch (e: io.github.prithirajn.readync.source.UpcomingSff.MetronRefused) {
            store.update { it.copy(extras = it.extras + ("metron.refused" to now.toString())) }
            return 0
        } catch (e: Exception) { return 0 }
        store.update { it.copy(releasesChecked = it.releasesChecked + ("metron:pull" to now), extras = it.extras - "metron.refused") }
        var found = 0
        for (b in issues) {
            val id = b.url?.let { Regex("/issue/(\\d+)/").find(it)?.groupValues?.get(1) } ?: continue
            found += keep(store, Release(
                key = "metron:$id", kind = "issue", series = b.series ?: b.title, title = b.title, number = b.number, date = b.day,
                source = listOfNotNull("Metron", b.publisher).joinToString(" · "), coverUrl = b.cover, url = b.url, seenAt = now,
            ))
        }
        return found
    }

    /** Keeps a release (updating its day if it changed); 1 when it's new. */
    private fun keep(store: Store, r: Release): Int {
        var isNew = 0
        val now = System.currentTimeMillis() / 1000
        store.update { st ->
            val had = st.releases[r.key]
            isNew = if (had == null) 1 else 0
            st.copy(releases = st.releases + (r.key to merged(had, r, now)))
        }
        return isNew
    }

    /** [r] as found now, over what was kept of it before ([had]). */
    private fun merged(had: Release?, r: Release, now: Long): Release {
        // Out a while before it was first seen: listed, but no "it's out" alert for old news.
        if (had == null) return if (r.isOut(now) && r.kind != "webnovel" && (r.date == null || r.date < now - 7 * DAY)) r.copy(notified = "out") else r
        // A day that moved (a delay, or sooner) resets the reminder, is remembered to show "moved
        // from …", and is news again; what you were told, skipped and watched is otherwise kept.
        val moved = had.date != null && r.date != null && had.date != r.date
        return r.copy(
            seenAt = had.seenAt, notified = if (moved && had.notified == "soon") null else had.notified,
            movedFrom = if (moved) had.date else had.movedFrom, seen = had.seen && !moved, skipped = had.skipped, priceWatch = had.priceWatch,
            // What Goodreads said is kept across finding it again (a list only knows the translators).
            facts = when {
                had.facts == null -> r.facts
                r.facts == null -> had.facts
                had.facts.checkedAt > 0 -> had.facts
                else -> r.facts
            },
        )
    }

    /** What to tell you about now: ones out you haven't been told of, and reminders a few days before. */
    fun toNotify(s: Snapshot, now: Long = System.currentTimeMillis() / 1000): List<Pair<Release, String>> {
        val set = s.releaseSettings
        if (!set.enabled) return emptyList()
        return s.releases.values.filter { !it.skipped && it.kind != "sff" }.mapNotNull { r ->
            when {
                set.notifyOut && r.isOut(now) && r.notified != "out" -> r to "out"
                set.remindDays > 0 && !r.isOut(now) && r.notified == null && r.date != null && r.date - now <= set.remindDays * DAY -> r to "soon"
                else -> null
            }
        }.sortedBy { it.first.date ?: it.first.seenAt }
    }
}
