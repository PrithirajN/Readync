package io.github.prithirajn.trackersync.source

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Xml
import io.github.prithirajn.trackersync.net.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/**
 * OPDS catalogs: your book servers (Calibre-Web, Calibre's content server, Kavita, Komga, Stump,
 * COPS, Project Gutenberg…) read the way an OPDS reader reads them, so they can be browsed and,
 * above all, searched all at once rather than one server at a time.
 *
 * Both kinds are read: OPDS 1 (Atom XML, with search through an OpenSearch description or a
 * template) and OPDS 2 (JSON, with a templated search link). Sign-ins are Basic auth, sent only
 * to the server's own host.
 */
object Opds {

    // ---- servers ----

    data class Server(
        val id: String,
        val name: String,
        val url: String,
        val user: String? = null,
        val password: String? = null,
        /** The search address worked out from the catalog ({searchTerms} where the words go), once found. */
        val search: String? = null,
    ) {
        val host: String? get() = runCatching { URI(url).host?.lowercase() }.getOrNull()
    }

    private const val PREFS = "opds"
    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers
    private var loaded = false

    /** The saved servers (in the app's own storage, not in backups or exports). */
    fun load(context: Context): List<Server> {
        if (!loaded) {
            loaded = true
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("servers", null)
            _servers.value = runCatching {
                val a = JSONArray(raw ?: "[]")
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    Server(
                        o.getString("id"), o.getString("name"), o.getString("url"),
                        o.optString("user").ifBlank { null }, o.optString("password").ifBlank { null },
                        o.optString("search").ifBlank { null },
                    )
                }
            }.getOrDefault(emptyList())
        }
        return _servers.value
    }

    private fun save(context: Context, list: List<Server>) {
        _servers.value = list
        val a = JSONArray()
        for (s in list) a.put(JSONObject().put("id", s.id).put("name", s.name).put("url", s.url)
            .put("user", s.user ?: "").put("password", s.password ?: "").put("search", s.search ?: ""))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("servers", a.toString()).apply()
    }

    fun put(context: Context, server: Server) {
        val list = load(context)
        save(context, if (list.any { it.id == server.id }) list.map { if (it.id == server.id) server else it } else list + server)
    }

    fun remove(context: Context, id: String) = save(context, load(context).filter { it.id != id })

    // ---- what a catalog holds ----

    /** A file to get: its address and kind ("application/epub+zip"…). */
    data class Acquisition(val href: String, val type: String?, val rel: String?) {
        /** "EPUB", "PDF", "CBZ"… for a key. */
        val format: String get() = formatOf(type, href)
    }

    /** One entry: a book (with files to get) or a way further in (a shelf, an author, a series). */
    data class Entry(
        val id: String,
        val title: String,
        val authors: List<String> = emptyList(),
        val summary: String? = null,
        val cover: String? = null,
        val thumbnail: String? = null,
        val acquisitions: List<Acquisition> = emptyList(),
        /** For a navigation entry: the feed it opens. */
        val navigation: String? = null,
        val series: String? = null,
        val seriesIndex: String? = null,
        val published: String? = null,
        val publisher: String? = null,
        val language: String? = null,
        val categories: List<String> = emptyList(),
        /** When the server last changed it (for most, when it was added): ISO text. */
        val updated: String? = null,
    ) {
        val isBook: Boolean get() = acquisitions.isNotEmpty() || (navigation == null && summary != null)
        val image: String? get() = thumbnail ?: cover
    }

    data class Feed(
        val title: String,
        val url: String,
        val entries: List<Entry>,
        val next: String?,
        /** The search address for this catalog, as found in it (a template or an OpenSearch description). */
        val searchLink: String?,
        val searchIsDescription: Boolean,
        /** The catalog's own "newest first" feed, when it names one. */
        val newest: String? = null,
    )

    class OpdsException(message: String) : Exception(message)

    // ---- reading a feed ----

    private fun auth(server: Server, url: String): Map<String, String> {
        val h = HashMap<String, String>()
        h["Accept"] = "application/atom+xml;profile=opds-catalog, application/opds+json, application/atom+xml, application/json;q=0.9, */*;q=0.8"
        val sameHost = runCatching { URI(url).host?.lowercase() == server.host }.getOrDefault(false)
        if (sameHost && !server.user.isNullOrBlank()) {
            h["Authorization"] = "Basic " + Base64.encodeToString("${server.user}:${server.password.orEmpty()}".toByteArray(), Base64.NO_WRAP)
        }
        return h
    }

    fun fetch(server: Server, url: String, timeoutMs: Int = 25_000): Feed {
        val r = Http.request("GET", url, auth(server, url), timeoutMs = timeoutMs)
        if (r.code == 401 || r.code == 403) throw OpdsException(if (server.user.isNullOrBlank()) "${server.name} needs a username and password" else "${server.name} didn't accept the username and password")
        if (!r.ok) throw OpdsException("${server.name} answered HTTP ${r.code}")
        val body = r.body.trimStart()
        return when {
            body.startsWith("{") -> parseJson(body, r.url.ifBlank { url })
            body.startsWith("<") -> parseAtom(body, r.url.ifBlank { url })
            else -> throw OpdsException("${server.name} didn't send a catalog (is the address its OPDS one?)")
        }
    }

    private fun resolve(base: String, href: String?): String? {
        if (href.isNullOrBlank()) return null
        // Template parts ({searchTerms}, {?query}…) aren't allowed in an address: set aside while it's joined to the base.
        val held = ArrayList<String>()
        val plain = Regex("\\{[^}]*\\}").replace(href.trim()) { m -> held += m.value; "TPL${held.size - 1}X" }
        val joined = runCatching { URI(base).resolve(plain.replace(" ", "%20")).toString() }.getOrNull()
            ?: runCatching { URL(URL(base), plain).toString() }.getOrNull()
            ?: return href
        return held.foldIndexed(joined) { i, acc, t -> acc.replace("TPL${i}X", t) }
    }

    private fun isAcq(rel: String?) = rel != null && rel.startsWith("http://opds-spec.org/acquisition")
    private fun isImage(rel: String?) = rel == "http://opds-spec.org/image" || rel == "http://opds-spec.org/cover" || rel == "x-stanza-cover-image"
    private fun isThumb(rel: String?) = rel == "http://opds-spec.org/image/thumbnail" || rel == "http://opds-spec.org/thumbnail" || rel == "x-stanza-cover-image-thumbnail"
    private fun isFeedType(type: String?) = type != null && (type.contains("atom+xml") || type.contains("opds+json") || type.contains("opds-catalog"))

    internal fun parseAtom(xml: String, base: String): Feed {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(xml.reader())
        var title = ""
        var next: String? = null
        var search: String? = null
        var searchDesc = false
        var newest: String? = null
        val entries = ArrayList<Entry>()
        // The entry being read.
        var inEntry = false
        var e = HashMap<String, Any?>()
        var authors = ArrayList<String>()
        var acqs = ArrayList<Acquisition>()
        var cats = ArrayList<String>()
        var inAuthor = false
        val path = ArrayList<String>()
        fun local(n: String) = n.substringAfter(':')
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val name = local(p.name)
                    path += name
                    when {
                        name == "entry" -> { inEntry = true; e = HashMap(); authors = ArrayList(); acqs = ArrayList(); cats = ArrayList() }
                        name == "author" && inEntry -> inAuthor = true
                        name == "link" -> {
                            val rel = p.getAttributeValue(null, "rel")
                            val type = p.getAttributeValue(null, "type")
                            val href = resolve(base, p.getAttributeValue(null, "href"))
                            if (href != null) {
                                if (inEntry) {
                                    when {
                                        isAcq(rel) -> acqs += Acquisition(href, type, rel)
                                        isThumb(rel) -> e["thumb"] = href
                                        isImage(rel) -> e["cover"] = href
                                        rel == "http://opds-spec.org/sort/new" && newest == null -> { newest = href; if (e["nav"] == null) e["nav"] = href }
                                        isFeedType(type) && rel != "alternate" && e["nav"] == null -> e["nav"] = href
                                        // Some servers list a file without the acquisition rel.
                                        rel == null && type != null && formatOf(type, href) != "FILE" && !isFeedType(type) -> acqs += Acquisition(href, type, "http://opds-spec.org/acquisition")
                                    }
                                } else {
                                    when (rel) {
                                        "http://opds-spec.org/sort/new" -> newest = href
                                        "next" -> next = href
                                        "search" -> if (search == null || type?.contains("atom") == true) {
                                            search = href; searchDesc = type?.contains("opensearchdescription") == true && !href.contains("{searchTerms}")
                                        }
                                    }
                                }
                            }
                        }
                        name == "category" && inEntry -> (p.getAttributeValue(null, "label") ?: p.getAttributeValue(null, "term"))?.let { cats += it }
                        name == "series" && inEntry -> { // Calibre's <calibre:series> is text; schema.org's is attributes
                            p.getAttributeValue(null, "name")?.let { e["series"] = it }
                            p.getAttributeValue(null, "position")?.let { e["seriesIndex"] = it }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val t = p.text?.trim().orEmpty()
                    if (t.isNotEmpty()) {
                        val tag = path.lastOrNull()
                        if (inEntry) when (tag) {
                            "title" -> if (path.getOrNull(path.size - 2) == "entry") e["title"] = t
                            "name" -> if (inAuthor) authors += t
                            "id" -> if (path.getOrNull(path.size - 2) == "entry") e["id"] = t
                            "summary", "content" -> if (e["summary"] == null || tag == "summary") e["summary"] = stripHtml(t)
                            "issued", "published" -> if (e["published"] == null) e["published"] = t
                            "updated" -> if (path.getOrNull(path.size - 2) == "entry") e["updated"] = t
                            "publisher" -> e["publisher"] = t
                            "language" -> e["language"] = t
                            "series" -> e["series"] = t
                            "series_index" -> e["seriesIndex"] = t
                        } else if (tag == "title" && title.isEmpty()) title = t
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = local(p.name)
                    if (name == "author") inAuthor = false
                    if (name == "entry" && inEntry) {
                        inEntry = false
                        val t = (e["title"] as? String) ?: ""
                        if (t.isNotBlank()) entries += Entry(
                            id = (e["id"] as? String) ?: (t + authors.joinToString()),
                            title = t, authors = authors.distinct(),
                            summary = e["summary"] as? String,
                            cover = e["cover"] as? String, thumbnail = e["thumb"] as? String,
                            acquisitions = acqs.distinctBy { it.href },
                            navigation = if (acqs.isEmpty()) e["nav"] as? String else null,
                            series = e["series"] as? String, seriesIndex = e["seriesIndex"] as? String,
                            published = (e["published"] as? String)?.take(10),
                            publisher = e["publisher"] as? String, language = e["language"] as? String,
                            categories = cats.distinct().take(6),
                            updated = e["updated"] as? String,
                        )
                    }
                    if (path.isNotEmpty()) path.removeAt(path.lastIndex)
                }
            }
            ev = p.next()
        }
        return Feed(title, base, entries, next, search, searchDesc, newest)
    }

    internal fun parseJson(json: String, base: String): Feed {
        val o = JSONObject(json)
        val meta = o.optJSONObject("metadata")
        var next: String? = null
        var search: String? = null
        var newest: String? = null
        o.optJSONArray("links")?.let { ls ->
            for (i in 0 until ls.length()) {
                val l = ls.optJSONObject(i) ?: continue
                val rel = relOf(l)
                when {
                    rel.any { it.endsWith("sort/new") } -> newest = resolve(base, l.optString("href"))
                    "next" in rel -> next = resolve(base, l.optString("href"))
                    "search" in rel -> search = l.optString("href").let { h -> if (l.optBoolean("templated")) resolveTemplate(base, h) else resolve(base, h) }
                }
            }
        }
        val entries = ArrayList<Entry>()
        o.optJSONArray("navigation")?.let { addNav(it, base, entries) }
        o.optJSONArray("publications")?.let { addPubs(it, base, entries) }
        o.optJSONArray("groups")?.let { gs ->
            for (i in 0 until gs.length()) {
                val g = gs.optJSONObject(i) ?: continue
                g.optJSONArray("navigation")?.let { addNav(it, base, entries) }
                g.optJSONArray("publications")?.let { addPubs(it, base, entries) }
            }
        }
        return Feed(meta?.optString("title").orEmpty(), base, entries, next, search, false, newest)
    }

    private fun relOf(l: JSONObject): List<String> = when (val r = l.opt("rel")) {
        is String -> listOf(r)
        is JSONArray -> (0 until r.length()).map { r.optString(it) }
        else -> emptyList()
    }

    /** An OPDS 2 URI template ("/search{?query}") turned into one with {searchTerms}. */
    private fun resolveTemplate(base: String, h: String): String {
        val withTerms = h.replace(Regex("\\{\\?([a-zA-Z_]+)[^}]*\\}")) { m -> "?" + m.groupValues[1] + "={searchTerms}" }
            .replace("{query}", "{searchTerms}")
        return resolve(base, withTerms.replace("{searchTerms}", "SEARCHTERMS"))?.replace("SEARCHTERMS", "{searchTerms}") ?: withTerms
    }

    private fun addNav(a: JSONArray, base: String, out: MutableList<Entry>) {
        for (i in 0 until a.length()) {
            val l = a.optJSONObject(i) ?: continue
            val href = resolve(base, l.optString("href")) ?: continue
            out += Entry(id = href, title = l.optString("title").ifBlank { href }, navigation = href)
        }
    }

    private fun addPubs(a: JSONArray, base: String, out: MutableList<Entry>) {
        for (i in 0 until a.length()) {
            val p = a.optJSONObject(i) ?: continue
            val m = p.optJSONObject("metadata") ?: continue
            val authors = when (val au = m.opt("author")) {
                is String -> listOf(au)
                is JSONObject -> listOf(au.optString("name"))
                is JSONArray -> (0 until au.length()).mapNotNull { j -> au.opt(j).let { x -> (x as? JSONObject)?.optString("name") ?: x as? String } }
                else -> emptyList()
            }.filter { it.isNotBlank() }
            val acqs = ArrayList<Acquisition>()
            p.optJSONArray("links")?.let { ls ->
                for (j in 0 until ls.length()) {
                    val l = ls.optJSONObject(j) ?: continue
                    val rel = relOf(l)
                    if (rel.any { isAcq(it) }) resolve(base, l.optString("href"))?.let { acqs += Acquisition(it, l.optString("type").ifBlank { null }, rel.first()) }
                }
            }
            var img: String? = null
            p.optJSONArray("images")?.let { im -> img = im.optJSONObject(0)?.optString("href")?.let { resolve(base, it) } }
            val series = m.optJSONObject("belongsTo")?.opt("series").let { s -> (s as? JSONObject) ?: (s as? JSONArray)?.optJSONObject(0) }
            out += Entry(
                id = m.optString("identifier").ifBlank { m.optString("title") },
                title = m.optString("title"), authors = authors,
                summary = m.optString("description").ifBlank { null }?.let { stripHtml(it) },
                cover = img, acquisitions = acqs,
                series = series?.optString("name")?.ifBlank { null }, seriesIndex = series?.opt("position")?.toString(),
                published = m.optString("published").take(10).ifBlank { null },
                language = m.optString("language").ifBlank { null },
            )
        }
    }

    private fun stripHtml(s: String): String =
        android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_COMPACT).toString().replace(Regex("\\n{3,}"), "\n\n").trim()

    // ---- search ----

    /**
     * The server's search address ({searchTerms} where the words go): read from its catalog the first
     * time (through its OpenSearch description where it has one) and kept. Null when it has no search.
     */
    fun searchTemplate(context: Context, server: Server): String? {
        server.search?.let { return it }
        val root = fetch(server, server.url)
        var t = root.searchLink ?: return null
        if (root.searchIsDescription) {
            val r = Http.request("GET", t, auth(server, t))
            if (!r.ok) return null
            t = templateFromDescription(r.body, t) ?: return null
        }
        put(context, server.copy(search = t))
        return t
    }

    /** The search template in an OpenSearch description, preferring an OPDS (Atom) one. */
    internal fun templateFromDescription(xml: String, base: String): String? {
        val p = Xml.newPullParser()
        p.setInput(xml.reader())
        val found = ArrayList<Pair<String?, String>>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name.substringAfter(':') == "Url") {
                p.getAttributeValue(null, "template")?.let { found += p.getAttributeValue(null, "type") to it }
            }
            ev = p.next()
        }
        val pick = found.firstOrNull { it.first?.contains("atom") == true }?.second ?: found.firstOrNull()?.second ?: return null
        return resolve(base, pick.replace("{searchTerms}", "SEARCHTERMS"))?.replace("SEARCHTERMS", "{searchTerms}")
    }

    /** Searches one server: its results (books first), up to two pages. */
    fun search(context: Context, server: Server, query: String): List<Entry> {
        val template = searchTemplate(context, server)?.let { t ->
            // Kept from before relative search addresses were joined to the server's: joined now.
            if (t.startsWith("http://", true) || t.startsWith("https://", true)) t
            else resolve(server.url, t)?.also { fixed -> put(context, server.copy(search = fixed)) } ?: t
        } ?: throw OpdsException("${server.name} has no search")
        val url = template
            .replace("{searchTerms}", URLEncoder.encode(query, "UTF-8").replace("+", "%20"))
            // Optional OpenSearch parameters left empty.
            .replace(Regex("\\{[a-zA-Z:]+\\?\\}"), "")
            .replace("{startPage}", "1").replace("{startIndex}", "0").replace("{count}", "50")
        val first = fetch(server, url)
        val all = ArrayList(first.entries)
        first.next?.takeIf { all.size < 60 }?.let { n -> runCatching { all += fetch(server, n).entries } }
        return all.distinctBy { it.id }
    }

    // ---- what's new ----

    private val NEWEST_NAV = Regex("(?i)\\b(newest|recent(ly)?( added| additions)?|latest|new (books|additions|arrivals)|by date|date added)\\b")
    private val latestCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Entry>>>()

    /**
     * The books [server] added most recently, newest first, up to [count]: from the catalog's own
     * "newest" feed (OPDS sort/new), or the shelf it calls Newest, Recently added or the like.
     * Kept for an hour unless [fresh]. Blocking.
     */
    fun latest(server: Server, count: Int = 30, fresh: Boolean = false): List<Entry> {
        val now = System.currentTimeMillis()
        if (!fresh) latestCache[server.id]?.takeIf { now - it.first < 3_600_000 }?.let { return it.second }
        val root = fetch(server, server.url)
        val url = root.newest
            ?: root.entries.firstOrNull { it.navigation != null && NEWEST_NAV.containsMatchIn(it.title) }?.navigation
            ?: throw OpdsException("${server.name} has no list of newest books")
        var feed = fetch(server, url)
        // A "newest" shelf that is itself a list of shelves: its first one.
        if (feed.entries.none { it.isBook }) {
            feed.entries.firstOrNull { it.navigation != null && NEWEST_NAV.containsMatchIn(it.title) }?.navigation?.let { feed = fetch(server, it) }
        }
        val out = ArrayList(feed.entries.filter { it.isBook })
        var next = feed.next
        while (out.size < count && next != null) {
            val f = runCatching { fetch(server, next!!) }.getOrNull() ?: break
            out += f.entries.filter { it.isBook }
            next = f.next
        }
        return out.take(count).also { latestCache[server.id] = now to it }
    }

    // ---- getting a file ----

    /**
     * Downloads [acq] into Downloads/Readync and returns where it went (a content address to open
     * it with). Blocking. The sign-in goes only to the server's own host, and redirects are
     * followed by hand so it isn't carried elsewhere.
     */
    /** Where a download is: what it's doing, the file's name once known, bytes so far of how many (0 when the server doesn't say), and the speed. */
    data class Progress(val stage: String, val name: String? = null, val done: Long = 0, val total: Long = 0, val bytesPerSec: Double = 0.0)

    /** A finished download: where it is, its name, size and kind. */
    data class Saved(val uri: Uri, val name: String, val size: Long, val type: String)

    /**
     * Downloads [acq] into Downloads/Readync, telling [progress] how it's going (a few times a second),
     * and stops (removing the part written) when [cancel] is set. Blocking. The sign-in goes only to
     * the server's own host, and redirects are followed by hand so it isn't carried elsewhere.
     */
    fun download(
        context: Context, server: Server, acq: Acquisition, title: String,
        cancel: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        progress: (Progress) -> Unit = {},
    ): Saved {
        val (conn, url) = connect(server, acq.href, cancel, progress)
        val type = conn.contentType?.substringBefore(';')?.trim()?.takeIf { it != "application/octet-stream" } ?: acq.type ?: "application/octet-stream"
        val disposition = conn.getHeaderField("Content-Disposition")
        val fromHeader = disposition?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        val ext = extensionFor(acq.type ?: type, url)
        val name = (fromHeader ?: (title.replace(Regex("[\\\\/:*?\"<>|]"), "").take(80).trim() + ext)).ifBlank { "book$ext" }
        val total = conn.contentLengthLong.coerceAtLeast(0)
        progress(Progress("Downloading", name, 0, total))
        var target: Uri? = null
        var file: File? = null
        val cr = context.contentResolver
        var written = 0L
        try {
            conn.inputStream.use { input ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, type)
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/" + io.github.prithirajn.trackersync.Edition.name)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    target = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw OpdsException("Couldn't make the file in Downloads")
                    cr.openOutputStream(target!!)?.use { out -> written = copy(input, out, name, total, cancel, progress) } ?: throw OpdsException("Couldn't write to Downloads")
                    progress(Progress("Saving", name, written, total))
                    cr.update(target!!, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                } else {
                    val dir = File(context.getExternalFilesDir(null), "Books").apply { mkdirs() }
                    file = File(dir, name)
                    file!!.outputStream().use { out -> written = copy(input, out, name, total, cancel, progress) }
                    target = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".pages", file!!)
                }
            }
        } catch (e: Exception) {
            // Nothing half-written is left behind.
            target?.let { runCatching { cr.delete(it, null, null) } }
            file?.let { runCatching { it.delete() } }
            throw if (e is OpdsException) e else OpdsException(if (cancel.get()) "Cancelled" else "The download stopped: ${e.message ?: "connection lost"}")
        } finally {
            conn.disconnect()
        }
        return Saved(target!!, name, written, type)
    }

    /** Opens [href] on [server], following redirects by hand (the sign-in goes only to the server's own host). */
    private fun connect(
        server: Server, href: String, cancel: java.util.concurrent.atomic.AtomicBoolean, progress: (Progress) -> Unit,
    ): Pair<HttpURLConnection, String> {
        var url = href
        var conn: HttpURLConnection
        var hops = 0
        progress(Progress("Connecting to ${server.name}"))
        while (true) {
            if (cancel.get()) throw OpdsException("Cancelled")
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000; readTimeout = 60_000
                setRequestProperty("User-Agent", Http.defaultUserAgent)
                auth(server, url)["Authorization"]?.let { setRequestProperty("Authorization", it) }
            }
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) {
                val loc = conn.getHeaderField("Location") ?: break
                url = URL(URL(url), loc).toString(); conn.disconnect()
                progress(Progress("Following the link to ${runCatching { URL(url).host }.getOrNull() ?: "the file"}"))
                continue
            }
            if (code == 401 || code == 403) { conn.disconnect(); throw OpdsException("${server.name} wouldn't hand over the file (it needs a sign-in, HTTP $code)") }
            if (code == 404) { conn.disconnect(); throw OpdsException("${server.name} no longer has that file (HTTP 404)") }
            if (code !in 200..299) { conn.disconnect(); throw OpdsException("${server.name} answered HTTP $code for the file") }
            break
        }
        return conn to url
    }

    /**
     * Downloads [acq] into [file] (the app's own storage), for the app's own use. Blocking; nothing
     * half-written is left behind.
     */
    fun downloadTo(
        server: Server, acq: Acquisition, file: File,
        cancel: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        progress: (Progress) -> Unit = {},
    ): Long {
        val (conn, _) = connect(server, acq.href, cancel, progress)
        val total = conn.contentLengthLong.coerceAtLeast(0)
        val part = File(file.parentFile, file.name + ".part")
        try {
            part.parentFile?.mkdirs()
            val written = conn.inputStream.use { input -> part.outputStream().use { out -> copy(input, out, file.name, total, cancel, progress) } }
            if (!part.renameTo(file)) { part.copyTo(file, overwrite = true); part.delete() }
            return written
        } catch (e: Exception) {
            runCatching { part.delete() }
            throw if (e is OpdsException) e else OpdsException(if (cancel.get()) "Cancelled" else "The download stopped: ${e.message ?: "connection lost"}")
        } finally {
            conn.disconnect()
        }
    }

    private fun copy(
        input: java.io.InputStream, out: java.io.OutputStream, name: String, total: Long,
        cancel: java.util.concurrent.atomic.AtomicBoolean, progress: (Progress) -> Unit,
    ): Long {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        val started = System.nanoTime()
        var lastTold = 0L
        // Speed over the last couple of seconds, so it settles rather than jumping.
        val window = ArrayDeque<Pair<Long, Long>>()
        while (true) {
            if (cancel.get()) throw OpdsException("Cancelled")
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            val now = System.nanoTime()
            window.addLast(now to done)
            while (window.size > 2 && now - window.first().first > 2_000_000_000L) window.removeFirst()
            if (now - lastTold > 200_000_000L) {
                lastTold = now
                val (t0, d0) = window.first()
                val secs = (now - t0) / 1e9
                val speed = if (secs > 0.05) (done - d0) / secs else done / ((now - started) / 1e9).coerceAtLeast(0.05)
                progress(Progress("Downloading", name, done, total, speed))
            }
        }
        progress(Progress("Downloading", name, done, total, 0.0))
        return done
    }

    /** A cover or thumbnail, with the sign-in when it's on the server's own host. Blocking. */
    fun image(server: Server, url: String): ByteArray? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("User-Agent", Http.defaultUserAgent)
            auth(server, url)["Authorization"]?.let { setRequestProperty("Authorization", it) }
        }
        if (conn.responseCode !in 200..299) { conn.disconnect(); return null }
        conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
    }.getOrNull()

    fun formatOf(type: String?, href: String): String {
        val t = type?.lowercase().orEmpty()
        val h = href.lowercase().substringBefore('?')
        return when {
            "epub" in t || h.endsWith(".epub") || h.endsWith(".kepub") -> if ("kepub" in t || h.endsWith(".kepub") || h.contains("kepub")) "KEPUB" else "EPUB"
            "pdf" in t || h.endsWith(".pdf") -> "PDF"
            "cbz" in t || "comicbook+zip" in t || h.endsWith(".cbz") -> "CBZ"
            "cbr" in t || "comicbook-rar" in t || h.endsWith(".cbr") -> "CBR"
            "mobi" in t || "x-mobipocket" in t || h.endsWith(".mobi") -> "MOBI"
            "azw" in t || h.endsWith(".azw3") -> "AZW3"
            "fb2" in t || h.endsWith(".fb2") -> "FB2"
            "text/plain" in t || h.endsWith(".txt") -> "TXT"
            "html" in t && "opds" !in t -> "HTML"
            "zip" in t || h.endsWith(".zip") -> "ZIP"
            "audio" in t -> "AUDIO"
            else -> "FILE"
        }
    }

    private fun extensionFor(type: String, href: String): String = when (formatOf(type, href)) {
        "EPUB" -> ".epub"; "KEPUB" -> ".kepub.epub"; "PDF" -> ".pdf"; "CBZ" -> ".cbz"; "CBR" -> ".cbr"; "MOBI" -> ".mobi"
        "AZW3" -> ".azw3"; "FB2" -> ".fb2"; "TXT" -> ".txt"; "HTML" -> ".html"; "ZIP" -> ".zip"
        else -> href.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").takeIf { it.length in 2..5 }?.let { ".$it" } ?: ""
    }

    // ---- many at once ----

    private val IP = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d{1,5})?(/.*)?$")
    private val HOST = Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}(:\\d{1,5})?(/.*)?$")
    private val LOCAL = Regex("^(localhost|[A-Za-z0-9-]+\\.local)(:\\d{1,5})?(/.*)?$")

    /**
     * The server addresses in pasted text: one per line, or separated by spaces or commas, with or
     * without http:// (a bare IP or local name gets http://, a domain https://). Each once.
     */
    fun addressesIn(text: String): List<String> = text.split(Regex("[\\s,;|]+"))
        .map { it.trim().trim('<', '>', '(', ')', '[', ']', '"', '\'', '.') }
        .mapNotNull { t ->
            when {
                t.startsWith("http://", true) || t.startsWith("https://", true) -> t
                IP.matches(t) || LOCAL.matches(t) -> "http://$t"
                HOST.matches(t) -> "https://$t"
                else -> null
            }
        }
        .map { it.trimEnd('/') }
        .distinctBy { it.lowercase() }

    /** The same server however its address was written (scheme, trailing slash and case aside). */
    fun sameAddress(a: String, b: String): Boolean {
        fun n(x: String) = x.lowercase().removePrefix("http://").removePrefix("https://").trimEnd('/')
        return n(a) == n(b) || n(a).startsWith(n(b) + "/") || n(b).startsWith(n(a) + "/")
    }

    /**
     * Finds the catalog at [raw]: the address itself when it is one, else where servers usually keep
     * theirs (Calibre's /opds, Komga and Stump's /opds/v1.2/catalog, COPS's /feed.php, Kavita's
     * /api/opds…). Quick to give up on a server that doesn't answer. Throws when none answers.
     */
    fun discover(raw: String, user: String? = null, password: String? = null, timeoutMs: Int = 9_000): Server {
        val base = raw.trimEnd('/')
        val path = runCatching { URI(base).path.orEmpty() }.getOrDefault("")
        val tries = if (path.length > 1) listOf(base) else listOf(base, "$base/opds", "$base/opds/v1.2/catalog", "$base/feed.php", "$base/api/opds", "$base/catalog", "$base/opds-catalog")
        val probe = Server("probe", runCatching { URI(base).host }.getOrNull() ?: base, base, user, password)
        var last: Exception? = null
        for ((i, u) in tries.withIndex()) {
            try {
                val f = fetch(probe, u, timeoutMs)
                if (f.entries.isNotEmpty() || f.searchLink != null) {
                    val host = runCatching { URI(u).host + (URI(u).port.takeIf { it > 0 }?.let { ":$it" } ?: "") }.getOrNull() ?: u
                    return Server(java.util.UUID.randomUUID().toString(), f.title.takeIf { it.isNotBlank() && it.length < 60 }?.let { "$it ($host)" } ?: host, f.url.ifBlank { u }, user, password)
                }
            } catch (e: Exception) {
                last = e
                // Nothing answering at all: the other paths won't either.
                if (i == 0 && (e is java.net.SocketTimeoutException || e is java.net.ConnectException || e is java.net.UnknownHostException ||
                        e.cause is java.net.SocketTimeoutException || e.cause is java.net.ConnectException || e.cause is java.net.UnknownHostException)) break
            }
        }
        val why = last?.cause ?: last
        throw OpdsException(when {
            why is java.net.SocketTimeoutException || why is java.net.ConnectException || why is java.net.NoRouteToHostException -> "didn't answer"
            why is java.net.UnknownHostException -> "no such address"
            last is OpdsException -> last.message ?: "no catalog there"
            else -> "no catalog there"
        })
    }

    /** Known catalogs to start from: their name and OPDS address. */
    val suggestions = listOf(
        "Project Gutenberg" to "https://www.gutenberg.org/ebooks.opds/",
    )
}
