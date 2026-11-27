package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.net.Http
import org.json.JSONObject

/**
 * Posting to Threads through Meta's Threads API, with an access token from your own Meta app (you
 * as its tester, so no review is needed). Threads takes a picture only from a public web address,
 * so the picture is first put up for a day on Litterbox (catbox.moe's short-lived file host), and
 * Threads copies it from there.
 */
object ThreadsClient {
    var api = "https://graph.threads.net"
    var imageHost = "https://litterbox.catbox.moe/resources/internals/api.php"

    /** Threads' longest post. */
    const val MAX_TEXT = 500

    class ThreadsException(message: String, val signIn: Boolean = false) : Exception(message)

    data class Me(val id: String, val username: String?)

    private fun call(method: String, path: String, params: Map<String, String>, timeoutMs: Int = 30_000): JSONObject {
        val q = params.entries.joinToString("&") { "${it.key}=${Http.enc(it.value)}" }
        val r = if (method == "GET") Http.request("GET", "$api$path?$q", mapOf("Accept" to "application/json"), timeoutMs = timeoutMs)
            else Http.request("POST", "$api$path", mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"), body = q, timeoutMs = timeoutMs)
        val o = runCatching { JSONObject(r.body) }.getOrNull()
        val err = o?.optJSONObject("error")
        if (!r.ok || o == null || err != null) {
            val msg = err?.optString("message")?.takeIf { it.isNotBlank() } ?: "Threads answered HTTP ${r.code}"
            // 190: the token expired or was revoked.
            throw ThreadsException(msg, signIn = err?.optInt("code") == 190 || r.code == 401)
        }
        return o
    }

    /** Your last 50 threads: id, words, picture description, when. Empty when Threads can't be asked. */
    private fun seen(userId: String, token: String): List<DupGuard.Seen> {
        fun read(fields: String) = call("GET", "/v1.0/$userId/threads", mapOf("fields" to fields, "limit" to "50", "access_token" to token)).optJSONArray("data")
        val a = runCatching { read("id,text,timestamp,alt_text") }.getOrNull() ?: runCatching { read("id,text,timestamp") }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { m ->
            DupGuard.Seen(m.optString("id"), m.optString("text").takeIf { it.isNotBlank() && it != "null" },
                listOfNotNull(m.optString("alt_text").takeIf { it.isNotBlank() && it != "null" }), DupGuard.time(m.optString("timestamp")))
        }
    }

    /** The id of this post if it's already on your Threads (from this phone or another), logged; null to post it. */
    private fun already(userId: String, token: String, text: String, passages: List<String?>, store: io.github.prithirajn.readync.data.Store?): String? {
        val hit = DupGuard.find(seen(userId, token), text, passages) ?: return null
        store?.log(null, "Already on Threads, so not posted again: “${text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(70)}”")
        return hit.id
    }

    /** Whose token this is. */
    fun me(token: String): Me {
        val o = call("GET", "/v1.0/me", mapOf("fields" to "id,username", "access_token" to token))
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: throw ThreadsException("Threads didn't say whose token this is")
        return Me(id, o.optString("username").takeIf { it.isNotBlank() })
    }

    /** A one-hour token made into a 60-day one (needs your Meta app's Threads secret). */
    fun exchange(token: String, secret: String): String =
        call("GET", "/access_token", mapOf("grant_type" to "th_exchange_token", "client_secret" to secret, "access_token" to token))
            .optString("access_token").takeIf { it.isNotBlank() } ?: throw ThreadsException("Threads didn't give a longer-lasting token")

    /** A 60-day token renewed for another 60 days (once it's a day old). */
    fun refresh(token: String): String =
        call("GET", "/refresh_access_token", mapOf("grant_type" to "th_refresh_token", "access_token" to token))
            .optString("access_token").takeIf { it.isNotBlank() } ?: throw ThreadsException("Threads didn't renew the token")

    /** A short-lived public file host: its name, and how to put a PNG up there (returns its address, or throws). */
    private class Host(val name: String, val put: (ByteArray, String, String) -> String)

    private fun multipart(url: String, fields: Map<String, String>, fileField: String, png: ByteArray, fileName: String = "quote.png", type: String = "image/png"): io.github.prithirajn.readync.net.HttpResponse {
        val boundary = "----readync" + System.nanoTime()
        val out = java.io.ByteArrayOutputStream()
        for ((k, v) in fields) out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n".toByteArray())
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"\r\nContent-Type: $type\r\n\r\n".toByteArray())
        out.write(png)
        out.write("\r\n--$boundary--\r\n".toByteArray())
        return Http.request(
            "POST", url,
            mapOf("Content-Type" to "multipart/form-data; boundary=$boundary", "User-Agent" to "Readync/1.0 (Android)", "Accept" to "*/*"),
            raw = out.toByteArray(), timeoutMs = if (png.size > 4_000_000) 240_000 else 90_000,
        )
    }

