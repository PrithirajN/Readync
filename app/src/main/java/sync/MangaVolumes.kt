package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.source.MangaDex
import io.github.prithirajn.readync.source.MihonBackup

/**
 * Chapters read (in Mihon) as volumes read: each volume's share done, weighted by its chapters'
 * page counts when known (a 30-page chapter counts more than a 12-page one), else each chapter
 * equally. The chapter you're part-way through counts by the page you're on.
 */
object MangaVolumes {
    /** Pages in a typical manga chapter, when the real count isn't known. */
    const val TYPICAL_CHAPTER_PAGES = 20

    data class Progress(
        val volume: String,
        /** 0–1. */
        val percent: Double,
        val finished: Boolean,
        /** When you last read in it (unix seconds). */
        val lastReadAt: Long?,
        /** The chapter you're in (or finished last), for the chapter trackers. */
        val lastChapter: Double?,
    )

    fun of(map: MangaDex.Map, chapters: List<MihonBackup.Chapter>, typicalPages: Int = TYPICAL_CHAPTER_PAGES): List<Progress> {
        val byNumber = chapters.filter { it.number != null }.groupBy { it.number!! }
        val out = ArrayList<Progress>()
        for (v in map.volumes) {
            val known = v.chapters.mapNotNull { it.pages }
            val typical = if (known.isNotEmpty()) known.average() else 1.0
            var done = 0.0
            var total = 0.0
            var any = false
            var all = true
            var last: Long? = null
            var lastChapter: Double? = null
            for (c in v.chapters) {
                val weight = (c.pages ?: 0).takeIf { it > 0 }?.toDouble() ?: typical
                total += weight
                val mine = byNumber[c.number].orEmpty()
                val read = mine.any { it.read }
                // lastPage is the page you're on, counted from 0; it's 0 too when you haven't opened it.
                val onPage = mine.maxOfOrNull { it.lastPage } ?: 0
                when {
                    read -> { done += weight; any = true; lastChapter = c.number }
                    mine.any { it.started } -> {
                        all = false; any = true; lastChapter = c.number
                        // Without MangaDex's count, a typical chapter's length (about 20 pages).
                        val pages = c.pages ?: (if (known.isNotEmpty()) typical.toInt() else typicalPages).coerceAtLeast(onPage + 2)
                        done += weight * ((onPage + 1).toDouble() / pages).coerceIn(0.0, 0.99)
                    }
                    else -> all = false
                }
                mine.mapNotNull { it.readAt }.maxOrNull()?.let { t -> if (last == null || t > last!!) last = t }
            }
            if (!any) continue
            out += Progress(v.number, if (all) 1.0 else (done / total).coerceIn(0.0, 0.99), all, last, lastChapter)
        }
        return out
    }

    /** Chapters in a typical manga volume, when MangaDex hasn't the series. */
    const val TYPICAL_PER_VOLUME = 9

    /** Volumes of [per] chapters each (Vol. 1 = 1–9…), enough to cover chapter [upTo], for a series MangaDex hasn't got. */
    fun estimate(id: String, title: String, upTo: Double, per: Int = TYPICAL_PER_VOLUME, now: Long = System.currentTimeMillis() / 1000): MangaDex.Map {
        val p = per.coerceIn(1, 100)
        val count = ((kotlin.math.ceil(upTo).toInt().coerceAtLeast(1)) + p - 1) / p
        return MangaDex.Map(id, title, (1..count).map { v -> MangaDex.Volume(v.toString(), ((v - 1) * p + 1..v * p).map { MangaDex.Chapter(it.toDouble(), null) }) }, "estimate", now)
    }

    /** Volumes you set (chapters per volume), as a map; later volumes the size of your last. */
    fun fromCustom(id: String, title: String, custom: io.github.prithirajn.readync.source.ComicCollections.Map, upTo: Double): MangaDex.Map {
        val ext = io.github.prithirajn.readync.source.ComicCollections.extend(custom, upTo)
        return MangaDex.Map(id, title, ext.volumes.map { v -> MangaDex.Volume(v.number, v.issues.map { MangaDex.Chapter(it, null) }) }, "you", custom.at)
    }

    /**
     * Chapters past the last volume MangaDex lists (out in chapters, not yet collected): counted
     * toward the volumes still to come, each the size of an average one so far. These aren't
     * sent to trackers until the volume is out.
     */
    fun upcoming(map: MangaDex.Map, chapters: List<MihonBackup.Chapter>, typicalPages: Int = TYPICAL_CHAPTER_PAGES): List<Progress> {
        val last = map.lastChapter ?: return emptyList()
        val lastVol = map.volumes.lastOrNull()?.number?.toDoubleOrNull()?.toInt() ?: return emptyList()
        val beyond = chapters.filter { (it.number ?: 0.0) > last + 1e-6 && it.number!! == Math.floor(it.number) }
        if (beyond.none { it.started }) return emptyList()
        val size = (map.volumes.map { it.chapters.size }.average().takeIf { !it.isNaN() } ?: TYPICAL_PER_VOLUME.toDouble()).toInt().coerceAtLeast(1)
        val top = beyond.filter { it.started }.maxOf { it.number!! }
        val vols = ((top - last - 1).toInt() / size) + 1
        val future = MangaDex.Map(map.mangadexId, map.title, (1..vols).map { k ->
            val from = last.toInt() + (k - 1) * size + 1
            MangaDex.Volume((lastVol + k).toString(), (from until from + size).map { MangaDex.Chapter(it.toDouble(), null) })
        }, "estimate")
        return of(future, chapters, typicalPages).map { it.copy(finished = false, percent = it.percent.coerceAtMost(0.99)) }
    }

    /** "Chainsaw Man, Vol. 5" (the way trackers list volumes). */
    fun title(series: String, volume: String): String = "$series, Vol. $volume"
}
