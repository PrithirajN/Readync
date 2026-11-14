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
        s.koBooks.entries.firstOrNull { it.value.substringAfterLast('|') == document }?.let { return Found(it.key, true) }
        return s.koBooks.keys.firstOrNull { fileId(it) == document }?.let { Found(it, false) }
    }

    fun bookPath(s: Snapshot, document: String): String? = bookFor(s, document)?.path

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

    /**
     * HighlightSync's file for a book ([existing], a JSON array of KOReader annotations) with
     * CrossPoint highlights [clips] added: the ones whose words KOReader doesn't already have
     * highlighted and whose place is sure. Everything already in the file is kept as it was.
     * Null when the file isn't HighlightSync's kind (AnnotationSync's isn't written to).
     */
    fun addToHighlightSync(existing: String?, book: EpubText.Book, clips: List<Clipping>, zone: ZoneId = ZoneId.systemDefault(), sameFile: Boolean = true): Added? {
        val text = existing?.trim().orEmpty()
        val arr = when {
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
            arr.put(o)
            have += k
            pages += place.spine to page
            added += c.id
        }
        return Added(arr.toString(), added, skipped)
    }
}