    private fun failed(name: String, r: io.github.prithirajn.readync.net.HttpResponse): Nothing =
        throw ThreadsException("$name answered HTTP ${r.code}" + (r.body.trim().take(80).takeIf { it.isNotEmpty() && !it.startsWith("<") }?.let { " ($it)" } ?: ""))

    /**
     * Where pictures can go, in order: Litterbox (catbox.moe, a day), Catbox (kept), Uguu (3 hours), tmpfiles.org
     * (an hour). Threads copies a picture within a minute, so any of them is long enough. Some
     * networks or regions get refused by one (an HTTP 403), so the next is tried.
     */
    private val hosts = listOf(
        Host("Litterbox") { png, name, type ->
            val r = multipart(imageHost, mapOf("reqtype" to "fileupload", "time" to "24h"), "fileToUpload", png, name, type)
            r.body.trim().takeIf { r.ok && it.startsWith("https://") } ?: failed("Litterbox", r)
        },
        // catbox.moe itself (kept, not a day) on the same domain, for when Litterbox refuses (HTTP 403).
        Host("Catbox") { png, name, type ->
            val r = multipart("https://catbox.moe/user/api.php", mapOf("reqtype" to "fileupload"), "fileToUpload", png, name, type)
            r.body.trim().takeIf { r.ok && it.startsWith("https://") } ?: failed("Catbox", r)
        },
        Host("Uguu") { png, name, type ->
            val r = multipart("https://uguu.se/upload", emptyMap(), "files[]", png, name, type)
            val url = runCatching { JSONObject(r.body).optJSONArray("files")?.optJSONObject(0)?.optString("url") }.getOrNull()
            url?.takeIf { r.ok && it.startsWith("https://") } ?: failed("Uguu", r)
        },
        Host("tmpfiles.org") { png, name, type ->
            val r = multipart("https://tmpfiles.org/api/v1/upload", emptyMap(), "file", png, name, type)
            val url = runCatching { JSONObject(r.body).optJSONObject("data")?.optString("url") }.getOrNull()
            // Its page address; the file itself is under /dl/.
            url?.takeIf { r.ok && it.contains("tmpfiles.org/") }?.replace("http://", "https://")?.replaceFirst("tmpfiles.org/", "tmpfiles.org/dl/") ?: failed("tmpfiles.org", r)
        },
        // Two more on other domains, for networks (or a private DNS / ad blocker) that block the others.
        Host("qu.ax") { png, name, type ->
            val r = multipart("https://qu.ax/upload.php", emptyMap(), "files[]", png, name, type)
            val url = runCatching { JSONObject(r.body).optJSONArray("files")?.optJSONObject(0)?.optString("url") }.getOrNull()
            url?.takeIf { r.ok && it.startsWith("https://") } ?: failed("qu.ax", r)
        },
        Host("x0.at") { png, name, type ->
            val r = multipart("https://x0.at/", emptyMap(), "file", png, name, type)
            r.body.trim().lines().firstOrNull { it.startsWith("https://") || it.startsWith("http://") }?.trim()?.replace("http://", "https://")
                ?.takeIf { r.ok } ?: failed("x0.at", r)
        },
    )

    /** The host that worked last, tried first next time. */
    @Volatile private var lastGood = 0

    /** Puts [png] up at a public address for a while, for Threads to copy. Returns that address. */
    fun upload(png: ByteArray): String = upload(png, "quote.png", "image/png")

    /** Any file (a JPEG, a video) put up the same way, under [fileName] as [type]. */
    fun upload(bytes: ByteArray, fileName: String, type: String): String {
        val errors = ArrayList<String>()
        var unreachable = 0
        // Twice round: a dropped connection or a phone between networks usually clears in a few seconds.
        for (round in 0..1) {
            if (round == 1) { if (unreachable == 0) break; Thread.sleep(6_000); errors.clear(); unreachable = 0 }
            for (i in hosts.indices.sortedBy { if (it == lastGood) -1 else it }) {
                try {
                    return hosts[i].put(bytes, fileName, type).also { lastGood = i }
                } catch (e: Exception) {
                    if (io.github.prithirajn.readync.net.Http.isOffline(e)) unreachable++
                    errors += "${hosts[i].name}: " + (e.message ?: "failed")
                }
            }
        }
        // None of them reachable by name: the phone's internet, or its DNS (a Private DNS or ad blocker
        // that blocks file hosts), not the hosts themselves.
        val why = if (unreachable == hosts.size)
            "Couldn't reach any picture host for Threads. Check the internet; if it's on, a Private DNS or ad blocker may be blocking file hosts (Settings › Network › Private DNS)."
        else "Couldn't put the picture up for Threads"
        throw ThreadsException("$why (" + errors.joinToString("; ") + ")")
    }

