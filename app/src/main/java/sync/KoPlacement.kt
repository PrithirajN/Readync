package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.source.Clipping
import io.github.prithirajn.readync.source.EpubText
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Putting highlights on their exact words between KOReader and CrossPoint, using the book's EPUB
 * from KOReader's books folder on this device. Only the very same file counts (its KOReader
 * document ID is the CrossPoint book's), so the places are that file's, and a passage is only
 * placed when its words are found exactly once.
 */
object KoPlacement {

    /**
     * KOReader's and CrossPoint's document ID for a book by its file name ("Document matching:
     * Filename"): the MD5 of the file's name, without its folder.
     */
    fun fileId(fileName: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(fileName.substringAfterLast('/').toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** An EPUB in the picked folder for a book: [sameFile] when it's that very file (by content), else only by its name. */
    data class Found(val path: String, val sameFile: Boolean)

    /**
     * The EPUB in the picked folder that is [document]: the same file (its content gives the ID,
     * "Binary" matching), else one with the same name ("Filename" matching, which may be another
     * copy of it). Null when there's neither.
     */
    fun bookFor(s: Snapshot, document: String): Found? {
        // The very file anywhere (KOReader's folder here, the storage, or kept by Readync), the one kept here first.
        val same = s.koBooks.entries.filter { it.value.substringAfterLast('|') == document }
        (same.firstOrNull { it.key.startsWith(io.github.prithirajn.readync.source.BookFiles.PREFIX) } ?: same.firstOrNull())?.let { return Found(it.key, true) }
        // A copy you gave Readync for this book (another edition, say).
        val kept = io.github.prithirajn.readync.source.BookFiles.key(document)
        if (s.koBooks.containsKey(kept)) return Found(kept, false)
        s.koBooks.keys.firstOrNull { fileId(it) == document }?.let { return Found(it, false) }
        // The copy kept for quotes (Discover › Catalogs › Copies for quotes), when there's one.
        return copyFor?.invoke(document)
    }

    /** Set by the app: the book's copy kept for quotes, as "copy:<document>" (the very file when its ID is the book's). */
    @Volatile var copyFor: ((String) -> Found?)? = null

    const val COPY = "copy:"

    fun bookPath(s: Snapshot, document: String): String? = bookFor(s, document)?.path

    /**
     * A point where the device's count and this copy's are both known: at [at] in this copy's
     * chapter [spine], the device's count is [delta] more (or less). [exact] for one of the device's
     * own highlights (its words and their place); a bookmark's page start is near-exact.
     */
    data class Anchor(val spine: Int, val at: Int, val delta: Int, val exact: Boolean)

    /**
     * [place] (in this copy) moved to where the device has the same words, when a point before it
     * and one after it in the chapter agree on the difference (so the text between is laid out the
     * same in both files). Null when they don't, or there's no point on one side: not sure.
     */
    fun shifted(place: EpubText.Place, anchors: List<Anchor>): EpubText.Place? {
        val here = anchors.filter { it.spine == place.spine }
        val before = here.filter { it.at <= place.start }.maxByOrNull { it.at } ?: return null
        val after = here.filter { it.at >= place.end }.minByOrNull { it.at } ?: return null
        val d = when {
            before.exact && after.exact -> before.delta.takeIf { it == after.delta }
            before.exact -> before.delta.takeIf { kotlin.math.abs(after.delta - it) <= 2 }
            after.exact -> after.delta.takeIf { kotlin.math.abs(before.delta - it) <= 2 }
            else -> before.delta.takeIf { it == after.delta }
        } ?: return null
        if (place.start + d < 0) return null
        return EpubText.Place(place.spine, place.start + d, place.end + d)
    }

    /** [found] (path -> "modified|size") with each file's ID: kept for unchanged files, worked out by [id] for the rest. */
    fun refresh(old: Map<String, String>, found: Map<String, String>, id: (String) -> String?): Map<String, String> =
        found.mapNotNull { (path, stamp) ->
            val prev = old[path]
            if (prev != null && prev.substringBeforeLast('|') == stamp) path to prev
            else id(path)?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }?.let { path to "$stamp|$it" }
        }.toMap()

    /**
     * Where each KOReader highlight goes on CrossPoint, for the ones found exactly once. When the
     * EPUB here is only the same by name ([sameFile] false), a place counts only when it's in the
     * very chapter KOReader says, so a copy laid out differently isn't trusted.
     */
    fun forCrossPoint(book: EpubText.Book, clips: List<Clipping>, sameFile: Boolean = true): Map<String, EpubText.Place> =
        clips.mapNotNull { c ->
            runCatching { EpubText.find(book, c.text, c.spine) }.getOrNull()
                ?.takeIf { sameFile || (c.spine != null && it.spine == c.spine) }
                ?.let { c.id to it }
        }.toMap()

    private fun key(t: String) = t.lowercase().filter { it.isLetterOrDigit() }

    private val docFragment = Regex("""DocFragment\[(\d+)]""")

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * KOReader's positions for CrossPoint highlight [c] in [book]: CrossPoint's own place when the
     * text there is the highlight's, else where its words are found once. Checked both ways (the
     * positions lead back to the same words), or null.
     */
    fun koreaderPlace(book: EpubText.Book, c: Clipping, sameFile: Boolean = true): Triple<EpubText.Place, String, String>? {
        val given = if (c.spine != null && c.start != null && c.end != null) {
            EpubText.Place(c.spine, c.start, c.end).takeIf { EpubText.matches(book, it, c.text) }
        } else null
        // A copy that's the same only by name: only when CrossPoint's own place reads the same words here.
        if (!sameFile && given == null) return null
        val place = given ?: EpubText.find(book, c.text, c.spine) ?: return null
        val ch = book.chapter(place.spine) ?: return null
        val (p0, p1) = ch.koreaderRange(place.start, place.end) ?: return null
        val a = ch.offsetOf(p0) ?: return null
        val b = ch.offsetOf(p1) ?: return null
        if (a < place.start || b != place.end) return null
        if (!EpubText.matches(book, EpubText.Place(place.spine, a, b), c.text)) return null
        return Triple(place, p0, p1)
    }

