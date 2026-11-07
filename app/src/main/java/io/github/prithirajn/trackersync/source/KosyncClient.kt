package io.github.prithirajn.trackersync.source

import io.github.prithirajn.trackersync.data.Account
import io.github.prithirajn.trackersync.net.AuthException
import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.net.NetworkException
import io.github.prithirajn.trackersync.net.ServiceException
import io.github.prithirajn.trackersync.util.long
import io.github.prithirajn.trackersync.util.num
import io.github.prithirajn.trackersync.util.str
import org.json.JSONObject

/** The newest position a KOSync server holds for one book. */
data class KosyncProgress(val document: String, val percentage: Double, val timestamp: Long, val device: String?)

/**
 * A plain KOSync server (KOReader's sync.koreader.rocks, a self-hosted koreader-sync-server,
 * or any compatible one). The protocol can only check a login and read or write one book's
 * position by its KOReader document hash: no list of books, no titles. So it's used for books
 * the app already knows from crosspoint-sync.
 */
class KosyncClient(private val account: Account) {

    private fun headers() = mapOf(
        "x-auth-user" to account.username,
        "x-auth-key" to account.key,
        // Stock koreader-sync-server refuses requests without KOReader's media type.
        "Accept" to "application/vnd.koreader.v1+json",
    )

    /** True when the username and password are right. */
    fun verify(): Boolean {
        val r = Http.request("GET", account.server + "/users/auth", headers())
        if (r.code == 401 || r.code == 403) return false
        if (!r.ok) throw ServiceException("The KOSync server answered HTTP ${r.code}")
        return true
    }

    /** The server's newest position for [document], or null when it has none ("{}"). */
    fun progress(document: String): KosyncProgress? {
        val r = Http.request("GET", account.server + "/syncs/progress/" + Http.enc(document), headers())
        if (r.code == 401) throw AuthException("The KOSync server rejected the username or password")
        if (r.code == 404) return null
        if (!r.ok) throw ServiceException("The KOSync server answered HTTP ${r.code}")
        val o = runCatching { JSONObject(r.body) }.getOrNull() ?: return null
        val pct = o.num("percentage") ?: return null
        val ts = o.long("timestamp") ?: return null
        return KosyncProgress(document, pct.coerceIn(0.0, 1.0), ts, o.str("device"))
    }

    /**
     * Writes a position for [document]. Only a percentage is known here, so the position string
     * is a placeholder like crosspoint-sync's own imports use; readers that place by percentage
     * follow it, KOReader may stay where it is.
     */
    fun putProgress(document: String, percentage: Double, deviceId: String) {
        val p = percentage.coerceIn(0.0, 1.0)
        val body = JSONObject()
            .put("document", document)
            .put("progress", "tracker-sync:" + Math.round(p * 1_000_000))
            .put("percentage", p)
            .put("device", "Tracker Sync")
            .put("device_id", deviceId)
        val r = Http.request("PUT", account.server + "/syncs/progress", headers() + ("Content-Type" to "application/json"), body.toString())
        if (r.code == 401) throw AuthException("The KOSync server rejected the username or password")
        if (!r.ok) throw ServiceException("The KOSync server answered HTTP ${r.code}")
    }

    companion object {
        const val SUGGESTED_SERVER = "https://sync.koreader.rocks"

        /**
         * Signs in: tries the address as given (https first when no scheme is typed) and
         * returns the account that worked.
         */
        fun signIn(input: String, username: String, password: String): Account {
            val raw = input.trim().trimEnd('/').ifEmpty { SUGGESTED_SERVER }
            val candidates = if (raw.startsWith("http://") || raw.startsWith("https://")) listOf(raw) else listOf("https://$raw", "http://$raw")
            var lastError: Exception? = null
            for (base in candidates) {
                val account = Account(base, username.trim(), CrossPointClient.md5(password))
                try {
                    if (KosyncClient(account).verify()) return account
                    throw ServiceException("Wrong username or password")
                } catch (e: NetworkException) {
                    lastError = e
                }
            }
            throw ServiceException("Couldn't reach $raw (${lastError?.message ?: "no answer"})")
        }
    }
}
