package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.data.Book
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.data.Store
import io.github.prithirajn.readync.net.Http
import org.json.JSONArray
import org.json.JSONObject

/**
 * Your highlights, quotes and notes, from every source, kept in the apps you collect them in:
 *
 * - Readwise: each one sent once through its API (it adds them to your library and its daily review).
 * - Notion: a page per book under a page you choose, the new ones added to it as they come.
 * - Obsidian: a Markdown note per book in a folder of your vault, written again whenever the book's
 *   highlights change; anything you write outside Readync's part of the note is kept.
 *
 * After each sync, from every book (ones held back left out). Readwise and Notion get each
 * highlight once (later edits stay here); Obsidian always has them as they are now.
 */
object Exports {
    var readwise = "https://readwise.io/api/v2"
    var notion = "https://api.notion.com/v1"
    private const val NOTION_VERSION = "2022-06-28"

    /** Notion pages made or added to per sync, so a big first export is spread over a few. */
    private const val NOTION_BOOKS_PER_SYNC = 8

    class ExportException(message: String) : Exception(message)

    /** Each book here with its highlights and notes, in reading order (where in the book, then when). */
    fun books(s: Snapshot): List<Pair<Book, List<Highlights.Item>>> =
        s.books.values.filter { !it.held }.mapNotNull { b ->
            val items = Highlights.forBook(s, b.document).filter { it.clip.text.isNotBlank() }
            if (items.isEmpty()) null
            else b to items.sortedWith(compareBy<Highlights.Item> { it.clip.percent ?: 2.0 }.thenBy { it.clip.createdAt })
        }

    private fun isNote(id: String) = KoHighlightSync.isNote(id)

    private fun iso(sec: Long): String = java.time.Instant.ofEpochSecond(sec).toString()

    // ---- Readwise ----

    /** Whether [token] is a Readwise access token. */
    fun readwiseCheck(token: String): Boolean {
        val r = Http.request("GET", "$readwise/auth/", mapOf("Authorization" to "Token ${token.trim()}"), timeoutMs = 20_000)
        return r.code == 204 || r.ok
    }

    /** One highlight as Readwise takes it. */
    internal fun readwiseItem(b: Book, i: Highlights.Item): JSONObject {
        val c = i.clip
        val note = isNote(c.id)
        val o = JSONObject()
            .put("text", c.text.trim().take(8191))
            .put("title", b.displayTitle.take(511))
            .put("source_type", "readync")
            .put("category", "books")
        b.author?.let { o.put("author", it.take(1024)) }
        b.coverUrl?.let { o.put("image_url", it) }
        // A note on a bookmark has no passage: the note is the highlight, marked as such.
        if (note) o.put("note", "(a note on a bookmark)")
        else c.note?.takeIf { it.isNotBlank() }?.let { o.put("note", it.trim().take(8191)) }
        val page = c.pageIn(b.pageCount)
        when {
            page != null -> o.put("location", page).put("location_type", "page")
            c.percent != null -> o.put("location", Math.round(c.percent * 1000).toInt()).put("location_type", "order")
        }
        if (c.createdAt > 0) o.put("highlighted_at", iso(c.createdAt))
        return o
    }

    private fun pushReadwise(store: Store) {
        val s = store.snapshot
        val token = s.readwiseToken ?: return
        // Readwise linked on the crosspoint-sync server too: it sends CrossPoint's own highlights there.
        val cpIds = if ("readwise" in s.serverLinked) s.cpClips.map { it.id }.toSet() else emptySet()
        val todo = books(s).flatMap { (b, items) -> items.filter { it.clip.id !in s.readwiseDone && it.clip.id !in cpIds }.map { b to it } }
        if (todo.isEmpty()) return
        // In batches; each batch remembered as sent once Readwise has it.
        for (batch in todo.chunked(100)) {
            val body = JSONObject().put("highlights", JSONArray(batch.map { (b, i) -> readwiseItem(b, i) }))
            val r = Http.request(
                "POST", "$readwise/highlights/",
                mapOf("Authorization" to "Token $token", "Content-Type" to "application/json"),
                body = body.toString(), timeoutMs = 60_000,
            )
            if (r.code == 401 || r.code == 403) throw ExportException("Readwise didn't accept the token; paste a new one.")
            if (!r.ok) throw ExportException("Readwise answered HTTP ${r.code}")
            store.update { x -> x.copy(readwiseDone = x.readwiseDone + batch.map { it.second.clip.id }) }
        }
    }

