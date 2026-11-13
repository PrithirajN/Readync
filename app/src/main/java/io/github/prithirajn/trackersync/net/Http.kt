package io.github.prithirajn.trackersync.net

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A finished HTTP exchange. Header names are lowercased. */
class HttpResponse(
    val code: Int,
    val headers: Map<String, List<String>>,
    val body: String,
    val url: String,
) {
    val ok: Boolean get() = code in 200..299
    val isRedirect: Boolean get() = code in 300..399
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
    fun headerAll(name: String): List<String> = headers[name.lowercase()] ?: emptyList()
}

/** The request never got an HTTP answer (offline, DNS, timeout, TLS). */
class NetworkException(message: String, cause: Throwable? = null) : Exception(friendly(message, cause), cause)

/** A refused certificate said in words, with what to do (a home server's own certificate, usually). */
private fun friendly(message: String, cause: Throwable?): String {
    var e: Throwable? = cause
    while (e != null) {
        if (e is javax.net.ssl.SSLHandshakeException || e is java.security.cert.CertPathValidatorException || e is java.security.cert.CertificateException) {
            return "The server's certificate isn't trusted (a home server's own certificate?). Install it on this phone as a CA certificate (Settings › Security › Encryption & credentials), or use the server's http:// address on your own network."
        }
        if (e is javax.net.ssl.SSLPeerUnverifiedException) {
            return "The server's certificate is for a different name than the address you entered. Use the name on the certificate, or the server's http:// address on your own network."
        }
        e = e.cause
    }
    return message
}

/** The service rejected the saved login: the user has to sign in again. */
class AuthException(message: String) : Exception(message)

/** The service answered, but not with what we needed. */
class ServiceException(message: String) : Exception(message)

/**
 * Not an error: the update can't be made for a reason only the user can fix on the
 * tracker (e.g. no page count). Shown as a note on the book, not retried.
 */
class SkipException(message: String) : Exception(message)

