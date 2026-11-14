package io.github.prithirajn.trackersync.util

/**
 * The Markdown you write reviews in, turned into what each tracker takes: HTML for Goodreads and
 * StoryGraph, clean text for the rest. Supported: paragraphs (a single line break stays a line
 * break), `# headings`, `- ` / `* ` / `1. ` lists, `> quotes`, `**bold**`, `*italic*` / `_italic_`,
 * `~~strikethrough~~`, `` `code` ``, `[links](https://…)` and `||spoilers||`.
 */
object Markdown {

    sealed interface Inline
    data class Text(val text: String) : Inline
    data class Bold(val children: List<Inline>) : Inline
    data class Italic(val children: List<Inline>) : Inline
    data class Strike(val children: List<Inline>) : Inline
    data class Code(val text: String) : Inline
    data class Link(val children: List<Inline>, val url: String) : Inline
    data class Spoiler(val children: List<Inline>) : Inline
    object Break : Inline

    sealed interface Block
    data class Para(val inlines: List<Inline>) : Block
    data class Heading(val inlines: List<Inline>) : Block
    data class Quote(val blocks: List<Block>) : Block
    data class ListBlock(val ordered: Boolean, val items: List<List<Inline>>) : Block

    private val bullet = Regex("^\\s{0,3}[-*+]\\s+(.*)$")
    private val numbered = Regex("^\\s{0,3}\\d{1,3}[.)]\\s+(.*)$")
    private val heading = Regex("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$")
    private val quote = Regex("^\\s{0,3}>\\s?(.*)$")

    fun parse(md: String): List<Block> = blocks(md.replace("\r\n", "\n").lines())