    // ---- Notion ----

    /** The page id in a Notion link or id ("https://www.notion.so/Reading-1a2b…" -> "1a2b…"); null when there's none. */
    fun notionPageId(link: String): String? =
        Regex("([0-9a-fA-F]{32})|([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")
            .findAll(link.substringBefore('?')).lastOrNull()?.value?.replace("-", "")?.lowercase()

    private fun notionCall(token: String, method: String, path: String, body: JSONObject? = null): JSONObject {
        val r = Http.request(
            method, "$notion$path",
            mapOf("Authorization" to "Bearer $token", "Notion-Version" to NOTION_VERSION, "Content-Type" to "application/json"),
            body = body?.toString(), timeoutMs = 60_000,
        )
        val o = runCatching { JSONObject(r.body) }.getOrNull()
        if (!r.ok) {
            val msg = o?.optString("message")?.takeIf { it.isNotBlank() }
            throw ExportException(
                when {
                    r.code == 401 -> "Notion didn't accept the integration's secret."
                    r.code == 404 -> "Notion can't see that page: open it, ⋯ › Connections, and add your integration."
                    else -> msg ?: "Notion answered HTTP ${r.code}"
                },
            )
        }
        return o ?: JSONObject()
    }

    /** Whether the integration can reach the page books go under. Null when it can, else why not. */
    fun notionCheck(token: String, parent: String): String? =
        runCatching { notionCall(token.trim(), "GET", "/pages/$parent"); null }.getOrElse { it.message ?: "Couldn't reach Notion" }

    private fun rich(text: String, italic: Boolean = false, bold: Boolean = false, color: String? = null): JSONArray =
        // Notion takes at most 2000 characters a piece.
        JSONArray(text.chunked(2000).map { part ->
            JSONObject().put("type", "text").put("text", JSONObject().put("content", part))
                .put("annotations", JSONObject().put("italic", italic).put("bold", bold).put("color", color ?: "default"))
        })

    private fun block(type: String, content: JSONObject): JSONObject = JSONObject().put("object", "block").put("type", type).put(type, content)

