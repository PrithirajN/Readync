package io.github.prithirajn.trackersync.trackers

import io.github.prithirajn.trackersync.data.Link
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Visibility
import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.net.HttpResponse
import io.github.prithirajn.trackersync.net.ServiceException
import io.github.prithirajn.trackersync.source.Clipping
import io.github.prithirajn.trackersync.util.arr
import io.github.prithirajn.trackersync.util.idString
import io.github.prithirajn.trackersync.util.obj
import io.github.prithirajn.trackersync.util.objects
import io.github.prithirajn.trackersync.util.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * Micro.blog's bookshelves: the book you're reading goes on Currently reading, a finished one on
 * Finished reading (and off the other shelves). Micro.blog keeps no progress. Highlights, when
 * you turn them on, are posted to your blog: one post per book each time, with the passages and
 * your notes, through Micropub.
 */
object MicroblogTracker : Tracker {
    override val id = "microblog"
    override val name = "Micro.blog"
    override val signInKind = SignInKind.TOKEN
    override val privacy = PrivacySupport.PRIVATE_ONLY
    override val privacyNote = "Your bookshelves show on your Micro.blog profile; apps can't choose who sees them."
    override val readsProgress = false
    override val takesProgress = false
    override val statuses = setOf(ReadStatus.TO_READ, ReadStatus.READING, ReadStatus.FINISHED)
    override val hint = "Keeps your Currently reading and Finished reading shelves"
    override val tokenHelp = "Make an app token on micro.blog under Account › Edit Apps (App tokens), and paste it here."
    override val tokenUrl = "https://micro.blog/account/apps"
    override val tokenLabel = "App token"
    override val highlights = HighlightSupport.PUBLIC_POST
    // The post goes on your blog, public as Micro.blog posts are, whatever the setting above.
    override val highlightsIgnoreVisibility = true
    override val highlightsNote = "Posted on your blog: a post for each book with its new passages and your notes."

    private const val BASE = "https://micro.blog"

    private fun auth(session: Session) = "Bearer ${session.data.str("token") ?: ""}"

    private fun call(session: Session, method: String, path: String, form: Map<String, String>? = null): HttpResponse {
        val h = linkedMapOf("Authorization" to auth(session), "Accept" to "application/json")
        if (form != null) h["Content-Type"] = "application/x-www-form-urlencoded"
        val r = Http.request(method, BASE + path, h, form?.entries?.joinToString("&") { "${it.key}=${Http.enc(it.value)}" })
        // Already off that shelf.
        if (method == "DELETE" && r.code == 404) return r
        checkAnswer(r, name)
        return r
    }

    override fun signInWithToken(token: String): SignInResult {
        val s = Session(JSONObject().put("token", token.trim()).toString())
        val r = Http.request("GET", "$BASE/books/bookshelves", mapOf("Authorization" to auth(s), "Accept" to "application/json"))
        if (r.code == 401 || r.code == 403) throw ServiceException("Micro.blog didn't accept that token")
        checkAnswer(r, name)
        val who = runCatching {
            jsonOrNull(Http.request("GET", "$BASE/account/verify", mapOf("Content-Type" to "application/x-www-form-urlencoded"), "token=" + Http.enc(token.trim())).body)?.str("username")
        }.getOrNull()
        return SignInResult(s.encode(), who?.let { "@$it" } ?: "Micro.blog")
    }

    /** Shelf kind ("reading", "finished", "to-read") to its id. */
    private fun shelves(session: Session): Map<String, String> =
        (jsonOrNull(call(session, "GET", "/books/bookshelves").body)?.arr("items")?.objects() ?: emptyList()).mapNotNull { o ->
            val type = o.obj("_microblog")?.str("type") ?: return@mapNotNull null
            val id = o.idString("id") ?: return@mapNotNull null
            type to id
        }.toMap()

    private data class OnShelf(val book: RemoteBook, val isbn: String?, val shelf: String)

    private fun inventory(session: Session): List<OnShelf> {
        val out = ArrayList<OnShelf>()
        for ((type, id) in shelves(session)) {
            if (type !in setOf("reading", "finished", "to-read")) continue
            val items = jsonOrNull(call(session, "GET", "/books/bookshelves/${Http.enc(id)}").body)?.arr("items")?.objects() ?: continue
            for (b in items) {
                val bid = b.idString("id") ?: continue
                val title = b.str("title") ?: continue
                val author = b.arr("authors")?.objects()?.mapNotNull { it.str("name") }?.joinToString(", ")?.ifEmpty { null }
                out += OnShelf(RemoteBook(bid, title, author, coverUrl = b.str("image")), b.obj("_microblog")?.idString("isbn"), type)
            }
        }
        return out
    }