    data class Added(val json: String, val added: List<String>, val skipped: List<String>)

    /** Bookmarks written: the file, and for each CrossPoint bookmark id its key there (added), or why not. */
    data class BookmarksAdded(val json: String, val added: Map<String, String>, val skipped: List<String>, val removed: List<String>)

    /** KOReader's position for a CrossPoint bookmark in [book]: its own when it reads in this copy, else where its opening words are. */
    fun bookmarkPlace(book: EpubText.Book, b: io.github.prithirajn.readync.source.CrossPointClient.Bookmark, sameFile: Boolean): String? {
        val xp = b.xpath?.trim()
        if (xp != null) {
            val spine = docFragment.find(xp)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
            val ch = spine?.let { book.chapter(it) }
            val off = ch?.offsetOf(xp)
            if (ch != null && off != null) ch.koreaderRange(off, off + 1)?.first?.let { return it }
        }
        if (!sameFile) return null
        val words = b.summary?.trim()?.takeIf { it.length >= 12 } ?: return null
        val place = EpubText.find(book, words, b.spine) ?: return null
        return book.chapter(place.spine)?.koreaderRange(place.start, place.start + 1)?.first
    }

    /**
     * AnnotationSync's file for a book ([existing], a JSON object) with CrossPoint bookmarks added
     * ("BOOKMARK|<position>", as KOReader keeps them) and the ones deleted on CrossPoint since they
     * were given ([sent]: bookmark id -> key) marked deleted. One KOReader already has at that place
     * (or deleted there) isn't added again. Null when the file isn't AnnotationSync's.
     */
    fun addBookmarksToAnnotationSync(
        existing: String?, book: EpubText.Book, marks: List<io.github.prithirajn.readync.source.CrossPointClient.Bookmark>,
        sent: Map<String, String>, sameFile: Boolean, zone: ZoneId = ZoneId.systemDefault(),
    ): BookmarksAdded? {
        val text = existing?.trim().orEmpty()
        val keyed = when {
            text.isEmpty() -> JSONObject()
            text.startsWith("{") -> runCatching { JSONObject(text) }.getOrNull() ?: return null
            else -> return null
        }
        val pages = ArrayList<Pair<Int, Int>>()
        keyed.keys().forEach { k ->
            val o = keyed.optJSONObject(k) ?: return@forEach
            val spine = docFragment.find(o.optString("pos0", o.optString("page", "")))?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
            val page = o.optInt("pageno", 0)
            if (spine != null && page > 0) pages += spine to page
        }
        val added = LinkedHashMap<String, String>()
        val skipped = ArrayList<String>()
        val removed = ArrayList<String>()
        val now = stamp.format(Instant.now().atZone(zone))
        for (b in marks) {
            if (b.deleted) {
                // Deleted on CrossPoint after it was given: deleted in KOReader too, the way AnnotationSync marks it.
                val k = sent[b.id] ?: continue
                val o = keyed.optJSONObject(k) ?: continue
                if (!o.optBoolean("deleted", false)) { o.put("deleted", true).put("datetime_updated", now); removed += b.id }
                continue
            }
            if (b.id in sent) continue
            val p = bookmarkPlace(book, b, sameFile)
            if (p == null) { skipped += b.id; continue }
            val k = "BOOKMARK|$p"
            if (keyed.has(k)) { added[b.id] = k; continue } // KOReader has one there already (or deleted it)
            val spine = docFragment.find(p)?.groupValues?.get(1)?.toIntOrNull()?.minus(1) ?: 0
            val page = pages.filter { it.first <= spine }.maxByOrNull { it.first }?.second ?: pages.minByOrNull { it.first }?.second ?: 1
            val label = b.name?.trim()?.takeIf { it.isNotEmpty() } ?: b.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { it.take(80) + "…" } ?: "Bookmark"
            val o = JSONObject()
                .put("datetime", stamp.format(Instant.ofEpochSecond(b.updatedAt.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)).atZone(zone)))
                .put("page", p).put("pageno", page).put("text", label)
            keyed.put(k, o)
            pages += spine to page
            added[b.id] = k
        }
        return BookmarksAdded(keyed.toString(), added, skipped, removed)
    }

    /**
     * A KOReader sync plugin's file for a book ([existing]) with CrossPoint highlights [clips]
     * added: the ones whose words KOReader doesn't already have highlighted (or deleted) and whose
     * place is sure. Everything already in the file is kept as it was. HighlightSync's file is a
     * JSON array; AnnotationSync's is a JSON object keyed "pos0||pos1" (its merge takes in entries
     * it doesn't have, and a highlight deleted in KOReader stays there marked "deleted", so it isn't
     * added again). Null when the file is neither.
     */
    fun addToHighlightSync(existing: String?, book: EpubText.Book, clips: List<Clipping>, zone: ZoneId = ZoneId.systemDefault(), sameFile: Boolean = true): Added? {
        val text = existing?.trim().orEmpty()
        // AnnotationSync: an object keyed by position. Read into a list here, written back keyed.
        val keyed: JSONObject? = if (text.startsWith("{")) runCatching { JSONObject(text) }.getOrNull() ?: return null else null
        val arr = when {
            keyed != null -> JSONArray().also { a -> keyed.keys().forEach { k -> keyed.optJSONObject(k)?.let(a::put) } }
            text.isEmpty() -> JSONArray()
            text.startsWith("[") -> runCatching { JSONArray(text) }.getOrNull() ?: return null
            else -> return null
        }
        val have = ArrayList<String>()
        val pages = ArrayList<Pair<Int, Int>>() // (spine, page) of what's there
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            key(o.optString("text", "")).takeIf { it.isNotEmpty() }?.let(have::add)
            val spine = docFragment.find(o.optString("pos0", o.optString("page", "")))?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
            val page = o.optInt("pageno", 0)
            if (spine != null && page > 0) pages += spine to page
        }
        val added = ArrayList<String>()
        val skipped = ArrayList<String>()
        for (c in clips) {
            val k = key(c.text)
            if (k.isEmpty() || have.any { it.contains(k) || k.contains(it) }) { skipped += c.id; continue }
            val found = koreaderPlace(book, c, sameFile)
            if (found == null) { skipped += c.id; continue }
            val (place, p0, p1) = found
            // KOReader works out page numbers itself; a near one keeps the list in order meanwhile.
            val page = pages.filter { it.first <= place.spine }.maxByOrNull { it.first }?.second
                ?: pages.minByOrNull { it.first }?.second ?: 1
            val o = JSONObject()
                .put("datetime", stamp.format(Instant.ofEpochSecond(c.createdAt.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)).atZone(zone)))
                .put("drawer", "lighten").put("color", "yellow")
                .put("text", c.text).put("page", p0).put("pos0", p0).put("pos1", p1).put("pageno", page)
            c.chapter?.takeIf { it.isNotBlank() }?.let { o.put("chapter", it) }
            c.note?.takeIf { it.isNotBlank() }?.let { o.put("note", it) }
            if (keyed != null) {
                val at = "$p0||$p1"
                if (keyed.has(at)) { skipped += c.id; continue }
                keyed.put(at, o)
            } else arr.put(o)
            have += k
            pages += place.spine to page
            added += c.id
        }
        return Added((keyed ?: arr).toString(), added, skipped)
    }
}
