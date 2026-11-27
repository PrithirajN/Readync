package io.github.prithirajn.readync.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.data.Store
import io.github.prithirajn.readync.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Posting beyond Threads: what goes up on Threads (a highlight, a reading session, a review or a
 * recap) goes up the same on Bluesky and Mastodon too, each where you've turned it on,
 * with the same words and pictures. X can't be posted to by another app without its paid API, so
 * each post waits for X instead, and once a day (at the time you pick) a notification opens X with
 * it filled in, for one tap. Sign-ins are kept with the app's other data.
 */
object Socials {
    /**
     * Set on this thread just before a post's last "publish" request goes out. After that, a failure
     * (most often a timeout waiting for the answer) can still mean the post went up, so it must
     * never be put up again: Instagram reels in particular publish slowly and the answer can be
     * lost, which is how one highlight went up three times.
     */
    object PublishMark {
        private val sent = ThreadLocal<Boolean>()
        fun reset() = sent.set(false)
        fun mark() = sent.set(true)
        val wasSent: Boolean get() = sent.get() == true
    }


    // ---- settings, kept in Snapshot.extras ----

    private const val KEY = "socials"

    data class Bluesky(val on: Boolean = false, val handle: String = "", val appPassword: String = "", val pds: String = "https://bsky.social", val did: String? = null)
    data class Mastodon(val on: Boolean = false, val server: String = "", val token: String = "", val account: String? = null)
    data class XDaily(val on: Boolean = false, val hour: Int = 19, val minute: Int = 0)
    data class Config(val bluesky: Bluesky = Bluesky(), val mastodon: Mastodon = Mastodon(), val x: XDaily = XDaily())

    fun config(s: Snapshot): Config {
        val o = s.extras[KEY]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Config()
        fun JSONObject.str(k: String) = optString(k).takeIf { it.isNotBlank() && it != "null" }
        val b = o.optJSONObject("bluesky")
        val m = o.optJSONObject("mastodon")
        val x = o.optJSONObject("x")
        return Config(
            bluesky = b?.let { Bluesky(it.optBoolean("on"), it.str("handle").orEmpty(), it.str("appPassword").orEmpty(), it.str("pds") ?: "https://bsky.social", it.str("did")) } ?: Bluesky(),
            mastodon = m?.let { Mastodon(it.optBoolean("on"), it.str("server").orEmpty(), it.str("token").orEmpty(), it.str("account")) } ?: Mastodon(),
            x = x?.let { XDaily(it.optBoolean("on"), it.optInt("hour", 19), it.optInt("minute", 0)) } ?: XDaily(),
        )
    }

    fun withConfig(s: Snapshot, c: Config): Snapshot {
        // Anything else kept there (by other editions) is left as it is.
        val o = (s.extras[KEY]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject())
            .put("bluesky", JSONObject().put("on", c.bluesky.on).put("handle", c.bluesky.handle).put("appPassword", c.bluesky.appPassword).put("pds", c.bluesky.pds).put("did", c.bluesky.did ?: JSONObject.NULL))
            .put("mastodon", JSONObject().put("on", c.mastodon.on).put("server", c.mastodon.server).put("token", c.mastodon.token).put("account", c.mastodon.account ?: JSONObject.NULL))
            .put("x", JSONObject().put("on", c.x.on).put("hour", c.x.hour).put("minute", c.x.minute))
        return s.copy(extras = s.extras + (KEY to o.toString()))
    }

    /**
     * What to post: the words, the pictures (PNG) each with its description, and whether it gives the
     * story away; and what it's of ([kind]: "highlight", "review" or "recap"; its [book] and
     * [highlights]), for networks that make their own version.
     */
    class Post(
        val text: String, val pictures: List<Pair<ByteArray, String?>>, val spoiler: Boolean, val tags: List<String> = emptyList(), val label: String = "",
        val kind: String = "", val book: io.github.prithirajn.readync.data.Book? = null, val highlights: List<Highlights.Item> = emptyList(),
    )

