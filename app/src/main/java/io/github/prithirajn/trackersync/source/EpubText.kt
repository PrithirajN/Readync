package io.github.prithirajn.trackersync.source

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * An EPUB's chapters as text, counted the way CrossPoint counts them, so a highlight can be put on
 * the exact words: a chapter's "visible" text is every character inside its <body> in document
 * order, apart from <head>, <style>, <script>, <title> and <rp>, whitespace included, counted in
 * Unicode code points (crosspoint-sync's start_offset/end_offset, and what the Xteink draws by).
 * The same walk gives KOReader's positions ("/body/DocFragment[3]/body/div/p[5]/text().12"), the
 * way crengine writes them, so a CrossPoint highlight can be given to KOReader too.
 *
 * A passage is only ever placed when its words are found exactly once, so nothing is guessed.
 */
object EpubText {

    data class Place(val spine: Int, val start: Int, val end: Int)

    class Book internal constructor(private val files: Map<String, ByteArray>, val spine: List<String>) {
        private val chapters = HashMap<Int, Chapter?>()
        val size: Int get() = spine.size

        fun chapter(index: Int): Chapter? = chapters.getOrPut(index) {
            val bytes = spine.getOrNull(index)?.let { files[it] } ?: return@getOrPut null
            runCatching { Chapter.parse(index, String(bytes, Charsets.UTF_8)) }.getOrNull()
        }
    }

    // ---- Opening the EPUB ---------------------------------------------------------------------

    private val binary = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "ttf", "otf", "woff", "woff2", "mp3", "mp4", "m4a", "ogg", "css", "js")

    private fun keep(name: String) = name.substringAfterLast('.', "").lowercase() !in binary

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val files = HashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (!e.isDirectory && keep(e.name)) files[e.name] = z.readBytes()
                }
            }
            if (files.isNotEmpty()) return files
        } catch (_: Exception) {
        }
        // Some zips (stored entries with data descriptors) only open from a file.
        val tmp = java.io.File.createTempFile("readync", ".epub")
        try {
            tmp.writeBytes(bytes)
            ZipFile(tmp).use { z ->
                for (e in z.entries()) if (!e.isDirectory && keep(e.name)) files[e.name] = z.getInputStream(e).use { it.readBytes() }
            }
        } finally {
            tmp.delete()
        }
        return files
    }

    private val attr = Regex("""([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    private fun attrs(tag: String): Map<String, String> =
        attr.findAll(tag).associate { m -> m.groupValues[1].substringAfter(':') to unescape(m.groupValues[2].ifEmpty { m.groupValues[3] }) }

    private fun tags(xml: String, name: String): List<Map<String, String>> =
        Regex("""<(?:[\w.-]+:)?$name\b([^>]*)>""").findAll(xml).map { attrs(it.groupValues[1]) }.toList()

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** A path inside the zip, from a folder and an href ("../Text/ch1.xhtml#x"). */
    internal fun join(dir: String, href: String): String {
        val raw = href.substringBefore('#')
        val decoded = runCatching { java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
        val parts = ArrayList<String>()
        for (p in (if (dir.isEmpty()) decoded else "$dir/$decoded").split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += p
            }
        }
        return parts.joinToString("/")
    }

    /** The EPUB's chapters in reading order (its spine), or null when it can't be read as one. */
    fun open(bytes: ByteArray): Book? = runCatching {
        val files = entries(bytes)
        fun file(name: String) = files[name] ?: files.entries.firstOrNull { it.key.equals(name, true) }?.value
        val container = file("META-INF/container.xml")?.let { String(it, Charsets.UTF_8) } ?: return null
        val opfPath = tags(container, "rootfile").firstNotNullOfOrNull { it["full-path"] } ?: return null
        val opf = file(opfPath)?.let { String(it, Charsets.UTF_8) } ?: return null
        val dir = opfPath.substringBeforeLast('/', "")
        val manifest = tags(opf, "item").mapNotNull { a -> a["id"]?.let { id -> a["href"]?.let { id to join(dir, it) } } }.toMap()
        val spine = tags(opf, "itemref").mapNotNull { a -> a["idref"]?.let { manifest[it] } }
        if (spine.isEmpty()) return null
        val byName = HashMap<String, ByteArray>()
        for (path in spine) file(path)?.let { byName[path] = it }
        Book(byName, spine)
    }.getOrNull()

    /** The EPUB's title and first author from its package file, or nulls. */
    fun metadata(bytes: ByteArray): Pair<String?, String?> = info(bytes).let { it.title to it.creator }

    /** A chapter in the reading order: its contents title (or opening words), and where it starts as a share of the book's text. */
    data class Heading(val title: String, val spine: Int, val share: Double)

    /**
     * The book's chapters with where each starts (0–1, by visible characters, the way a reader's
     * place runs), titled from the contents (nav.xhtml or toc.ncx), else by their opening words.
     * Empty when it can't be read.
     */
    fun outline(bytes: ByteArray): List<Heading> = runCatching {
        val files = entries(bytes)
        fun file(name: String) = files[name] ?: files.entries.firstOrNull { it.key.equals(name, true) }?.value
        val container = file("META-INF/container.xml")?.let { String(it, Charsets.UTF_8) } ?: return emptyList()
        val opfPath = tags(container, "rootfile").firstNotNullOfOrNull { it["full-path"] } ?: return emptyList()
        val opf = file(opfPath)?.let { String(it, Charsets.UTF_8) } ?: return emptyList()
        val dir = opfPath.substringBeforeLast('/', "")
        val items = tags(opf, "item")
        val manifest = items.mapNotNull { a -> a["id"]?.let { id -> a["href"]?.let { id to join(dir, it) } } }.toMap()
        val spine = tags(opf, "itemref").mapNotNull { a -> a["idref"]?.let { manifest[it] } }
        if (spine.isEmpty()) return emptyList()
        // Each chapter's length in visible characters.
        val lengths = spine.mapIndexed { i, path -> file(path)?.let { runCatching { Chapter.parse(i, String(it, Charsets.UTF_8))?.length }.getOrNull() } ?: 0 }
        val total = lengths.sum().takeIf { it > 0 } ?: return emptyList()
        val starts = DoubleArray(spine.size)
        var run = 0
        for (i in spine.indices) { starts[i] = run.toDouble() / total; run += lengths[i] }
        fun clean(t: String) = unescape(t.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()
        // Titles from the contents: the first entry pointing at each chapter file.
        val titles = HashMap<Int, String>()
        val navPath = items.firstOrNull { (it["properties"] ?: "").split(' ').contains("nav") }?.get("href")?.let { join(dir, it) }
        val ncxPath = items.firstOrNull { it["media-type"] == "application/x-dtbncx+xml" }?.get("href")?.let { join(dir, it) }
        fun put(base: String, href: String, title: String) {
            val i = spine.indexOf(join(base, href))
            if (i >= 0 && title.isNotEmpty() && i !in titles) titles[i] = title.take(120)
        }
        navPath?.let { np -> file(np)?.let { String(it, Charsets.UTF_8) } }?.let { nav ->
            val toc = Regex("""<nav\b[^>]*(?:toc)[^>]*>([\s\S]*?)</nav>""", RegexOption.IGNORE_CASE).find(nav)?.groupValues?.get(1) ?: nav
            for (m in Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE).findAll(toc)) {
                val href = attrs(m.groupValues[1])["href"] ?: continue
                put(navPath.substringBeforeLast('/', ""), href, clean(m.groupValues[2]))
            }
        }
        if (titles.isEmpty()) ncxPath?.let { np -> file(np)?.let { String(it, Charsets.UTF_8) } }?.let { ncx ->
            for (m in Regex("""<navPoint\b[\s\S]*?<text>([\s\S]*?)</text>[\s\S]*?<content\b([^>]*)/?>""", RegexOption.IGNORE_CASE).findAll(ncx)) {
                val src = attrs(m.groupValues[2])["src"] ?: continue
                put(ncxPath.substringBeforeLast('/', ""), src, clean(m.groupValues[1]))
            }
        }
        spine.indices.filter { lengths[it] > 0 }.map { i ->
            val title = titles[i] ?: runCatching {
                file(spine[i])?.let { Chapter.parse(i, String(it, Charsets.UTF_8)) }?.let { ch ->
                    val n = minOf(ch.length, 400)
                    String(ch.text, 0, n).replace(Regex("\\s+"), " ").trim().take(60)
                }
            }.getOrNull().orEmpty()
            Heading(title, i, starts[i])
        }
    }.getOrDefault(emptyList())

    /**
     * What an EPUB says about itself: its title and author, and for a web novel saved as an EPUB
     * (by FanFicFare, WebToEpub, a CrossPoint plugin…) the story's link, from its details
     * (dc:source, dc:identifier) or its first pages, and how many chapters this copy has.
     */
    data class Info(val title: String?, val creator: String?, val storyLink: String? = null, val chapters: Int? = null)

    private val STORY = Regex(
        """https?://(?:www\.|m\.|forums\.)?(?:royalroad\.com/fiction/\d+[^\s"'<>)]*|archiveofourown\.org/works/\d+|ao3\.org/works/\d+|scribblehub\.com/series/\d+[^\s"'<>)]*|wattpad\.com/story/\d+[^\s"'<>)]*|fanfiction\.net/s/\d+[^\s"'<>)]*|spacebattles\.com/threads/[^\s"'<>)]+|sufficientvelocity\.com/threads/[^\s"'<>)]+|webnovel\.com/book/[^\s"'<>)]+)""",
        RegexOption.IGNORE_CASE,
    )

    /** The first story link in [text], as the site's own story address. */
    internal fun storyLinkIn(text: String): String? =
        STORY.findAll(unescape(text)).firstNotNullOfOrNull { WebNovels.canonical(it.value.trimEnd('.', ',', ';')) }

    fun info(bytes: ByteArray): Info = runCatching {
        val files = entries(bytes)
        fun file(name: String) = files[name] ?: files.entries.firstOrNull { it.key.equals(name, true) }?.value
        val container = file("META-INF/container.xml")?.let { String(it, Charsets.UTF_8) } ?: return Info(null, null)
        val opfPath = tags(container, "rootfile").firstNotNullOfOrNull { it["full-path"] } ?: return Info(null, null)
        val opf = file(opfPath)?.let { String(it, Charsets.UTF_8) } ?: return Info(null, null)
        fun first(name: String) = Regex("""<(?:[\w.-]+:)?$name\b[^>]*>([\s\S]*?)</(?:[\w.-]+:)?$name>""").find(opf)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), "")?.let(::unescape)?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
        // The story's link: in the book's details first, else on its first pages (a title or information page).
        val pages = files.keys.filter { Regex("(?i)\\.x?html?$").containsMatchIn(it) }.sorted()
        val front = (pages.filter { Regex("(?i)title|info|cover|front|intro|about|0000|^.*/?0+1?\\.x?html?$").containsMatchIn(it) } + pages).distinct().take(4)
        val link = storyLinkIn(opf) ?: front.firstNotNullOfOrNull { p -> file(p)?.let { storyLinkIn(String(it, Charsets.UTF_8).take(60_000)) } }
        // The chapters: the reading order, less the cover, title, contents and information pages.
        val chapters = link?.let {
            val spine = tags(opf, "itemref").mapNotNull { it["idref"] }
            spine.count { id -> !Regex("(?i)cover|title|toc|nav|info|front|intro|about|copyright|acknowledg|appendix").containsMatchIn(id) }.takeIf { n -> n > 0 }
        }
        Info(first("title"), first("creator"), link, chapters)
    }.getOrDefault(Info(null, null))

    // ---- A chapter -----------------------------------------------------------------------------

    internal class El(val name: String, val parent: El?) {
        val kids = ArrayList<Any>()
        var hidden = false
    }

    internal class Tx(val parent: El, val cps: IntArray) {
        var start = -1
        val ws = cps.all { it == ' '.code || it == '\n'.code || it == '\r'.code || it == '\t'.code }
    }

    private val NON_VISIBLE = setOf("head", "style", "script", "title", "rp")

    /** Elements a book's stylesheet normally lays out as blocks: crengine drops a whitespace-only run that starts one. */
    private val BLOCK = setOf(
        "body", "div", "p", "section", "article", "aside", "header", "footer", "nav", "main", "blockquote", "figure", "figcaption",
        "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "dl", "dt", "dd", "table", "thead", "tbody", "tfoot", "tr", "td", "th",
        "caption", "pre", "address", "hr", "center", "hgroup", "details", "summary",
    )

    class Chapter internal constructor(val spine: Int, private val body: El, private val texts: List<Tx>, val length: Int) {

        /** Its visible text, one code point per offset. */
        val text: IntArray by lazy {
            val out = IntArray(length)
            for (t in texts) if (t.start >= 0) System.arraycopy(t.cps, 0, out, t.start, t.cps.size)
            out
        }

        fun textOf(start: Int, end: Int): String {
            val t = text
            if (start < 0 || end > t.size || end <= start) return ""
            return String(t, start, end - start)
        }

        /** Every place [needle] appears once its text is passed through [normalize], as visible offsets. */
        internal fun find(needle: String, normalize: (Int) -> String): List<Pair<Int, Int>> {
            val sb = StringBuilder()
            val offsets = ArrayList<Int>()
            val t = text
            for (i in t.indices) {
                val n = normalize(t[i])
                if (n.isEmpty()) continue
                sb.append(n)
                repeat(n.length) { offsets += i }
            }
            val hay = sb.toString()
            val out = ArrayList<Pair<Int, Int>>()
            var at = hay.indexOf(needle)
            while (at >= 0 && out.size < 3) {
                out += offsets[at] to offsets[at + needle.length - 1] + 1
                at = hay.indexOf(needle, at + 1)
            }
            return out
        }

        private fun index(of: Any, parent: El): Pair<Int, Int> {
            // Element: its place among same-name siblings. Text: among text runs crengine keeps.
            if (of is El) {
                val same = parent.kids.filter { it is El && it.name == of.name }
                return same.indexOf(of) + 1 to same.size
            }
            val runs = parent.kids.filterIndexed { i, k -> k is Tx && !(i == 0 && k.ws && parent.name in BLOCK) }
            return runs.indexOf(of) + 1 to runs.size
        }

        private fun xpointer(t: Tx, offset: Int): String? {
            val chain = ArrayList<El>()
            var e: El? = t.parent
            while (e != null && e !== body) { chain += e; e = e.parent }
            if (e == null) return null
            val sb = StringBuilder("/body/DocFragment[").append(spine + 1).append("]/body")
            for (el in chain.asReversed()) {
                val (n, of) = index(el, el.parent!!)
                sb.append('/').append(el.name)
                if (of > 1) sb.append('[').append(n).append(']')
            }
            val (n, of) = index(t, t.parent)
            if (n == 0) return null
            sb.append("/text()")
            if (of > 1) sb.append('[').append(n).append(']')
            return sb.append('.').append(offset).toString()
        }

        /**
         * KOReader's start and end positions for visible offsets [start] until [end], the way its
         * highlights hold them (the end just after the last character), or null.
         */
        fun koreaderRange(start: Int, end: Int): Pair<String, String>? {
            if (start < 0 || end <= start || end > length) return null
            val first = texts.firstOrNull { it.start >= 0 && !it.ws && it.start + it.cps.size > start } ?: return null
            val last = texts.lastOrNull { it.start >= 0 && it.start <= end - 1 && it.start + it.cps.size > end - 1 } ?: return null
            if (last.ws) return null
            val p0 = xpointer(first, maxOf(0, start - first.start)) ?: return null
            val p1 = xpointer(last, end - 1 - last.start + 1) ?: return null
            return p0 to p1
        }

        /** The visible offset a KOReader position points at, or null when it isn't one of this chapter's. */
        fun offsetOf(xpointer: String): Int? {
            val m = Regex("""^/body/DocFragment\[(\d+)]/body((?:/[^/\[.]+(?:\[\d+])?)*)/text\(\)(?:\[(\d+)])?\.(\d+)$""").find(xpointer.trim()) ?: return null
            if (m.groupValues[1].toInt() != spine + 1) return null
            var el = body
            for (seg in m.groupValues[2].split('/').filter { it.isNotEmpty() }) {
                val name = seg.substringBefore('[')
                val n = seg.substringAfter('[', "1]").removeSuffix("]").toInt()
                el = el.kids.filter { it is El && it.name == name }.getOrNull(n - 1) as? El ?: return null
            }
            val n = m.groupValues[3].ifEmpty { "1" }.toInt()
            val runs = el.kids.filterIndexed { i, k -> k is Tx && !(i == 0 && k.ws && el.name in BLOCK) }
            val t = runs.getOrNull(n - 1) as? Tx ?: return null
            val off = m.groupValues[4].toInt()
            if (t.start < 0 || off > t.cps.size) return null
            return t.start + off
        }

        companion object {
            internal fun parse(spine: Int, source: String): Chapter? {
                val s = source.replace("\r\n", "\n").replace('\r', '\n')
                val root = El("#document", null)
                var cur = root
                val text = StringBuilder()
                fun flush() {
                    if (text.isEmpty()) return
                    val str = text.toString()
                    cur.kids += Tx(cur, str.codePoints().toArray())
                    text.setLength(0)
                }
                var i = 0
                val n = s.length
                while (i < n) {
                    val c = s[i]
                    if (c == '<') {
                        when {
                            s.startsWith("<!--", i) -> { flush(); val e = s.indexOf("-->", i + 4); i = if (e < 0) n else e + 3 }
                            s.startsWith("<![CDATA[", i) -> {
                                flush()
                                val e = s.indexOf("]]>", i + 9)
                                val body = s.substring(i + 9, if (e < 0) n else e)
                                if (body.isNotEmpty()) cur.kids += Tx(cur, body.codePoints().toArray())
                                i = if (e < 0) n else e + 3
                            }
                            s.startsWith("<?", i) -> { flush(); val e = s.indexOf("?>", i + 2); i = if (e < 0) n else e + 2 }
                            s.startsWith("<!", i) -> {
                                // DOCTYPE, possibly with an internal subset in [ ].
                                var j = i + 2
                                var depth = 0
                                while (j < n) { val ch = s[j]; if (ch == '[') depth++ else if (ch == ']') depth-- else if (ch == '>' && depth <= 0) break; j++ }
                                i = j + 1
                            }
                            s.startsWith("</", i) -> {
                                flush()
                                val e = s.indexOf('>', i)
                                val name = s.substring(i + 2, if (e < 0) n else e).trim().substringAfter(':')
                                // Close up to the matching element (tolerating a stray end tag).
                                var x: El? = cur
                                while (x != null && x.name != name) x = x.parent
                                if (x != null && x !== root) cur = x.parent ?: root
                                i = if (e < 0) n else e + 1
                            }
                            else -> {
                                flush()
                                // Find the tag's end, skipping quoted attribute values.
                                var j = i + 1
                                var q = 0.toChar()
                                while (j < n) {
                                    val ch = s[j]
                                    if (q != 0.toChar()) { if (ch == q) q = 0.toChar() } else if (ch == '"' || ch == '\'') q = ch else if (ch == '>') break
                                    j++
                                }
                                val inner = s.substring(i + 1, minOf(j, n))
                                val selfClosing = inner.endsWith("/")
                                val name = inner.trimEnd('/').trim().split(Regex("\\s+"), 2)[0].substringAfter(':')
                                if (name.isNotEmpty()) {
                                    val el = El(name, cur)
                                    el.hidden = cur.hidden || name.lowercase() in NON_VISIBLE
                                    cur.kids += el
                                    if (!selfClosing) cur = el
                                }
                                i = j + 1
                            }
                        }
                    } else if (c == '&') {
                        val e = s.indexOf(';', i)
                        val ref = if (e in (i + 2)..(i + 12)) s.substring(i + 1, e) else null
                        val cp = ref?.let { entity(it) }
                        if (cp != null) { text.append(cp); i = e + 1 } else { text.append(c); i++ }
                    } else {
                        text.append(c)
                        i++
                    }
                }
                flush()
                val body = findBody(root) ?: return null
                val texts = ArrayList<Tx>()
                var offset = 0
                fun walk(el: El) {
                    for (k in el.kids) {
                        if (k is El) { if (!k.hidden) walk(k) }
                        else if (k is Tx) { k.start = offset; offset += k.cps.size; texts += k }
                    }
                }
                walk(body)
                return Chapter(spine, body, texts, offset)
            }

            private fun findBody(e: El): El? {
                for (k in e.kids) if (k is El) { if (k.name.equals("body", true)) return k; findBody(k)?.let { return it } }
                return null
            }
        }
    }

    // ---- Character references ----------------------------------------------------------------

    private val ENTITIES: Map<String, String> by lazy {
        TABLE.split(' ').filter { it.contains(':') }.associate { e ->
            val (k, v) = e.split(':', limit = 2)
            k to v.split(',').joinToString("") { String(Character.toChars(it.toInt(16))) }
        }
    }

    /** "amp", "#160", "#xA0", "nbsp"… as text; null when unknown (it's kept as written, as CrossPoint does). */
    private fun entity(ref: String): String? = when {
        ref.startsWith("#x") || ref.startsWith("#X") -> ref.drop(2).toIntOrNull(16)?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
        ref.startsWith("#") -> ref.drop(1).toIntOrNull()?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
        else -> ENTITIES[ref]
    }

    /** The HTML entities CrossPoint's reader knows. */
    private const val TABLE =
        "AElig:c6 Aacute:c1 Acirc:c2 Agrave:c0 Alpha:391 Aring:c5 Atilde:c3 Auml:c4 Beta:392 Ccedil:c7 " +
        "Chi:3a7 Dagger:2021 Delta:394 ETH:d0 Eacute:c9 Ecirc:ca Egrave:c8 Epsilon:395 Eta:397 Euml:cb " +
        "Gamma:393 Iacute:cd Icirc:ce Igrave:cc Iota:399 Iuml:cf Kappa:39a Lambda:39b Mu:39c Ntilde:d1 Nu:39d " +
        "OElig:152 Oacute:d3 Ocirc:d4 Ograve:d2 Omega:3a9 Omicron:39f Oslash:d8 Otilde:d5 Ouml:d6 Phi:3a6 " +
        "Pi:3a0 Prime:2033 Psi:3a8 Rho:3a1 Scaron:160 Sigma:3a3 THORN:de Tau:3a4 Theta:398 Uacute:da Ucirc:db " +
        "Ugrave:d9 Upsilon:3a5 Uuml:dc Xi:39e Yacute:dd Yuml:178 Zeta:396 aacute:e1 acirc:e2 acute:b4 " +
        "aelig:e6 agrave:e0 alefsym:2135 alpha:3b1 amp:26 and:2227 ang:2220 apos:27 aring:e5 asymp:2248 " +
        "atilde:e3 auml:e4 bdquo:201e beta:3b2 brvbar:a6 bull:2022 cap:2229 ccedil:e7 cedil:b8 cent:a2 " +
        "chi:3c7 circ:2c6 clubs:2663 cong:2245 copy:a9 crarr:21b5 cup:222a curren:a4 dArr:21d3 dagger:2020 " +
        "darr:2193 deg:b0 delta:3b4 diams:2666 divide:f7 eacute:e9 ecirc:ea egrave:e8 empty:2205 emsp:20 " +
        "ensp:20 epsilon:3b5 equiv:2261 eta:3b7 eth:f0 euml:eb euro:20ac exist:2203 fnof:192 forall:2200 " +
        "frac12:bd frac14:bc frac34:be frasl:2044 gamma:3b3 ge:2265 gt:3e hArr:21d4 harr:2194 hearts:2665 " +
        "hellip:2026 iacute:ed icirc:ee iexcl:a1 igrave:ec image:2111 infin:221e int:222b iota:3b9 iquest:bf " +
        "isin:2208 iuml:ef kappa:3ba lArr:21d0 lambda:3bb lang:3008 laquo:ab larr:2190 lceil:2308 ldquo:201c " +
        "le:2264 lfloor:230a lowast:2217 loz:25ca lrm:200e lsaquo:2039 lsquo:2018 lt:3c macr:af mdash:2014 " +
        "micro:b5 middot:b7 minus:2212 mu:3bc nabla:2207 nbsp:a0 ndash:2013 ne:2260 ni:220b not:ac notin:2209 " +
        "nsub:2284 ntilde:f1 nu:3bd oacute:f3 ocirc:f4 oelig:153 ograve:f2 oline:203e omega:3c9 omicron:3bf " +
        "oplus:2295 or:2228 ordf:aa ordm:ba oslash:f8 otilde:f5 otimes:2297 ouml:f6 para:b6 part:2202 " +
        "permil:2030 perp:22a5 phi:3c6 pi:3c0 piv:3d6 plusmn:b1 pound:a3 prime:2032 prod:220f prop:221d " +
        "psi:3c8 quot:22 rArr:21d2 radic:221a rang:3009 raquo:bb rarr:2192 rceil:2309 rdquo:201d real:211c " +
        "reg:ae rfloor:230b rho:3c1 rlm:200f rsaquo:203a rsquo:2019 sbquo:201a scaron:161 sdot:22c5 sect:a7 " +
        "shy:ad sigma:3c3 sigmaf:3c2 sim:223c spades:2660 sub:2282 sube:2286 sum:2211 sup1:b9 sup2:b2 sup3:b3 " +
        "sup:2283 supe:2287 szlig:df tau:3c4 there4:2234 theta:3b8 thetasym:3d1 thinsp:20 thorn:fe tilde:2dc " +
        "times:d7 trade:2122 uArr:21d1 uacute:fa uarr:2191 ucirc:fb ugrave:f9 uml:a8 upsih:3d2 upsilon:3c5 " +
        "uuml:fc weierp:2118 xi:3be yacute:fd yen:a5 yuml:ff zeta:3b6 zwj:200d zwnj:200c "

    // ---- Finding a passage ---------------------------------------------------------------------

    private fun jsSpace(cp: Int) = cp == 0x09 || cp == 0x0A || cp == 0x0B || cp == 0x0C || cp == 0x0D || cp == 0x20 || cp == 0xA0 ||
        cp == 0x1680 || cp in 0x2000..0x200A || cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000 || cp == 0xFEFF

    /** crosspoint-sync's comparison: curly quotes as straight ones, whitespace and soft hyphens left out. */
    internal fun strict(cp: Int): String = when {
        cp == 0x2018 || cp == 0x2019 -> "'"
        cp == 0x201C || cp == 0x201D -> "\""
        jsSpace(cp) || cp == 0xAD -> ""
        else -> String(Character.toChars(cp))
    }

    /** The looser one, for long quotes only: punctuation left out too, and case ignored. */
    internal fun loose(cp: Int): String {
        if (jsSpace(cp) || cp == 0xAD) return ""
        when (Character.getType(cp).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> return ""
        }
        return String(Character.toChars(cp)).lowercase(java.util.Locale.ROOT)
    }

    private fun normalized(s: String, f: (Int) -> String) = buildString { s.codePoints().forEach { append(f(it)) } }

    /**
     * Where [quote] is in [book]: when [spine] is known, the passage has to appear exactly once in
     * that chapter; otherwise exactly once in the whole book. A long quote (40 letters or more)
     * that doesn't match exactly is tried again ignoring punctuation and case, as crosspoint-sync
     * does. Null when it isn't found, or is found more than once.
     */
    /**
     * Where [quote] is in [book], even when the words come up more than once (a line the title is
     * taken from, a phrase repeated): the one in the highlight's chapter ([spine]) and nearest its
     * place in the book ([percent], 0–1); the first, when there's nothing to go by. Null when it isn't there.
     */
    fun findNear(book: Book, quote: String, spine: Int? = null, percent: Double? = null): Place? {
        find(book, quote, spine)?.let { return it }
        fun every(needle: String, f: (Int) -> String): List<Place> =
            (0 until book.size).flatMap { i -> (book.chapter(i)?.find(needle, f) ?: emptyList()).map { Place(i, it.first, it.second) } }
        val exact = normalized(quote, ::strict)
        if (exact.isEmpty()) return null
        var hits = every(exact, ::strict)
        if (hits.isEmpty()) {
            val rough = normalized(quote, ::loose)
            if (rough.codePointCount(0, rough.length) >= 12) hits = every(rough, ::loose)
        }
        if (hits.isEmpty()) return null
        if (hits.size == 1) return hits[0]
        val inChapter = spine?.let { sp -> hits.filter { it.spine == sp } }.orEmpty()
        val pool = inChapter.ifEmpty { hits }
        if (pool.size == 1 || percent == null) return pool.first()
        // Nearest to where the highlight is in the whole book, counted by the chapters' lengths.
        val lengths = (0 until book.size).map { book.chapter(it)?.length ?: 0 }
        val starts = lengths.runningFold(0L) { acc, n -> acc + n }
        val want = starts.last() * percent.coerceIn(0.0, 1.0)
        return pool.minByOrNull { kotlin.math.abs(starts[it.spine] + it.start - want) }
    }

    fun find(book: Book, quote: String, spine: Int? = null): Place? {
        val exact = normalized(quote, ::strict)
        if (exact.isEmpty()) return null
        search(book, exact, ::strict, spine)?.let { return it }
        val rough = normalized(quote, ::loose)
        if (rough.codePointCount(0, rough.length) >= 40) return search(book, rough, ::loose, spine)
        return null
    }

    private fun search(book: Book, needle: String, f: (Int) -> String, spine: Int?): Place? {
        if (spine != null && spine in 0 until book.size) {
            val hits = book.chapter(spine)?.find(needle, f) ?: emptyList()
            if (hits.size == 1) return Place(spine, hits[0].first, hits[0].second)
            if (hits.size > 1) return null
        }
        var found: Place? = null
        for (i in 0 until book.size) {
            val hits = book.chapter(i)?.find(needle, f) ?: continue
            if (hits.isEmpty()) continue
            if (hits.size > 1 || found != null) return null
            found = Place(i, hits[0].first, hits[0].second)
        }
        return found
    }

    /** Whether the text at [place] reads as [quote] (ignoring whitespace and quote style). */
    fun matches(book: Book, place: Place, quote: String): Boolean {
        val ch = book.chapter(place.spine) ?: return false
        val there = normalized(ch.textOf(place.start, place.end), ::strict)
        return there.isNotEmpty() && there == normalized(quote, ::strict)
    }

    // ---- KOReader's document ID ---------------------------------------------------------------

    /**
     * KOReader's (and KOSync's and CrossPoint's) document ID for a file of [size] bytes: the MD5 of
     * 1 KB read at 0, 1 KB, 4 KB, 16 KB… up to 1 GB. [read] gives the bytes at an offset.
     */
    fun partialMd5(size: Long, read: (offset: Long, length: Int) -> ByteArray?): String {
        val md5 = MessageDigest.getInstance("MD5")
        for (i in -1..10) {
            val offset = if (i < 0) 0L else 1024L shl (2 * i)
            if (offset >= size) continue
            val b = read(offset, minOf(1024L, size - offset).toInt()) ?: continue
            md5.update(b)
        }
        return md5.digest().joinToString("") { "%02x".format(it) }
    }
}
