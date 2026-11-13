package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Release
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.data.Store
import io.github.prithirajn.trackersync.trackers.GoodreadsTracker
import io.github.prithirajn.trackersync.trackers.HardcoverTracker
import io.github.prithirajn.trackersync.trackers.Session

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
            val fromHc = hc?.let { runCatching { HardcoverTracker.seriesBooks(it, c.name) }.getOrNull() }.orEmpty()
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
            val positions = (fromHc.map { it.position } + fromGr.map { it.position }).distinct()
            for (p in nextOnes(c.reached, positions, set.inBetween)) {
                val h = fromHc.firstOrNull { it.position == p }
                val g = fromGr.firstOrNull { it.position == p }
                var date = h?.releaseDate ?: g?.date
                // Goodreads' list may not say when: the book's own page does.
                if (date == null && g != null && gr != null && System.currentTimeMillis() < deadline) date = runCatching { GoodreadsTracker.releaseDay(gr, g.id) }.getOrNull()
                if (date != null && date > now + set.aheadMonths * 31 * DAY) continue // further off than you want to see
                val label = ComicIssues.label(p)
                // In the language you read the series in: a translation's title (another alphabet, or
                // Spanish where yours is English) gives way to the other source's, else isn't news.
                val yours = c.book.title
                // A stand-in title ("The Devils book 3") gives way to a real one from the other source too.
                val hTitle = h?.title?.takeIf { !Matcher.otherLanguage(it, yours) }?.takeIf { g == null || !Release.placeholder(it) }
                val gTitle = g?.title?.takeIf { !Matcher.otherLanguage(it, yours) }
                val title = hTitle ?: gTitle ?: continue
                // Already in your library: not news. By its number in the series too, so an edition under
                // another title (or in another language) of a book you have isn't listed as new.
                if (haveNumber(s0, c.key, p)) continue
                if (s0.books.values.any { b -> b.title != null && (Matcher.sameBook(title, c.author, b.title, b.author) || (Matcher.baseTitle(MangaSources.seriesTitleOf(b.title)) == c.key && Matcher.volume(b.title) == label)) }) continue
                val key = "${c.key}|$label"
                val r = Release(
                    key = key, kind = if (c.kind == "book") "book" else "volume", series = c.name, title = title, number = label, date = date,
                    source = listOfNotNull(h?.let { "Hardcover" }, g?.let { "Goodreads" }).joinToString(" and "),
                    coverUrl = (if (hTitle != null) h?.coverUrl else null) ?: g?.coverUrl ?: h?.coverUrl,
                    url = g?.let { "${GoodreadsTracker.BASE}/book/show/${it.id}" } ?: h?.let { "https://hardcover.app/books/${it.id}" },
                    author = h?.author ?: g?.author ?: c.author, seenAt = now,
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
                val latest = runCatching { io.github.prithirajn.trackersync.source.MangaDex.latestChapter(m.mangadexId) }.getOrNull()
                store.update { it.copy(releasesChecked = it.releasesChecked + ("md:$key" to now)) }
                if (latest == null || latest.first <= furthest + 1e-6) continue
                val label = ComicIssues.label(latest.first)
                found += keep(store, Release(
                    key = "${Matcher.baseTitle(name)}|ch$label", kind = "chapter", series = name, title = "$name, Chapter $label",
                    number = "Chapter $label", date = latest.second, source = "MangaDex", url = "https://mangadex.org/title/${m.mangadexId}", seenAt = now,
                ))
            }
        }
        tidy(store)
        return found
    }

    /** New chapters of a web novel you follow, seen when its page was looked at again. */
    fun webNovelGrew(store: Store, document: String, title: String, from: Int, to: Int, url: String, site: String, latest: String? = null) {
        if (!store.snapshot.releaseSettings.let { it.enabled && it.webNovels } || to <= from) return
        val now = System.currentTimeMillis() / 1000
        keep(store, Release(
            key = "${Matcher.baseTitle(title)}|web$to", kind = "webnovel", series = title,
            title = "${to - from} new chapter${if (to - from == 1) "" else "s"} of $title", number = "Chapter $to", date = now,
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
    private fun haveNumber(s: io.github.prithirajn.trackersync.data.Snapshot, key: String, n: Double): Boolean =
        s.books.values.any { b ->
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
        val kept = st.releases.filterValues { r ->
            val base = Matcher.baseTitle(r.series)
            if (base in st.releasesHidden) return@filterValues false
            if ((r.kind == "book" || r.kind == "volume") && r.number?.toDoubleOrNull()?.let { haveNumber(st, base, it) } == true) return@filterValues false
            // Found before this check: one in another language than the series as you read it.
            if (r.kind == "book" || r.kind == "volume") {
                val yours = st.books.values.firstOrNull { b -> st.facts[b.document]?.series?.let { Matcher.baseTitle(MangaSources.seriesTitleOf(it)) } == base }?.title
                if (Matcher.otherLanguage(r.title, yours)) return@filterValues false
            }
            // A chapter's news goes stale; the next book stays until you have it (or hide the series).
            if ((r.kind == "chapter" || r.kind == "webnovel") && r.seenAt < now - 60 * DAY) return@filterValues false
            when (r.kind) {
                "book", "volume" -> st.books.values.none { b ->
                    b.title != null && (Matcher.sameBook(r.title, r.author, b.title, b.author) ||
                        (Matcher.baseTitle(MangaSources.seriesTitleOf(b.title)) == base && Matcher.volume(b.title) == r.number))
                }
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

    /** Keeps a release (updating its day if it changed); 1 when it's new. */
    private fun keep(store: Store, r: Release): Int {
        var isNew = 0
        val now = System.currentTimeMillis() / 1000
        store.update { st ->
            val had = st.releases[r.key]
            isNew = if (had == null) 1 else 0
            // Out a while before it was first seen: listed, but no "it's out" alert for old news.
            val fresh = if (had == null && r.isOut(now) && r.kind != "webnovel" && (r.date == null || r.date < now - 7 * DAY)) r.copy(notified = "out") else r
            // A day that moved (a delay) resets the reminder; what you were told is otherwise kept.
            val merged = if (had == null) fresh else r.copy(seenAt = had.seenAt, notified = if (had.date != r.date && had.notified == "soon") null else had.notified)
            st.copy(releases = st.releases + (r.key to merged))
        }
        return isNew
    }

    /** What to tell you about now: ones out you haven't been told of, and reminders a few days before. */
    fun toNotify(s: Snapshot, now: Long = System.currentTimeMillis() / 1000): List<Pair<Release, String>> {
        val set = s.releaseSettings
        if (!set.enabled) return emptyList()
        return s.releases.values.mapNotNull { r ->
            when {
                set.notifyOut && r.isOut(now) && r.notified != "out" -> r to "out"
                set.remindDays > 0 && !r.isOut(now) && r.notified == null && r.date != null && r.date - now <= set.remindDays * DAY -> r to "soon"
                else -> null
            }
        }.sortedBy { it.first.date ?: it.first.seenAt }
    }
}
