package io.github.prithirajn.readync.trackers

import io.github.prithirajn.readync.net.AuthException
import io.github.prithirajn.readync.net.Http
import io.github.prithirajn.readync.util.long
import io.github.prithirajn.readync.util.obj
import io.github.prithirajn.readync.util.str
import org.json.JSONObject

/** Firebase email/password auth, used by Fable and Pagebound. */
internal object Firebase {
    data class Tokens(val idToken: String, val refreshToken: String, val expiresAt: Long, val email: String?)

    private fun message(body: String): String? =
        runCatching { JSONObject(body).obj("error")?.str("message") }.getOrNull()

    private fun friendly(code: String?): String = when {
        code == null -> "Sign-in failed"
        code.startsWith("INVALID_LOGIN_CREDENTIALS") || code.startsWith("INVALID_PASSWORD") ||
            code.startsWith("EMAIL_NOT_FOUND") -> "Wrong email or password"
        code.startsWith("TOO_MANY_ATTEMPTS") -> "Too many attempts; try again later"
        code.startsWith("USER_DISABLED") -> "This account is disabled"
        else -> code.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }

    fun signIn(url: String, body: JSONObject): Tokens {
        val r = Http.request("POST", url, mapOf("Content-Type" to "application/json"), body.toString())
        if (!r.ok) throw AuthException(friendly(message(r.body)))
        val o = JSONObject(r.body)
        val id = o.str("idToken") ?: throw AuthException("Sign-in returned no token")
        val refresh = o.str("refreshToken") ?: throw AuthException("Sign-in returned no token")
        val expiresIn = o.long("expiresIn") ?: 3600L
        return Tokens(id, refresh, now() + expiresIn, o.str("email"))
    }

    fun refresh(apiKey: String, refreshToken: String): Tokens {
        val r = Http.request(
            "POST",
            "https://securetoken.googleapis.com/v1/token?key=$apiKey",
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            Http.form("grant_type" to "refresh_token", "refresh_token" to refreshToken),
        )
        if (!r.ok) {
            // Only Google saying the sign-in itself is over means signing in again. Its token service
            // being busy or down for a moment (429, 5xx) or any other hiccup is kept for later instead.
            val why = message(r.body).orEmpty()
            val over = r.code in 400..403 && Regex("TOKEN_EXPIRED|INVALID_REFRESH_TOKEN|USER_DISABLED|USER_NOT_FOUND|INVALID_GRANT_TYPE|MISSING_REFRESH_TOKEN|PROJECT_NUMBER_MISMATCH").containsMatchIn(why)
            if (over) throw AuthException("Session expired; sign in again")
            throw io.github.prithirajn.readync.net.BusyException(
                "The sign-in service didn't answer properly (HTTP ${r.code}${if (why.isNotEmpty()) ": $why" else ""}); will try again",
                io.github.prithirajn.readync.net.BusyException.retryAfter(r),
            )
        }
        val o = runCatching { JSONObject(r.body) }.getOrNull()
            ?: throw io.github.prithirajn.readync.net.BusyException("The sign-in service sent something unreadable; will try again")
        val id = o.str("id_token") ?: throw AuthException("Session expired; sign in again")
        val refresh = o.str("refresh_token") ?: refreshToken
        val expiresIn = o.long("expires_in") ?: 3600L
        return Tokens(id, refresh, now() + expiresIn, null)
    }

    fun now(): Long = System.currentTimeMillis() / 1000
}