    /** A highlight as Notion blocks: the passage as a quote, where and when under it, your note in a callout. */
    internal fun notionBlocks(b: Book, i: Highlights.Item): List<JSONObject> {
        val c = i.clip
        val out = ArrayList<JSONObject>()
        val day = if (c.createdAt > 0) java.time.Instant.ofEpochSecond(c.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() else null
        val where = listOfNotNull(c.chapter?.takeIf { it.isNotBlank() }, HighlightPosition.label(c), day, i.from).joinToString(" · ")
        if (isNote(c.id)) {
            out += block("callout", JSONObject().put("rich_text", rich(c.text.trim())).put("icon", JSONObject().put("type", "emoji").put("emoji", "📝")))
        } else {
            out += block("quote", JSONObject().put("rich_text", rich(c.text.trim())))
            c.note?.takeIf { it.isNotBlank() }?.let { n ->
                out += block("callout", JSONObject().put("rich_text", rich(n.trim())).put("icon", JSONObject().put("type", "emoji").put("emoji", "💭")))
            }
        }
        if (where.isNotBlank()) out += block("paragraph", JSONObject().put("rich_text", rich(where, italic = true, color = "gray")))
        return out
    }

    /** The book's page under [parent]: its cover, author and series at the top. Returns its id. */
    private fun notionBookPage(token: String, parent: String, b: Book): String {
        val series = QuoteCardText.seriesOf(b.displayTitle)
        val head = listOfNotNull(b.author?.let { "by $it" }, series).joinToString(" · ")
        val page = JSONObject()
            .put("parent", JSONObject().put("page_id", parent))
            .put("icon", JSONObject().put("type", "emoji").put("emoji", "📖"))
            .put("properties", JSONObject().put("title", JSONObject().put("title", rich(b.displayTitle.take(1900)))))
        b.coverUrl?.takeIf { it.startsWith("https://") }?.let { page.put("cover", JSONObject().put("type", "external").put("external", JSONObject().put("url", it))) }
        val kids = ArrayList<JSONObject>()
        if (head.isNotBlank()) kids += block("paragraph", JSONObject().put("rich_text", rich(head, italic = true)))
        kids += block("divider", JSONObject())
        page.put("children", JSONArray(kids))
        return notionCall(token, "POST", "/pages", page).optString("id").takeIf { it.isNotBlank() } ?: throw ExportException("Notion didn't make the page")
    }

    private fun pushNotion(store: Store) {
        val s = store.snapshot
        val token = s.notionToken ?: return
        val parent = s.notionParent ?: return
        var touched = 0
        for ((b, items) in books(s)) {
            val fresh = items.filter { it.clip.id !in store.snapshot.notionDone }
            if (fresh.isEmpty()) continue
            if (touched >= NOTION_BOOKS_PER_SYNC) break
            touched++
            var page = store.snapshot.notionPages[b.document]
            if (page == null) {
                page = notionBookPage(token, parent, b)
                val made = page
                store.update { it.copy(notionPages = it.notionPages + (b.document to made)) }
            }
            // At most 100 blocks a request: a few highlights at a time, each remembered once Notion has it.
            var batch = ArrayList<JSONObject>()
            var ids = ArrayList<String>()
            fun send() {
                if (batch.isEmpty()) return
                try {
                    notionCall(token, "PATCH", "/blocks/$page/children", JSONObject().put("children", JSONArray(batch)))
                } catch (e: ExportException) {
                    // The book's page was deleted in Notion: made again next time.
                    if (e.message?.contains("can't see") == true) store.update { it.copy(notionPages = it.notionPages - b.document) }
                    throw e
                }
                val done = ids.toList()
                store.update { it.copy(notionDone = it.notionDone + done) }
                batch = ArrayList(); ids = ArrayList()
            }
            for (i in fresh) {
                val blocks = notionBlocks(b, i)
                if (batch.size + blocks.size > 90) send()
                batch.addAll(blocks); ids += i.clip.id
            }
            send()
        }
    }

    // ---- Obsidian ----

    /** Reads and writes a note in the vault folder you picked (Android folder access): set up by the app. */
    @Volatile var readNote: ((folder: String, name: String) -> String?)? = null
    @Volatile var writeNote: ((folder: String, name: String, text: String) -> Boolean)? = null

    const val START = "<!-- readync:start -->"
    const val END = "<!-- readync:end -->"

    /** A safe file name for the book's note. */
    internal fun noteName(b: Book): String =
        b.displayTitle.replace(Regex("""[\\/:*?"<>|#^\[\]]"""), " ").replace(Regex("\\s+"), " ").trim().take(120).ifEmpty { "Untitled" } + ".md"

    private fun yaml(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Readync's part of the note: what the book is, then every highlight with its note, your rating and review. */
    internal fun noteBody(b: Book, items: List<Highlights.Item>): String = buildString {
        val series = QuoteCardText.seriesOf(b.displayTitle)
        appendLine("## ${QuoteCardText.short(b.displayTitle)}")
        appendLine(listOfNotNull(b.author?.let { "*by $it*" }, series, "${b.percent}% · ${b.status?.label ?: "Reading"}").joinToString(" · "))
        b.rating?.let { r -> appendLine(); appendLine("**My rating:** " + "⭐".repeat(r.toInt()) + (if (r % 1.0 >= 0.5) "½" else "") + " ($r/5)") }
        appendLine()
        appendLine("### Highlights")
        appendLine()
        for (i in items) {
            val c = i.clip
            val day = if (c.createdAt > 0) java.time.Instant.ofEpochSecond(c.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() else null
            val where = listOfNotNull(c.chapter?.takeIf { it.isNotBlank() }, HighlightPosition.label(c), day).joinToString(" · ")
            if (isNote(c.id)) {
                appendLine("> [!note] Note" + (if (where.isNotBlank()) " · $where" else ""))
                c.text.trim().lines().forEach { appendLine("> $it") }
            } else {
                c.text.trim().lines().forEach { appendLine("> $it") }
                if (where.isNotBlank()) appendLine(">\n> — *$where*")
                c.note?.takeIf { it.isNotBlank() }?.let { n ->
                    appendLine()
                    appendLine("> [!quote]- My note")
                    n.trim().lines().forEach { appendLine("> $it") }
                }
            }
            appendLine()
        }
        b.review?.takeIf { it.isNotBlank() }?.let { r ->
            appendLine("### My review")
            appendLine()
            appendLine(r.trim())
            appendLine()
        }
    }

    /** The whole note: frontmatter, Readync's part between its markers, and what you wrote outside them kept. */
    internal fun note(b: Book, items: List<Highlights.Item>, old: String?): String {
        val front = buildString {
            appendLine("---")
            appendLine("title: " + yaml(QuoteCardText.short(b.displayTitle)))
            b.author?.let { appendLine("author: " + yaml(it)) }
            QuoteCardText.seriesOf(b.displayTitle)?.let { appendLine("series: " + yaml(it)) }
            appendLine("status: " + yaml(b.status?.label ?: "Reading"))
            appendLine("progress: ${b.percent}")
            b.rating?.let { appendLine("rating: $it") }
            b.coverUrl?.let { appendLine("cover: " + yaml(it)) }
            appendLine("highlights: ${items.size}")
            appendLine("tags: [readync, books]")
            appendLine("---")
        }
        val part = "$START\n${noteBody(b, items)}$END"
        if (old == null || !old.contains(START) || !old.contains(END)) return front + "\n" + part + "\n"
        // Yours kept: everything you wrote outside Readync's markers (the frontmatter is Readync's).
        val before = old.substringBefore(START).let { t -> if (t.trimStart().startsWith("---")) t.trimStart().removePrefix("---").substringAfter("---").trimStart('\n') else t }
        val after = old.substringAfter(END)
        return front + "\n" + before.trimStart('\n') + part + after.ifEmpty { "\n" }
    }

    private fun pushObsidian(store: Store) {
        val s = store.snapshot
        val folder = s.obsidianFolder ?: return
        val read = readNote ?: return
        val write = writeNote ?: throw ExportException("Folder access isn't available")
        val hashes = HashMap(s.obsidianHashes)
        for ((b, items) in books(s)) {
            val name = noteName(b)
            // What Readync would write for it now; unchanged since the last time, it's left alone.
            val hash = Integer.toHexString((noteBody(b, items) + name).hashCode())
            if (hashes[b.document] == hash) continue
            val text = note(b, items, read(folder, name))
            if (!write(folder, name, text)) throw ExportException("Couldn't write “$name” in that folder; pick it again.")
            hashes[b.document] = hash
        }
        if (hashes != s.obsidianHashes) store.update { it.copy(obsidianHashes = hashes) }
    }

    // ---- all of them ----

    /** Each set-up export brought up to date; each one's problem kept for its card (and the others carry on). */
    fun run(store: Store) {
        val jobs = listOf("readwise" to ::pushReadwise, "notion" to ::pushNotion, "obsidian" to ::pushObsidian)
        for ((name, job) in jobs) {
            val on = when (name) {
                "readwise" -> store.snapshot.readwiseToken != null
                "notion" -> store.snapshot.notionToken != null && store.snapshot.notionParent != null
                else -> store.snapshot.obsidianFolder != null
            }
            if (!on) continue
            val r = runCatching { job(store) }
            store.update { s ->
                r.fold(
                    { s.copy(exportErrors = s.exportErrors - name, exportOkAt = s.exportOkAt + (name to System.currentTimeMillis() / 1000)) },
                    { e -> s.copy(exportErrors = s.exportErrors + (name to (e.message ?: "Couldn't export"))) },
                )
            }
        }
    }
}

/** A book's own name and its series, from its title (as the share cards show them), for code outside the ui package. */
object QuoteCardText {
    fun short(t: String): String = io.github.prithirajn.readync.ui.QuoteCard.shortTitle(t)
    fun seriesOf(t: String): String? = io.github.prithirajn.readync.ui.QuoteCard.seriesLine(t)
}
