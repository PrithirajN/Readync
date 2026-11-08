package io.github.prithirajn.trackersync.source

import io.github.prithirajn.trackersync.data.Account
import io.github.prithirajn.trackersync.net.AuthException
import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.net.ServiceException
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** Where Moon+ Reader last was in one book, from its sync file ("Book.epub.po"). */
data class MoonPosition(
    /** The sync file's path on the server, which names the book. */
    val path: String,
    /** The book's file name without ".po" ("The Gate of the Feral Gods - Matt Dinniman.epub"). */
    val fileName: String,
    /** When Moon+ saved it (millis). */
    val savedAt: Long,
    val chapter: Int?,
    /** 0–1 through the whole book. */
    val fraction: Double,
    /** The book's own title, from its highlights file (more reliable than the file name). */
    val title: String? = null,
    /** Its highlights, when its highlights file changed since last time (null: not read this time). */
    val highlights: List<MoonHighlight>? = null,
)

/** One Moon+ Reader highlight, from a book's ".an" file. [createdAt] is millis. */
data class MoonHighlight(
    val id: String,
    val chapter: Int?,
    /** Where in the chapter it starts (characters). */
    val start: Long?,
    val text: String,
    val note: String?,
    val createdAt: Long,
)

/**
 * Moon+ Reader's progress, through the WebDAV storage its sync writes to (Koofr, Nextcloud or any
 * WebDAV server). With sync on, Moon+ keeps one small file per book, named after the book's file
 * with ".po" added, holding a line like "1502878256645*28@0#15529:12.3%": when it was saved, the
 * chapter, a position in it, and how far through the book. Nothing here is written.
 *
 * [account]'s server is the WebDAV address (for Koofr, https://app.koofr.net/dav/Koofr), its
 * username the account's email and its key an app password.
 */
class MoonReaderClient(private val account: Account) {

    private val base: String = account.server.trim().trimEnd('/')

    private fun headers(extra: Map<String, String> = emptyMap()) = mapOf(
        "Authorization" to "Basic " + Base64.getEncoder().encodeToString("${account.username}:${account.key}".toByteArray()),
        "User-Agent" to "Readync (Android)",
    ) + extra

    /** One entry of a folder listing: its path (decoded, from the server root) and whether it's a folder. */
    data class Entry(val path: String, val folder: Boolean, val modified: String?)

