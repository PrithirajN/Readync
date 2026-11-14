package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.source.AniList
import io.github.prithirajn.readync.source.MihonBackup

/**
 * Manga and comics from wherever you read them, as one list: Mihon's backup on this phone (which
 * knows the page you're on) and your AniList list (which Mihon on any of your devices keeps up to
 * date). The same series from both is one series, at whichever is further along.
 */
object MangaSources {
    /** Countries AniList files manga, manhwa and manhua under; anything else is a comic. */
    private val ASIAN = setOf("JP", "KR", "CN", "TW", "HK")

    fun fromAniList(e: AniList.Entry): MihonBackup.Series {
        val comic = e.country != null && e.country !in ASIAN
        return MihonBackup.Series(
            source = -2, url = "anilist:${e.mediaId}", title = e.title, author = e.author, coverUrl = e.coverUrl, favorite = true,
            chapters = (1..e.progress.coerceAtLeast(0)).map { n ->
                MihonBackup.Chapter("al:$n", if (comic) "#$n" else "Chapter $n", n.toDouble(), true, 0, if (n == e.progress) e.updatedAt else null)
            },
            tracking = listOfNotNull(
                MihonBackup.Tracking(MihonBackup.ANILIST, e.mediaId, e.progress.toDouble()),
                e.malId?.let { MihonBackup.Tracking(MihonBackup.MYANIMELIST, it, e.progress.toDouble()) },
            ),
            origin = "anilist", otherTitles = e.otherTitles, comic = comic,
        )
    }

    /** A Mihon series caught up with what AniList says you've read (on another device, say). */
    fun withAniList(s: MihonBackup.Series, e: AniList.Entry): MihonBackup.Series {
        // AniList's entry, at the chapter AniList has right now (the backup's note of it may be stale).
        val tracking = s.tracking.filter { it.trackerId != MihonBackup.ANILIST } + MihonBackup.Tracking(MihonBackup.ANILIST, e.mediaId, e.progress.toDouble())
        val read = s.chapters.filter { it.read }.mapNotNull { it.number }.maxOrNull() ?: 0.0
        if (e.progress <= read) return s.copy(tracking = tracking, otherTitles = (s.otherTitles + e.otherTitles + e.title).distinct())
        val have = s.chapters.mapNotNull { it.number?.toInt() }.toSet()
        val chapters = s.chapters.map { c ->
            val n = c.number ?: return@map c
            if (n <= e.progress + 1e-6 && !c.read) c.copy(read = true, readAt = if (n.toInt() == e.progress) (e.updatedAt ?: c.readAt) else c.readAt) else c
        } + (1..e.progress).filter { it !in have }.map { n ->
            MihonBackup.Chapter("al:$n", "Chapter $n", n.toDouble(), true, 0, if (n == e.progress) e.updatedAt else null)
        }
        return s.copy(chapters = chapters, tracking = tracking, otherTitles = (s.otherTitles + e.otherTitles + e.title).distinct())
    }

    private val VOL_TAIL = Regex("""(?i)[\s,:\-–]*(?:\(|\b)?(?:vol\.?|volume|book|tpb|issue|#)\s*#?\d+(?:\.\d+)?\)?.*$""")

    private val EDITION_TAIL = Regex("""(?i)\s*\((?:single\s+issues?|collected\s+editions?|trade\s+paperbacks?|tpb|comics?|\d{4}(?:\s*[-–]\s*\d{0,4})?)\)\s*$""")

    /** The series in a volume's or issue's title: "Saga, Vol. 9", "Saga #54" and "Saga (Single Issues)" are "Saga". */
    fun seriesTitleOf(title: String): String {
        var t = title.trim()
        repeat(3) { t = t.replace(VOL_TAIL, "").replace(EDITION_TAIL, "").trim() }
        return t.ifEmpty { title.trim() }
    }

    /** The series key for a title you log by hand. */
    fun loggedKey(title: String) = "logged:" + Matcher.baseTitle(title).ifBlank { title.trim().lowercase() }

