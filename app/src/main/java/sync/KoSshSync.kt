package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.source.EpubText
import io.github.prithirajn.readync.source.KoSidecar
import io.github.prithirajn.readync.source.KoSshClient

/**
 * KOReader on another device over its SSH server: finding KOReader and its files there, and
 * handing it new highlights and bookmarks the way KOReader itself syncs annotations between
 * devices (KOReader 2025.04 and later): a "<book file>.annotations.lua" file next to the book's
 * settings, which KOReader merges in the next time it opens the book and then deletes. Nothing of
 * KOReader's own files is rewritten, so a book open in KOReader loses nothing; it takes them in
 * once you open it again.
 *
 * The import file carries an old "datetime" on purpose: KOReader drops a highlight of its own
 * that's missing from an import file only when it's older than the file's datetime, so with
 * 1970 nothing of yours is ever removed; the file only adds.
 */
object KoSshSync {

    data class Device(
        val koDir: String,
        val roots: List<String>,
        /** "v2025.08-…" from KOReader's git-rev, or null. */
        val version: String?,
        val running: Boolean,
        /** KOReader's "Document metadata folder": "doc", "dir" or "hash". */
        val metadataFolder: String,
        val exportFolder: String?,
        val lastFile: String?,
    ) {
        /** KOReader's own label for where it runs. */
        val deviceName: String get() = when {
            koDir.startsWith("/mnt/us") -> "Kindle"
            koDir.startsWith("/mnt/onboard") -> "Kobo"
            koDir.startsWith("/mnt/ext1") -> "PocketBook"
            else -> "your e-reader"
        }

        /** Whether this KOReader takes in annotation files (2025.04 and later). Unknown versions are tried. */
        val canImport: Boolean get() {
            val m = Regex("""v?(\d{4})\.(\d{2})""").find(version ?: return true) ?: return true
            val y = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
            return y > 2025 || (y == 2025 && mo >= 4)
        }
    }

    private val KO_DIRS = listOf(
        "/mnt/us/koreader", "/mnt/onboard/.adds/koreader", "/mnt/ext1/applications/koreader", "/mnt/ext1/koreader",
        "/mnt/sd/koreader", "/mnt/us/extensions/koreader",
    )

    /** Finds KOReader on the device ([known] first) and reads its settings. */
    fun probe(c: KoSshClient, known: String?): Device {
        val dirs = (listOfNotNull(known) + KO_DIRS).distinct()
        val script = dirs.joinToString("; ") { "[ -f ${KoSshClient.q("$it/reader.lua")} ] && echo ${KoSshClient.q("KODIR $it")}" } +
            "; echo PWD \$PWD; ps 2>/dev/null | grep -c '[r]eader\\.lua'"
        val (out, _) = c.exec(script)
        val koDir = out.lines().firstOrNull { it.startsWith("KODIR ") }?.removePrefix("KODIR ")?.trim()
            ?: out.lines().firstOrNull { it.startsWith("PWD ") }?.removePrefix("PWD ")?.trim()?.takeIf { p ->
                c.exec("[ -f ${KoSshClient.q("$p/reader.lua")} ] && echo yes").first.contains("yes")
            }
            ?: throw IllegalStateException("Couldn't find KOReader on that device")
        val running = (out.lines().lastOrNull { it.trim().matches(Regex("\\d+")) }?.trim()?.toIntOrNull() ?: 0) > 0
        val version = c.readOrNull("$koDir/git-rev")?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
        val settings = c.readOrNull("$koDir/settings.reader.lua")?.toString(Charsets.UTF_8)?.let { runCatching { KoSidecar.Lua.parse(it) }.getOrNull() as? Map<*, *> }
        val home = (settings?.get("home_dir") as? String)?.trimEnd('/')
        val mount = koDir.split('/').filter { it.isNotEmpty() }.take(2).joinToString("/", prefix = "/")
        val roots = listOf(mount) + listOfNotNull(home?.takeIf { it != mount && !it.startsWith("$mount/") })
        return Device(
            koDir = koDir, roots = roots, version = version, running = running,
            metadataFolder = (settings?.get("document_metadata_folder") as? String) ?: "doc",
            exportFolder = (settings?.get("annotations_export_folder") as? String)?.trimEnd('/')?.takeIf { it.isNotEmpty() },
            lastFile = settings?.get("lastfile") as? String,
        )
    }

    data class Scan(val sidecars: List<String>, val annotationFiles: List<String>, val epubs: List<String>)

    /** KOReader's book settings files, waiting annotation files, and the EPUBs, under the device's book folders. */
    fun scan(c: KoSshClient, d: Device): Scan {
        val roots = (d.roots + d.koDir).distinct().joinToString(" ") { KoSshClient.q(it) }
        val wanted = "\\( -name 'metadata.*.lua' -o -name '*.annotations.lua' -o -name '*.epub' -o -name '*.EPUB' -o -name '*.Epub' \\)"
        var (out, code) = c.exec(
            "find $roots \\( -name '.Trash*' -o -name 'system' -o -name '.kobo' -o -name 'cache' \\) -prune -o $wanted -print 2>/dev/null",
            timeoutMs = 120_000,
        )
        // A device's own find without -prune: the plain search.
        if (out.isBlank() && code != 0) out = c.exec("find $roots $wanted 2>/dev/null", timeoutMs = 120_000).first
        val all = out.lines().map { it.trim() }.filter { it.startsWith("/") }.distinct()
        return Scan(
            sidecars = all.filter { it.substringAfterLast('/').let { n -> n.startsWith("metadata.") && n.endsWith(".lua") } && it.contains(".sdr/") },
            annotationFiles = all.filter { it.endsWith(".annotations.lua") },
            epubs = all.filter { it.lowercase().endsWith(".epub") && !it.contains(".sdr/") },
        )
    }