    override fun search(session: Session, title: String, author: String?): List<RemoteBook> {
        val q = listOfNotNull(title, author).joinToString(" ")
        val r = call(session, "GET", "/books/search?q=${Http.enc(q)}&format=jsonfeed")
        return (jsonOrNull(r.body)?.arr("items")?.objects() ?: emptyList()).mapNotNull { b ->
            val id = b.idString("id") ?: return@mapNotNull null
            val t = b.str("title") ?: return@mapNotNull null
            val a = b.arr("authors")?.objects()?.mapNotNull { it.str("name") }?.joinToString(", ")?.ifEmpty { null }
            // The ISBN rides along, to put a book that isn't on a shelf yet on one.
            RemoteBook(id, t, a, uuid = b.obj("_microblog")?.idString("isbn"), coverUrl = b.str("image"))
        }
    }

    override fun shelfCandidates(session: Session): List<RemoteBook> = inventory(session).map { it.book }.distinctBy { it.id }

    private fun mine(session: Session, link: Link): List<OnShelf> {
        val inv = inventory(session)
        return inv.filter { it.book.id == link.externalId }.ifEmpty {
            link.externalUuid?.let { isbn -> inv.filter { it.isbn == isbn } } ?: emptyList()
        }
    }

    override fun state(session: Session, link: Link): RemoteState {
        val on = mine(session, link).map { it.shelf }.toSet()
        return RemoteState(
            when {
                "finished" in on -> ReadStatus.FINISHED
                "reading" in on -> ReadStatus.READING
                "to-read" in on -> ReadStatus.TO_READ
                else -> null
            },
            null,
        )
    }

    override fun pushProgress(session: Session, link: Link, percent: Int, visibility: Visibility) {}

    override fun setStatus(session: Session, link: Link, status: ReadStatus) {
        val target = when (status) {
            ReadStatus.READING -> "reading"
            ReadStatus.FINISHED -> "finished"
            ReadStatus.TO_READ -> "to-read"
            else -> return
        }
        val shelfIds = shelves(session)
        val to = shelfIds[target] ?: throw ServiceException("Your Micro.blog has no ${target.replace('-', ' ')} shelf")
        val on = mine(session, link)
        val bookId = on.firstOrNull()?.book?.id ?: link.externalId ?: return
        if (on.none { it.shelf == target }) {
            // A book on no shelf yet is put there by its ISBN, when the match has one.
            val isbn = link.externalUuid?.takeIf { on.isEmpty() && it.isNotBlank() }
            call(session, "POST", "/books/bookshelves/${Http.enc(to)}/assign", if (isbn != null) mapOf("isbn" to isbn) else mapOf("book_id" to bookId))
        }
        // Off the other shelves it moved from.
        for (from in listOf("to-read", "reading", "finished").filter { it != target }) {
            if (on.none { it.shelf == from }) continue
            if (target == "to-read") continue
            val id = shelfIds[from] ?: continue
            call(session, "DELETE", "/books/bookshelves/${Http.enc(id)}/remove/${Http.enc(bookId)}")
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun para(s: String) = esc(s.trim()).replace("\n", "<br>")

    override fun postHighlights(session: Session, link: Link, percent: Int, entries: List<Clipping>) {
        if (entries.isEmpty()) return
        val title = link.title ?: "a book"
        val bookUrl = link.externalUuid?.takeIf { it.isNotBlank() }?.let { "$BASE/books/${Http.enc(it)}" }
        val head = "<p>" + (bookUrl?.let { "<a href=\"${esc(it)}\">${esc(title)}</a>" } ?: esc(title)) + (link.author?.let { " by ${esc(it)}" } ?: "") + "</p>"
        val body = (listOf(head) + entries.sortedBy { it.createdAt }.map { c ->
            "<blockquote><p>${para(c.text)}</p></blockquote>" +
                (c.note?.takeIf { it.isNotBlank() }?.let { "<p>${para(it)}</p>" } ?: "") +
                (c.chapter?.takeIf { it.isNotBlank() }?.let { "<p><em>${para(it)}</em></p>" } ?: "")
        }).joinToString("\n\n")
        val post = JSONObject()
            .put("type", JSONArray().put("h-entry"))
            .put("properties", JSONObject()
                .put("name", JSONArray().put("📚 Clippings from $title"))
                .put("content", JSONArray().put(JSONObject().put("html", body)))
                .put("post-status", JSONArray().put("published")))
        val r = Http.request("POST", "$BASE/micropub", mapOf("Authorization" to auth(session), "Content-Type" to "application/json", "Accept" to "application/json"), post.toString())
        checkAnswer(r, name, "posting the highlights")
    }
}
