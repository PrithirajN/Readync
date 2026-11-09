package io.github.prithirajn.trackersync.source

import io.github.prithirajn.trackersync.data.Account
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
 * Bookmarks (no passage) are left out unless they carry a note. Nothing is written.
 */
class KoHighlightsClient(account: Account) {
    private val dav = MoonReaderClient(account)

    fun verify(): Boolean = dav.verify()

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
    fun files(folders: List<String>, known: Map<String, String> = emptyMap(), seen: MutableMap<String, String> = HashMap()): List<KoFile> {
        val out = ArrayList<KoFile>()
        for (folder in folders) {
            for (e in dav.list(dav.urlFor(folder)).filter { !it.folder && isSyncFile(it.path) }) {
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
                val place = k ?: listOf(a.opt("pos0"), a.opt("pos1")).joinToString("||") { (it ?: "").toString() }
                KoAnnotation(
                    key = place.ifBlank { "$created|${text.take(40)}" },
                    text = text.ifEmpty { note ?: "" },
                    note = note?.takeIf { text.isNotEmpty() },
                    chapter = a.optString("chapter", "").trim().takeIf { it.isNotEmpty() },
                    pageno = a.optInt("pageno", 0).takeIf { it > 0 },
                    createdAt = created,
                    updatedAt = timeOf(a.optString("datetime_updated", "")),
                    spine = listOf(a.opt("pos0"), a.opt("page")).firstNotNullOfOrNull { p ->
                        (p as? String)?.let { Regex("DocFragment\\[(\\d+)\\]").find(it)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)?.takeIf { n -> n >= 0 } }
                    },
                )
            }
            return KoFile(path, docId, bookName, list)
        }
    }
}