object Http {
    const val BROWSER_USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UD1A.230803.041) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    /** This phone's browser (set when the app starts); the fixed one only until then. */
    var defaultUserAgent: String = BROWSER_USER_AGENT

    /**
     * The client hints a Chromium browser sends with [ua] (its brands and version, phone, Android),
     * so a request's hints always agree with its user agent. None for a user agent that isn't Chromium's.
     */
    fun clientHints(ua: String): Map<String, String> {
        val v = Regex("Chrome/(\\d+)").find(ua)?.groupValues?.get(1) ?: return emptyMap()
        val brand = if (ua.contains("; wv")) "Android WebView" else "Google Chrome"
        return mapOf(
            "sec-ch-ua" to "\"$brand\";v=\"$v\", \"Chromium\";v=\"$v\", \"Not?A_Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to if (ua.contains("Mobile")) "?1" else "?0",
            "sec-ch-ua-platform" to if (ua.contains("Android")) "\"Android\"" else "\"Linux\"",
        )
    }

    private fun executeSingle(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Int,
        raw: ByteArray?,
    ): HttpResponse {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw NetworkException(e.message ?: "Couldn't open $url", e)
        }
        try {
            try {
                conn.requestMethod = method
            } catch (e: java.net.ProtocolException) {
                // Android's client takes PATCH; a plain JVM one doesn't, so set the field it checks.
                runCatching {
                    val f = HttpURLConnection::class.java.getDeclaredField("method")
                    f.isAccessible = true
                    f.set(conn, method)
                    conn.javaClass.declaredFields.firstOrNull { it.name == "delegate" }?.let { d ->
                        d.isAccessible = true
                        (d.get(conn) as? HttpURLConnection)?.let { inner -> f.set(inner, method) }
                    }
                }.getOrElse { throw e }
            }
            conn.instanceFollowRedirects = false
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                conn.setRequestProperty("User-Agent", defaultUserAgent)
            }
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null || raw != null) {
                val bytes = raw ?: body!!.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val hs = HashMap<String, List<String>>()
            for ((k, v) in conn.headerFields) {
                if (k != null) hs[k.lowercase()] = v
            }
            return HttpResponse(code, hs, text, conn.url.toString())
        } catch (e: IOException) {
            throw NetworkException(e.message ?: "Network error", e)
        } finally {
            conn.disconnect()
        }
    }

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        followRedirects: Boolean = true,
        timeoutMs: Int = 20_000,
        /** A binary body (a multipart upload with an image), sent instead of [body]. */
        raw: ByteArray? = null,
    ): HttpResponse {
        var currentUrl = url
        var currentMethod = method
        var currentBody = body
        var currentRaw = raw
        var currentHeaders = headers
        var redirectCount = 0
        val maxRedirects = if (followRedirects) 5 else 0

        while (true) {
            val r = executeSingle(currentMethod, currentUrl, currentHeaders, currentBody, timeoutMs, currentRaw)
            if (followRedirects && r.isRedirect && redirectCount < maxRedirects) {
                val location = r.header("location")
                if (!location.isNullOrBlank()) {
                    redirectCount++
                    val nextUrl = try {
                        URL(URL(currentUrl), location).toString()
                    } catch (e: Exception) {
                        return r
                    }
                    val from = URL(currentUrl)
                    val to = URL(nextUrl)
                    // Never from https down to plain http: the redirect is handed back instead.
                    if (from.protocol == "https" && to.protocol != "https") return r
                    // Sign-ins stay with the site they belong to: dropped when the redirect leaves it.
                    if (!from.host.equals(to.host, ignoreCase = true)) currentHeaders = withoutCredentials(currentHeaders)
                    if (r.code in listOf(301, 302, 303) && currentMethod != "GET" && currentMethod != "HEAD") {
                        currentMethod = "GET"
                        currentBody = null
                        currentRaw = null
                    }
                    currentUrl = nextUrl
                    continue
                }
            }
            return r
        }
    }

    private val credentialHeader = Regex("(?i)^(authorization|proxy-authorization|cookie)$|auth|token|api-?key|session|csrf")

    /** [headers] without anything that carries a sign-in (Authorization, Cookie, API keys, tokens). */
    internal fun withoutCredentials(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { !credentialHeader.containsMatchIn(it) }

    /**
     * Runs [send] and, if the service answered with a gateway error (502/503/504: the request
     * didn't get through, e.g. a server waking up), waits and tries once more. Other errors
     * aren't retried, since the request may already have taken effect.
     */
    fun retryGateway(send: () -> HttpResponse): HttpResponse {
        val r = send()
        if (r.code !in 502..504) return r
        Thread.sleep(2_000)
        return send()
    }

    /**
     * Downloads a file (a cover image, a book). Follows cross-protocol redirects up to 5 times;
     * [headers] (a sign-in) go only to the first site, never to one it redirects to. Returns null on any failure.
     */
    fun bytes(url: String, maxBytes: Int = 4_000_000, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15_000): ByteArray? = try {
        val firstHost = URL(url).host
        var currentUrl = url
        var redirects = 0
        var result: ByteArray? = null
        while (redirects < 5) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", defaultUserAgent)
                if (URL(currentUrl).host == firstHost) for ((k, v) in headers) conn.setRequestProperty(k, v)
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    if (loc.isNullOrBlank()) break
                    currentUrl = URL(URL(currentUrl), loc).toString()
                    redirects++
                    continue
                }
                if (code !in 200..299) {
                    break
                }
                result = conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) return null
                    }
                    out.toByteArray()
                }
                break
            } finally {
                conn.disconnect()
            }
        }
        result
    } catch (e: IOException) {
        null
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
}

/**
 * A small cookie jar: name -> value, sent as one Cookie header, updated from
 * Set-Cookie responses. Expiry and paths are ignored; each tracker uses one host.
 */
class CookieJar(initial: Map<String, String> = emptyMap()) {
    private val cookies = LinkedHashMap(initial)

    val values: Map<String, String> get() = LinkedHashMap(cookies)

    operator fun get(name: String): String? = cookies[name]

    fun header(): String = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** Returns true when a cookie changed. */
    fun absorb(response: HttpResponse): Boolean {
        var changed = false
        for (line in response.headerAll("set-cookie")) {
            val pair = line.substringBefore(';')
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            val deleted = value.isEmpty() || line.contains("max-age=0", ignoreCase = true) ||
                line.contains("expires=Thu, 01 Jan 1970", ignoreCase = true)
            if (deleted) {
                if (cookies.remove(name) != null) changed = true
            } else if (cookies[name] != value) {
                cookies[name] = value
                changed = true
            }
        }
        return changed
    }

    companion object {
        fun parse(header: String): CookieJar {
            val map = LinkedHashMap<String, String>()
            for (part in header.split(';')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                map[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
            }
            return CookieJar(map)
        }
    }
}