    private fun blocks(lines: List<String>): List<Block> {
        val out = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.isBlank() -> i++
                heading.matches(line) -> { out += Heading(inlines(heading.find(line)!!.groupValues[1])); i++ }
                quote.matches(line) -> {
                    val inner = ArrayList<String>()
                    while (i < lines.size && quote.matches(lines[i])) { inner += quote.find(lines[i])!!.groupValues[1]; i++ }
                    out += Quote(blocks(inner))
                }
                bullet.matches(line) || numbered.matches(line) -> {
                    val ordered = !bullet.matches(line)
                    val re = if (ordered) numbered else bullet
                    val items = ArrayList<String>()
                    while (i < lines.size && lines[i].isNotBlank()) {
                        val m = re.find(lines[i])
                        when {
                            m != null -> items += m.groupValues[1]
                            // A wrapped item line carries on the item above.
                            items.isNotEmpty() && !bullet.matches(lines[i]) && !numbered.matches(lines[i]) -> items[items.size - 1] += " " + lines[i].trim()
                            else -> break
                        }
                        i++
                    }
                    out += ListBlock(ordered, items.map { inlines(it) })
                }
                else -> {
                    val para = ArrayList<String>()
                    while (i < lines.size && lines[i].isNotBlank() && !heading.matches(lines[i]) && !quote.matches(lines[i]) &&
                        !bullet.matches(lines[i]) && !numbered.matches(lines[i])) { para += lines[i].trimEnd(); i++ }
                    val joined = ArrayList<Inline>()
                    para.forEachIndexed { k, l -> if (k > 0) joined += Break; joined += inlines(l.trim()) }
                    out += Para(joined)
                }
            }
        }
        return out
    }

    /** Inline marks; anything unclosed stays as typed. */
    fun inlines(s: String): List<Inline> {
        val out = ArrayList<Inline>()
        val buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { out += Text(buf.toString()); buf.clear() } }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] in "\\*_~`[]()|#>-") { buf.append(s[i + 1]); i += 2; continue }
            fun wrap(open: String, make: (List<Inline>) -> Inline): Boolean {
                if (!s.startsWith(open, i)) return false
                val end = s.indexOf(open, i + open.length)
                if (end <= i + open.length) return false
                val inner = s.substring(i + open.length, end)
                if (inner.isBlank() || inner.first().isWhitespace() || inner.last().isWhitespace()) return false
                // _italic_ only at word edges, so snake_case stays.
                if (open == "_" && ((i > 0 && s[i - 1].isLetterOrDigit()) || (end + 1 < s.length && s[end + 1].isLetterOrDigit()))) return false
                flush(); out += make(inlines(inner)); i = end + open.length
                return true
            }
            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i + 1) { flush(); out += Code(s.substring(i + 1, end)); i = end + 1; continue }
            }
            if (c == '[') {
                val close = s.indexOf("](", i + 1)
                val end = if (close > 0) s.indexOf(')', close + 2) else -1
                val url = if (end > 0) s.substring(close + 2, end).trim() else ""
                if (close > i + 1 && end > 0 && (url.startsWith("http://") || url.startsWith("https://"))) {
                    flush(); out += Link(inlines(s.substring(i + 1, close)), url); i = end + 1; continue
                }
            }
            if (wrap("**", ::Bold) || wrap("__", ::Bold) || wrap("~~", ::Strike) || wrap("||", ::Spoiler) ||
                wrap("*", ::Italic) || wrap("_", ::Italic)) continue
            buf.append(c); i++
        }
        flush()
        return out
    }

    /** Whether the review marks a ||spoiler||: trackers without inline spoilers flag the whole review. */
    fun hasSpoiler(md: String): Boolean {
        fun any(inl: List<Inline>): Boolean = inl.any { x ->
            when (x) {
                is Spoiler -> true
                is Bold -> any(x.children)
                is Italic -> any(x.children)
                is Strike -> any(x.children)
                is Link -> any(x.children)
                else -> false
            }
        }
        fun block(b: Block): Boolean = when (b) {
            is Para -> any(b.inlines)
            is Heading -> any(b.inlines)
            is Quote -> b.blocks.any { block(it) }
            is ListBlock -> b.items.any { any(it) }
        }
        return parse(md).any { block(it) }
    }

    // ---- outputs ----

    /** Plain text with the marks taken out: lists as bullets, links as "text (address)". */
    fun toPlain(md: String): String = parse(md).joinToString("\n\n") { plainBlock(it) }.trim()

    private fun plainBlock(b: Block): String = when (b) {
        is Para -> plain(b.inlines)
        is Heading -> plain(b.inlines)
        is Quote -> "“" + b.blocks.joinToString("\n\n") { plainBlock(it) } + "”"
        is ListBlock -> b.items.mapIndexed { n, it -> (if (b.ordered) "${n + 1}. " else "• ") + plain(it) }.joinToString("\n")
    }

    fun plain(inl: List<Inline>): String = inl.joinToString("") { x ->
        when (x) {
            is Text -> x.text
            is Bold -> plain(x.children)
            is Italic -> plain(x.children)
            is Strike -> plain(x.children)
            is Code -> x.text
            is Spoiler -> "[spoiler] " + plain(x.children)
            is Link -> plain(x.children).let { t -> if (t == x.url) t else "$t (${x.url})" }
            Break -> "\n"
        }
    }

    /**
     * HTML for Goodreads' review box: <b>, <i>, <s>, <a>, <blockquote>, <spoiler> and <br>
     * (it has no lists or headings, so those become bullets and bold lines).
     */
    fun toGoodreadsHtml(md: String): String = parse(md).joinToString("<br><br>") { grBlock(it) }

    private fun grBlock(b: Block): String = when (b) {
        is Para -> html(b.inlines, spoiler = "spoiler")
        is Heading -> "<b>" + html(b.inlines, spoiler = "spoiler") + "</b>"
        is Quote -> "<blockquote>" + b.blocks.joinToString("<br><br>") { grBlock(it) } + "</blockquote>"
        is ListBlock -> b.items.mapIndexed { n, it -> (if (b.ordered) "${n + 1}. " else "• ") + html(it, spoiler = "spoiler") }.joinToString("<br>")
    }

    /** HTML for StoryGraph's editor (Trix): blocks in <div>, lists and quotes as themselves. */
    fun toTrixHtml(md: String): String = parse(md).joinToString("") { trixBlock(it) }

    private fun trixBlock(b: Block): String = when (b) {
        is Para -> "<div>" + html(b.inlines, spoiler = null) + "</div>"
        is Heading -> "<h1>" + html(b.inlines, spoiler = null) + "</h1>"
        is Quote -> "<blockquote>" + b.blocks.joinToString("<br>") { blk -> trixBlock(blk).removePrefix("<div>").removeSuffix("</div>") } + "</blockquote>"
        is ListBlock -> (if (b.ordered) "<ol>" else "<ul>") + b.items.joinToString("") { "<li>" + html(it, spoiler = null) + "</li>" } +
            (if (b.ordered) "</ol>" else "</ul>")
    }

    /** [spoiler] is the tag the site hides spoilers in, or null to mark them in words. */
    private fun html(inl: List<Inline>, spoiler: String?): String = inl.joinToString("") { x ->
        when (x) {
            is Text -> Html.escape(x.text)
            is Bold -> "<b>" + html(x.children, spoiler) + "</b>"
            is Italic -> "<i>" + html(x.children, spoiler) + "</i>"
            is Strike -> "<s>" + html(x.children, spoiler) + "</s>"
            is Code -> "<code>" + Html.escape(x.text) + "</code>"
            is Link -> "<a href=\"" + Html.escape(x.url) + "\">" + html(x.children, spoiler) + "</a>"
            is Spoiler -> if (spoiler != null) "<$spoiler>" + html(x.children, spoiler) + "</$spoiler>" else "[spoiler] " + html(x.children, null)
            Break -> "<br>"
        }
    }
}
