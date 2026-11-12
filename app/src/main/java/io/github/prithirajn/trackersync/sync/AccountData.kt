package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.Secrets
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.data.Store
import io.github.prithirajn.trackersync.net.ServiceException
import org.json.JSONObject

/**
 * Your reading data, without any sign-ins: the library (progress, statuses, dates, ratings,
 * reviews), physical books, highlights and your edits to them, which books each tracker is
 * matched to and what was already sent there, web novels and logged manga or comics.
 *
 * Unlike the full backup it isn't locked with a password (it holds no passwords or tokens) and it
 * doesn't replace what's on the phone: it's merged in, so it can move between phones that are
 * both set up, and between app versions in either direction. Fields an older version doesn't know
 * are skipped; fields a newer file lacks keep their defaults.
 */
object AccountData {
    const val FORMAT = "readync-data"
    /** Bumped only when a field changes meaning; new fields don't need it. */
    const val SCHEMA = 1

    /** What goes in the file: the saved fields that are reading data, never sign-ins or device state. */
    internal val KEYS = listOf(
        "books", "links", "removedBooks", "rereads",
        "paperClips", "cpClips", "kindleClips", "libbyClips", "moonClips", "historyClips", "koClips", "readingDays", "clipEdits", "clipPages", "clipRemote", "clipHidden", "sentClippings",
        "seekquelRatings", "seekquelDates",
        "webNovels", "kosyncIds", "logged", "seriesRead", "pagesPer", "comicPerVolume", "comicCustom", "facts",
    )

    /** Inside the file, highlight text is plain: there's no phone key to seal it with. */
    private object Open : Secrets {
        override fun seal(plain: String) = plain
        override fun open(sealed: String): String? = sealed
    }

    /** True when [bytes] look like a reading-data file (rather than a full backup or anything else). */
    fun isData(bytes: ByteArray): Boolean =
        runCatching { JSONObject(String(bytes)).optString("format") == FORMAT }.getOrDefault(false)

    fun export(s: Snapshot, appVersion: String, appBuild: Int, now: Long = System.currentTimeMillis()): ByteArray {
        val all = Store.encode(s.copy(running = false), Open)
        val data = JSONObject()
        for (k in KEYS) if (all.has(k)) data.put(k, all.get(k))
        return JSONObject()
            .put("format", FORMAT).put("schema", SCHEMA)
            .put("app", JSONObject().put("version", appVersion).put("build", appBuild))
            .put("exportedAt", now)
            .put("books", s.books.size)
            .put("data", data)
            .toString(1).toByteArray()
    }

    /** What a file holds, before it's merged. [newer] when it came from a newer schema than this app knows. */
    data class Read(val incoming: Snapshot, val books: Int, val highlights: Int, val fromVersion: String?, val exportedAt: Long, val newer: Boolean)

    fun read(bytes: ByteArray): Read {
        val o = runCatching { JSONObject(String(bytes)) }.getOrNull()
            ?: throw ServiceException("That file isn't Readync data")
        when (o.optString("format")) {
            FORMAT -> Unit
            "tracker-sync-backup" -> throw ServiceException("That's a full backup. It needs its password: use Restore instead.")
            else -> throw ServiceException("That file isn't Readync data")
        }
        val data = o.optJSONObject("data") ?: JSONObject()
        // Only the reading-data fields are read, whatever else a file might carry.
        val only = JSONObject()
        for (k in KEYS) if (data.has(k)) only.put(k, data.get(k))
        val snap = Store.decode(only, Open)
        return Read(
            incoming = snap,
            books = snap.books.size,
            highlights = snap.paperClips.size + snap.cpClips.size + snap.kindleClips.size + snap.libbyClips.size + snap.moonClips.size + snap.historyClips.size + snap.koClips.size,
            fromVersion = o.optJSONObject("app")?.optString("version")?.takeIf { it.isNotBlank() },
            exportedAt = o.optLong("exportedAt"),
            newer = o.optInt("schema", 1) > SCHEMA,
        )
    }

    /** What a merge did, for the message afterwards. */
    data class Merged(val snapshot: Snapshot, val added: Int, val updated: Int, val highlights: Int)

