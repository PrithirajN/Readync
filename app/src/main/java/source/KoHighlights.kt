package io.github.prithirajn.readync.source

import io.github.prithirajn.readync.data.Account
import org.json.JSONArray
import org.json.JSONObject

/** One KOReader highlight or note, from a sync plugin's file. [createdAt] is unix seconds. */
data class KoAnnotation(
    /** Stable for the same highlight between syncs: its position in the book, else its time and text. */
    val key: String,
    val text: String,
    val note: String?,
    val chapter: String?,
    val pageno: Int?,
    val createdAt: Long,
    val updatedAt: Long?,
    /** Its chapter as the book file's spine index (0 first), from KOReader's position ("/body/DocFragment[N]…"), for CrossPoint. */
    val spine: Int? = null,
    /** A note left on a bookmark (no passage): [text] is the note itself. */
    val bookmark: Boolean = false,
)

/** One book's file from a KOReader sync plugin: what names the book, and its highlights. */
data class KoFile(
    /** The file's path on the server. */
    val path: String,
    /** KOReader's document ID when the file is named by it (AnnotationSync's default), else null. */
    val documentId: String?,
    /** The book's file name, when the sync file is named after it (HighlightSync, or AnnotationSync's "use filename"). */
    val bookName: String?,
    val annotations: List<KoAnnotation>,
    /** The book's own title and author, when the file says (KOReader's sidecar does). */
    val title: String? = null,
    val author: String? = null,
    /** How many pages KOReader lays the book out in, when the file says (its sidecar does). */
    val pages: Int? = null,
    /** The book's own file name as KOReader has it ("Dune - Frank Herbert.epub"), when known exactly, for "Filename" matching. */
    val fileName: String? = null,
)

/**
 * Where KOReader is in a book, from AnnotationSync's "<document ID>.progress.json" (or
 * "<book file name>.progress.json"): its newest position from any of your devices.
 */
data class KoPosition(
    val path: String,
    val documentId: String?,
    val bookName: String?,
    val percentage: Double,
    /** Unix seconds. */
    val timestamp: Long,
    val device: String?,
    /** KOReader's page there, in its own layout. */
    val page: Int? = null,
    /** The book's own file name, when the position file is named after it. */
    val fileName: String? = null,
)

/**
 * KOReader highlights and notes from the files its sync plugins keep in cloud storage (Koofr or
 * any WebDAV), one JSON file per book:
 *
 * - HighlightSync (gitalexcampos/highlightsync.koplugin): "<book file name>.sdr.json", KOReader's
 *   annotation list as it is.
 * - AnnotationSync (dani84bs/AnnotationSync.koplugin): "<KOReader document ID>.json" (or
 *   "<book file name>.json" with "Use filename instead of hash"), the annotations keyed by their
 *   place, deleted ones kept with "deleted" set.
 *
 * Each annotation is KOReader's own: the passage ("text"), your "note", "chapter", "pageno",
 * "datetime" (and "datetime_updated"), "drawer" (its style), "pos0"/"pos1" (where it is).
 * Bookmarks (no passage) are left out unless they carry a note. AnnotationSync's
 * "….progress.json" files give where you are in each book. Nothing here writes; only the opt-in
 * "Also give CrossPoint highlights to KOReader" adds to HighlightSync's file.
 */
class KoHighlightsClient(account: Account) {
    private val dav = MoonReaderClient(account)

    fun verify(): Boolean = dav.verify()

    /** EPUBs in [folder] and a few levels below it (a Calibre library keeps each book in Author/Title/). */
    fun epubs(folder: String, depth: Int = 3): List<MoonReaderClient.Entry> {
        val out = ArrayList<MoonReaderClient.Entry>()
        var level = listOf(folder)
        var seen = 0
        repeat(depth + 1) {
            val next = ArrayList<String>()
            for (dir in level) {
                val entries = runCatching { dav.list(dav.urlFor(dir)) }.getOrElse { if (it is io.github.prithirajn.readync.net.AuthException) throw it else emptyList() }
                for (e in entries) {
                    if (e.folder) { if (++seen < 1500) next += dav.relative(e.path) }
                    else if (e.path.endsWith(".epub", true)) out += e.copy(path = dav.relative(e.path))
                }
            }
            if (next.isEmpty()) return out
            level = next
        }
        return out
    }

