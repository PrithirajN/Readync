package io.github.prithirajn.trackersync.source

import io.github.prithirajn.trackersync.util.Proto
import io.github.prithirajn.trackersync.util.bool
import io.github.prithirajn.trackersync.util.float
import io.github.prithirajn.trackersync.util.long
import io.github.prithirajn.trackersync.util.messages
import io.github.prithirajn.trackersync.util.str
import java.util.zip.GZIPInputStream

/**
 * A Mihon (or Tachiyomi, Komikku) backup file (.tachibk / .proto.gz): your library's series, each
 * chapter you've read and the page you stopped on, when you read them, and the trackers each
 * series is on there. Field numbers follow Mihon's own backup models (mihonapp/mihon).
 */
object MihonBackup {

    data class Chapter(
        val url: String, val name: String, val number: Double?, val read: Boolean, val lastPage: Int, val readAt: Long?,
        /** Opened at its first page (Mihon says 0 for both that and never opened; a log knows which). */
        val opened: Boolean = false,
    ) {
        /** Part-way through it (or finished). */
        val started: Boolean get() = read || lastPage > 0 || opened
    }

    /** A series' entry on a tracker inside Mihon (AniList = 2, MyAnimeList = 1, MangaUpdates = 7…). */
    data class Tracking(val trackerId: Int, val mediaId: Long, val lastChapter: Double)

    data class Series(
        val source: Long,
        val url: String,
        val title: String,
        val author: String?,
        val coverUrl: String?,
        val favorite: Boolean,
        val chapters: List<Chapter>,
        val tracking: List<Tracking>,
        /** Where it came from: "mihon" (a backup here) or "anilist" (your list there, read anywhere). */
        val origin: String = "mihon",
        /** Its other names (AniList's romaji and synonyms), for finding it elsewhere. */
        val otherTitles: List<String> = emptyList(),
        /** A Western comic (read by issue), when known; else guessed from its chapter names. */
        val comic: Boolean? = null,
        /** Start and finish days you gave per chapter or issue (unix seconds), from your log. */
        val issueDates: Map<Double, Pair<Long?, Long?>> = emptyMap(),
    ) {
        /** A key for the series that stays the same between backups. */
        val key: String get() = "$source:$url"
        val anilistId: Long? get() = tracking.firstOrNull { it.trackerId == ANILIST }?.mediaId?.takeIf { it > 0 }
        val malId: Long? get() = tracking.firstOrNull { it.trackerId == MYANIMELIST }?.mediaId?.takeIf { it > 0 }
        val mangaUpdatesId: Long? get() = tracking.firstOrNull { it.trackerId == MANGAUPDATES }?.mediaId?.takeIf { it > 0 }
        /** When you last read anything in it (unix seconds), or null. */
        val lastReadAt: Long? get() = chapters.mapNotNull { it.readAt }.maxOrNull()
    }

    /** Reads the newest backup in a folder: (file name, its time in unix seconds, bytes). Set by the app. */
    @Volatile var reader: ((folder: String) -> Triple<String, Long, ByteArray>?)? = null

    const val MYANIMELIST = 1
    const val ANILIST = 2
    const val MANGAUPDATES = 7

    /** Reads a backup; gzipped or plain. Throws when it isn't a Mihon backup. */
    fun parse(bytes: ByteArray): List<Series> {
        val raw = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        } else bytes
        val top = runCatching { Proto(raw).fields() }.getOrElse { throw IllegalArgumentException("That isn't a Mihon backup") }
        return top.messages(1).mapNotNull { m ->
            val title = m.str(3)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // History: when each chapter (by its link) was last read, in milliseconds.
            val history = m.messages(104).mapNotNull { h -> h.str(1)?.let { it to (h.long(2) ?: 0L) } }.toMap()
            Series(
                source = m.long(1) ?: 0L,
                url = m.str(2).orEmpty(),
                title = title,
                author = m.str(5)?.trim()?.takeIf { it.isNotEmpty() },
                coverUrl = m.str(9)?.takeIf { it.startsWith("http") },
                favorite = m[100]?.let { m.bool(100) } ?: true,
                chapters = m.messages(16).map { c ->
                    val url = c.str(1).orEmpty()
                    Chapter(
                        url = url,
                        name = c.str(2).orEmpty(),
                        number = c.float(9)?.toDouble()?.takeIf { it >= 0 },
                        read = c.bool(4),
                        lastPage = (c.long(6) ?: 0L).toInt(),
                        readAt = history[url]?.takeIf { it > 0 }?.let { it / 1000 },
                    )
                },
                tracking = m.messages(18).map { t ->
                    Tracking(
                        trackerId = (t.long(1) ?: 0L).toInt(),
                        mediaId = t.long(100)?.takeIf { it > 0 } ?: t.long(3) ?: 0L,
                        lastChapter = t.float(6)?.toDouble() ?: 0.0,
                    )
                },
            )
        }
    }
}
