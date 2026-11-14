package io.github.prithirajn.readync.source

import android.content.Context
import io.github.prithirajn.readync.net.Http
import io.github.prithirajn.readync.sync.Matcher
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
    data class Trap(val post: Post, val books: List<Found>, val at: Long, val seen: List<String> = emptyList()) {
        fun toJson(): JSONObject = JSONObject().put("url", post.url).put("platform", post.platform)
            .put("creator", post.creator ?: JSONObject.NULL).put("caption", post.caption ?: JSONObject.NULL)
            .put("thumb", post.thumbnail ?: JSONObject.NULL).put("at", at)
            .put("books", JSONArray().also { a -> books.forEach { a.put(it.toJson()) } })
            .put("seen", JSONArray(seen))

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
                o.optJSONArray("seen")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } } }.orEmpty(),
            )
        }
    }

    // ---- what's been looked at (kept on the phone) ----

    private const val PREFS = "post-books"

    fun saved(context: Context): List<Trap> = runCatching {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("traps", "[]"))
        // The same post shared twice (each share's link carries its own tracking bits) is kept once, the newest.
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Trap::from) }.sortedByDescending { it.at }.distinctBy { keyOf(it.post.url) }
    }.getOrDefault(emptyList())

    /**
     * One post however its link was shared: "?igsh=…", "?utm_source=…", /reel/ or /p/, a trailing
     * slash, www or m. aside. Instagram, TikTok, YouTube and Threads by the post's own id.
     */
    fun keyOf(url: String): String {
        val u = url.trim()
        Regex("instagram\\.com/(?:[^/?#]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)").find(u)?.let { return "ig:" + it.groupValues[1] }
        Regex("tiktok\\.com/.*?/(?:video|photo)/(\\d+)").find(u)?.let { return "tt:" + it.groupValues[1] }
        Regex("(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|live/)|youtu\\.be/)([A-Za-z0-9_-]{6,})").find(u)?.let { return "yt:" + it.groupValues[1] }
        Regex("threads\\.(?:net|com)/@[^/]+/post/([A-Za-z0-9_-]+)").find(u)?.let { return "th:" + it.groupValues[1] }
        return u.substringBefore('#').substringBefore('?').trimEnd('/').lowercase()
            .replace("://www.", "://").replace("://m.", "://")
    }

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
                ?: runCatching { io.github.prithirajn.readync.ui.PageBrowser.fetch(context, url) }.getOrNull()
            if (html != null) {
                val desc = meta(html, "og:description") ?: meta(html, "description") ?: meta(html, "twitter:description")
                // Instagram's: "123 likes, 4 comments - name on Date: "caption"".
                val ig = desc?.let { Regex("(?s)^[\\d,.KkMm]+ likes?.*?-\\s*([^ ]+) on [^:]+:\\s*[\"“](.*?)(?:[\"”]\\.?)?\\s*$").find(it) }
                if (caption == null) caption = ig?.groupValues?.get(2)?.removeSuffix("...")?.removeSuffix("…") ?: desc ?: meta(html, "og:title")
                if (creator == null) creator = ig?.groupValues?.get(1) ?: meta(html, "og:site_name")?.takeIf { platform == "Web" }
                if (thumb == null) thumb = meta(html, "og:image") ?: meta(html, "twitter:image")
            }
        }
        if (caption == null && thumb == null) throw PostException("Couldn't read that post (it may be private, or the site turned the app away)")
        return Post(url, platform, creator, caption?.let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim() }, thumb)
    }

    private fun meta(html: String, prop: String): String? =
        (Regex("""<meta[^>]+(?:property|name)=["']${Regex.escape(prop)}["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']${Regex.escape(prop)}["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1))
            // Addresses in a page's tags carry "&amp;": a picture's signed address doesn't load with it.
            ?.replace("&amp;", "&")?.replace("&#38;", "&")?.replace("&#x2F;", "/")

    /**
     * The post's pictures, best first: every picture of an Instagram post (a carousel's too, at full
     * size) where its embed page gives them, then the preview the link shows. Blocking.
     */
    fun pictures(post: Post): List<String> {
        val out = ArrayList<String>()
        if (post.platform == "Instagram") {
            Regex("instagram\\.com/(?:[^/?#]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)").find(post.url)?.groupValues?.get(1)?.let { code ->
                runCatching {
                    Http.request("GET", "https://www.instagram.com/p/$code/embed/captioned/", mapOf("Accept" to "text/html", "Accept-Language" to "en-IN,en;q=0.9"), timeoutMs = 15_000).body
                }.getOrNull()?.let { out += instagramPictures(it) }
            }
        }
        post.thumbnail?.let { out += it }
        // The same picture at two sizes has the same file name.
        return out.distinctBy { it.substringBefore('?').substringAfterLast('/') }.take(6)
    }

    /** The pictures an Instagram embed page carries: the carousel's full-size ones, else the one shown. */
    internal fun instagramPictures(html: String): List<String> {
        var t = html
        repeat(3) {
            t = t.replace("\\\\/", "/").replace("\\/", "/").replace("\\\\u0026", "&").replace("\\u0026", "&").replace("\\\"", "\"")
        }
        t = t.replace("&amp;", "&")
        val urls = ArrayList<String>()
        Regex("\"display_url\"\\s*:\\s*\"(https://[^\"\\\\\\s]+)\"").findAll(t).forEach { urls += it.groupValues[1] }
        Regex("<img[^>]*EmbeddedMediaImage[^>]*>").findAll(t).forEach { m ->
            val tag = m.value
            val best = Regex("srcset=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)?.split(',')?.map { it.trim() }
                ?.maxByOrNull { it.substringAfterLast(' ').removeSuffix("w").toIntOrNull() ?: 0 }?.substringBefore(' ')
            (best ?: Regex("src=\"([^\"]+)\"").find(tag)?.groupValues?.get(1))?.let { urls += it }
        }
        return urls.filter { it.startsWith("https://") }
    }

    /**
     * The words on the post's pictures (a cover or a stack of books often names them), read on the
     * phone. Small pictures are enlarged first (the reader misses small letters), and each picture's
     * biggest lettering, put together top to bottom (a cover's title over two or three lines), comes first.
     */
    suspend fun pictureText(context: Context, urls: List<String>): List<String> {
        val out = ArrayList<String>()
        for ((i, u) in urls.withIndex()) {
            val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val bytes = runCatching { Http.bytes(u, maxBytes = 8_000_000) }.getOrNull() ?: return@withContext null
                File(context.cacheDir, "post-pic-$i.jpg").also { sharpened(it, bytes) }
            } ?: continue
            val lines = runCatching { io.github.prithirajn.readync.ui.PageScanner.read(context, android.net.Uri.fromFile(f)) }.getOrDefault(emptyList())
            f.delete()
            biggestLettering(lines)?.let { out += it }
            out += lines.map { it.text.trim() }.filter { it.length >= 3 }
        }
        return out.distinct()
    }

    /** Writes [bytes] to [f], enlarged up to three times when the picture is under 1000 pixels wide. */
    private fun sharpened(f: File, bytes: ByteArray) {
        val bmp = runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        if (bmp == null || bmp.width >= 1000) { f.writeBytes(bytes); bmp?.recycle(); return }
        val k = minOf(3f, 1400f / bmp.width)
        val big = runCatching { android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) }.getOrNull()
        if (big == null) { f.writeBytes(bytes); bmp.recycle(); return }
        f.outputStream().use { big.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
        if (big !== bmp) big.recycle()
        bmp.recycle()
    }

    /** A picture's tallest lines (within a third of the tallest), top to bottom, as one: usually a cover's title. */
    internal fun biggestLettering(lines: List<io.github.prithirajn.readync.sync.PageReader.Line>): String? {
        val words = lines.filter { it.text.trim().length >= 2 }
        if (words.size < 2) return null
        val max = words.maxOf { it.bottom - it.top }
        val tall = words.filter { it.bottom - it.top >= max * 0.7f }.sortedBy { it.top }
        if (tall.size !in 2..5) return null
        return tall.joinToString(" ") { it.text.trim() }.takeIf { it.split(' ').size in 2..10 }
    }

    /**
     * The books the on-device model sees in pictures (a cover with no readable title, a shelf), when
     * it can see pictures (Gemma 4): its "Title — Author" guesses, each still checked like the rest.
     */
    suspend fun coverGuesses(context: Context, files: List<File>): List<Guess> {
        val m = io.github.prithirajn.readync.ai.Ai.model() ?: return emptyList()
        val prompt = """Is there a book in this picture (a cover, a book held up, a shelf)? For each book you can read or recognise, write its title and author as: Title — Author
One book per line, at most five. Write nothing else. If there's no book you're sure of, write NONE."""
        val out = ArrayList<Guess>()
        for (f in files.take(3)) {
            val reply = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { m.look(f.path, prompt) }.getOrNull() } ?: return out
            if (reply.trim().startsWith("NONE", true)) continue
            reply.lines().forEach { l ->
                val t = l.replace(Regex("^[\\s\\d.)\\-–—•*]+"), "").trim().trim('*')
                if (t.isEmpty() || t.equals("NONE", true)) return@forEach
                val parts = t.split(Regex("\\s+[—–-]\\s+|\\s+by\\s+"), limit = 2)
                val title = parts[0].trim('"', '“', '”', ' ')
                if (title.length >= 2) out += Guess(title, parts.getOrNull(1)?.trim()?.ifBlank { null }, "cover")
            }
        }
        return out.distinctBy { letters(it.query) }
    }

    /** The post's pictures saved for the model to look at (the first few). */
    private suspend fun pictureFiles(context: Context, urls: List<String>): List<File> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        urls.take(3).mapIndexedNotNull { i, u ->
            val bytes = runCatching { Http.bytes(u, maxBytes = 8_000_000) }.getOrNull() ?: return@mapIndexedNotNull null
            File(context.cacheDir, "post-look-$i.jpg").apply { writeBytes(bytes) }
        }
    }

    // ---- picking the names out ----

    /** A name to check: its title, author when given, where it came from, and the account it was credited to ("by @someone"). */
    data class Guess(val query: String, val author: String?, val from: String, val handle: String? = null)

    private val NOT_BOOKS = setOf(
        "booktok", "bookstagram", "booktube", "books", "book", "reading", "reader", "readers", "bookish", "booklover", "bookworm",
        "fantasy", "romance", "romantasy", "thriller", "fyp", "foryou", "foryoupage", "viral", "tbr", "booktoker", "bookrecs",
        "bookrecommendations", "bookreview", "reels", "explore", "trending", "fiction", "scifi", "horror", "darkromance", "spicybooks",
        "kindle", "kindleunlimited", "audiobook", "audiobooks", "booksbooksbooks", "currentlyreading", "readingvlog", "bookhaul",
    )

    /** The names a post's caption and picture give, most likely first. */
    fun guesses(caption: String?, picture: List<String>, authors: List<String> = pricedNames(caption)): List<Guess> {
        val out = LinkedHashMap<String, Guess>()
        fun add(q: String, a: String?, from: String, handle: String? = null) {
            val t = q.replace(Regex("^[\\s\\d.)\\-–—•*#📚📖✨🔥⭐️❤️]+"), "").replace(Regex("[\\s:;,.!?\\-–—]+$"), "").trim()
            if (t.length < 3 || t.split(' ').size > 12) return
            if (t.lowercase().replace(" ", "") in NOT_BOOKS) return
            out.putIfAbsent(t.lowercase(), Guess(t, a?.trim()?.takeIf { it.length in 3..60 }, from, handle))
        }
        // A seller's "Vogt - 300" lines name who wrote the books, not what they are: left out here.
        val text = caption.orEmpty().lines().filterNot { PRICED.containsMatchIn(it) }.joinToString("\n").replace(PRICED_INLINE, " ")
        // "Title by Author" (also "Title - Author" on a list line).
        for (line in text.split('\n', '•', '|')) {
            Regex("""["“]?([A-Z0-9][^"“”\n]{2,80}?)["”]?\s+(?:by|-|–|—)\s+(@?[\p{L}0-9_][\p{L}0-9_.'\- ]{1,40}?)(?=$|[,.!?;:)(\n]|\s{2}|\s[#@])""").findAll(line).forEach { m ->
                // "Behold the cover of Last Song of Ash by aptshadow": the title is the name-like end of the
                // phrase, and an account's handle (one word, small letters or @) isn't the author.
                val a = m.groupValues[2].trim()
                val handle = a.startsWith("@") || (!a.contains(' ') && (a.first().isLowerCase() || a.any { it == '_' || it.isDigit() }))
                // An account credited as the author: the rest of its handle after the words ("@david.scott.hay.official").
                val full = if (a.startsWith("@")) Regex("@[A-Za-z0-9_.]+").find(line, m.range.first + m.value.indexOf(a))?.value ?: a else a
                add(titleTail(m.groupValues[1]).ifBlank { m.groupValues[1] }, a.takeUnless { handle || a.first().isLowerCase() }, "caption", full.takeIf { handle })
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
        // A line with one of the post's authors in it ("IAN WATSON THE BOOK OF BEING"): the rest is the
        // title, checked with that author. A line that's only the author ("A.E. van Vogt") isn't a title.
        fun authored(l: String): Pair<String, String>? {
            for (a in authors) withoutAuthor(l, a)?.let { return it to a }
            return null
        }
        fun onlyAuthor(l: String) = authors.any { a -> withoutAuthor(l, a) == "" }
        for (l in picture) {
            val byAuthor = authored(l)
            if (byAuthor != null) { if (byAuthor.first.isNotEmpty()) add(byAuthor.first, byAuthor.second, "picture"); continue }
            if (l.split(' ').size in 2..8 && Regex("^[A-Z0-9]").containsMatchIn(l) && !chrome.containsMatchIn(l)) add(l, null, "picture")
        }
        // A cover's title is often split over lines ("LAST SONG" / "OF ASH"): neighbouring short lines put together.
        val short = picture.map { it.trim() }
        for (i in short.indices) for (n in 2..3) {
            if (i + n > short.size) continue
            val part = short.subList(i, i + n)
            if (part.any { it.split(' ').size > 4 || chrome.containsMatchIn(it) || !Regex("^[A-Za-z0-9]").containsMatchIn(it) }) continue
            val joined = part.joinToString(" ")
            val byAuthor = authored(joined)
            if (byAuthor != null) { if (byAuthor.first.split(' ').size in 2..8) add(byAuthor.first, byAuthor.second, "picture"); continue }
            if (part.any { onlyAuthor(it) }) continue
            if (joined.split(' ').size in 2..8 && Regex("^[A-Z0-9]").containsMatchIn(joined)) add(joined, null, "picture")
        }
        // Hashtags that name a book or series (#fourthwing), after the rest.
        Regex("#([A-Za-z][A-Za-z0-9]{4,40})").findAll(text).map { it.groupValues[1] }.forEach { h ->
            if (h.lowercase() !in NOT_BOOKS && !h.lowercase().startsWith("book")) add(h.replace(Regex("([a-z])([A-Z])"), "$1 $2"), null, "hashtag")
        }
        return out.values.toList()
    }

    /** A seller's list line: a name, then its price ("Vogt - 300", "Ian Watson - ₹200 each"). */
    private val PRICED = Regex("""(?i)^\s*([\p{L}][\p{L}.' ]{1,40}?)\s*[-–—:=]\s*(?:₹|rs\.?|inr|\$|£|€)?\s*\d{2,6}\s*(?:/-)?\s*(?:each|only|rs|inr|/-)?\s*$""")

    /**
     * The same, inside running text: a post's caption read from its page arrives as one line
     * ("Vogt - 300 Cj Cherryh - 300 Ian Watson - 200 each"), each name in capitals before its price.
     */
    private val PRICED_INLINE = Regex("""\b([A-Z][\p{L}.']*(?:\s+[A-Z][\p{L}.']*){0,3})\s*[-–—:=]\s*(?:₹|[Rr]s\.?|INR|\$|£|€)?\s*\d{2,6}\b(?:\s*/-)?(?:\s+each)?""")

    /** The authors a seller's post lists with prices: who wrote the books it shows, not their titles. */
    fun pricedNames(caption: String?): List<String> {
        val text = caption.orEmpty()
        val byLine = text.lines().mapNotNull { PRICED.find(it)?.groupValues?.get(1)?.trim() }
        val inline = PRICED_INLINE.findAll(text).map { it.groupValues[1].trim() }.toList()
        return (byLine + inline).filter { it.split(' ').size <= 4 && letters(it).length >= 3 }
            .filterNot { it.lowercase() in setOf("likes", "comments", "comment", "views", "rs", "price") }
            .distinctBy { letters(it) }
    }

    private val PARTICLES = setOf("van", "von", "de", "der", "den", "la", "le", "du", "di", "da", "del", "st", "st.", "mc", "mac", "and", "&")

    /**
     * [line] with [author] taken out: what's left (maybe nothing), or null when the author isn't in it.
     * The author is found by their surname; the initials, first names and "van"s before it go with it.
     */
    internal fun withoutAuthor(line: String, author: String): String? {
        val words = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val names = author.split(Regex("[\\s.]+")).map { letters(it) }.filter { it.isNotEmpty() }
        val surname = names.lastOrNull()?.takeIf { it.length >= 3 } ?: return null
        val at = words.indexOfFirst { letters(it) == surname }
        if (at < 0) return null
        var from = at
        while (from > 0) {
            val w = words[from - 1]
            val l = letters(w)
            val initials = Regex("^([A-Za-z]\\.){1,3}$").matches(w) || (l.length <= 2 && w.all { it.isUpperCase() || it == '.' })
            if (l in names || l in PARTICLES || w.lowercase() in PARTICLES || initials) from-- else break
        }
        // A co-author written next to it ("C.J. Cherryh Mercedes Lackey") is left in: the title is the longer side.
        val before = words.subList(0, from)
        val after = words.subList(at + 1, words.size)
        val rest = if (after.size >= before.size) after else before
        return rest.joinToString(" ").trim().trim('-', '–', '—', ':', ',', ' ')
    }

    /** A "book" whose title is only a person the post names as an author ("A. E. van Vogt"): not one of its books. */
    fun isAuthorName(title: String, authors: Collection<String>): Boolean {
        val t = letters(Matcher.baseTitle(title))
        if (t.isEmpty() || title.trim().split(Regex("\\s+")).size > 5) return false
        return authors.any { a ->
            val surname = a.split(Regex("[\\s.]+")).map { letters(it) }.lastOrNull { it.isNotEmpty() } ?: return@any false
            surname.length >= 3 && (t == letters(a) || t.endsWith(surname))
        }
    }

    /** The names the on-device AI reads out of the caption and picture text, as "Title — Author" lines. */
    fun aiGuesses(caption: String?, picture: List<String>): List<Guess> {
        val m = io.github.prithirajn.readync.ai.Ai.model() ?: return emptyList()
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

    private val SMALL_WORDS = setOf("of", "the", "a", "an", "and", "in", "on", "to", "for", "at", "or", "with", "from", "into", "&")

    /** The name-like end of a phrase: "Behold the cover of Last Song of Ash" -> "Last Song of Ash". */
    fun titleTail(phrase: String): String {
        val w = phrase.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var i = w.size
        while (i > 0) {
            val x = w[i - 1]
            if (x.first().isUpperCase() || x.first().isDigit() || x.lowercase() in SMALL_WORDS) i-- else break
        }
        var start = i
        while (start < w.size && w[start].lowercase() in SMALL_WORDS && !w[start].first().isUpperCase()) start++
        return w.drop(start).joinToString(" ")
    }

    /**
     * Books a post only compares its book to ("X meets Y", "for fans of X", "perfect for readers of X"),
     * as letters: not the books the post is about.
     */
    fun comparisons(caption: String?): Set<String> {
        val text = caption ?: return emptySet()
        val out = HashSet<String>()
        Regex("""([A-Z0-9][^.!?\n—–:;]{2,70}?)\s+meets\s+([A-Z0-9][^.!?\n,—–:;]{2,60})""").findAll(text).forEach { m ->
            out += letters(titleTail(m.groupValues[1])); out += letters(m.groupValues[2].trim())
        }
        Regex("""(?i)(?:fans of|readers of|if you (?:liked|loved|enjoyed)|perfect for|reminiscent of|in the vein of|comp(?:ed|s)? to)\s+([^.!?\n—–:;]{3,80})""").findAll(text).forEach { m ->
            m.groupValues[1].split(Regex(",\\s*|\\s+and\\s+|\\s+or\\s+|\\s*&\\s*")).forEach { out += letters(it.trim().trim('"', '“', '”')) }
        }
        return out.filter { it.length >= 4 }.toSet()
    }

    /** A line on a cover that reads like its author's name ("ADRIAN TCHAIKOVSKY"), not its title or a blurb. */
    fun pictureAuthor(picture: List<String>, title: String): String? {
        val t = letters(title)
        val blurb = setOf("novel", "bestseller", "bestselling", "author", "times", "edition", "book", "books", "series", "story", "stories",
            "coming", "cover", "reveal", "new", "york", "sunday", "award", "winning", "winner", "of", "the", "and", "a", "an", "in", "presents")
        return picture.map { it.trim() }.firstOrNull { l ->
            val w = l.split(Regex("\\s+"))
            w.size in 2..3 && w.all { x -> x.length >= 2 && x.all { it.isLetter() || it == '.' || it == '\'' || it == '-' } && x.lowercase() !in blurb } &&
                !t.contains(letters(l)) && !letters(l).contains(t)
        }?.split(Regex("\\s+"))?.joinToString(" ") { x -> if (x.all { !it.isLowerCase() }) x.lowercase().replaceFirstChar { it.uppercase() } else x }
    }

    /**
     * Names the catalogue doesn't know yet (a cover reveal for next year's book) that the post's caption
     * gives and its picture shows too: kept, as the post names them, with the cover's author line.
     */
    fun unlisted(guesses: List<Guess>, picture: List<String>, found: List<Found>, thumb: String?, caption: String? = null): List<Found> {
        val seen = letters(picture.joinToString(" "))
        val have = found.map { letters(Matcher.baseTitle(it.title)) }.toSet()
        // A post plainly about a book (its cover, a paperback, a release): "Title by @account" counts on its own.
        val aboutABook = caption?.let { BOOK_WORDS.containsMatchIn(it) } == true
        return guesses.asSequence()
            .filter { it.from == "caption" && letters(it.query).length >= 6 && letters(it.query) !in have }
            .filter { g -> (seen.isNotEmpty() && seen.contains(letters(g.query))) || (aboutABook && g.handle != null && g.query.first().isUpperCase()) }
            .distinctBy { letters(it.query) }
            .take(2)
            .map { g -> Found(g.query, g.author ?: pictureAuthor(picture, g.query) ?: g.handle?.let(::nameOfHandle), thumb, null, null, "caption") }
            .toList()
    }

    private val BOOK_WORDS = Regex("(?i)\\b(cover|paperback|hardcover|hardback|novel|book|preorder|pre-order|out now|release|releases|available|coming|debut|ARC|edition|signed|chapter)\\b")

    /** An author's name from their account: "@david.scott.hay.official" -> David Scott Hay. */
    fun nameOfHandle(handle: String): String? {
        val words = handle.removePrefix("@").split('.', '_').filter { it.isNotBlank() }
            .filter { it.lowercase() !in setOf("official", "author", "writes", "writer", "books", "book", "the", "real", "iam", "art", "official1") && it.all { c -> c.isLetter() } }
        if (words.size !in 2..4) return null
        return words.joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
    }

    // ---- checking each against a catalogue ----

    private fun letters(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The book [g] names, from Google Books, else Open Library, when one clearly is it. Blocking. */
    fun verify(g: Guess): Found? = runCatching { google(g) }.getOrNull() ?: runCatching { openLibrary(g) }.getOrNull()

    /** Whether a catalogue's [title] is the name [g] gives (the whole title, or its start). */
    private fun titleFits(g: Guess, title: String): Boolean {
        val want = letters(g.query)
        val have = letters(Matcher.baseTitle(title))
        return when (g.from) {
            "hashtag", "picture", "cover" -> have == want || (have.length >= 6 && want.startsWith(have))
            else -> have == want || (have.length >= 5 && (want.startsWith(have) || have.startsWith(want)))
        }
    }

    /** Open Library's search, for books Google Books doesn't list (or when it turns the app away). */
    private fun openLibrary(g: Guess): Found? {
        val q = "title=" + URLEncoder.encode(g.query, "UTF-8") + (g.author?.let { "&author=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        val j = JSONObject(Http.request("GET", "https://openlibrary.org/search.json?$q&limit=5&fields=title,author_name,isbn,cover_i,number_of_pages_median", timeoutMs = 12_000).body)
        val docs = j.optJSONArray("docs") ?: return null
        for (i in 0 until docs.length()) {
            val d = docs.optJSONObject(i) ?: continue
            val title = d.optString("title").ifBlank { continue }
            if (!titleFits(g, title)) continue
            val authors = d.optJSONArray("author_name")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            if (g.author != null && authors.isNotEmpty() && authors.none { a -> letters(a).contains(letters(g.author).takeLast(5)) }) continue
            val isbn = d.optJSONArray("isbn")?.let { a -> (0 until a.length()).map { a.optString(it) }.firstOrNull { it.length == 13 } }
            val cover = d.optInt("cover_i").takeIf { it > 0 }?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" }
            return Found(title, authors.firstOrNull() ?: g.author, cover, isbn, d.optInt("number_of_pages_median").takeIf { it > 0 }, g.from)
        }
        return null
    }

    private fun google(g: Guess): Found? {
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
                "hashtag", "picture", "cover" -> have == want || (have.length >= 6 && want.startsWith(have))
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
     * One name you picked or typed (a line read off the picture, a title): checked against Google
     * Books, with and without the author; kept as written when the catalogue doesn't have it yet. Blocking.
     */
    fun lookUp(name: String, author: String? = null): Found {
        val q = name.trim()
        val g = Guess(q, author?.trim()?.ifBlank { null }, "caption")
        return runCatching { verify(g) }.getOrNull()
            ?: g.author?.let { runCatching { verify(g.copy(author = null)) }.getOrNull() }
            ?: Found(if (q.none { it.isLowerCase() }) q.lowercase().split(' ').joinToString(" ") { w -> if (w in SMALL_WORDS) w else w.replaceFirstChar { it.uppercase() } }.replaceFirstChar { it.uppercase() } else q,
                g.author, null, null, null, "typed")
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
                    val read = runCatching { io.github.prithirajn.readync.ui.PageScanner.read(context, android.net.Uri.fromFile(f)) }.getOrDefault(emptyList())
                    note(listOfNotNull(biggestLettering(read)) + read.map { it.text })
                    f.delete()
                }
            } else {
                pictures++
                progress("Reading picture ${n + 1} of ${uris.size}")
                val read = runCatching { io.github.prithirajn.readync.ui.PageScanner.read(context, uri) }.getOrDefault(emptyList())
                note(listOfNotNull(biggestLettering(read)) + read.map { it.text })
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
        // Nothing named in words: the model looks at the pictures (or a few frames), when it can.
        var foundAll = found
        if (found.isEmpty() && io.github.prithirajn.readync.ai.Ai.model() != null) {
            progress("Looking at the pictures")
            val files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                uris.filter { !context.contentResolver.getType(it).orEmpty().startsWith("video/") }.take(3).mapIndexedNotNull { i, u ->
                    runCatching { context.contentResolver.openInputStream(u)?.use { inp -> File(context.cacheDir, "post-look-m$i.jpg").apply { outputStream().use { inp.copyTo(it) } } } }.getOrNull()
                }
            }
            val seen = coverGuesses(context, files)
            files.forEach { it.delete() }
            if (seen.isNotEmpty()) foundAll = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                seen.mapNotNull { g -> runCatching { verify(g) ?: g.author?.let { verify(g.copy(author = null)) } }.getOrNull() }
                    .distinctBy { letters(Matcher.baseTitle(it.title)) + "|" + letters(it.author ?: "") }
            }
        }
        val label = when {
            videos > 0 && pictures == 0 -> "Screen recording"
            videos == 0 -> if (pictures == 1) "Screenshot" else "$pictures screenshots"
            else -> "Recording and screenshots"
        }
        val now = System.currentTimeMillis() / 1000
        return Trap(Post("media:$now", label, null, null, null), foundAll, now, lines.take(40))
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
        val urls = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pictures(post) }
        progress(if (urls.size > 1) "Reading the words on its ${urls.size} pictures" else "Reading the words on its picture")
        val picture = pictureText(context, urls)
        // The way book posts are written first (quick); the on-device AI only when that finds nothing
        // (loading and asking it takes a while).
        val comps = comparisons(post.caption)
        // A seller's list ("Vogt - 300 / Cj Cherryh - 300 / Ian Watson - 200 each") says how many books
        // to expect at least, and who wrote them.
        val priced = pricedNames(post.caption)
        val wanted = maxOf(1, priced.size)
        progress("Picking out the books")
        val ruled = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            guesses(post.caption, picture, priced).distinctBy { letters(it.query) }.filterNot { letters(it.query) in comps }.take(30)
        }
        suspend fun check(gs: List<Guess>): List<Found> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val checked = gs.mapNotNull { g -> runCatching { verify(g) ?: g.author?.takeIf { g.from == "picture" }?.let { verify(g.copy(author = null)) } }.getOrNull() }
                .filterNot { letters(Matcher.baseTitle(it.title)) in comps || isAuthorName(it.title, priced) }
                .distinctBy { letters(Matcher.baseTitle(it.title)) + "|" + letters(it.author ?: "") }
            checked + unlisted(gs, picture, checked, post.thumbnail, post.caption)
        }
        progress("Checking ${ruled.size} names against Google Books")
        val found = check(ruled).toMutableList()
        // Fewer books than the post shows: the AI reads the words again, then looks at the pictures.
        if (found.size < wanted && io.github.prithirajn.readync.ai.Ai.model() != null) {
            progress("Asking the on-device AI")
            val asked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                aiGuesses(post.caption, picture).distinctBy { letters(it.query) }.filterNot { letters(it.query) in comps || it.query.lowercase() in ruled.map { r -> r.query.lowercase() } }.take(15)
            }
            if (asked.isNotEmpty()) { progress("Checking what it named"); found += check(asked) }
            dedupe(found)
        }
        // Nothing named in words: the model looks at the pictures themselves, when it can.
        if (found.size < wanted && urls.isNotEmpty() && io.github.prithirajn.readync.ai.Ai.model() != null) {
            progress("Looking at the pictures")
            val files = pictureFiles(context, urls)
            val seen = coverGuesses(context, files)
            files.forEach { it.delete() }
            if (seen.isNotEmpty()) {
                progress("Checking what it saw against Google Books")
                found += kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    seen.mapNotNull { g -> runCatching { verify(g) ?: g.author?.let { verify(g.copy(author = null)) } }.getOrNull() }
                        .filterNot { letters(Matcher.baseTitle(it.title)) in comps || isAuthorName(it.title, priced) }
                }
                dedupe(found)
            }
        }
        return Trap(post, found, System.currentTimeMillis() / 1000, picture.take(40))
    }

    /** The same book found twice (by its words and by its picture): kept once, the first time. */
    private fun dedupe(found: MutableList<Found>) {
        val seen = HashSet<String>()
        found.retainAll { seen.add(letters(Matcher.baseTitle(it.title))) }
    }
}
