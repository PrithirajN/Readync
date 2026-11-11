package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.net.Http
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

    /** Puts [png] up at a public address for a day, for Threads to copy. Returns that address. */
    fun upload(png: ByteArray): String {
        val boundary = "----readync" + System.nanoTime()
        val out = java.io.ByteArrayOutputStream()
        fun field(name: String, value: String) {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("reqtype", "fileupload")
        field("time", "24h")
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"fileToUpload\"; filename=\"quote.png\"\r\nContent-Type: image/png\r\n\r\n".toByteArray())
        out.write(png)
        out.write("\r\n--$boundary--\r\n".toByteArray())
        val r = Http.request(
            "POST", imageHost,
            mapOf("Content-Type" to "multipart/form-data; boundary=$boundary", "User-Agent" to Http.defaultUserAgent),
            raw = out.toByteArray(), timeoutMs = 90_000,
        )
        val url = r.body.trim()
        if (!r.ok || !url.startsWith("https://")) throw ThreadsException("Couldn't put the picture up for Threads (HTTP ${r.code})")
        return url
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
    fun postCarousel(userId: String, token: String, text: String, images: List<Pair<String, String?>>, topic: String? = null, spoiler: Boolean = false): String {
        if (images.size == 1) return post(userId, token, text, images[0].first, images[0].second, topic, spoiler)
        require(images.size in 2..20) { "A carousel takes 2 to 20 pictures" }
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
        return call("POST", "/v1.0/$userId/threads_publish", mapOf("creation_id" to container, "access_token" to token), 60_000)
            .optString("id").takeIf { it.isNotBlank() } ?: throw ThreadsException("Threads didn't publish the post")
    }

    /**
     * Posts [text] (with the picture at [imageUrl], when given) to your Threads. A picture takes
     * Threads a little while to fetch: waited for, up to about a minute. Returns the post's id.
     */
    /** A topic tag as Threads takes it: no full stops or ampersands, at most 50 characters. Null for none. */
    internal fun topicTag(t: String?): String? = t?.replace(Regex("[.&#]"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.take(50)?.takeIf { it.isNotEmpty() }

    fun post(userId: String, token: String, text: String, imageUrl: String?, altText: String? = null, topic: String? = null, spoiler: Boolean = false): String {
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
        return call("POST", "/v1.0/$userId/threads_publish", mapOf("creation_id" to container, "access_token" to token), 60_000)
            .optString("id").takeIf { it.isNotBlank() } ?: throw ThreadsException("Threads didn't publish the post")
    }
}