    /** A folder holding your EPUBs, found by looking for one: the top folder it's in ("/Calibre Library", "/Books"). */
    fun findBooksFolder(): String? {
        val likely = listOf("/Calibre Library", "/Calibre", "/Books", "/eBooks", "/Ebooks", "/KOReader", "/Documents")
        val hit = dav.findFolders(likely, max = 1) { it.path.endsWith(".epub", true) }.firstOrNull() ?: return null
        val top = hit.split('/').filter { it.isNotEmpty() }.firstOrNull() ?: return "/"
        return "/$top"
    }

    /** A book file's bytes. */
    fun bytes(rel: String): ByteArray? = dav.fetch(rel)

    /** Where the plugins usually keep their files, tried before looking through everything. */
    private val likely = listOf(
        "/KOReader", "/koreader", "/KOReader/highlights", "/KOReader/annotations", "/Highlights", "/highlights",
        "/HighlightSync", "/AnnotationSync", "/Annotations", "/annotations", "/Apps/KOReader", "/Books", "/books",
    )

    /** Folders holding the plugins' files: [hints] if they still have some, else found. */
    fun findFolders(hints: List<String> = emptyList()): List<String> {
        val ok = hints.filter { h -> runCatching { dav.list(dav.urlFor(h)) }.getOrDefault(emptyList()).any { !it.folder && isSyncFile(it.path) } }
        if (ok.isNotEmpty()) return ok
        return dav.findFolders(likely, max = 3) { isSyncFile(it.path) }
    }

    /**
     * Every book's file in [folders], read when it changed since [known] (path -> "last
     * modified" as the server gave it); [seen] gets every file's stamp.
     */
    fun files(
        folders: List<String>, known: Map<String, String> = emptyMap(), seen: MutableMap<String, String> = HashMap(),
        positions: MutableList<KoPosition>? = null,
    ): List<KoFile> {
        val out = ArrayList<KoFile>()
        for (folder in folders) {
            val entries = dav.list(dav.urlFor(folder)).filter { !it.folder }
            // AnnotationSync's position files, when asked for.
            if (positions != null) for (e in entries.filter { isProgressFile(it.path) }) {
                val rel = dav.relative(e.path)
                if (e.modified != null && known[rel] == e.modified) { seen[rel] = e.modified; continue }
                val bytes = dav.fetch(rel) ?: continue
                parseProgress(rel, String(bytes, Charsets.UTF_8))?.let(positions::add)
                e.modified?.let { seen[rel] = it }
            }
            for (e in entries.filter { isSyncFile(it.path) }) {
                val rel = dav.relative(e.path)
                if (e.modified != null && known[rel] == e.modified) { seen[rel] = e.modified; continue }
                val bytes = dav.fetch(rel) ?: continue
                val parsed = parse(rel, String(bytes, Charsets.UTF_8)) ?: continue
                e.modified?.let { seen[rel] = it }
                out += parsed
            }
        }
        return out
    }

