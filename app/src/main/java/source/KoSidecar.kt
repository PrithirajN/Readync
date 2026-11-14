package io.github.prithirajn.readync.source

/**
 * KOReader's sidecar files: "metadata.<ext>.lua" in each book's ".sdr" folder (next to the book,
 * or in koreader/docsettings or koreader/hashdocsettings), the book's settings as a Lua table
 * KOReader writes with its own dump. They hold the book's highlights and notes ("annotations",
 * or "highlight" in older versions), its document ID ("partial_md5_checksum", the same one
 * KOSync and CrossPoint use), its title and authors ("doc_props"), and how far you are.
 *
 * Read from a folder on this device you picked; Android's own part ([reader]) lists and reads the
 * files, the rest here is plain parsing.
 */
object KoSidecar {

    /** One sidecar file read: where it is (a stable id) and its text. */
    data class Raw(val path: String, val text: String, val modified: Long = 0L)

    /**
     * Lists the sidecar files under the picked folder [tree] and reads the ones whose "last
     * modified" isn't [known]'s (path -> stamp); [seen] gets every file's stamp. Set by the app.
     */
    @Volatile var reader: ((tree: String, known: Map<String, String>, seen: MutableMap<String, String>) -> List<Raw>)? = null

    /**
     * The EPUBs under the picked folder (path -> "last modified|size"), found on the same walk as
     * the sidecars ([reader] fills it), so a highlight can be placed on its exact words.
     */
    @Volatile var books: Map<String, String> = emptyMap()

    /** A book file's KOReader document ID (its partial MD5), or null. Set by the app. */
    @Volatile var bookId: ((tree: String, path: String) -> String?)? = null

    /** A book file's bytes, or null. Set by the app. */
    @Volatile var bookBytes: ((tree: String, path: String) -> ByteArray?)? = null

    /**
     * Where KOReader is in the book, from its sidecar ("percent_finished", and the book marked
     * finished in its status), as of when the file was last saved ([modifiedMs]). For KOReader on
     * this device without any sync. Null when it doesn't say.
     */
    fun position(path: String, lua: String, modifiedMs: Long): KoPosition? {
        if (modifiedMs <= 0) return null
        val root = runCatching { Lua.parse(lua) }.getOrNull() as? Map<*, *> ?: return null
        var pct = (root["percent_finished"] as? Double)?.takeIf { !it.isNaN() && it >= 0 }?.coerceAtMost(1.0) ?: return null
        if (((root["summary"] as? Map<*, *>)?.get("status") as? String) == "complete") pct = 1.0
        val f = parse(path, lua)
        val pages = (root["doc_pages"] as? Double)?.toInt()?.takeIf { it > 0 }
        return KoPosition(
            path = path, documentId = f?.documentId, bookName = f?.bookName, percentage = pct, timestamp = modifiedMs / 1000,
            device = "KOReader on this device", page = pages?.let { Math.round(it * pct).toInt().coerceAtLeast(1) }, fileName = f?.fileName,
        )
    }

    /** A sidecar's highlights, as the same per-book file the sync plugins give. Null when it isn't one. */
    fun parse(path: String, lua: String): KoFile? {
        val root = runCatching { Lua.parse(lua) }.getOrNull() as? Map<*, *> ?: return null
        val docId = (root["partial_md5_checksum"] as? String)?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }
        val props = root["doc_props"] as? Map<*, *>
        val title = (props?.get("title") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val authors = (props?.get("authors") as? String)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(", ")?.takeIf { it.isNotEmpty() }
        // The ".sdr" folder's name is the book file's name without its extension.
        val fromFolder = path.substringBeforeLast('/').substringAfterLast('/').removeSuffix(".sdr").takeIf { it.isNotBlank() && !it.matches(Regex("[0-9a-fA-F]{32}")) }
        val name = title?.let { t -> authors?.let { "$t - $it" } ?: t } ?: fromFolder

        val entries = ArrayList<Map<*, *>>()
        (root["annotations"] as? Map<*, *>)?.values?.forEach { (it as? Map<*, *>)?.let(entries::add) }
        (root["annotations"] as? List<*>)?.forEach { (it as? Map<*, *>)?.let(entries::add) }
        // Older KOReader: highlights by page, ["highlight"] = { [page] = { [1] = {...} } }.
        if (entries.isEmpty()) {
            (root["highlight"] as? Map<*, *>)?.values?.forEach { page ->
                when (page) {
                    is Map<*, *> -> page.values.forEach { (it as? Map<*, *>)?.let(entries::add) }
                    is List<*> -> page.forEach { (it as? Map<*, *>)?.let(entries::add) }
                }
            }
        }
        val json = org.json.JSONArray()
        for (e in entries) {
            val o = org.json.JSONObject()
            for ((k, v) in e) {
                if (k !is String) continue
                when (v) {
                    is String, is Boolean -> o.put(k, v)
                    is Double -> o.put(k, if (v % 1.0 == 0.0) v.toLong() else v)
                    is Map<*, *> -> o.put(k, org.json.JSONObject(v.mapKeys { it.key.toString() }))
                }
            }
            json.put(o)
        }
        return KoHighlightsClient.parse(path, json.toString())?.copy(
            path = path, documentId = docId, bookName = name, title = title, author = authors,
            pages = (root["doc_pages"] as? Double)?.toInt()?.takeIf { it > 0 },
            // The book file: the ".sdr" folder's name plus the extension in "metadata.<ext>.lua".
            fileName = fromFolder?.let { stem ->
                path.substringAfterLast('/').removePrefix("metadata.").removeSuffix(".lua").takeIf { it.isNotEmpty() && !it.contains('/') }?.let { "$stem.$it" }
            },
        )
    }

