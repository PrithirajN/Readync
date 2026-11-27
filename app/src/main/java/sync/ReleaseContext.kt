package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.data.Book
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.data.Release
import io.github.prithirajn.readync.data.SffFeedItem
import io.github.prithirajn.readync.data.Snapshot

/**
 * What a release means for you, worked out from what's already in the app: where you are in its
 * series, your highlights from the book before it (to catch up before it's out), and the news that
 * mentions it. Nothing here goes online.
 */
object ReleaseContext {
    private const val DAY = 86_400L

    /**
     * Where you are in a release's series: the numbers you've read or started, the ones between the
     * last of those and this release that you haven't (the gap to read first), and which of the gap
     * are on your Want to Read. For a manga chapter, the chapter you're on.
     */
    data class Place(val read: List<Double>, val gap: List<Double>, val wanted: List<Double>, val chapter: Double? = null)

    /** Your library's books of the series [base] (by their series details, or the volume in the title), with their numbers. */
    private fun seriesBooks(s: Snapshot, base: String): List<Pair<Book, Double>> =
        s.books.values.mapNotNull { b ->
            val f = s.facts[b.document]
            val name = f?.series?.let { MangaSources.seriesTitleOf(it) } ?: b.title?.let { MangaSources.seriesTitleOf(it) } ?: return@mapNotNull null
            if (Matcher.baseTitle(name) != base) return@mapNotNull null
            val n = f?.seriesNumber?.toDoubleOrNull() ?: b.title?.let { Matcher.volume(it) }?.toDoubleOrNull() ?: return@mapNotNull null
            b to n
        }

    private fun started(b: Book) = b.status == ReadStatus.FINISHED || b.status == ReadStatus.READING || b.status == ReadStatus.PAUSED || b.percentage > 0.0

    fun place(s: Snapshot, r: Release): Place? {
        val base = Matcher.baseTitle(r.series)
        if (r.kind == "chapter") {
            val read = s.mangaMaps.entries.firstOrNull { Matcher.baseTitle(MangaSources.seriesTitleOf(it.value.title)) == base }?.key?.let { s.seriesRead[it] } ?: return null
            val furthest = listOfNotNull(read.read.maxOfOrNull { it.second }, read.current).maxOrNull() ?: return null
            return Place(emptyList(), emptyList(), emptyList(), chapter = furthest)
        }
        if (r.kind != "book" && r.kind != "volume") return null
        val n = r.number?.toDoubleOrNull() ?: return null
        val mine = seriesBooks(s, base)
        val read = mine.filter { started(it.first) }.map { it.second }.distinct().sorted()
        if (read.isEmpty()) return null
        // Whole numbers after the furthest you've read and before this one: what to read first.
        val from = Math.floor(read.last()).toLong() + 1
        val to = Math.ceil(n).toLong() - 1
        val gap = if (to >= from) (from..to).map { it.toDouble() }.filter { it !in read } else emptyList()
        val wanted = mine.filter { (b, num) -> num in gap && !started(b) }.map { it.second }.distinct()
        return Place(read, gap, wanted)
    }

    /** The furthest book of [r]'s series you've started or read (for "last one N months ago"), with its number. */
    fun lastRead(s: Snapshot, r: Release): Pair<Book, Double>? {
        if (r.kind != "book" && r.kind != "volume") return null
        return seriesBooks(s, Matcher.baseTitle(r.series)).filter { started(it.first) }.maxByOrNull { it.second }
    }

    /** "1–3, 5": numbers as short ranges. */
    fun ranges(ns: List<Double>): String {
        val whole = ns.distinct().sorted()
        val out = ArrayList<String>()
        var i = 0
        while (i < whole.size) {
            var j = i
            while (j + 1 < whole.size && whole[j + 1] == whole[j] + 1.0 && whole[j] == Math.floor(whole[j])) j++
            out += if (j > i + 0) "${ComicIssues.label(whole[i])}–${ComicIssues.label(whole[j])}" else ComicIssues.label(whole[i])
            i = j + 1
        }
        return out.joinToString(", ")
    }

    /** One line for [Place]: "You've read 1–2 · read 3 first (on your Want to Read)". */
    fun placeLine(p: Place, kind: String): String {
        if (p.chapter != null) return "You're on chapter ${ComicIssues.label(p.chapter)}"
        val word = if (kind == "volume") "vol." else "book"
        val have = "You've read ${if (p.read.size == 1) "$word " else "${word}s "}${ranges(p.read)}".replace("vol.s", "vols.")
        if (p.gap.isEmpty()) return "$have · this is next"
        val first = "read ${ranges(p.gap)} first"
        return when {
            p.wanted.size == p.gap.size -> "$have · $first (on your Want to Read)"
            p.wanted.isNotEmpty() -> "$have · $first (${ranges(p.wanted)} on your Want to Read)"
            else -> "$have · $first"
        }
    }

    /**
     * Before a book is out (from a month before to a fortnight after): your highlights from the book
     * before it in the series, a few spread through the book so they read as a recap.
     */
    data class CatchUp(val book: Book, val passages: List<Highlights.Item>, val total: Int)

    fun catchUp(s: Snapshot, r: Release, now: Long = System.currentTimeMillis() / 1000): CatchUp? {
        if (r.kind != "book" && r.kind != "volume") return null
        val day = r.date ?: return null
        if (day - now > 30 * DAY || now - day > 14 * DAY) return null
        val n = r.number?.toDoubleOrNull() ?: return null
        val before = seriesBooks(s, Matcher.baseTitle(r.series)).filter { (b, num) -> num < n && started(b) }.maxByOrNull { it.second }?.first ?: return null
        val all = Highlights.forBook(s, before.document)
        if (all.isEmpty()) return null
        val inOrder = all.sortedBy { it.clip.percent ?: (it.clip.spine?.toDouble() ?: 0.0) / 1000.0 }
        val pick = if (inOrder.size <= 4) inOrder else (0 until 4).map { i -> inOrder[(i * (inOrder.size - 1)) / 3] }.distinct()
        return CatchUp(before, pick, all.size)
    }

    /** News stories (Discover › News) that name this release: its title, or its series and number. */
    fun news(s: Snapshot, r: Release): List<SffFeedItem> {
        if (r.kind == "chapter" || r.kind == "webnovel" || r.announcedOnly || Release.placeholder(r.title)) return emptyList()
        val title = Matcher.baseTitle(r.title)
        val base = Matcher.baseTitle(r.series)
        // A short title ("Dawn") would match too much on its own.
        val byTitle = title.length >= 8 || title.contains(' ')
        return s.sffItems.filter { item ->
            // The whole headline ("Cover reveal: Best Served Cold"), not just the part before a colon.
            fun has(text: String?) = text != null && " ${Matcher.normalizeText(text)} ".contains(" $title ")
            (byTitle && (has(item.title) || has(item.subtitle))) ||
                (r.number != null && item.series?.let { Matcher.baseTitle(MangaSources.seriesTitleOf(it)) } == base && item.seriesNumber == r.number)
        }.distinctBy { it.url ?: it.id }.take(2)
    }
}