    /** Chapter numbers read for a log: everything up to it (or only it), the one you're in at its page. */
    private fun loggedChapters(l: io.github.prithirajn.readync.data.LoggedSeries): List<MihonBackup.Chapter> {
        val last = kotlin.math.floor(l.at).toInt()
        val firstRead = if (l.fromStart) 1 else last
        val out = ArrayList<MihonBackup.Chapter>()
        for (n in firstRead..last) {
            val current = n == last
            val readNow = n > l.before + 1e-6 || current
            val d = l.dates[n.toDouble()]
            out += MihonBackup.Chapter(
                "log:$n", if (l.comic) "#$n" else "Chapter $n", n.toDouble(),
                read = !current || l.finished,
                lastPage = if (current && !l.finished) (l.page - 1).coerceAtLeast(0) else 0,
                // The day you gave for finishing it, else when you logged it (for ones read since the last log).
                readAt = d?.second ?: d?.first?.takeIf { current } ?: if (readNow) l.loggedAt else null,
                opened = current && !l.finished,
            )
        }
        return out
    }

    /** A series you log by hand, as the others are read. */
    fun fromLogged(key: String, l: io.github.prithirajn.readync.data.LoggedSeries) = MihonBackup.Series(
        source = -3, url = key.removePrefix("logged:"), title = l.title, author = l.author, coverUrl = null, favorite = true,
        chapters = loggedChapters(l), tracking = emptyList(), origin = "logged", comic = l.comic, issueDates = l.dates,
    )

    /**
     * Your logs added in: a log for a series that's already here (from Mihon or AniList, by title)
     * moves it on when it's further along; else it's a series of its own.
     */
    fun withLogged(all: List<MihonBackup.Series>, logged: kotlin.collections.Map<String, io.github.prithirajn.readync.data.LoggedSeries>): List<MihonBackup.Series> {
        val out = all.toMutableList()
        for ((key, l) in logged) {
            val i = out.indexOfFirst { s -> (listOf(s.title) + s.otherTitles).any { Matcher.baseTitle(it) == Matcher.baseTitle(l.title) } }
            if (i < 0) { out += fromLogged(key, l); continue }
            val s = out[i]
            val mine = loggedChapters(l).associateBy { it.number!! }
            val have = s.chapters.mapNotNull { it.number }.toSet()
            val merged = s.chapters.map { c ->
                val m = c.number?.let { mine[it] } ?: return@map c
                if (c.read) c else c.copy(read = m.read, lastPage = maxOf(c.lastPage, m.lastPage), readAt = m.readAt ?: c.readAt, opened = c.opened || m.opened)
            } + mine.values.filter { it.number!! !in have }
            out[i] = s.copy(chapters = merged, comic = s.comic ?: l.comic, issueDates = s.issueDates + l.dates)
        }
        return out
    }

    /** Both lists as one: each Mihon series with its AniList entry (by AniList id, else by title), then AniList's others. */
    fun merge(mihon: List<MihonBackup.Series>, anilist: List<AniList.Entry>): List<MihonBackup.Series> {
        val used = HashSet<Long>()
        val out = mihon.map { s ->
            val e = anilist.firstOrNull { it.mediaId == s.anilistId }
                ?: if (s.anilistId == null) anilist.firstOrNull { e -> e.mediaId !in used && (listOf(e.title) + e.otherTitles).any { Matcher.baseTitle(it) == Matcher.baseTitle(s.title) } } else null
            if (e == null) s else { used += e.mediaId; withAniList(s, e) }
        }
        return out + anilist.filter { it.mediaId !in used }.map(::fromAniList)
    }
}

/**
 * Western comics, read by issue: each issue is its own book ("Saga #54"), the way book trackers
 * list single issues. The issue you're part-way through counts by the page you're on.
 */
object ComicIssues {
    /** Pages in a typical single issue, when the real count isn't known. */
    const val TYPICAL_ISSUE_PAGES = 24

    private val ISSUE = Regex("""(?i)(?:\bissue\s*#?|#)\s*(\d+(?:\.\d+)?)""")
    private val CHAPTER = Regex("""(?i)\b(?:chapter|ch\.|episode|ep\.)\s*\d""")

    /** The issue number in a chapter's name ("Saga Issue #54", "#54"), or null. */
    fun issueNumber(name: String): Double? = ISSUE.find(name)?.groupValues?.get(1)?.toDoubleOrNull()

