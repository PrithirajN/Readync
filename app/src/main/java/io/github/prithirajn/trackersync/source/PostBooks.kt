package io.github.prithirajn.trackersync.source

import android.content.Context
import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.sync.Matcher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * The books in a TikTok, Instagram or YouTube post: its caption read (through the platform's
 * public oEmbed, else the page's own tags), the words on its picture read on the phone, the names
 * picked out (by the on-device AI when it's set up, else from the way book posts are written:
 * "Title by Author", numbered lists, quoted titles, the picture's lines, hashtags), and each one
 * checked against Google Books, so only real books come back.
 */
object PostBooks {

    class PostException(message: String) : Exception(message)

    data class Post(
        val url: String,
        val platform: String,
        val creator: String?,
        val caption: String?,
        val thumbnail: String?,
    )

    data class Found(
        val title: String,
        val author: String?,
        val cover: String?,
        val isbn: String?,
        val pages: Int?,
        /** Where it was named: "caption", "picture", "hashtag". */
        val from: String,
    ) {
        fun toJson(): JSONObject = JSONObject().put("t", title).put("a", author ?: JSONObject.NULL).put("c", cover ?: JSONObject.NULL)
            .put("i", isbn ?: JSONObject.NULL).put("p", pages ?: JSONObject.NULL).put("f", from)

        companion object {
            fun from(o: JSONObject) = Found(
                o.optString("t"), if (o.isNull("a")) null else o.optString("a"), if (o.isNull("c")) null else o.optString("c"),
                if (o.isNull("i")) null else o.optString("i"), if (o.isNull("p")) null else o.optInt("p"), o.optString("f", "caption"),
            )
        }
    }

    /** One post looked at: where, whose, when, and the books found in it. */
    data class Trap(val post: Post, val books: List<Found>, val at: Long) {
        fun toJson(): JSONObject = JSONObject().put("url", post.url).put("platform", post.platform)
            .put("creator", post.creator ?: JSONObject.NULL).put("caption", post.caption ?: JSONObject.NULL)
            .put("thumb", post.thumbnail ?: JSONObject.NULL).put("at", at)
            .put("books", JSONArray().also { a -> books.forEach { a.put(it.toJson()) } })

        companion object {
            fun from(o: JSONObject) = Trap(
                Post(
                    o.optString("url"), o.optString("platform"),
                    if (o.isNull("creator")) null else o.optString("creator"),
                    if (o.isNull("caption")) null else o.optString("caption"),
                    if (o.isNull("thumb")) null else o.optString("thumb"),
                ),
                o.optJSONArray("books")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Found::from) } }.orEmpty(),
                o.optLong("at"),
            )
        }
    }

    // ---- what's been looked at (kept on the phone) ----

    private const val PREFS = "post-books"

    fun saved(context: Context): List<Trap> = runCatching {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("traps", "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Trap::from) }
    }.getOrDefault(emptyList())

    fun save(context: Context, traps: List<Trap>) {
        val a = JSONArray().also { arr -> traps.take(60).forEach { arr.put(it.toJson()) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("traps", a.toString()).apply()
    }

    // ---- reading the post ----

    fun platformOf(url: String): String? {
        val u = url.lowercase()
        return when {
            "tiktok.com" in u -> "TikTok"
            "instagram.com" in u || "instagr.am" in u -> "Instagram"
            "youtube.com" in u || "youtu.be" in u -> "YouTube"
            "threads.net" in u || "threads.com" in u -> "Threads"
            "pinterest." in u || "pin.it" in u -> "Pinterest"
            else -> null
        }
    }

    /** The first link in shared text (a TikTok or Instagram share comes with words around it). */
    fun linkIn(text: String): String? = Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', '"')

    /** Reads a post: its caption, who posted it, its picture. Blocking. */
    fun read(context: Context, link: String): Post {
        // Short links (vm.tiktok.com, instagr.am…) followed to the post.
        val url = runCatching { Http.request("GET", link, mapOf("Accept" to "text/html"), timeoutMs = 15_000).url }.getOrNull()?.ifBlank { null } ?: link
        val platform = platformOf(url) ?: platformOf(link) ?: "Web"
        // The platform's public oEmbed first: the caption without signing in.
        val oembed = when (platform) {
            "TikTok" -> "https://www.tiktok.com/oembed?url=" + URLEncoder.encode(url.substringBefore('?'), "UTF-8")
            "YouTube" -> "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8")
            else -> null
        }
        var caption: String? = null
        var creator: String? = null
        var thumb: String? = null
        oembed?.let { o ->
            runCatching { JSONObject(Http.request("GET", o, mapOf("Accept" to "application/json"), timeoutMs = 15_000).body) }.getOrNull()?.let { j ->
                caption = j.optString("title").ifBlank { null }
                creator = j.optString("author_name").ifBlank { null }
                thumb = j.optString("thumbnail_url").ifBlank { null }
            }
        }
        // Then the page itself (its description and picture tags), through the unseen browser if a plain fetch is turned away.
        if (caption == null || thumb == null) {
            val html = runCatching { Http.request("GET", url, mapOf("Accept" to "text/html", "Accept-Language" to "en-IN,en;q=0.9"), timeoutMs = 15_000).body }.getOrNull()
                ?.takeIf { meta(it, "og:description") != null || meta(it, "description") != null }
                ?: runCatching { io.github.prithirajn.trackersync.ui.PageBrowser.fetch(context, url) }.getOrNull()
            if (html != null) {
                val desc = meta(html, "og:description") ?: meta(html, "description") ?: meta(html, "twitter:description")
                // Instagram's: "123 likes, 4 comments - name on Date: "caption"".
                val ig = desc?.let { Regex("(?s)^[\\d,.KkMm]+ likes?.*?-\\s*([^ ]+) on [^:]+:\\s*[\"“](.*)[\"”]\\.?\\s*$").find(it) }
                if (caption == null) caption = ig?.groupValues?.get(2) ?: desc ?: meta(html, "og:title")
                if (creator == null) creator = ig?.groupValues?.get(1) ?: meta(html, "og:site_name")?.takeIf { platform == "Web" }
                if (thumb == null) thumb = meta(html, "og:image") ?: meta(html, "twitter:image")
            }
        }
        if (caption == null && thumb == null) throw PostException("Couldn't read that post (it may be private, or the site turned the app away)")
        return Post(url, platform, creator, caption?.let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim() }, thumb)
    }

    private fun meta(html: String, prop: String): String? =
        Regex("""<meta[^>]+(?:property|name)=["']${Regex.escape(prop)}["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']${Regex.escape(prop)}["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)

    /** The words on the post's picture (a cover or a stack of books often names them), read on the phone. */
    suspend fun pictureText(context: Context, thumb: String?): List<String> {
        if (thumb == null) return emptyList()
        val bytes = runCatching { Http.bytes(thumb) }.getOrNull() ?: return emptyList()
        val f = File(context.cacheDir, "post-thumb.jpg").apply { writeBytes(bytes) }
        val uri = android.net.Uri.fromFile(f)
        return runCatching { io.github.prithirajn.trackersync.ui.PageScanner.read(context, uri) }.getOrDefault(emptyList())
            .map { it.text.trim() }.filter { it.length >= 3 }
    }

    // ---- picking the names out ----

    data class Guess(val query: String, val author: String?, val from: String)

    private val NOT_BOOKS = setOf(
        "booktok", "bookstagram", "booktube", "books", "book", "reading", "reader", "readers", "bookish", "booklover", "bookworm",
        "fantasy", "romance", "romantasy", "thriller", "fyp", "foryou", "foryoupage", "viral", "tbr", "booktoker", "bookrecs",
        "bookrecommendations", "bookreview", "reels", "explore", "trending", "fiction", "scifi", "horror", "darkromance", "spicybooks",
        "kindle", "kindleunlimited", "audiobook", "audiobooks", "booksbooksbooks", "currentlyreading", "readingvlog", "bookhaul",
    )

    /** The names a post's caption and picture give, most likely first. */
    fun guesses(caption: String?, picture: List<String>): List<Guess> {
        val out = LinkedHashMap<String, Guess>()
        fun add(q: String, a: String?, from: String) {
            val t = q.replace(Regex("^[\\s\\d.)\\-–—•*#📚📖✨🔥⭐️❤️]+"), "").replace(Regex("[\\s:;,.!?\\-–—]+$"), "").trim()
            if (t.length < 3 || t.split(' ').size > 12) return
            if (t.lowercase().replace(" ", "") in NOT_BOOKS) return
            out.putIfAbsent(t.lowercase(), Guess(t, a?.trim()?.takeIf { it.length in 3..60 }, from))
        }
        val text = caption.orEmpty()
        // "Title by Author" (also "Title - Author" on a list line).
        for (line in text.split('\n', '•', '|')) {
            Regex("""["“]?([A-Z0-9][^"“”\n]{2,80}?)["”]?\s+(?:by|-|–|—)\s+([A-Z][\p{L}.'\- ]{2,40}?)(?=$|[,.!?;:)(\n]|\s{2}|\s[#@])""").findAll(line).forEach { m ->
                add(m.groupValues[1], m.groupValues[2], "caption")
            }
        }
        // Quoted titles.
        Regex("""["“]([^"“”]{3,80})["”]""").findAll(text).forEach { add(it.groupValues[1], null, "caption") }
        // Numbered or bulleted list lines.
        for (line in text.split('\n')) {
            Regex("""^\s*(?:\d{1,2}[.)]|[-•*📚📖✨⭐️])\s*(.{3,80})$""").find(line)?.let { add(it.groupValues[1].substringBefore(" by ").substringBefore(" - "), it.groupValues[1].substringAfter(" by ", "").ifBlank { null }, "caption") }
        }
        // The picture's lines: two or three words or more, in title or capital letters (not the app's
        // own buttons and counts: likes, follow, reply, views…).
        val chrome = Regex("(?i)^(follow|following|reply|replies|share|like[sd]?|views?|comments?|send|save|repost|more|original audio|see translation|add comment|suggested|sponsored)\\b|\\b\\d+(\\.\\d+)?[KkMm]\\b|^@")
        for (l in picture) {
            if (l.split(' ').size in 2..8 && Regex("^[A-Z0-9]").containsMatchIn(l) && !chrome.containsMatchIn(l)) add(l, null, "picture")
        }
        // Hashtags that name a book or series (#fourthwing), after the rest.
        Regex("#([A-Za-z][A-Za-z0-9]{4,40})").findAll(text).map { it.groupValues[1] }.forEach { h ->
            if (h.lowercase() !in NOT_BOOKS && !h.lowercase().startsWith("book")) add(h.replace(Regex("([a-z])([A-Z])"), "$1 $2"), null, "hashtag")
        }
        return out.values.toList()
    }

    /** The names the on-device AI reads out of the caption and picture text, as "Title — Author" lines. */
    fun aiGuesses(caption: String?, picture: List<String>): List<Guess> {
        val m = io.github.prithirajn.trackersync.ai.Ai.model() ?: return emptyList()
        val prompt = """List every book named in this social media post. Use the caption and the words read from the post's picture.
Write one book per line as: Title — Author (leave the author out if it isn't given). Write nothing else. If no book is named, write NONE.

Caption:
${caption.orEmpty().take(1500)}

Words on the picture:
${picture.joinToString("\n").take(800)}"""
        val raw = runCatching { m.generate(prompt) }.getOrNull() ?: return emptyList()
        if (raw.trim().equals("NONE", true)) return emptyList()
        return raw.lines().mapNotNull { l ->
            val t = l.replace(Regex("^[\\s\\d.)\\-–—•*]+"), "").trim()
            if (t.isEmpty() || t.equals("NONE", true)) return@mapNotNull null
            val parts = t.split(Regex("\\s+[—–-]\\s+|\\s+by\\s+"), limit = 2)
            Guess(parts[0].trim('"', '“', '”', ' '), parts.getOrNull(1)?.trim(), "caption")
        }.take(20)
    }

    // ---- checking each against a catalogue ----

    private fun letters(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The book [g] names, from Google Books, when one clearly is it. Blocking. */
    fun verify(g: Guess): Found? {
        val q = buildString {
            append("intitle:").append(g.query)
            g.author?.let { append(" inauthor:").append(it) }
        }
        val j = runCatching {
            JSONObject(Http.request("GET", "https://www.googleapis.com/books/v1/volumes?maxResults=5&printType=books&q=" + URLEncoder.encode(q, "UTF-8"), timeoutMs = 12_000).body)
        }.getOrNull() ?: return null
        val items = j.optJSONArray("items") ?: return null
        val want = letters(g.query)
        for (i in 0 until items.length()) {
            val v = items.optJSONObject(i)?.optJSONObject("volumeInfo") ?: continue
            val title = v.optString("title").ifBlank { continue }
            val have = letters(Matcher.baseTitle(title))
            // The name has to be the book's title (or its title the name), not just share a word with it;
            // a hashtag or a line off the picture has to be the whole title.
            val ok = when (g.from) {
                "hashtag", "picture" -> have == want || (have.length >= 6 && want.startsWith(have))
                else -> have == want || (have.length >= 5 && (want.startsWith(have) || have.startsWith(want)))
            }
            if (!ok) continue
            val authors = v.optJSONArray("authors")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            if (g.author != null && authors.isNotEmpty() && authors.none { a -> letters(a).contains(letters(g.author).takeLast(5)) }) continue
            val isbn = v.optJSONArray("industryIdentifiers")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("type") == "ISBN_13" }?.optString("identifier")
            }
            val cover = v.optJSONObject("imageLinks")?.let { it.optString("thumbnail").ifBlank { it.optString("smallThumbnail") } }
                ?.ifBlank { null }?.replace("http://", "https://")?.replace("&edge=curl", "")
            return Found(title, authors.firstOrNull() ?: g.author, cover, isbn, v.optInt("pageCount").takeIf { it > 0 }, g.from)
        }
        return null
    }

    /**
     * The books in screenshots or a screen recording of a post (shared to the app, or picked): the
     * words read on each picture, or on a frame of the recording about every second, on the phone.
     * A line seen in several frames comes first (a cover held up for a while beats a passing word).
     */
    suspend fun fromMedia(context: Context, uris: List<android.net.Uri>, progress: (String) -> Unit = {}): Trap {
        val counts = LinkedHashMap<String, Int>()
        fun note(lines: List<String>) = lines.map { it.trim() }.filter { it.length >= 3 }.distinct().forEach { counts[it] = (counts[it] ?: 0) + 1 }
        var videos = 0
        var pictures = 0
        for ((n, uri) in uris.withIndex()) {
            val type = context.contentResolver.getType(uri).orEmpty()
            if (type.startsWith("video/")) {
                videos++
                progress("Reading the recording")
                val frames = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { videoFrames(context, uri) }
                for ((i, f) in frames.withIndex()) {
                    progress("Reading frame ${i + 1} of ${frames.size}")
                    note(runCatching { io.github.prithirajn.trackersync.ui.PageScanner.read(context, android.net.Uri.fromFile(f)) }.getOrDefault(emptyList()).map { it.text })
                    f.delete()
                }
            } else {
                pictures++
                progress("Reading picture ${n + 1} of ${uris.size}")
                note(runCatching { io.github.prithirajn.trackersync.ui.PageScanner.read(context, uri) }.getOrDefault(emptyList()).map { it.text })
            }
        }
        val lines = counts.entries.sortedByDescending { it.value }.map { it.key }.take(120)
        progress("Picking out the books")
        val guesses = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            (aiGuesses(null, lines) + guesses(null, lines)).distinctBy { letters(it.query) }.take(30)
        }
        progress("Checking ${guesses.size} names against Google Books")
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            guesses.mapNotNull { g -> runCatching { verify(g) }.getOrNull() }
                .distinctBy { letters(Matcher.baseTitle(it.title)) + "|" + letters(it.author ?: "") }
        }
        val label = when {
            videos > 0 && pictures == 0 -> "Screen recording"
            videos == 0 -> if (pictures == 1) "Screenshot" else "$pictures screenshots"
            else -> "Recording and screenshots"
        }
        val now = System.currentTimeMillis() / 1000
        return Trap(Post("media:$now", label, null, null, null), found, now)
    }

    /** A frame about every second of a recording (up to 45), as pictures in the app's cache. */
    private fun videoFrames(context: Context, uri: android.net.Uri): List<File> {
        val r = android.media.MediaMetadataRetriever()
        val out = ArrayList<File>()
        try {
            r.setDataSource(context, uri)
            val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return emptyList()
            val step = maxOf(1000L, ms / 45)
            var t = 0L
            var i = 0
            while (t < ms && out.size < 45) {
                val bmp = r.getFrameAtTime(t * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bmp != null) {
                    val f = File(context.cacheDir, "post-frame-$i.jpg")
                    f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                    bmp.recycle()
                    out += f; i++
                }
                t += step
            }
        } catch (_: Exception) {
        } finally {
            runCatching { r.release() }
        }
        return out
    }

    /** Every book in the post at [link], checked. Blocking (it reads, looks at the picture, asks Google Books). */
    suspend fun find(context: Context, link: String, progress: (String) -> Unit = {}): Trap {
        progress("Reading the post")
        val post = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { read(context, link) }
        progress("Reading the words on its picture")
        val picture = pictureText(context, post.thumbnail)
        progress("Picking out the books")
        val guesses = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            (aiGuesses(post.caption, picture) + guesses(post.caption, picture)).distinctBy { letters(it.query) }.take(24)
        }
        progress("Checking ${guesses.size} names against Google Books")
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            guesses.mapNotNull { g -> runCatching { verify(g) }.getOrNull() }
                .distinctBy { letters(Matcher.baseTitle(it.title)) + "|" + letters(it.author ?: "") }
        }
        return Trap(post, found, System.currentTimeMillis() / 1000)
    }
}