    class SocialException(message: String) : Exception(message)

    // ---- after each Threads post ----

    /**
     * [post] put up on each network that's on, one after another. A network that fails is noted in
     * the log and doesn't stop the rest (or the Threads post, which has already gone up).
     */
    fun mirror(context: Context, store: Store, post: Post) {
        val c = config(store.snapshot)
        fun attempt(name: String, run: () -> String) {
            runCatching(run)
                .onSuccess { store.log(null, "Posted to $name" + (post.label.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")) }
                .onFailure { e -> store.log(null, "Couldn't post to $name" + (post.label.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ": ${e.message}", error = true) }
        }
        if (c.bluesky.on && c.bluesky.handle.isNotBlank() && c.bluesky.appPassword.isNotBlank()) attempt("Bluesky") { BlueskyClient.post(store, c.bluesky, post) }
        if (c.mastodon.on && c.mastodon.server.isNotBlank() && c.mastodon.token.isNotBlank()) attempt("Mastodon") { MastodonClient.post(c.mastodon, post, store) }
        if (c.x.on) runCatching { XQueue.add(context, post) }
    }

    // ---- pictures ----

    /** [png] as a JPEG no larger than [maxBytes] (Bluesky takes about 1 MB a picture). */
    internal fun jpeg(png: ByteArray, maxBytes: Int = 950_000): Pair<ByteArray, IntArray> {
        var bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: throw SocialException("A picture couldn't be read")
        val size = intArrayOf(bmp.width, bmp.height)
        var q = 92
        while (true) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
            val bytes = out.toByteArray()
            if (bytes.size <= maxBytes || (q <= 50 && bmp.width <= 720)) { bmp.recycle(); return bytes to size }
            if (q > 60) q -= 10 else {
                val smaller = Bitmap.createScaledBitmap(bmp, (bmp.width * 0.8).toInt(), (bmp.height * 0.8).toInt(), true)
                if (smaller !== bmp) bmp.recycle()
                bmp = smaller; q = 85
            }
        }
    }

    /** Words cut to [max] characters at a word, with an ellipsis, keeping a closing link line whole. */
    internal fun fit(text: String, max: Int): String {
        if (text.length <= max) return text
        val lines = text.trimEnd().split('\n')
        val link = lines.lastOrNull()?.takeIf { it.contains("https://") }
        val body = if (link != null) lines.dropLast(1).joinToString("\n").trimEnd() else text
        val room = max - (link?.let { it.length + 2 } ?: 0) - 1
        val cut = body.take(room.coerceAtLeast(1))
        val sp = cut.lastIndexOf(' ')
        val short = (if (sp > room / 2) cut.take(sp) else cut).trimEnd(',', ';', ':', ' ') + "…"
        return if (link != null) "$short\n\n$link" else short
    }
}

/**
 * Bluesky through its open AT Protocol: signed in with your handle and an app password (Settings ›
 * Privacy and security › App passwords, never your real password), pictures uploaded as blobs, the
 * post written as a record, with its links and hashtags marked so they're tappable.
 */
object BlueskyClient {
    private const val MAX = 300

    private fun json(method: String, url: String, body: JSONObject?, jwt: String?): JSONObject {
        val headers = HashMap<String, String>()
        headers["Accept"] = "application/json"
        if (body != null) headers["Content-Type"] = "application/json"
        jwt?.let { headers["Authorization"] = "Bearer $it" }
        val r = Http.request(method, url, headers, body?.toString(), timeoutMs = 45_000)
        val o = runCatching { JSONObject(r.body) }.getOrNull()
        if (!r.ok) throw Socials.SocialException("Bluesky answered HTTP ${r.code}" + (o?.optString("message")?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
        return o ?: JSONObject()
    }

    /** Signs in: (access token, your DID). */
    fun session(b: Socials.Bluesky): Pair<String, String> {
        val o = json("POST", "${b.pds.trimEnd('/')}/xrpc/com.atproto.server.createSession", JSONObject().put("identifier", b.handle.removePrefix("@").trim()).put("password", b.appPassword.trim()), null)
        val jwt = o.optString("accessJwt").takeIf { it.isNotBlank() } ?: throw Socials.SocialException("Bluesky didn't sign in")
        val did = o.optString("did").takeIf { it.isNotBlank() } ?: throw Socials.SocialException("Bluesky didn't say whose account it is")
        return jwt to did
    }

    fun post(store: Store, b: Socials.Bluesky, post: Socials.Post): String {
        val (jwt, did) = session(b)
        val base = b.pds.trimEnd('/')
        // Bluesky has no tap-to-reveal for spoilers: the post says so first.
        val words = Socials.fit((if (post.spoiler) "⚠️ Spoilers\n\n" else "") + post.text, MAX)
        // Never twice: your last 50 posts read back (pictures' descriptions too); one that's this post is left as it is.
        runCatching {
            val feed = json("GET", "$base/xrpc/app.bsky.feed.getAuthorFeed?actor=${Http.enc(did)}&limit=50&filter=posts_no_replies", null, jwt).optJSONArray("feed")
            val seen = (0 until (feed?.length() ?: 0)).mapNotNull { feed!!.optJSONObject(it)?.optJSONObject("post") }.map { p ->
                val imgs = p.optJSONObject("embed")?.optJSONArray("images")
                DupGuard.Seen(p.optString("uri"), p.optJSONObject("record")?.optString("text"),
                    (0 until (imgs?.length() ?: 0)).mapNotNull { imgs!!.optJSONObject(it)?.optString("alt")?.takeIf { a -> a.isNotBlank() } },
                    DupGuard.time(p.optJSONObject("record")?.optString("createdAt")))
            }
            DupGuard.find(seen, words, post.pictures.map { it.second })
        }.getOrNull()?.let { hit ->
            store.log(null, "Already on Bluesky, so not posted again: “${words.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(70)}”")
            return hit.id
        }
        val images = JSONArray()
        for ((png, alt) in post.pictures.take(4)) {
            val (jpg, size) = Socials.jpeg(png)
            val r = Http.request("POST", "$base/xrpc/com.atproto.repo.uploadBlob", mapOf("Content-Type" to "image/jpeg", "Authorization" to "Bearer $jwt", "Accept" to "application/json"), raw = jpg, timeoutMs = 90_000)
            if (!r.ok) throw Socials.SocialException("Bluesky didn't take a picture (HTTP ${r.code})")
            val blob = JSONObject(r.body).optJSONObject("blob") ?: throw Socials.SocialException("Bluesky didn't take a picture")
            images.put(JSONObject().put("alt", (alt ?: "").take(1000)).put("image", blob)
                .put("aspectRatio", JSONObject().put("width", size[0]).put("height", size[1])))
        }
        val record = JSONObject()
            .put("\$type", "app.bsky.feed.post")
            .put("text", words)
            .put("createdAt", java.time.Instant.now().toString())
            .put("langs", JSONArray().put("en"))
        facets(words).takeIf { it.length() > 0 }?.let { record.put("facets", it) }
        if (images.length() > 0) record.put("embed", JSONObject().put("\$type", "app.bsky.embed.images").put("images", images))
        val o = json("POST", "$base/xrpc/com.atproto.repo.createRecord", JSONObject().put("repo", did).put("collection", "app.bsky.feed.post").put("record", record), jwt)
        if (b.did != did) store.update { s -> Socials.withConfig(s, Socials.config(s).let { c -> c.copy(bluesky = c.bluesky.copy(did = did)) }) }
        return o.optString("uri")
    }

    /** Links and hashtags in [text], marked by their byte positions (Bluesky counts in UTF-8 bytes). */
    internal fun facets(text: String): JSONArray {
        val out = JSONArray()
        fun bytes(upTo: Int) = text.substring(0, upTo).toByteArray(Charsets.UTF_8).size
        Regex("https?://[^\\s]+").findAll(text).forEach { m ->
            out.put(JSONObject().put("index", JSONObject().put("byteStart", bytes(m.range.first)).put("byteEnd", bytes(m.range.last + 1)))
                .put("features", JSONArray().put(JSONObject().put("\$type", "app.bsky.richtext.facet#link").put("uri", m.value.trimEnd('.', ',', ')')))))
        }
        Regex("(?<![\\w/])#([\\p{L}\\d_]{2,60})").findAll(text).forEach { m ->
            out.put(JSONObject().put("index", JSONObject().put("byteStart", bytes(m.range.first)).put("byteEnd", bytes(m.range.last + 1)))
                .put("features", JSONArray().put(JSONObject().put("\$type", "app.bsky.richtext.facet#tag").put("tag", m.groupValues[1]))))
        }
        return out
    }
}

/**
 * Mastodon (any server): your server's address and an access token you make there (Preferences ›
 * Development › New application, with write:statuses, write:media and read:accounts). Pictures
 * go up with their descriptions; a spoiler goes behind a content warning, as Mastodon does it.
 */
object MastodonClient {
    private const val MAX = 500

    private fun base(m: Socials.Mastodon) = m.server.trim().trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }

    /** Whose token this is: "@you@server". */
    fun whoAmI(m: Socials.Mastodon): String {
        val r = Http.request("GET", "${base(m)}/api/v1/accounts/verify_credentials", mapOf("Authorization" to "Bearer ${m.token.trim()}", "Accept" to "application/json"), timeoutMs = 20_000)
        if (!r.ok) throw Socials.SocialException("Mastodon answered HTTP ${r.code} (is the token right, with read:accounts?)")
        val acct = JSONObject(r.body).optString("acct").takeIf { it.isNotBlank() } ?: throw Socials.SocialException("Mastodon didn't say whose token this is")
        val host = runCatching { java.net.URI(base(m)).host }.getOrNull()
        return "@$acct" + (host?.let { "@$it" } ?: "")
    }

    private fun upload(m: Socials.Mastodon, png: ByteArray, alt: String?): String {
        val boundary = "----readync" + System.nanoTime()
        val out = ByteArrayOutputStream()
        alt?.takeIf { it.isNotBlank() }?.let { out.write("--$boundary\r\nContent-Disposition: form-data; name=\"description\"\r\n\r\n${it.take(1500)}\r\n".toByteArray()) }
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"quote.png\"\r\nContent-Type: image/png\r\n\r\n".toByteArray())
        out.write(png)
        out.write("\r\n--$boundary--\r\n".toByteArray())
        val r = Http.request("POST", "${base(m)}/api/v2/media", mapOf("Authorization" to "Bearer ${m.token.trim()}", "Content-Type" to "multipart/form-data; boundary=$boundary", "Accept" to "application/json"), raw = out.toByteArray(), timeoutMs = 90_000)
        if (!r.ok) throw Socials.SocialException("Mastodon didn't take a picture (HTTP ${r.code})")
        val o = JSONObject(r.body)
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: throw Socials.SocialException("Mastodon didn't take a picture")
        // 202: still being processed; ready once it has an address.
        if (r.code == 202 || o.isNull("url")) {
            for (i in 0 until 12) {
                Thread.sleep(2_500)
                val s = Http.request("GET", "${base(m)}/api/v1/media/$id", mapOf("Authorization" to "Bearer ${m.token.trim()}", "Accept" to "application/json"), timeoutMs = 20_000)
                if (s.code == 200 && !JSONObject(s.body).isNull("url")) break
            }
        }
        return id
    }

    fun post(m: Socials.Mastodon, post: Socials.Post, store: Store? = null): String {
        val words = Socials.fit(post.text, MAX)
        // Never twice: your last 40 posts read back (pictures' descriptions too); one that's this post is left as it is.
        runCatching {
            val auth = mapOf("Authorization" to "Bearer ${m.token.trim()}", "Accept" to "application/json")
            val me = JSONObject(Http.request("GET", "${base(m)}/api/v1/accounts/verify_credentials", auth, timeoutMs = 20_000).body).optString("id")
            val r = Http.request("GET", "${base(m)}/api/v1/accounts/$me/statuses?limit=40&exclude_replies=true&exclude_reblogs=true", auth, timeoutMs = 20_000)
            val a = org.json.JSONArray(r.body)
            val seen = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { p ->
                val media = p.optJSONArray("media_attachments")
                DupGuard.Seen(p.optString("url"), android.text.Html.fromHtml(p.optString("content"), android.text.Html.FROM_HTML_MODE_LEGACY).toString(),
                    (0 until (media?.length() ?: 0)).mapNotNull { media!!.optJSONObject(it)?.optString("description")?.takeIf { d -> d.isNotBlank() && d != "null" } },
                    DupGuard.time(p.optString("created_at")))
            }
            DupGuard.find(seen, words, post.pictures.map { it.second })
        }.getOrNull()?.let { hit ->
            store?.log(null, "Already on Mastodon, so not posted again: “${words.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(70)}”")
            return hit.id
        }
        val ids = post.pictures.take(4).map { (png, alt) -> upload(m, png, alt) }
        val form = StringBuilder()
        fun add(k: String, v: String) { if (form.isNotEmpty()) form.append('&'); form.append(Http.enc(k)).append('=').append(Http.enc(v)) }
        add("status", words)
        add("visibility", "public")
        add("language", "en")
        ids.forEach { add("media_ids[]", it) }
        if (post.spoiler) { add("spoiler_text", "Spoilers"); add("sensitive", "true") }
        val r = Http.request("POST", "${base(m)}/api/v1/statuses", mapOf(
            "Authorization" to "Bearer ${m.token.trim()}", "Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json",
            // The same post isn't sent twice if a retry follows a dropped answer.
            "Idempotency-Key" to Integer.toHexString((words + ids.joinToString()).hashCode()),
        ), form.toString(), timeoutMs = 45_000)
        if (!r.ok) throw Socials.SocialException("Mastodon didn't take the post (HTTP ${r.code})" + (runCatching { JSONObject(r.body).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
        return JSONObject(r.body).optString("url")
    }
}

/**
 * Posts waiting for X: their words and pictures kept in the app's own storage (the newest 10), and
 * a daily notification at your time that opens X with the oldest, filled in.
 */
object XQueue {
    private fun dir(context: Context) = File(context.filesDir, "x-queue").apply { mkdirs() }

    fun add(context: Context, post: Socials.Post) {
        val d = File(dir(context), System.currentTimeMillis().toString()).apply { mkdirs() }
        File(d, "text.txt").writeText(post.text)
        File(d, "label.txt").writeText(post.label)
        post.pictures.take(4).forEachIndexed { k, (png, _) -> File(d, "p$k.png").writeBytes(png) }
        // The newest 10 kept.
        dir(context).listFiles()?.sortedByDescending { it.name }?.drop(10)?.forEach { it.deleteRecursively() }
    }

    fun count(context: Context): Int = dir(context).listFiles()?.size ?: 0

    /** The oldest waiting post: its folder, words, pictures and what it is. */
    fun oldest(context: Context): Triple<File, String, List<File>>? {
        val d = dir(context).listFiles()?.minByOrNull { it.name } ?: return null
        val text = runCatching { File(d, "text.txt").readText() }.getOrNull() ?: run { d.deleteRecursively(); return null }
        return Triple(d, text, d.listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name }.orEmpty())
    }

    fun label(d: File): String = runCatching { File(d, "label.txt").readText() }.getOrDefault("")

    /** One post waiting for X: its folder, words, what it is, and its pictures. */
    class Entry(val dir: File, val text: String, val label: String, val pictures: List<File>)

    /** Everything waiting for X, oldest first. */
    fun all(context: Context): List<Entry> = dir(context).listFiles()?.sortedBy { it.name }?.mapNotNull { d ->
        val text = runCatching { File(d, "text.txt").readText() }.getOrNull() ?: return@mapNotNull null
        Entry(d, text, label(d), d.listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name }.orEmpty())
    }.orEmpty()

    /** New words for a waiting post. */
    fun edit(d: File, text: String) { runCatching { File(d, "text.txt").writeText(text) } }

    /** Taken out of the queue without posting. */
    fun remove(d: File) { d.deleteRecursively() }

    /** Opens X with the oldest waiting post (then it's done). Returns what to say. */
    fun open(context: Context): String {
        val (d, _, _) = oldest(context) ?: return "Nothing waiting for X"
        return open(context, d)
    }

    /** Opens X with this waiting post (then it's done). Returns what to say. */
    fun open(context: Context, d: File): String {
        if (!d.exists()) return "That post isn't waiting any more"
        val text = runCatching { File(d, "text.txt").readText() }.getOrNull() ?: return "That post isn't waiting any more"
        val pics = d.listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name }.orEmpty()
        val bitmaps = pics.mapNotNull { BitmapFactory.decodeFile(it.path) }
        val how = io.github.prithirajn.readync.ui.XPost.pictures(context, bitmaps, text)
        if (how != null) d.deleteRecursively()
        return io.github.prithirajn.readync.ui.XPost.said(how, if (bitmaps.size > 1) "the pictures" else "the picture")
    }
}

/**
 * The daily nudge for X: at your time, if posts are waiting, a notification; tapping it opens X
 * with the oldest one filled in (one tap to post). Scheduled again each time the time changes.
 */
class XDailyWorker(context: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(context, params) {
    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val ctx = applicationContext
        val waiting = XQueue.count(ctx)
        if (waiting == 0) return androidx.work.ListenableWorker.Result.success()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return androidx.work.ListenableWorker.Result.success()
        ctx.getSystemService(android.app.NotificationManager::class.java)
            ?.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "Ready for X", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val label = XQueue.oldest(ctx)?.first?.let { XQueue.label(it) }.orEmpty()
        val open = android.app.PendingIntent.getActivity(
            ctx, 77,
            android.content.Intent(ctx, io.github.prithirajn.readync.MainActivity::class.java)
                .setAction(io.github.prithirajn.readync.MainActivity.ACTION_X_POST)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        androidx.core.app.NotificationManagerCompat.from(ctx).notify(
            7_700,
            androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(io.github.prithirajn.readync.R.drawable.lucide_upload)
                .setContentTitle("Ready to post on X")
                .setContentText((label.ifBlank { "Your latest post" }.replaceFirstChar { it.uppercase() }) + if (waiting > 1) " · ${waiting - 1} more waiting" else "")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        return androidx.work.ListenableWorker.Result.success()
    }

    companion object {
        const val CHANNEL = "x-ready"
        private const val WORK = "x-daily"

        /** Daily at [hour]:[minute] while [on]; cancelled otherwise. */
        fun schedule(context: Context, on: Boolean, hour: Int, minute: Int) {
            val wm = androidx.work.WorkManager.getInstance(context)
            if (!on) { wm.cancelUniqueWork(WORK); return }
            val now = java.time.ZonedDateTime.now()
            var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = java.time.Duration.between(now, next).toMinutes().coerceAtLeast(1)
            val req = androidx.work.PeriodicWorkRequestBuilder<XDailyWorker>(1, java.util.concurrent.TimeUnit.DAYS)
                .setInitialDelay(delay, java.util.concurrent.TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(WORK, androidx.work.ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