    /** True when a series is read by issue: told so, or most of its chapters are named as issues. */
    fun isComic(s: MihonBackup.Series): Boolean {
        s.comic?.let { return it }
        val names = s.chapters.map { it.name }.filter { it.isNotBlank() }.take(40)
        if (names.isEmpty()) return false
        val issues = names.count { issueNumber(it) != null && !CHAPTER.containsMatchIn(it) }
        return issues * 10 >= names.size * 6
    }

    fun of(chapters: List<MihonBackup.Chapter>, pages: Int = TYPICAL_ISSUE_PAGES): List<MangaVolumes.Progress> =
        chapters.mapNotNull { c -> (issueNumber(c.name) ?: c.number)?.let { it to c } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .mapNotNull { (n, cs) ->
                val read = cs.any { it.read }
                val onPage = cs.maxOf { it.lastPage }
                if (cs.none { it.started }) return@mapNotNull null
                val pct = if (read) 1.0 else ((onPage + 1).toDouble() / pages.coerceAtLeast(onPage + 2)).coerceIn(0.0, 0.99)
                MangaVolumes.Progress(label(n), pct, read, cs.mapNotNull { it.readAt }.maxOrNull(), n)
            }

    /** The issue each chapter is, and how far into it you are (0–1), with when you last read it. */
    private fun issues(chapters: List<MihonBackup.Chapter>, pages: Int = TYPICAL_ISSUE_PAGES): java.util.SortedMap<Double, Triple<Double, Boolean, Long?>> =
        chapters.mapNotNull { c -> (issueNumber(c.name) ?: c.number)?.let { it to c } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, cs) ->
                val read = cs.any { it.read }
                val onPage = cs.maxOf { it.lastPage }
                val part = when {
                    read -> 1.0
                    cs.any { it.started } -> ((onPage + 1).toDouble() / pages.coerceAtLeast(onPage + 2)).coerceIn(0.0, 0.99)
                    else -> 0.0
                }
                Triple(part, read, cs.mapNotNull { it.readAt }.maxOrNull())
            }.toSortedMap()

    /**
     * Issues read as collected volumes: each volume's share done (every issue counts the same; the
     * one you're in by its page), like manga volumes.
     */
    fun volumes(map: io.github.prithirajn.readync.source.ComicCollections.Map, chapters: List<MihonBackup.Chapter>, pages: Int = TYPICAL_ISSUE_PAGES): List<MangaVolumes.Progress> {
        val mine = issues(chapters, pages)
        return map.volumes.mapNotNull { v ->
            val parts = v.issues.map { n -> mine.entries.firstOrNull { kotlin.math.abs(it.key - n) < 1e-6 }?.value }
            if (parts.all { it == null || it.first == 0.0 }) return@mapNotNull null
            val all = parts.all { it?.second == true }
            val done = parts.sumOf { it?.first ?: 0.0 } / v.issues.size
            val lastIssue = v.issues.zip(parts).lastOrNull { (it.second?.first ?: 0.0) > 0 }?.first
            MangaVolumes.Progress(v.number, if (all) 1.0 else done.coerceIn(0.0, 0.99), all, parts.mapNotNull { it?.third }.maxOrNull(), lastIssue)
        }
    }

    /** Issues past the last collected volume (not in a trade yet), each as its own book. */
    fun uncollected(map: io.github.prithirajn.readync.source.ComicCollections.Map, chapters: List<MihonBackup.Chapter>, pages: Int = TYPICAL_ISSUE_PAGES): List<MangaVolumes.Progress> {
        val last = map.lastIssue ?: return of(chapters, pages)
        return of(chapters, pages).filter { (it.lastChapter ?: 0.0) > last + 1e-6 }
    }

    /** The furthest issue you've read or opened. */
    fun furthest(chapters: List<MihonBackup.Chapter>): Double? = issues(chapters).filterValues { it.first > 0 }.keys.maxOrNull()

    fun label(n: Double): String = if (n == Math.floor(n)) n.toLong().toString() else n.toString()

    /** "Saga #54". */
    fun title(series: String, issue: String) = "$series #$issue"
}