    /** A book file's KOReader document ID, read in small pieces over SFTP. */
    fun bookId(c: KoSshClient, path: String, size: Long): String? {
        if (size <= 0) return null
        var failed = false
        val id = EpubText.partialMd5(size) { o, n -> c.readRange(path, o, n).also { if (it == null) failed = true } }
        return id.takeIf { !failed }
    }

    /** Where KOReader looks for [bookPath]'s annotation file (by its settings), [id] being the book's document ID. */
    fun importPath(d: Device, bookPath: String, id: String): String {
        val fileName = bookPath.substringAfterLast('/')
        val dir = d.exportFolder ?: run {
            val stem = bookPath.substringBeforeLast('.', bookPath)
            when (d.metadataFolder) {
                "dir" -> "${d.koDir}/docsettings$stem.sdr"
                "hash" -> "${d.koDir}/hashdocsettings/${id.take(2)}/$id.sdr"
                else -> "$stem.sdr"
            }
        }
        return "$dir/$fileName.annotations.lua"
    }

    /** One annotation for KOReader: a highlight (with [pos1]) or a page bookmark. */
    data class Item(
        val page: String, val pos1: String?, val text: String, val datetime: String,
        val chapter: String? = null, val note: String? = null,
    ) {
        val isHighlight get() = pos1 != null
        fun toLua(): Map<String, Any> = buildMap {
            put("page", page)
            put("datetime", datetime)
            put("text", text)
            if (pos1 != null) {
                put("pos0", page); put("pos1", pos1); put("drawer", "lighten"); put("color", "yellow")
            }
            chapter?.takeIf { it.isNotBlank() }?.let { put("chapter", it) }
            note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
        }
    }

    /**
     * The annotation file with [items] in it: an existing one (from an earlier sync, or another
     * device's export) keeps everything it has, and [items] it already holds aren't added again.
     * Null when there's nothing new.
     */
    fun mergeImport(existing: String?, items: List<Item>, path: String): String? {
        val old = existing?.let { runCatching { KoSidecar.Lua.parse(it) }.getOrNull() as? Map<*, *> }
        val annotations = ArrayList<Any?>()
        (old?.get("annotations") as? List<*>)?.let { annotations.addAll(it) }
        (old?.get("annotations") as? Map<*, *>)?.let { m -> m.entries.sortedBy { (it.key as? Double) ?: 0.0 }.forEach { annotations += it.value } }
        fun same(a: Map<*, *>, i: Item) = a["page"] == i.page && (a["pos1"] as? String) == i.pos1 && (a["drawer"] == null) == !i.isHighlight
        var added = 0
        for (i in items) {
            if (annotations.any { (it as? Map<*, *>)?.let { a -> same(a, i) } == true }) continue
            annotations += i.toLua()
            added++
        }
        if (added == 0) return null
        val root = LinkedHashMap<String, Any?>()
        old?.forEach { (k, v) -> if (k is String) root[k] = v }
        root["annotations"] = annotations
        // Another device's own export keeps its id and date; Readync's file says it's Readync's.
        if (old == null || root["device_id"] == null) {
            root["device_id"] = "readync"
            root["datetime"] = "1970-01-01 00:00:00"
        }
        root.remove("paging") // EPUBs: KOReader's export leaves it out, and it must match
        return "-- $path\nreturn " + dump(root) + "\n"
    }

    /** Lua the way KOReader's dump writes it (keys sorted), for KOReader to read back with dofile. */
    fun dump(v: Any?, indent: Int = 0): String = when (v) {
        null -> "nil"
        is String -> quote(v)
        is Boolean -> v.toString()
        is Int, is Long -> v.toString()
        is Double -> if (v % 1.0 == 0.0 && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Float -> v.toDouble().let { dump(it, indent) }
        is List<*> -> table(v.mapIndexed { i, x -> (i + 1).toDouble() to x }, indent)
        is Map<*, *> -> table(v.entries.map { it.key to it.value }, indent)
        else -> quote(v.toString())
    }

    private fun table(entries: List<Pair<Any?, Any?>>, indent: Int): String {
        if (entries.isEmpty()) return "{}"
        val pad = "    ".repeat(indent + 1)
        // KOReader's ordered dump: numbers first, in order, then strings alphabetically.
        val sorted = entries.filter { it.first != null && it.second != null }.sortedWith(compareBy<Pair<Any?, Any?>>({ if (it.first is Number) 0 else 1 }, { (it.first as? Number)?.toDouble() ?: 0.0 }, { it.first.toString() }))
        val sb = StringBuilder("{")
        for ((k, x) in sorted) {
            sb.append('\n').append(pad).append('[').append(dump(if (k is Number) k.toDouble() else k)).append("] = ").append(dump(x, indent + 1)).append(',')
        }
        return sb.append('\n').append("    ".repeat(indent)).append('}').toString()
    }

    /** Lua's %q, the way KOReader's dump quotes strings. */
    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '\u0000' -> sb.append("\\0")
            ch.code < 32 || ch.code == 127 -> sb.append(String.format("\\%03d", ch.code))
            else -> sb.append(ch)
        }
        return sb.append('"').toString()
    }
}