    companion object {
        private val hex32 = Regex("(?i)^[0-9a-f]{32}$")

        /** A plugin's per-book file (not its progress or settings files). */
        internal fun isSyncFile(path: String): Boolean {
            val name = path.substringAfterLast('/')
            if (!name.endsWith(".json", true)) return false
            val lower = name.lowercase()
            if (lower.endsWith(".progress.json") || lower.startsWith("settings") || lower.startsWith("annotationsync") || lower == "sync.json") return false
            return true
        }

        /** AnnotationSync's per-book position file. */
        internal fun isProgressFile(path: String) = path.substringAfterLast('/').lowercase().endsWith(".progress.json")

        /** A position file: each device's place ({"Kindle": {"percentage": 0.42, "timestamp": "…"}}); the newest counts. */
        internal fun parseProgress(path: String, json: String): KoPosition? {
            val o = runCatching { JSONObject(json.trim()) }.getOrNull() ?: return null
            var best: KoPosition? = null
            for (device in o.keys()) {
                val d = o.optJSONObject(device) ?: continue
                val pct = d.optDouble("percentage", Double.NaN).takeIf { !it.isNaN() && it >= 0.0 }?.coerceAtMost(1.0) ?: continue
                val at = timeOf(d.optString("timestamp", "")) ?: d.optLong("timestamp", 0L).takeIf { it > 0 } ?: continue
                if (best == null || at > best.timestamp) {
                    val (docId, name) = nameOf(path.substringBeforeLast(".progress.json").substringBeforeLast(".PROGRESS.JSON") + ".json")
                    best = KoPosition(path, docId, name, pct, at, device, d.optInt("page", 0).takeIf { it > 0 }, fileNameOf(path))
                }
            }
            return best
        }

        /**
         * The book's exact file name from a plugin's file named after it: AnnotationSync's "Use
         * filename" keeps it whole ("Dune - Frank Herbert.epub.json"); HighlightSync's ".sdr.json"
         * has characters changed, so it isn't exact. Null for files named by document ID.
         */
        internal fun fileNameOf(path: String): String? {
            val base = path.substringAfterLast('/').let { n ->
                when {
                    n.endsWith(".progress.json", true) -> n.dropLast(".progress.json".length)
                    n.endsWith(".json", true) -> n.dropLast(5)
                    else -> n
                }
            }
            if (hex32.matches(base) || base.endsWith(".sdr", true)) return null
            return base.takeIf { it.contains('.') }
        }

        /** What a sync file's name says about its book. */
        internal fun nameOf(path: String): Pair<String?, String?> {
            val base = path.substringAfterLast('/').removeSuffix(".json").removeSuffix(".JSON")
            if (hex32.matches(base)) return base.lowercase() to null
            // HighlightSync: the sidecar folder's name ("Red_Rising.sdr"), with characters other
            // than letters, digits, ".", "-" and "_" made "_".
            val name = base.removeSuffix(".sdr").removeSuffix(".SDR")
            return null to name.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        }

        private fun timeOf(s: String?): Long? {
            val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            // KOReader writes local time: "2024-03-09 18:20:11".
            return runCatching {
                java.time.LocalDateTime.parse(t.replace(' ', 'T')).atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
            }.getOrNull() ?: runCatching { java.time.OffsetDateTime.parse(t).toEpochSecond() }.getOrNull()
        }

        /** A plugin's file: a list (HighlightSync) or a map keyed by place (AnnotationSync). Null when it's neither. */
        internal fun parse(path: String, json: String): KoFile? {
            val t = json.trim()
            val entries: List<Pair<String?, JSONObject>> = when {
                t.startsWith("[") -> runCatching { JSONArray(t) }.getOrNull()?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { null to it } } }
                t.startsWith("{") -> runCatching { JSONObject(t) }.getOrNull()?.let { o ->
                    // Some versions wrap the list: {"annotations": [...]}.
                    o.optJSONArray("annotations")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { null to it } } }
                        ?: o.keys().asSequence().mapNotNull { k -> o.optJSONObject(k)?.let { k to it } }.toList()
                }
                else -> null
            } ?: return null
            val (docId, bookName) = nameOf(path)
            val list = entries.mapNotNull { (k, a) ->
                if (a.optBoolean("deleted", false)) return@mapNotNull null
                val text = a.optString("text", "").trim()
                val note = a.optString("note", "").trim().takeIf { it.isNotEmpty() }
                val isHighlight = a.has("pos0") && a.has("pos1")
                if (text.isEmpty() && note == null) return@mapNotNull null
                if (!isHighlight && note == null) return@mapNotNull null // a plain bookmark
                val created = timeOf(a.optString("datetime", "")) ?: timeOf(a.optString("datetime_updated", "")) ?: 0L
                val place = k ?: if (isHighlight) listOf(a.opt("pos0"), a.opt("pos1")).joinToString("||") { (it ?: "").toString() }
                    else "BOOKMARK|" + (a.opt("page") ?: "")
                // A note on a bookmark: KOReader fills "text" with "in <chapter>", so the note is what's kept.
                val bookmark = !isHighlight
                KoAnnotation(
                    key = place.takeIf { it.isNotBlank() && it != "BOOKMARK|" } ?: "$created|${text.take(40)}",
                    text = if (bookmark) note!! else text.ifEmpty { note ?: "" },
                    note = if (bookmark) null else note?.takeIf { text.isNotEmpty() },
                    bookmark = bookmark,
                    chapter = a.optString("chapter", "").trim().takeIf { it.isNotEmpty() },
                    pageno = a.optInt("pageno", 0).takeIf { it > 0 },
                    createdAt = created,
                    updatedAt = timeOf(a.optString("datetime_updated", "")),
                    spine = listOf(a.opt("pos0"), a.opt("page")).firstNotNullOfOrNull { p ->
                        (p as? String)?.let { Regex("DocFragment\\[(\\d+)\\]").find(it)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)?.takeIf { n -> n >= 0 } }
                    },
                )
            }
            return KoFile(path, docId, bookName, list, fileName = fileNameOf(path))
        }
    }
}
