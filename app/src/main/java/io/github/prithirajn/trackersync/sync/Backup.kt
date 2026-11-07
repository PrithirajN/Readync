package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.data.Secrets
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.data.Store
import io.github.prithirajn.trackersync.net.ServiceException
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import io.github.prithirajn.trackersync.Edition

/**
 * Everything in the app (accounts, tracker sign-ins, matches, physical books, highlights,
 * settings) in one file, encrypted with a password you choose: PBKDF2-SHA256 to an AES-256
 * key, AES-GCM to seal. The phone's own Keystore key can't leave the phone, so a backup
 * carries the secrets under the password instead.
 */
object Backup {
    private const val FORMAT = "tracker-sync-backup"
    private const val ITERATIONS = 150_000

    /** Inside the sealed file, secrets are plain: the whole file is what's encrypted. */
    private object Open : Secrets {
        override fun seal(plain: String) = plain
        override fun open(sealed: String): String? = sealed
    }

    private fun key(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    fun export(s: Snapshot, password: String): ByteArray {
        if (password.length < 6) throw ServiceException("Use a password of at least 6 characters")
        val plain = Store.encode(s.copy(running = false), Open).put("exportedAt", System.currentTimeMillis()).toString().toByteArray()
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(128, iv))
        val sealed = cipher.doFinal(plain)
        val b64 = Base64.getEncoder()
        return JSONObject()
            .put("format", FORMAT).put("v", 1)
            .put("kdf", "PBKDF2WithHmacSHA256").put("iterations", ITERATIONS)
            .put("salt", b64.encodeToString(salt)).put("iv", b64.encodeToString(iv))
            .put("data", b64.encodeToString(sealed))
            .toString().toByteArray()
    }

    fun restore(bytes: ByteArray, password: String): Snapshot {
        val o = runCatching { JSONObject(String(bytes)) }.getOrNull()
        if (o == null || o.optString("format") != FORMAT) throw ServiceException("That isn't a Tracker Sync backup")
        val b64 = Base64.getDecoder()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val plain = try {
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(password, b64.decode(o.getString("salt")), o.optInt("iterations", ITERATIONS)),
                GCMParameterSpec(128, b64.decode(o.getString("iv"))),
            )
            cipher.doFinal(b64.decode(o.getString("data")))
        } catch (e: AEADBadTagException) {
            throw ServiceException("Wrong password, or the file is damaged")
        }
        return Store.decode(JSONObject(String(plain)), Open).copy(running = false)
    }
}




object AppUpdater {
    // Each edition updates from its own repo: yours from the private one, the shared (lite) one
    // from the public repo PrithirajN/Readync, which needs no GitHub token.
    val DEFAULT_REPO: String = if (Edition.full) "PrithirajN/tracker-sync" else "PrithirajN/Readync"

    data class Release(
        val tagName: String,
        val versionName: String,
        val name: String,
        val notes: String,
        val assetId: Long,
        val assetName: String,
        val downloadUrl: String,
        val isNewer: Boolean,
    )

    sealed class CheckResult {
        data class Available(val release: Release) : CheckResult()
        data class UpToDate(val currentVersion: String) : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    /**
     * Compares version numbers (e.g. "1.2.1" vs "1.2.0").
     * Returns true if candidate is newer than current.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val cleanCandidate = candidate.trim().removePrefix("v").substringBefore("-")
        val cleanCurrent = current.trim().removePrefix("v").substringBefore("-")
        val cParts = cleanCandidate.split(".").mapNotNull { it.toIntOrNull() }
        val curParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(cParts.size, curParts.size)
        for (i in 0 until maxLen) {
            val c = cParts.getOrElse(i) { 0 }
            val cur = curParts.getOrElse(i) { 0 }
            if (c > cur) return true
            if (c < cur) return false
        }
        return false
    }

    fun checkUpdate(repo: String = DEFAULT_REPO, token: String?, currentVersion: String): CheckResult {
        val cleanRepo = repo.trim().ifEmpty { DEFAULT_REPO }
        val url = "https://api.github.com/repos/$cleanRepo/releases/latest"
        val headers = HashMap<String, String>().apply {
            put("Accept", "application/vnd.github+json")
            put("User-Agent", "TrackerSync-App")
            if (!token.isNullOrBlank()) {
                put("Authorization", "Bearer ${token.trim()}")
            }
        }
        val res = try {
            Http.request("GET", url, headers = headers, timeoutMs = 15_000)
        } catch (e: Exception) {
            return CheckResult.Error(e.message ?: "Network error checking for updates")
        }

        if (res.code == 404) {
            return if (token.isNullOrBlank()) {
                CheckResult.Error("Repository not found or private. Enter your GitHub Personal Access Token.")
            } else {
                CheckResult.Error("No releases found on $cleanRepo, or token lacks access.")
            }
        }
        if (res.code == 401 || res.code == 403) {
            return CheckResult.Error("GitHub token rejected or expired (HTTP ${res.code}).")
        }
        if (!res.ok) {
            return CheckResult.Error("GitHub returned HTTP ${res.code}.")
        }

        val json = try {
            JSONObject(res.body)
        } catch (e: Exception) {
            return CheckResult.Error("Invalid response from GitHub Releases API.")
        }

        val tag = json.optString("tag_name", "").ifEmpty { json.optString("name", "") }
        val name = json.optString("name", tag)
        val body = json.optString("body", "")
        val assets = json.optJSONArray("assets") ?: return CheckResult.Error("No APK attached to release $tag.")

        var apkAsset: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val aname = a.optString("name", "")
            if (aname.endsWith(".apk", ignoreCase = true)) {
                apkAsset = a
                break
            }
        }
        if (apkAsset == null) {
            return CheckResult.Error("Release $tag has no .apk file attached.")
        }

        val assetId = apkAsset.optLong("id", 0L)
        val assetName = apkAsset.optString("name", "TrackerSync.apk")
        val downloadUrl = apkAsset.optString("browser_download_url", "")
        val candidateVersion = tag.removePrefix("v").trim()
        val newer = isNewer(candidateVersion, currentVersion)

        val release = Release(
            tagName = tag,
            versionName = candidateVersion,
            name = name,
            notes = body,
            assetId = assetId,
            assetName = assetName,
            downloadUrl = downloadUrl,
            isNewer = newer,
        )

        return if (newer) CheckResult.Available(release) else CheckResult.UpToDate(currentVersion)
    }

    /** Your repo's latest release page, where its APK can be downloaded by hand. */
    fun releasesPage(repo: String): String = "https://github.com/${repo.trim().ifEmpty { DEFAULT_REPO }}/releases/latest"

    /** The repo's address for Obtainium ("https://github.com/owner/repo"). */
    fun repoUrl(repo: String): String = "https://github.com/${repo.trim().ifEmpty { DEFAULT_REPO }}"

    /**
     * Opens Obtainium with this app's repo, ready to add (it installs and updates the app from
     * your GitHub releases). Its add link is obtainium://add/<repo address>.
     */
    fun obtainiumLink(repo: String): String = "obtainium://add/" + repoUrl(repo)

    /** Where to get Obtainium when it isn't installed. */
    const val OBTAINIUM_DOWNLOAD = "https://github.com/ImranR98/Obtainium/releases/latest"
}
