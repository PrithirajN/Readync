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
            val lines = runCatching { io.github.prithirajn.trackersync.ui.PageScanner.read(context, android.net.Uri.fromFile(f)) }.getOrDefault(emptyList())
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
    internal fun biggestLettering(lines: List<io.github.prithirajn.trackersync.sync.PageReader.Line>): String? {
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
        val m = io.github.prithirajn.trackersync.ai.Ai.model() ?: return emptyList()
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
            Regex("""["“]?([A-Z0-9][^"“”\n]{2,80}?)["”]?\s+(?:by|-|–|—)\s+(@?[\p{L}0-9_][\p{L}0-9_.'\- ]{1,40}?)(?=$|[,.!?;:)(\n]|\s{2}|\s[#@])""").findAll(line).forEach { m ->
                // "Behold the cover of Last Song of Ash by aptshadow": the title is the name-like end of the
                // phrase, and an account's handle (one word, small letters or @) isn't the author.
                val a = m.groupValues[2].trim()
                val handle = a.startsWith("@") || (!a.contains(' ') && (a.first().isLowerCase() || a.any { it == '_' || it.isDigit() }))
                add(titleTail(m.groupValues[1]).ifBlank { m.groupValues[1] }, a.takeUnless { handle || a.first().isLowerCase() }, "caption")
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
        // A cover's title is often split over lines ("LAST SONG" / "OF ASH"): neighbouring short lines put together.
        val short = picture.map { it.trim() }
        for (i in short.indices) for (n in 2..3) {
            if (i + n > short.size) continue
            val part = short.subList(i, i + n)
            if (part.any { it.split(' ').size > 4 || chrome.containsMatchIn(it) || !Regex("^[A-Za-z0-9]").containsMatchIn(it) }) continue
            val joined = part.joinToString(" ")
            if (joined.split(' ').size in 2..8 && Regex("^[A-Z0-9]").containsMatchIn(joined)) add(joined, null, "picture")
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
    fun unlisted(guesses: List<Guess>, picture: List<String>, found: List<Found>, thumb: String?): List<Found> {
        val seen = letters(picture.joinToString(" "))
        if (seen.isEmpty()) return emptyList()
        val have = found.map { letters(Matcher.baseTitle(it.title)) }.toSet()
        return guesses.asSequence()
            .filter { it.from == "caption" && letters(it.query).length >= 6 && seen.contains(letters(it.query)) && letters(it.query) !in have }
            .distinctBy { letters(it.query) }
            .take(2)
            .map { g -> Found(g.query, g.author ?: pictureAuthor(picture, g.query), thumb, null, null, "caption") }
            .toList()
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
                    val read = runCatching { io.github.prithirajn.trackersync.ui.PageScanner.read(context, android.net.Uri.fromFile(f)) }.getOrDefault(emptyList())
                    note(listOfNotNull(biggestLettering(read)) + read.map { it.text })
                    f.delete()
                }
            } else {
                pictures++
                progress("Reading picture ${n + 1} of ${uris.size}")
                val read = runCatching { io.github.prithirajn.trackersync.ui.PageScanner.read(context, uri) }.getOrDefault(emptyList())
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
        if (found.isEmpty() && io.github.prithirajn.trackersync.ai.Ai.model() != null) {
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
        progress("Picking out the books")
        val guesses = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val comps = comparisons(post.caption)
            (aiGuesses(post.caption, picture) + guesses(post.caption, picture)).distinctBy { letters(it.query) }
                .filterNot { letters(it.query) in comps }.take(30)
        }
        progress("Checking ${guesses.size} names against Google Books")
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val comps = comparisons(post.caption)
            val checked = guesses.mapNotNull { g -> runCatching { verify(g) }.getOrNull() }
                .filterNot { letters(Matcher.baseTitle(it.title)) in comps }
                .distinctBy { letters(Matcher.baseTitle(it.title)) + "|" + letters(it.author ?: "") }
            checked + unlisted(guesses, picture, checked, post.thumbnail)
        }.toMutableList()
        // Nothing named in words: the model looks at the pictures themselves, when it can.
        if (found.isEmpty() && urls.isNotEmpty() && io.github.prithirajn.trackersync.ai.Ai.model() != null) {
            progress("Looking at the pictures")
            val files = pictureFiles(context, urls)
            val seen = coverGuesses(context, files)
            files.forEach { it.delete() }
            if (seen.isNotEmpty()) {
                progress("Checking what it saw against Google Books")
                found += kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    seen.mapNotNull { g -> runCatching { verify(g) ?: g.author?.let { verify(g.copy(author = null)) } }.getOrNull() }
                        .distinctBy { letters(Matcher.baseTitle(it.title)) + "|" + letters(it.author ?: "") }
                }
            }
        }
        return Trap(post, found, System.currentTimeMillis() / 1000, picture.take(40))
    }
}