    /** Waits until Threads has fetched a picture for [container] (up to about a minute). */
    private fun waitReady(container: String, token: String) {
        for (i in 0 until 14) {
            Thread.sleep(if (i == 0) 6_000 else 5_000)
            val st = runCatching { call("GET", "/v1.0/$container", mapOf("fields" to "status,error_message", "access_token" to token)) }.getOrNull() ?: continue
            when (st.optString("status")) {
                "FINISHED" -> return
                "ERROR", "EXPIRED" -> throw ThreadsException("Threads couldn't use a picture" + (st.optString("error_message").takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
            }
        }
    }

    /**
     * Posts [text] with several pictures to swipe through (a carousel, 2 to 20), each with its
     * description. Returns the post's id.
     */
    fun postCarousel(userId: String, token: String, text: String, images: List<Pair<String, String?>>, topic: String? = null, spoiler: Boolean = false, store: io.github.prithirajn.readync.data.Store? = null): String {
        if (images.size == 1) return post(userId, token, text, images[0].first, images[0].second, topic, spoiler, store)
        require(images.size in 2..20) { "A carousel takes 2 to 20 pictures" }
        already(userId, token, text, images.map { it.second }, store)?.let { return it }
        // One at a time, in order: Threads shows a carousel's pictures in the order of its children.
        val children = images.map { (url, alt) ->
            val params = linkedMapOf("access_token" to token, "media_type" to "IMAGE", "image_url" to url, "is_carousel_item" to "true")
            alt?.takeIf { it.isNotBlank() }?.let { params["alt_text"] = it.take(1000) }
            call("POST", "/v1.0/$userId/threads", params, 60_000).optString("id").takeIf { it.isNotBlank() } ?: throw ThreadsException("Threads didn't take a picture")
        }
        check(children.size == images.size) { "A picture went missing" }
        // Each picture fetched before they're put together.
        for (c in children) waitReady(c, token)
        val container = call("POST", "/v1.0/$userId/threads", linkedMapOf(
            "access_token" to token, "media_type" to "CAROUSEL", "children" to children.joinToString(","), "text" to text.take(MAX_TEXT),
        ).also { p -> topicTag(topic)?.let { p["topic_tag"] = it }; if (spoiler) p["is_spoiler_media"] = "true" }, 60_000).optString("id").takeIf { it.isNotBlank() } ?: throw ThreadsException("Threads didn't take the post")
        runCatching { waitReady(container, token) }
        Socials.PublishMark.mark()
        return call("POST", "/v1.0/$userId/threads_publish", mapOf("creation_id" to container, "access_token" to token), 60_000)
            .optString("id").takeIf { it.isNotBlank() } ?: throw ThreadsException("Threads didn't publish the post")
    }

    /**
     * Posts [text] (with the picture at [imageUrl], when given) to your Threads. A picture takes
     * Threads a little while to fetch: waited for, up to about a minute. Returns the post's id.
     */
    /** A topic tag as Threads takes it: no full stops or ampersands, at most 50 characters. Null for none. */
    internal fun topicTag(t: String?): String? = t?.replace(Regex("[.&#]"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.take(50)?.takeIf { it.isNotEmpty() }

    fun post(userId: String, token: String, text: String, imageUrl: String?, altText: String? = null, topic: String? = null, spoiler: Boolean = false, store: io.github.prithirajn.readync.data.Store? = null): String {
        already(userId, token, text, listOf(altText), store)?.let { return it }
        val params = linkedMapOf("access_token" to token, "text" to text.take(MAX_TEXT), "media_type" to if (imageUrl != null) "IMAGE" else "TEXT")
        imageUrl?.let { params["image_url"] = it }
        // The picture described for screen readers (the passage it shows).
        if (imageUrl != null) altText?.takeIf { it.isNotBlank() }?.let { params["alt_text"] = it.take(1000) }
        // One topic per post, shown with it on Threads.
        topicTag(topic)?.let { params["topic_tag"] = it }
        // Behind Threads' tap-to-reveal when the picture gives the story away.
        if (spoiler && imageUrl != null) params["is_spoiler_media"] = "true"
        val container = call("POST", "/v1.0/$userId/threads", params, 60_000).optString("id").takeIf { it.isNotBlank() }
            ?: throw ThreadsException("Threads didn't take the post")
        if (imageUrl != null) {
            var ready = false
            for (i in 0 until 14) {
                Thread.sleep(if (i == 0) 6_000 else 5_000)
                val st = runCatching { call("GET", "/v1.0/$container", mapOf("fields" to "status,error_message", "access_token" to token)) }.getOrNull() ?: continue
                when (st.optString("status")) {
                    "FINISHED" -> { ready = true; break }
                    "ERROR", "EXPIRED" -> throw ThreadsException("Threads couldn't use the picture" + (st.optString("error_message").takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
                }
            }
            // Not said to be ready in time: publishing usually works anyway; it says if not.
            if (!ready) Thread.sleep(5_000)
        }
        Socials.PublishMark.mark()
        return call("POST", "/v1.0/$userId/threads_publish", mapOf("creation_id" to container, "access_token" to token), 60_000)
            .optString("id").takeIf { it.isNotBlank() } ?: throw ThreadsException("Threads didn't publish the post")
    }
}
