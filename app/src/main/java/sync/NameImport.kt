package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.source.MoonReaderClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Names for books known only by KOReader's document ID, from files you have: one or more, read
 * together. Any of these work:
 *
 * - A list of document IDs with names: JSON ({"<id>": "Title - Author"}) or CSV/text lines
 *   ("<id>, Title, Author" or "<id>  Title - Author").
 * - A list of document IDs with another number for each (a Calibre book id, say:
 *   {"<id>": 282}), plus anything giving those numbers names: Calibre's CSV catalog (id, title,
 *   authors), or JSON records with an "id" and a "title"/"book"/"name" (and "authors"/"author"),
 *   even inside a script.
 *
 * Nothing is matched by guessing here: a name is used only when the files tie it to the ID.
 */
object NameImport {

    data class Result(val names: Map<String, Pair<String, String?>>, val unresolved: Int)

    private val hex32 = Regex("(?i)^[0-9a-f]{32}$")

    /** "Cat Who Saved Books, The - Sosuke Natsukawa" -> ("The Cat Who Saved Books", "Sosuke Natsukawa"), with file-name noise taken off. */
    internal fun split(name: String): Pair<String, String?> {
        val clean = name
            .replace(Regex("(?i)\\.(epub|kepub|azw3?|mobi|pdf|fb2|txt)$"), "")
            // A file name's "_ " stands for a colon ("Ripe_ A Novel"); an ISBN on the end isn't part of the name.
            .replace("_ ", ": ")
            .replace(Regex("[\\s_]*-[\\s_]*(97[89])?\\d{9}[\\dXx]\\s*$"), "")
            .replace(Regex("(?i)[_ ]?(z-library|1lib|z-lib|worldfreebooks)\\S*(,\\s*\\S+)*"), "")
            .replace(Regex("_+"), " ")
            .replace(Regex("\\s*\\(\\d+\\)\\s*$"), "")
            .replace(Regex("\\s+"), " ").trim().trimEnd(',', '-', ' ')
        var (t, a) = MoonReaderClient.guesses(clean).firstOrNull() ?: (clean to null)
        // "Roger Zelazny - A Night in the Lonesome October": the author first, when the first part reads like a name and the other doesn't.
        fun nameLike(x: String) = x.split(' ').let { w -> w.size in 2..3 && w.all { it.firstOrNull()?.isUpperCase() == true } && w.none { it.lowercase() in setOf("the", "a", "an", "of", "and", "in") } }
        if (a != null && nameLike(t) && !nameLike(a) && a.split(' ').size > t.split(' ').size) { val x = t; t = a; a = x }
        // The title without a long subtitle ("Trad Wife: A Modern Social Horror…" -> "Trad Wife").
        t = t.substringBefore(": ").trim().ifEmpty { t }
        return KoHighlightSync.tidyTitle(t.replace(Regex("\\s*\\(\\d+\\)\\s*$"), "").trim()) to a?.replace(Regex("\\s*\\(\\d+\\)\\s*$"), "")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Every JSON value in [text]: the whole text, else each JSON object or array found inside it (a script's data). */
    private fun jsonValues(text: String): List<Any> {
        val t = text.trim()
        runCatching { return listOf(if (t.startsWith("[")) JSONArray(t) else JSONObject(t)) }
        val out = ArrayList<Any>()
        var i = 0
        while (i < text.length && out.size < 50) {
            val c = text[i]
            if (c == '[' || c == '{') {
                val end = matching(text, i)
                if (end > i) {
                    val chunk = text.substring(i, end + 1)
                    val v = runCatching { if (c == '[') JSONArray(chunk) else JSONObject(chunk) }.getOrNull()
                    if (v != null && chunk.length > 20) { out += v; i = end + 1; continue }
                }
            }
            i++
        }
        return out
    }

    /** Where the bracket at [start] closes, skipping strings; -1 when it doesn't. */
    private fun matching(s: String, start: Int): Int {
        var depth = 0
        var inStr = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') i++ else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '[', '{' -> depth++
                ']', '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    private fun str(o: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> o.opt(k)?.let { v -> (if (v is JSONArray) (0 until v.length()).joinToString(", ") { v.optString(it) } else v.toString()).trim().takeIf { it.isNotEmpty() && it != "null" } } }

    fun read(files: List<String>): Result {
        val direct = HashMap<String, Pair<String, String?>>() // ID -> name
        val byNumber = HashMap<String, String>() // ID -> another number (a Calibre id)
        val numbered = HashMap<String, Pair<String, String?>>() // that number -> name

        fun record(o: JSONObject) {
            val id = str(o, "id", "calibre_id", "book_id") ?: return
            val title = str(o, "title", "book", "name") ?: return
            val author = str(o, "authors", "author", "author_sort")
            numbered[id] = if (author != null && title.contains(" - ").not()) KoHighlightSync.tidyTitle(title.trim()) to author else split(title).let { (t, a) -> t to (author ?: a) }
        }
        fun map(o: JSONObject) {
            for (k in o.keys()) {
                if (!hex32.matches(k)) continue
                when (val v = o.opt(k)) {
                    is Number -> byNumber[k.lowercase()] = v.toLong().toString()
                    is String -> if (v.trim().matches(Regex("\\d+"))) byNumber[k.lowercase()] = v.trim() else direct[k.lowercase()] = split(v)
                    is JSONObject -> str(v, "title", "book", "name")?.let { t -> direct[k.lowercase()] = split(t).let { (tt, a) -> tt to (str(v, "authors", "author") ?: a) } }
                }
            }
        }

        for (text in files) {
            for (v in jsonValues(text)) {
                when (v) {
                    is JSONObject -> { map(v); record(v) }
                    is JSONArray -> for (i in 0 until v.length()) v.optJSONObject(i)?.let { o -> map(o); record(o); str(o, "document", "hash", "md5", "partial_md5_checksum")?.takeIf { hex32.matches(it) }?.let { h -> str(o, "title", "book", "name")?.let { t -> direct[h.lowercase()] = split(t).let { (tt, a) -> tt to (str(o, "authors", "author") ?: a) } } } }
                }
            }
            // CSV or plain lines.
            if (jsonValues(text).isEmpty()) {
                val rows = HistoryImport.csvRows(text)
                val head = rows.firstOrNull()?.map { it.trim().lowercase() } ?: emptyList()
                val idCol = head.indexOfFirst { it == "id" || it == "calibre_id" }
                val titleCol = head.indexOf("title")
                val authCol = head.indexOfFirst { it == "authors" || it == "author" }
                if (idCol >= 0 && titleCol >= 0) {
                    // Calibre's catalog: id, title, authors…
                    for (r in rows.drop(1)) {
                        val id = r.getOrNull(idCol)?.trim() ?: continue
                        val t = r.getOrNull(titleCol)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                        numbered[id] = KoHighlightSync.tidyTitle(t) to r.getOrNull(authCol)?.trim()?.replace(" & ", ", ")?.takeIf { it.isNotEmpty() }
                    }
                } else for (line in text.lines()) {
                    val m = Regex("(?i)\\b([0-9a-f]{32})\\b[\\s,;:|\\t=-]+(.+)").find(line) ?: continue
                    val rest = m.groupValues[2].trim().trim('"')
                    if (rest.matches(Regex("\\d+"))) { byNumber[m.groupValues[1].lowercase()] = rest; continue }
                    val parts = HistoryImport.csvRows(rest).firstOrNull()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: continue
                    direct[m.groupValues[1].lowercase()] = if (parts.size >= 2) KoHighlightSync.tidyTitle(parts[0]) to parts[1] else split(parts[0])
                }
            }
        }
        val names = HashMap(direct)
        var unresolved = 0
        for ((h, n) in byNumber) {
            if (names.containsKey(h)) continue
            numbered[n]?.let { names[h] = it } ?: unresolved++
        }
        return Result(names, unresolved)
    }

    /**
     * [s] with the names in as titles: books here without one take theirs, and KOReader's files
     * waiting for a name (they're read again every sync until matched) find their book on the next.
     */
    fun apply(s: Snapshot, r: Result): Snapshot {
        val titles = HashMap(s.koTitles)
        for ((h, n) in r.names) titles[h] = n.first + "\t" + (n.second ?: "")
        return s.copy(koTitles = titles)
    }

    /** How many of [r]'s IDs belong to something here: a book's own ID, or a KOReader file waiting for its name. */
    fun relevant(s: Snapshot, r: Result): Int = r.names.keys.count { h ->
        s.books.containsKey(h) || s.books.values.any { h in it.aliases } || s.koHlUnmatched.contains(h) ||
            s.koHlDocs.keys.any { it.contains(h) } || s.koHlMods.keys.any { it.contains(h) }
    }
}