    /**
     * [incoming] merged into [current]. Books new here are added; a book on both keeps the more
     * recent progress, and anything only one side has (a rating, dates, a cover) is filled in.
     * This phone's matches, sign-ins and settings stay as they are; what was already sent to a
     * tracker is remembered, so nothing is sent twice.
     */
    fun merge(current: Snapshot, incoming: Snapshot): Merged {
        val books = HashMap(current.books)
        var added = 0
        var updated = 0
        for ((doc, inc) in incoming.books) {
            val cur = books[doc]
            if (cur == null) { books[doc] = inc; added++; continue }
            val m = mergeBook(cur, inc)
            if (m != cur) { books[doc] = m; updated++ }
        }
        fun <T> byId(a: List<T>, b: List<T>, id: (T) -> String): List<T> {
            val have = a.map(id).toSet()
            return a + b.filter { id(it) !in have }
        }
        val paper = byId(current.paperClips, incoming.paperClips) { it.id }
        val cp = byId(current.cpClips, incoming.cpClips) { it.id }
        val kindle = byId(current.kindleClips, incoming.kindleClips) { it.id }
        val libby = byId(current.libbyClips, incoming.libbyClips) { it.id }
        val moon = byId(current.moonClips, incoming.moonClips) { it.id }
        val hist = byId(current.historyClips, incoming.historyClips) { it.id }
        val kos = byId(current.koClips, incoming.koClips) { it.id }
        val newClips = (paper.size - current.paperClips.size) + (cp.size - current.cpClips.size) +
            (kindle.size - current.kindleClips.size) + (libby.size - current.libbyClips.size) + (moon.size - current.moonClips.size) + (hist.size - current.historyClips.size) + (kos.size - current.koClips.size)
        // Maps: what this phone has wins; the file fills in the rest.
        val s = current.copy(
            books = books,
            links = incoming.links + current.links,
            removedBooks = (incoming.removedBooks + current.removedBooks).filterKeys { it !in books },
            rereads = incoming.rereads + current.rereads,
            paperClips = paper, cpClips = cp, kindleClips = kindle, libbyClips = libby, moonClips = moon, historyClips = hist, koClips = kos,
            readingDays = (incoming.readingDays.keys + current.readingDays.keys).associateWith { d -> (incoming.readingDays[d] ?: emptyMap()) + (current.readingDays[d] ?: emptyMap()) },
            clipEdits = incoming.clipEdits + current.clipEdits,
            clipPages = incoming.clipPages + current.clipPages,
            clipRemote = incoming.clipRemote + current.clipRemote,
            clipHidden = current.clipHidden + incoming.clipHidden,
            clipChecked = incoming.clipChecked + current.clipChecked,
            sentClippings = current.sentClippings + incoming.sentClippings,
            seekquelRatings = incoming.seekquelRatings + current.seekquelRatings,
            seekquelDates = incoming.seekquelDates + current.seekquelDates,
            webNovels = incoming.webNovels + current.webNovels,
            logged = incoming.logged + current.logged,
            seriesRead = incoming.seriesRead + current.seriesRead,
            pagesPer = incoming.pagesPer + current.pagesPer,
            comicPerVolume = incoming.comicPerVolume + current.comicPerVolume,
            comicCustom = incoming.comicCustom + current.comicCustom,
            facts = incoming.facts + current.facts,
            kosyncIds = incoming.kosyncIds + current.kosyncIds,
        )
        return Merged(s, added, updated, newClips)
    }

    /**
     * One book from both sides: the more recently updated one's progress and status, with gaps
     * filled from the other. Whether it's held back from the trackers stays as it is on this phone.
     */
    internal fun mergeBook(cur: Book, inc: Book): Book {
        val incNewer = inc.timestamp > cur.timestamp
        val base = if (incNewer) inc else cur
        val other = if (incNewer) cur else inc
        return base.copy(
            title = base.title ?: other.title,
            author = base.author ?: other.author,
            aliases = (base.aliases + other.aliases).distinct(),
            coverUrl = base.coverUrl ?: other.coverUrl,
            description = base.description ?: other.description,
            descriptionSource = if (base.description != null) base.descriptionSource else other.descriptionSource,
            detailsFetched = base.detailsFetched || other.detailsFetched,
            pageCount = base.pageCount ?: other.pageCount,
            pagesRead = base.pagesRead ?: other.pagesRead,
            finishedAt = base.finishedAt ?: other.finishedAt,
            startedAt = base.startedAt ?: other.startedAt,
            rating = base.rating ?: other.rating,
            review = base.review?.takeIf { it.isNotBlank() } ?: other.review,
            reviewAsked = base.reviewAsked || other.reviewAsked,
            isbn = base.isbn ?: other.isbn,
            held = cur.held,
            origin = cur.origin,
        )
    }
}