    /** A folder's entries (not the folder itself). */
    fun list(url: String): List<Entry> {
        val r = Http.request(
            "PROPFIND", url,
            headers(mapOf("Depth" to "1", "Content-Type" to "application/xml; charset=utf-8")),
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getlastmodified/></d:prop></d:propfind>""",
        )
        if (r.code == 401 || r.code == 403) throw AuthException("The WebDAV server rejected the email or app password")
        if (r.code == 404) return emptyList()
        if (r.code != 207 && !r.ok) throw ServiceException("The WebDAV server answered HTTP ${r.code}")
        val self = pathOf(url)
        return parseListing(r.body).filter { it.path.trimEnd('/') != self.trimEnd('/') }
    }

    /** True when the address and sign-in work. */
    fun verify(): Boolean = try { list("$base/"); true } catch (e: AuthException) { false }

    /** Where Moon+ usually keeps its sync files, tried before looking through everything. */
    private val likely = listOf("/Apps/Books/.Moon+/Cache", "/Books/.Moon+/Cache", "/.Moon+/Cache", "/Moon+/Cache", "/Apps/Books", "/Books")

    /**
     * The folder holding Moon+'s ".po" files: [hint] if it was found before, else one of the
     * usual places, else the first folder found holding any, looking a few levels down.
     */
    fun findFolder(hint: String? = null): String? {
        val tried = HashSet<String>()
        for (p in listOfNotNull(hint) + likely) {
            val u = urlFor(p)
            if (!tried.add(u)) continue
            if (list(u).any { !it.folder && it.path.endsWith(".po", true) }) return p
        }
        // Breadth first, a few levels down, skipping obviously unrelated folders.
        var level = listOf("")
        var seen = 0
        repeat(5) {
            val next = ArrayList<String>()
            for (dir in level) {
                val entries = runCatching { list(urlFor(dir)) }.getOrElse { if (it is AuthException) throw it else emptyList() }
                if (entries.any { !it.folder && it.path.endsWith(".po", true) }) return dir.ifEmpty { "/" }
                for (e in entries) if (e.folder && ++seen < 400) next += relative(e.path)
            }
            // Folders with Moon+ in the name first.
            level = next.sortedByDescending { it.contains("moon", true) || it.contains("books", true) }.take(120)
            if (level.isEmpty()) return null
        }
        return null
    }

    /** A file's bytes, or null when it isn't there. */
    private fun fetch(rel: String): ByteArray? {
        val r = Http.request("GET", urlFor(rel), headers())
        if (r.code == 401 || r.code == 403) throw AuthException("The WebDAV server rejected the email or app password")
        if (!r.ok) return null
        // The body comes back as text; ".an" files are compressed, so they're read again as bytes.
        return r.body.toByteArray(Charsets.ISO_8859_1).takeIf { r.body.all { it.code < 256 } } ?: rawFetch(rel)
    }

    private fun rawFetch(rel: String): ByteArray? = runCatching {
        val conn = java.net.URL(urlFor(rel)).openConnection() as java.net.HttpURLConnection
        headers().forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.connectTimeout = 20_000; conn.readTimeout = 20_000
        try { if (conn.responseCode in 200..299) conn.inputStream.use { it.readBytes() } else null } finally { conn.disconnect() }
    }.getOrNull()

    /**
     * Every book's position in [folder] (as [findFolder] gave it), with its highlights. Files
     * whose "last modified" matches [known] (path -> as the server gave it last time) aren't
     * fetched again: a book whose position file didn't change is left out, and one whose
     * highlights file didn't change comes without highlights. [seen] gets every file's stamp.
     */
    fun positions(folder: String, known: Map<String, String> = emptyMap(), seen: MutableMap<String, String> = HashMap()): List<MoonPosition> {
        val all = list(urlFor(folder)).filter { !it.folder }
        val byName = all.associateBy { it.path.substringAfterLast('/') }
        val out = ArrayList<MoonPosition>()
        for (f in all.filter { it.path.endsWith(".po", true) }) {
            val rel = relative(f.path)
            val name = f.path.substringAfterLast('/').dropLast(3)
            val an = byName["$name.an"]
            val anRel = an?.let { relative(it.path) }
            val poChanged = f.modified == null || known[rel] != f.modified
            val anChanged = an != null && (an.modified == null || known[anRel] != an.modified)
            if (!poChanged && !anChanged) { f.modified?.let { seen[rel] = it }; an?.modified?.let { seen[anRel!!] = it }; continue }
            val po = fetch(rel)?.let { parsePo(String(it, Charsets.UTF_8)) } ?: continue
            f.modified?.let { seen[rel] = it }
            val notes = if (anChanged) anRel?.let { fetch(it) }?.let { parseAn(it) } else null
            if (notes != null) an?.modified?.let { seen[anRel!!] = it } else an?.modified?.takeIf { !anChanged }?.let { seen[anRel!!] = it }
            out += MoonPosition(rel, name, po.first, po.second, po.third, title = notes?.first, highlights = notes?.second)
        }
        return out
    }

    /** A path below the WebDAV address as a full URL, each part encoded. */
    private fun urlFor(rel: String): String {
        val parts = rel.split('/').filter { it.isNotEmpty() }.joinToString("/") { Http.enc(it).replace("+", "%20") }
        return if (parts.isEmpty()) "$base/" else "$base/$parts/".let { if (rel.endsWith(".po", true)) it.trimEnd('/') else it }
    }

    /** The decoded path of a URL from the server's root. */
    private fun pathOf(url: String): String = runCatching { URI(url).rawPath }.getOrDefault(url).let(::decode)

    /** A path from the server's root, made relative to the WebDAV address. */
    private fun relative(path: String): String {
        val root = pathOf("$base/").trimEnd('/')
        return if (path.startsWith(root)) path.removePrefix(root) else path
    }

    companion object {
        private fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

        /** A PROPFIND reply's entries, whatever the server's namespace prefix ("d:", "D:", none). */
        internal fun parseListing(xml: String): List<Entry> {
            val tag = "(?:[A-Za-z0-9]+:)?"
            return Regex("<${tag}response\\b[^>]*>(.*?)</${tag}response>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(xml).mapNotNull { m ->
                    val body = m.groupValues[1]
                    val href = Regex("<${tag}href[^>]*>(.*?)</${tag}href>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(body)?.groupValues?.get(1)?.trim()
                        ?: return@mapNotNull null
                    val raw = href.replace("&amp;", "&")
                    val path = decode(runCatching { URI(raw).rawPath ?: raw }.getOrDefault(raw))
                    val folder = Regex("<${tag}collection\\b", RegexOption.IGNORE_CASE).containsMatchIn(body)
                    val modified = Regex("<${tag}getlastmodified[^>]*>(.*?)</", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
                    Entry(path, folder, modified)
                }.toList()
        }

        /**
         * A ".po" file's line: "1502878256645*28@0#15529:12.3%" as (saved at millis, chapter,
         * fraction). Null for anything else.
         */
        internal fun parsePo(text: String): Triple<Long, Int?, Double>? {
            val m = Regex("(\\d{10,14})\\*(\\d+)@\\d+#\\d+:(\\d+(?:[.,]\\d+)?)%").find(text.trim()) ?: return null
            var at = m.groupValues[1].toLongOrNull() ?: return null
            if (at < 100_000_000_000L) at *= 1000 // seconds, not millis
            val pct = m.groupValues[3].replace(',', '.').toDoubleOrNull() ?: return null
            return Triple(at, m.groupValues[2].toIntOrNull(), (pct / 100.0).coerceIn(0.0, 1.0))
        }

        /**
         * A ".an" file: zlib-compressed text. After a short header ("26492", "indent:false",
         * "trim:false"), each highlight is a block after a "#" line: its number, the book's title,
         * the book file's path (twice), the chapter, a part number, where in the chapter it starts,
         * its length, its colour, when it was made (millis), a bookmark name, your note, the
         * passage, then three flags. Returns the book's title and its highlights; null when the
         * file isn't one.
         */
        internal fun parseAn(bytes: ByteArray): Pair<String?, List<MoonHighlight>>? {
            val text = runCatching {
                val inf = java.util.zip.Inflater()
                inf.setInput(bytes)
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    out.write(buf, 0, n)
                }
                inf.end()
                String(out.toByteArray(), Charsets.UTF_8)
            }.getOrNull()?.takeIf { it.isNotEmpty() }
                // Some versions may save it uncompressed.
                ?: String(bytes, Charsets.UTF_8).takeIf { it.contains("\n#\n") }
                ?: return null
            val blocks = text.replace("\r\n", "\n").split(Regex("\n#\n"))
            if (blocks.size < 2) return null to emptyList()
            var title: String? = null
            val out = ArrayList<MoonHighlight>()
            for (block in blocks.drop(1)) {
                val l = block.split('\n')
                if (l.size < 14) continue
                val num = l[0].trim()
                if (num.toLongOrNull() == null) continue
                title = title ?: l[1].trim().takeIf { it.isNotEmpty() }
                val createdAt = l[9].trim().toLongOrNull() ?: 0L
                // Lines 10 and 11: a bookmark name and your note; then the passage (it may run over
                // several lines) and three flags at the end.
                val end = l.size - (if (l.last().isEmpty()) 4 else 3)
                if (end < 12) continue
                val passage = l.subList(12, end).joinToString("\n").trim()
                if (passage.isEmpty()) continue
                out += MoonHighlight(
                    id = num + ":" + createdAt,
                    chapter = l[4].trim().toIntOrNull(),
                    start = l[6].trim().toLongOrNull(),
                    text = passage,
                    note = l[11].trim().takeIf { it.isNotEmpty() }?.replace("<BR>", "\n"),
                    createdAt = createdAt,
                )
            }
            return title to out
        }

        private val bookExt = Regex("\\.(epub|kepub|mobi|azw3?|fb2|txt|pdf|cbz|cbr|docx?|umd|chm|zip)$", RegexOption.IGNORE_CASE)

        /**
         * A title and author guessed from a book's file name. "Title - Author.epub" (how Calibre
         * names copies) and "Author - Title.epub" both come back, best guess first; a name with
         * no dash is all title.
         */
        fun guesses(fileName: String): List<Pair<String, String?>> {
            val bare = fileName.replace(bookExt, "").replace('_', ' ').replace(Regex("\\s+"), " ").trim()
            val parts = bare.split(Regex("\\s+[-–—]\\s+"), limit = 2)
            if (parts.size < 2 || parts.any { it.isBlank() }) return listOf(bare to null)
            return listOf(parts[0].trim() to parts[1].trim(), parts[1].trim() to parts[0].trim())
        }
    }
}