    /**
     * The Lua that KOReader's dump writes: tables with ["key"] = value and [1] = value, strings
     * in double quotes with Lua escapes, numbers, true/false and nil. A table whose keys are 1..n
     * comes back as a List, any other as a Map.
     */
    object Lua {
        fun parse(text: String): Any? {
            val p = P(text)
            p.ws()
            if (p.peekWord("return")) p.i += 6
            return p.value()
        }

        private class P(val s: String) {
            var i = 0

            fun ws() {
                while (i < s.length) {
                    val c = s[i]
                    if (c.isWhitespace() || c == ';') { i++; continue }
                    if (c == '-' && s.startsWith("--", i)) {
                        if (s.startsWith("--[[", i)) { val e = s.indexOf("]]", i); i = if (e < 0) s.length else e + 2 }
                        else { while (i < s.length && s[i] != '\n') i++ }
                        continue
                    }
                    break
                }
            }

            fun peekWord(w: String) = s.startsWith(w, i) && (i + w.length >= s.length || !s[i + w.length].isLetterOrDigit())

            fun value(): Any? {
                ws()
                if (i >= s.length) throw IllegalStateException("end of text")
                return when {
                    s[i] == '{' -> table()
                    s[i] == '"' || s[i] == '\'' -> string()
                    s.startsWith("[[", i) || s.startsWith("[=", i) -> longString()
                    peekWord("true") -> { i += 4; true }
                    peekWord("false") -> { i += 5; false }
                    peekWord("nil") -> { i += 3; null }
                    else -> number()
                }
            }

            fun number(): Double {
                val start = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "+-.")) {
                    if ((s[i] == '+' || s[i] == '-') && i > start && s[i - 1] != 'e' && s[i - 1] != 'E') break
                    i++
                }
                val t = s.substring(start, i)
                return when {
                    t.startsWith("0x") || t.startsWith("0X") -> t.drop(2).toLong(16).toDouble()
                    t.startsWith("-0x") -> -t.drop(3).toLong(16).toDouble()
                    t == "inf" || t == "math.huge" -> Double.POSITIVE_INFINITY
                    else -> t.toDoubleOrNull() ?: throw IllegalStateException("bad value '$t' at $start")
                }
            }

            fun longString(): String {
                var level = 0
                var j = i + 1
                while (j < s.length && s[j] == '=') { level++; j++ }
                val close = "]" + "=".repeat(level) + "]"
                val start = j + 1
                val end = s.indexOf(close, start)
                i = if (end < 0) s.length else end + close.length
                return s.substring(start, if (end < 0) s.length else end).removePrefix("\n")
            }

            fun string(): String {
                val q = s[i++]
                val sb = StringBuilder()
                while (i < s.length) {
                    val c = s[i++]
                    if (c == q) return fixBytes(sb.toString())
                    if (c != '\\') { sb.append(c); continue }
                    if (i >= s.length) break
                    when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'a' -> sb.append('\u0007'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'v' -> sb.append('\u000B')
                        '\n' -> sb.append('\n') // %q writes a newline as a backslash and a real newline
                        '\r' -> { sb.append('\n'); if (i < s.length && s[i] == '\n') i++ }
                        'z' -> { while (i < s.length && s[i].isWhitespace()) i++ }
                        'x' -> { val h = s.substring(i, minOf(i + 2, s.length)); i += h.length; sb.append(h.toInt(16).toChar()) }
                        else -> if (e.isDigit()) {
                            // Up to three decimal digits: a byte. KOReader's text is UTF-8, so bytes are gathered and decoded.
                            var n = e - '0'
                            var k = 0
                            while (k < 2 && i < s.length && s[i].isDigit()) { n = n * 10 + (s[i] - '0'); i++; k++ }
                            sb.append(n.toChar())
                        } else sb.append(e)
                    }
                }
                return fixBytes(sb.toString())
            }

            /** Escaped bytes (\226\128\153) back into the UTF-8 characters they spell. */
            fun fixBytes(t: String): String {
                if (t.none { it.code in 0x80..0xFF }) return t
                val bytes = java.io.ByteArrayOutputStream()
                for (ch in t) {
                    if (ch.code in 0x80..0xFF) bytes.write(ch.code) else bytes.write(ch.toString().toByteArray(Charsets.UTF_8))
                }
                return runCatching { String(bytes.toByteArray(), Charsets.UTF_8) }.getOrDefault(t)
            }

            fun table(): Any {
                i++ // {
                val map = LinkedHashMap<Any, Any?>()
                var n = 1
                while (true) {
                    ws()
                    if (i >= s.length) break
                    if (s[i] == '}') { i++; break }
                    val key: Any
                    if (s[i] == '[' && !s.startsWith("[[", i) && !s.startsWith("[=", i)) {
                        i++
                        val k = value()
                        ws(); if (i < s.length && s[i] == ']') i++
                        ws(); if (i < s.length && s[i] == '=') i++
                        key = when (k) {
                            is Double -> if (k % 1.0 == 0.0) k.toLong() else k
                            null -> "nil"
                            else -> k
                        }
                    } else if (s[i].isLetter() || s[i] == '_') {
                        val start = i
                        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                        val word = s.substring(start, i)
                        ws()
                        if (i < s.length && s[i] == '=' && !s.startsWith("==", i)) { i++; key = word }
                        else { i = start; key = (n++).toLong() }
                    } else key = (n++).toLong()
                    val v = value()
                    map[key] = v
                    ws()
                    if (i < s.length && (s[i] == ',' || s[i] == ';')) i++
                }
                val longs = map.keys.filterIsInstance<Long>()
                return if (map.isNotEmpty() && longs.size == map.size && longs.sorted() == (1L..map.size.toLong()).toList()) {
                    longs.sorted().map { map[it] }
                } else map
            }
        }
    }
}
