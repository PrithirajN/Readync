package io.github.prithirajn.trackersync.trackers

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Places in an EPUB, as the reading apps take them: KOReader's XPath (what CrossPoint sends) turned
 * into an EPUB CFI in another copy of the book (Audiobookshelf's reader, BookFusion), and a
 * highlight's range CFI, from CrossPoint's chapter offsets or by finding its words (BookOrbit,
 * Readest). Ported from crosspoint-sync's bookfusion-epub.ts, which does the same on the server.
 *
 * Only the text entries of a book are kept (container, package and chapters), each at most 1 MiB,
 * and a few books are kept in memory for a short while, so one sync downloads a book once.
 */
object EpubCfi {
    class EpubException(message: String) : Exception("EPUB place: $message")

    private fun invalid(reason: String): Nothing = throw EpubException(reason)

    /** An EPUB's text entries by path; null marks one too large to read. */
    class Book(val entries: Map<String, ByteArray?>)

    private const val MAX_XML = 1024 * 1024
    private const val MAX_ARCHIVE = 200L * 1024 * 1024
    private val TEXT_ENTRY = Regex("^META-INF/|\\.(?:x?html?|xml|opf|ncx)$", RegexOption.IGNORE_CASE)

    fun open(bytes: ByteArray): Book {
        if (bytes.size > MAX_ARCHIVE) invalid("EPUB is over 200 MiB")
        val out = HashMap<String, ByteArray?>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var count = 0
            while (true) {
                val e = z.nextEntry ?: break
                if (++count > 10_000) invalid("EPUB has too many entries")
                if (e.isDirectory || !TEXT_ENTRY.containsMatchIn(e.name)) continue
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var tooBig = false
                while (true) {
                    val n = z.read(chunk)
                    if (n < 0) break
                    if (buf.size() + n > MAX_XML) { tooBig = true; break }
                    buf.write(chunk, 0, n)
                }
                out[e.name] = if (tooBig) null else buf.toByteArray()
            }
        }
        if (out.isEmpty()) invalid("not an EPUB")
        return Book(out)
    }

    // A few books for a short while: one sync places many highlights in the same book.
    private val cache = object : LinkedHashMap<String, Pair<Long, Book>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Book>>?) = size > 6
    }

    /** The book under [key] (no secrets in it, please), downloaded with [load] when it isn't kept. */
    fun cached(key: String, load: () -> ByteArray): Book {
        synchronized(cache) {
            cache[key]?.takeIf { System.currentTimeMillis() - it.first < 15 * 60_000 }?.let { return it.second }
        }
        val book = open(load())
        synchronized(cache) { cache[key] = System.currentTimeMillis() to book }
        return book
    }

    fun forget(key: String) = synchronized(cache) { cache.remove(key) }

    // ---- XML ----

    private fun children(n: Node): List<Node> {
        val l = n.childNodes
        return (0 until l.length).map { l.item(it) }
    }

    private fun elements(n: Node): List<Element> = children(n).filterIsInstance<Element>()
    private fun isText(n: Node) = n.nodeType == Node.TEXT_NODE || n.nodeType == Node.CDATA_SECTION_NODE
    private fun local(e: Node): String = e.localName ?: e.nodeName.substringAfter(':')

    private fun child(n: Node, name: String): Element = elements(n).firstOrNull { local(it) == name } ?: invalid("missing $name element")

    /** HTML's named characters, as numbers, since a plain XML parser knows only five. */
    private val ENTITIES = mapOf(
        "nbsp" to 160, "shy" to 173, "ensp" to 8194, "emsp" to 8195, "thinsp" to 8201, "zwnj" to 8204, "zwj" to 8205,
        "lrm" to 8206, "rlm" to 8207, "ndash" to 8211, "mdash" to 8212, "lsquo" to 8216, "rsquo" to 8217, "sbquo" to 8218,
        "ldquo" to 8220, "rdquo" to 8221, "bdquo" to 8222, "dagger" to 8224, "Dagger" to 8225, "bull" to 8226, "hellip" to 8230,
        "prime" to 8242, "Prime" to 8243, "lsaquo" to 8249, "rsaquo" to 8250, "euro" to 8364, "trade" to 8482, "copy" to 169,
        "reg" to 174, "deg" to 176, "plusmn" to 177, "sup2" to 178, "sup3" to 179, "acute" to 180, "micro" to 181, "para" to 182,
        "middot" to 183, "cedil" to 184, "sup1" to 185, "ordm" to 186, "raquo" to 187, "frac14" to 188, "frac12" to 189,
        "frac34" to 190, "iquest" to 191, "laquo" to 171, "not" to 172, "macr" to 175, "iexcl" to 161, "cent" to 162,
        "pound" to 163, "curren" to 164, "yen" to 165, "brvbar" to 166, "sect" to 167, "uml" to 168, "ordf" to 170,
        "times" to 215, "divide" to 247, "szlig" to 223, "Agrave" to 192, "Aacute" to 193, "Acirc" to 194, "Atilde" to 195,
        "Auml" to 196, "Aring" to 197, "AElig" to 198, "Ccedil" to 199, "Egrave" to 200, "Eacute" to 201, "Ecirc" to 202,
        "Euml" to 203, "Igrave" to 204, "Iacute" to 205, "Icirc" to 206, "Iuml" to 207, "ETH" to 208, "Ntilde" to 209,
        "Ograve" to 210, "Oacute" to 211, "Ocirc" to 212, "Otilde" to 213, "Ouml" to 214, "Oslash" to 216, "Ugrave" to 217,
        "Uacute" to 218, "Ucirc" to 219, "Uuml" to 220, "Yacute" to 221, "THORN" to 222, "agrave" to 224, "aacute" to 225,
        "acirc" to 226, "atilde" to 227, "auml" to 228, "aring" to 229, "aelig" to 230, "ccedil" to 231, "egrave" to 232,
        "eacute" to 233, "ecirc" to 234, "euml" to 235, "igrave" to 236, "iacute" to 237, "icirc" to 238, "iuml" to 239,
        "eth" to 240, "ntilde" to 241, "ograve" to 242, "oacute" to 243, "ocirc" to 244, "otilde" to 245, "ouml" to 246,
        "oslash" to 248, "ugrave" to 249, "uacute" to 250, "ucirc" to 251, "uuml" to 252, "yacute" to 253, "thorn" to 254,
        "yuml" to 255, "OElig" to 338, "oelig" to 339, "Scaron" to 352, "scaron" to 353, "Yuml" to 376, "fnof" to 402,
        "circ" to 710, "tilde" to 732, "larr" to 8592, "rarr" to 8594, "uarr" to 8593, "darr" to 8595, "harr" to 8596,
        "hearts" to 9829, "spades" to 9824, "clubs" to 9827, "diams" to 9830, "infin" to 8734, "ne" to 8800, "le" to 8804, "ge" to 8805,
    )

    private fun decode(bytes: ByteArray): String {
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            else -> Regex("^<\\?xml\\s[^>]*encoding=[\"']([^\"']+)").find(String(bytes, 0, minOf(160, bytes.size), Charsets.ISO_8859_1))
                ?.groupValues?.get(1)?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
        }
        return String(bytes, charset).removePrefix("\uFEFF")
    }

    private fun parse(bytes: ByteArray): Element {
        var src = decode(bytes)
        if (src.count { it == '<' } > 50_000) invalid("XML has too many nodes")
        // No DOCTYPE (nothing is fetched for it), and HTML's named characters as numbers.
        src = src.replace(Regex("<!DOCTYPE[^>\\[]*(\\[[^\\]]*\\])?\\s*>", RegexOption.IGNORE_CASE), "")
        src = src.replace(Regex("&([A-Za-z][A-Za-z0-9]*);")) { m ->
            val name = m.groupValues[1]
            when {
                name in setOf("amp", "lt", "gt", "quot", "apos") -> m.value
                else -> ENTITIES[name]?.let { "&#$it;" } ?: "&#65533;"
            }
        }
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        f.isCoalescing = false
        f.isIgnoringComments = false
        f.isValidating = false
        runCatching { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        val doc = runCatching { f.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(src))) }
            .getOrElse { invalid("unreadable XML (${it.message?.take(80)})") }
        return doc.documentElement ?: invalid("XML root missing")
    }

    // ---- Chapters ----

    private class Chapter(val opf: Element, val spine: Element, val items: List<Element>, val index: Int, val html: Element, val body: Element)

    private fun join(dir: String, href: String): String {
        val parts = ArrayList<String>()
        for (p in (if (dir.isEmpty()) href else "$dir/$href").split('/')) {
            when (p) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += p
            }
        }
        return parts.joinToString("/")
    }

    private fun <T> withChapter(book: Book, select: (Element, Element, List<Element>) -> Int, resolve: (Chapter) -> T): T {
        fun read(path: String): ByteArray {
            if (book.entries.containsKey(path) && book.entries[path] == null) invalid("EPUB XML is over 1 MiB")
            return book.entries[path] ?: book.entries.entries.firstOrNull { it.key.equals(path, true) }?.value ?: invalid("EPUB entry missing")
        }
        val container = parse(read("META-INF/container.xml"))
        val rootfiles = elements(child(container, "rootfiles"))
        val rootfile = rootfiles.firstOrNull { it.getAttribute("media-type") == "application/oebps-package+xml" } ?: rootfiles.firstOrNull() ?: invalid("EPUB package missing")
        val opfPath = rootfile.getAttribute("full-path").ifEmpty { invalid("EPUB package path missing") }
        val opf = parse(read(opfPath))
        val spine = child(opf, "spine")
        val items = elements(spine).filter { local(it) == "itemref" }
        val index = select(opf, spine, items)
        if (index < 0 || index >= items.size) invalid("chapter out of range")
        val item = elements(child(opf, "manifest")).firstOrNull { local(it) == "item" && it.getAttribute("id") == items[index].getAttribute("idref") }
            ?: invalid("spine item missing from manifest")
        val href = item.getAttribute("href").ifEmpty { invalid("chapter path missing") }
        val path = join(opfPath.substringBeforeLast('/', ""), java.net.URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8"))
        val html = parse(read(path))
        val body = child(html, "body")
        return resolve(Chapter(opf, spine, items, index, html, body))
    }

    private fun chapterCount(book: Book): Int = withChapter(book, { _, _, items -> if (items.isEmpty()) invalid("no chapters") else 0 }) { it.items.size }

    // ---- KOReader XPath -> CFI ----

    /** Where a KOReader XPath is in this copy: its chapter, its share of the whole book, and its CFI. */
    data class Position(val chapterIndex: Int, val inBook: Double, val cfi: String)

    fun position(book: Book, xpath: String): Position {
        val m = Regex("^/body/DocFragment(?:\\[(\\d+)])?/body(?:/(.*))?$").find(xpath)
        if (m == null || xpath.length > 4096) invalid("expected a KOReader XPath")
        val rest = m.groupValues[2].takeIf { it.isNotEmpty() }
        return withChapter(book, { _, _, _ -> (m.groupValues[1].takeIf { it.isNotEmpty() }?.toInt() ?: 1) - 1 }) { ch ->
            val steps = arrayListOf(2 * (elements(ch.html).indexOf(ch.body) + 1))
            var target: Node = ch.body
            var offset = 0
            var cfiOffset = 0
            var textStep: Int? = null
            val parts = rest?.split('/') ?: emptyList()
            for ((i, part) in parts.withIndex()) {
                val t = Regex("^text\\(\\)(?:\\[(\\d+)])?(?:\\.(\\d+))?$").find(part)
                if (t != null) {
                    if (i != parts.size - 1) invalid("text must end the XPath")
                    val nodes = children(target)
                    val selected = nodes.filter { isText(it) }.getOrNull((t.groupValues[1].takeIf { it.isNotEmpty() }?.toInt() ?: 1) - 1) ?: invalid("text node missing")
                    val codepoints = t.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
                    val v = selected.nodeValue ?: ""
                    var count = 0
                    var at = 0
                    while (count < codepoints && at < v.length) {
                        // CFI counts UTF-16 units; CrossPoint's XPath offsets count code points.
                        at += Character.charCount(v.codePointAt(at))
                        count++
                    }
                    if (count != codepoints) invalid("text offset out of range")
                    offset = at
                    var step = 1
                    // Comments and CDATA can split text without making a new CFI text slot.
                    for (n in nodes) {
                        if (n === selected) break
                        if (n.nodeType == Node.ELEMENT_NODE) { step += 2; cfiOffset = 0 } else if (isText(n)) cfiOffset += n.nodeValue?.length ?: 0
                    }
                    textStep = step
                    cfiOffset += offset
                    target = selected
                } else {
                    val e = Regex("^([\\w:.-]+)(?:\\[(\\d+)])?$").find(part) ?: invalid("unsupported XPath step")
                    val siblings = elements(target)
                    val name = e.groupValues[1].substringAfterLast(':')
                    val found = siblings.filter { local(it) == name }.getOrNull((e.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 1) - 1) ?: invalid("XPath element missing")
                    steps += 2 * (siblings.indexOf(found) + 1)
                    target = found
                }
            }
            var total = 0
            var before = -1
            fun walk(n: Node) {
                if (n === target) before = total + offset
                if (isText(n)) total += n.nodeValue?.length ?: 0
                for (c in children(n)) walk(c)
            }
            walk(ch.body)
            if (before < 0 || before > total) invalid("XPath text position out of range")
            val packageStep = 2 * (elements(ch.opf).indexOf(ch.spine) + 1)
            val itemStep = 2 * (elements(ch.spine).indexOf(ch.items[ch.index]) + 1)
            val suffix = textStep?.let { "/$it:$cfiOffset" } ?: ""
            Position(
                ch.index,
                (ch.index + if (total > 0) before.toDouble() / total else 0.0) / ch.items.size,
                "epubcfi(/$packageStep/$itemStep!/${steps.joinToString("/")}$suffix)",
            )
        }
    }

    // ---- Highlights -> range CFI ----

    // Text CrossPoint's firmware never counts (its VisibleTextUtils::isNonVisibleElement).
    private val NON_VISIBLE = setOf("head", "style", "script", "title", "rp")

    /** CFI steps from the chapter's <html> down to a text node, then its CFI text slot and offset there. */
    private fun textPoint(html: Element, node: Node, offset: Int): String {
        val parent = node.parentNode
        var slot = 1
        var slotOffset = offset
        // Neighbouring text nodes (split by a comment or CDATA) share a CFI slot.
        for (s in children(parent)) {
            if (s === node) break
            if (s.nodeType == Node.ELEMENT_NODE) { slot += 2; slotOffset = offset } else if (isText(s)) slotOffset += s.nodeValue?.length ?: 0
        }
        val steps = ArrayList<Int>()
        var el: Node = parent
        while (el !== html) {
            steps.add(0, 2 * (elements(el.parentNode).indexOf(el) + 1))
            el = el.parentNode
        }
        return "/${steps.joinToString("/")}/$slot:$slotOffset"
    }

    /** A highlight's range CFI and the text it covers (to check against the highlight before trusting it). */
    data class Range(val cfi: String, val text: String)

    /**
     * The range CFI for CrossPoint's chapter offsets [start]–[end] (visible code points inside
     * <body>) in spine item [spineIndex].
     */
    fun rangeCfi(book: Book, spineIndex: Int, start: Int, end: Int): Range {
        if (start < 0 || end <= start) invalid("bad highlight offsets")
        return withChapter(book, { _, _, _ -> spineIndex }) { ch ->
            var count = 0
            var from: Pair<Node, Int>? = null
            var to: Pair<Node, Int>? = null
            val covered = StringBuilder()
            fun visit(n: Node) {
                for (c in children(n)) {
                    if (to != null) return
                    if (c.nodeType == Node.ELEMENT_NODE) {
                        if (local(c).lowercase() !in NON_VISIBLE) visit(c)
                    } else if (isText(c)) {
                        val v = c.nodeValue ?: ""
                        var utf16 = 0
                        while (utf16 < v.length) {
                            if (count == start) from = c to utf16
                            if (count == end) { to = c to utf16; return }
                            val cp = v.codePointAt(utf16)
                            if (from != null) covered.appendCodePoint(cp)
                            count++
                            utf16 += Character.charCount(cp)
                        }
                        if (count == end && from != null) { to = c to utf16; return }
                    }
                }
            }
            visit(ch.body)
            val f = from ?: invalid("highlight offsets out of range")
            val t = to ?: invalid("highlight offsets out of range")
            val a = textPoint(ch.html, f.first, f.second).split('/')
            val b = textPoint(ch.html, t.first, t.second).split('/')
            var shared = 0
            while (shared < minOf(a.size, b.size) - 1 && a[shared] == b[shared] && ':' !in a[shared]) shared++
            val packageStep = 2 * (elements(ch.opf).indexOf(ch.spine) + 1)
            val itemStep = 2 * (elements(ch.spine).indexOf(ch.items[ch.index]) + 1)
            Range(
                "epubcfi(/$packageStep/$itemStep!${a.subList(0, shared).joinToString("/")},/${a.subList(shared, a.size).joinToString("/")},/${b.subList(shared, b.size).joinToString("/")})",
                covered.toString(),
            )
        }
    }

    /** Whitespace as JavaScript's \\s has it (no-break and other wide spaces too), and soft hyphens. */
    private val JS_SPACE = Regex("[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF\\u00AD]+")

    /** Formatting differences between a reader's saved quote and the book's own text. */
    fun normalizeQuote(v: String): String =
        v.replace('\u2018', '\'').replace('\u2019', '\'').replace('\u201C', '"').replace('\u201D', '"').replace(JS_SPACE, "")

    private fun typography(v: String): String =
        v.replace(JS_SPACE, "").filterNot { Character.getType(it).let { t ->
            t == Character.CONNECTOR_PUNCTUATION.toInt() || t == Character.DASH_PUNCTUATION.toInt() || t == Character.START_PUNCTUATION.toInt() ||
                t == Character.END_PUNCTUATION.toInt() || t == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || t == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                t == Character.OTHER_PUNCTUATION.toInt()
        } }.lowercase()

    private data class Found(val spine: Int, val start: Int, val end: Int)

    private fun find(book: Book, quote: String, normalize: (String) -> String): Found? {
        val needle = normalize(quote)
        if (needle.isEmpty()) invalid("highlight text is empty")
        var found: Found? = null
        for (index in 0 until chapterCount(book)) {
            val (text, offsets) = runCatching {
                withChapter(book, { _, _, _ -> index }) { ch ->
                    val sb = StringBuilder()
                    // Saved offsets count code points; one entry per normalized UTF-16 unit.
                    val offs = ArrayList<Int>()
                    var offset = 0
                    fun visit(n: Node) {
                        for (c in children(n)) {
                            if (c.nodeType == Node.ELEMENT_NODE) {
                                if (local(c).lowercase() !in NON_VISIBLE) visit(c)
                            } else if (isText(c)) {
                                val v = c.nodeValue ?: ""
                                var i = 0
                                while (i < v.length) {
                                    val cp = v.codePointAt(i)
                                    val norm = normalize(String(Character.toChars(cp)))
                                    if (norm.isNotEmpty()) { sb.append(norm); repeat(norm.length) { offs += offset } }
                                    offset++
                                    i += Character.charCount(cp)
                                }
                            }
                        }
                    }
                    visit(ch.body)
                    sb.toString() to offs
                }
            }.getOrNull() ?: continue
            val at = text.indexOf(needle)
            if (at < 0) continue
            if (found != null || text.indexOf(needle, at + 1) >= 0) invalid("the highlight's words appear more than once in that copy of the book")
            found = Found(index, offsets[at], offsets[at + needle.length - 1] + 1)
        }
        return found
    }

    /** A highlight with no offsets, found by its words: only when they're in the book once. */
    fun quoteRangeCfi(book: Book, quote: String): Range {
        var f = find(book, quote, ::normalizeQuote)
        // Editions differ in quote marks, case and punctuation: a long quote is still a safe anchor.
        if (f == null && typography(quote).codePointCount(0, typography(quote).length) >= 40) f = find(book, quote, ::typography)
        f ?: invalid("the highlight's words aren't in that copy of the book")
        return rangeCfi(book, f.spine, f.start, f.end)
    }

    /**
     * The range CFI for a highlight: from CrossPoint's offsets when it has them and they hold its
     * words, else by finding its words. Throws when it can't be placed with confidence.
     */
    fun highlightRange(book: Book, text: String, spine: Int?, start: Int?, end: Int?): Range {
        if (spine != null && start != null && end != null) {
            val r = runCatching { rangeCfi(book, spine, start, end) }.getOrNull()
            if (r != null && normalizeQuote(r.text).startsWith(normalizeQuote(text).take(40))) return r
        }
        return quoteRangeCfi(book, text)
    }
}
