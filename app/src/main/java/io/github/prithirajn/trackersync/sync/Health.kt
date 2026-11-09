package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.net.AuthException
import io.github.prithirajn.trackersync.net.NetworkException

/**
 * The health lights on the Services screen: one per linked tracker and one for
 * crosspoint-sync. Green is fine, amber wants a look, red is broken, grey is switched off.
 */
object Health {
    enum class Level { GOOD, WARN, BAD, OFF }

    /** A light's colour, a short title, and what it means for the user. */
    data class Status(val level: Level, val title: String, val detail: String)

    /** crosspoint-sync not reached for this long (while signed in) turns the light amber. */
    const val STALE_MS = 3 * 60 * 60 * 1000L

    fun tracker(s: Snapshot, id: String, name: String): Status? {
        val svc = s.service(id)
        if (!svc.linked) return null
        val failing = s.pending.values.count { it.service == id && it.tries > 0 }
        val waiting = s.pending.values.count { it.service == id }
        val review = s.links.values.count { it.service == id && ((it.externalId == null && it.note == "Needs a match") || (it.externalId != null && !it.confirmed && it.source != io.github.prithirajn.trackersync.data.LinkSource.NONE)) }
        return when {
            svc.needsReauth -> Status(Level.BAD, "$name needs you to sign in again", svc.lastError ?: "Its sign-in expired, so nothing is being sent. Tap Sign in.")
            !svc.enabled -> Status(Level.OFF, "Sync to $name is off", "Nothing is sent to $name until you switch it back on.")
            svc.lastError != null -> Status(Level.BAD, "$name had a problem", svc.lastError + if (failing > 0) "\n$failing update${if (failing == 1) "" else "s"} will be retried." else "")
            failing > 0 -> Status(Level.WARN, "$name is retrying", "$failing update${if (failing == 1) "" else "s"} didn't go through and will be tried again.")
            review > 0 -> Status(Level.WARN, "$name needs a match", "$review book${if (review == 1) "" else "s"} couldn't be matched automatically. Open the book in Library to pick one.")
            waiting > 0 -> Status(Level.GOOD, "$name is working", "$waiting update${if (waiting == 1) "" else "s"} will go out on the next sync.")
            else -> Status(Level.GOOD, "$name is healthy", if (svc.lastSync > 0) "Everything is up to date." else "Linked. It syncs on the next pass.")
        }
    }

    fun source(s: Snapshot, now: Long = System.currentTimeMillis()): Status? {
        if (s.account == null) return null
        val error = s.sourceError
        return when {
            error != null -> Status(Level.BAD, "crosspoint-sync had a problem", error)
            s.sourceOkAt == 0L -> Status(Level.WARN, "crosspoint-sync not reached yet", "It's checked on the next sync. Tap to check now.")
            now - s.sourceOkAt > STALE_MS -> Status(Level.WARN, "crosspoint-sync not reached lately", "The last good sync was a while ago; the phone may have been offline. Tap to check now.")
            else -> Status(Level.GOOD, "crosspoint-sync is healthy", "Your reading is coming through.")
        }
    }

    fun kosync(s: Snapshot, now: Long = System.currentTimeMillis()): Status? {
        val k = s.kosync ?: return null
        val host = k.server.removePrefix("https://").removePrefix("http://")
        val error = s.kosyncError
        return when {
            error != null -> Status(Level.BAD, "KOSync server had a problem", error)
            s.kosyncOkAt == 0L -> Status(Level.WARN, "KOSync server not checked yet", "$host is checked on the next sync. Tap to check now.")
            now - s.kosyncOkAt > STALE_MS -> Status(Level.WARN, "KOSync server not reached lately", "The last good check of $host was a while ago; the phone may have been offline. Tap to check now.")
            else -> Status(Level.GOOD, "KOSync server is healthy", "Newer progress on $host reaches your trackers.")
        }
    }

    fun moon(s: Snapshot, now: Long = System.currentTimeMillis()): Status? {
        s.moon ?: return null
        val error = s.moonError
        return when {
            error == MOON_NO_FILES -> Status(Level.WARN, "No Moon+ Reader sync files yet", error)
            error != null -> Status(Level.BAD, "Moon+ Reader sync had a problem", error)
            s.moonOkAt == 0L -> Status(Level.WARN, "Moon+ Reader not checked yet", "It's checked on the next sync. Tap to check now.")
            now - s.moonOkAt > STALE_MS -> Status(Level.WARN, "Moon+ Reader not reached lately", "The last good check was a while ago; the phone may have been offline. Tap to check now.")
            else -> Status(Level.GOOD, "Moon+ Reader is healthy", "Newer progress in Moon+ Reader reaches your trackers.")
        }
    }

    fun koHl(s: Snapshot, now: Long = System.currentTimeMillis()): Status? {
        s.koHl ?: return null
        val error = s.koHlError
        return when {
            error == KOHL_NO_FILES -> Status(Level.WARN, "No KOReader highlight files yet", error)
            error != null -> Status(Level.BAD, "KOReader highlights had a problem", error)
            s.koHlOkAt == 0L -> Status(Level.WARN, "KOReader highlights not checked yet", "They're checked on the next sync. Tap to check now.")
            now - s.koHlOkAt > STALE_MS -> Status(Level.WARN, "KOReader highlights not reached lately", "The last good check was a while ago; the phone may have been offline. Tap to check now.")
            else -> Status(Level.GOOD, "KOReader highlights are healthy", "New highlights from KOReader come in on every sync.")
        }
    }

    fun koLocal(s: Snapshot): Status? {
        s.koLocal ?: return null
        val error = s.koLocalError
        return when {
            error == KOLOCAL_NO_FILES -> Status(Level.WARN, "No KOReader sidecars in that folder", error)
            error != null -> Status(Level.BAD, "Couldn't read KOReader's folder", error)
            s.koLocalOkAt == 0L -> Status(Level.WARN, "KOReader folder not read yet", "It's read on the next sync. Tap to read it now.")
            else -> Status(Level.GOOD, "KOReader folder is readable", "New highlights from KOReader on this device come in on every sync.")
        }
    }

    const val KOLOCAL_NO_FILES = "No KOReader sidecar folders (\u201C.sdr\u201D) found there. Pick the folder your books are in, or koreader/docsettings if KOReader keeps its book settings there. Open a book in KOReader once so it has one."

    const val KOHL_NO_FILES = "Connected, but no KOReader highlight files were found yet. Set HighlightSync or AnnotationSync to sync to this storage, sync a book in KOReader, or type the folder here."

    fun describeKoHl(e: Exception): String = when (e) {
        is AuthException -> "The WebDAV server rejected the email or app password. Remove KOReader highlights here and add them again with a new app password."
        is NetworkException -> "Couldn't reach the WebDAV server (${e.message ?: "offline"}). It's retried on the next sync."
        else -> e.message ?: "Something went wrong reading KOReader's highlight files."
    }

    const val MOON_NO_FILES = "Connected, but no Moon+ Reader sync files were found yet. In Moon+ Reader, turn on sync to WebDAV with the same address and account, then read a page of any book."

    fun describeMoon(e: Exception): String = when (e) {
        is AuthException -> "The WebDAV server rejected the email or app password. Remove Moon+ Reader here and add it again with a new app password."
        is NetworkException -> "Couldn't reach the WebDAV server (${e.message ?: "offline"}). It's retried on the next sync."
        else -> e.message ?: "Something went wrong reading Moon+ Reader's sync files."
    }

    fun describeKosync(e: Exception): String = when (e) {
        is AuthException -> "The KOSync server rejected your username or password. Remove it and add it again."
        is NetworkException -> "Couldn't reach the KOSync server (${e.message ?: "offline"}). It's retried on the next sync."
        else -> e.message ?: "Something went wrong reading the KOSync server."
    }

    /** What went wrong reading crosspoint-sync, in words for the light. */
    fun describeSource(e: Exception): String = when (e) {
        is AuthException -> "The server rejected your username or password. Sign out and sign in again."
        is NetworkException -> "Couldn't reach the server (${e.message ?: "offline"}). It's retried on the next sync."
        else -> e.message ?: "Something went wrong reading your progress."
    }

    fun kindle(s: Snapshot, now: Long = System.currentTimeMillis()): Status? {
        val k = s.kindle ?: return null
        val error = s.kindleError
        return when {
            !k.enabled -> Status(Level.OFF, "Kindle sync is off", "Nothing is read from ${k.domain} until you switch it back on.")
            error != null -> Status(Level.BAD, "Amazon Kindle needs attention", error)
            s.kindleOkAt == 0L -> Status(Level.WARN, "Amazon Kindle not checked yet", "${k.domain} is checked on the next sync. Tap to check now.")
            now - s.kindleOkAt > STALE_MS -> Status(Level.WARN, "Amazon Kindle not checked lately", "The last good check of ${k.domain} was a while ago. Tap to check now.")
            else -> Status(Level.GOOD, "Amazon Kindle is healthy", "Reading progress and highlights from ${k.domain} are syncing.")
        }
    }

    fun describeKindle(e: Exception): String = when (e) {
        is AuthException -> "Amazon requires you to sign in again. Tap Sign in on the Amazon Kindle card."
        is NetworkException -> "Couldn't reach Amazon Kindle (${e.message ?: "offline"}). It's retried on the next sync."
        else -> e.message ?: "Something went wrong reading your Amazon Kindle library."
    }

    fun mihon(s: Snapshot, now: Long = System.currentTimeMillis()): Status? {
        val m = s.mihon ?: return null
        val error = s.mihonError
        return when {
            !m.enabled -> Status(Level.OFF, "Mihon sync is off", "Its backups aren't read until you switch it back on.")
            error != null -> Status(Level.BAD, "Mihon needs attention", error)
            s.mihonOkAt == 0L -> Status(Level.WARN, "Mihon not read yet", "Its newest backup is read on the next sync. Tap to read it now.")
            m.backupAt > 0 && now / 1000 - m.backupAt > 3 * 86_400 -> Status(Level.WARN, "Mihon's backup is old", "The newest backup is from a few days ago. Mihon makes them on its own schedule (Settings \u2192 Data and storage \u2192 Automatic backups); daily keeps volumes current.")
            else -> Status(Level.GOOD, "Mihon is healthy", "Chapters you read in Mihon reach your trackers as volumes.")
        }
    }

    fun mangaUpdates(s: Snapshot): Status? {
        val m = s.mangaUpdates ?: return null
        val error = s.mangaUpdatesError
        return when {
            !m.enabled -> Status(Level.OFF, "MangaUpdates is off", "Chapters aren't sent until you switch it back on.")
            error != null -> Status(Level.BAD, "MangaUpdates needs attention", error)
            else -> Status(Level.GOOD, "MangaUpdates is linked", "The chapter you're on in Mihon is sent to your Reading list.")
        }
    }

    fun anilist(s: Snapshot): Status? {
        val a = s.anilist ?: return null
        val error = s.anilistError
        return when {
            !a.read && !a.send -> Status(Level.OFF, "AniList is off", "Your list isn't read and nothing is sent until you switch one back on.")
            error != null -> Status(Level.BAD, "AniList needs attention", error)
            s.anilistOkAt == 0L -> Status(Level.WARN, "AniList not read yet", "Your list is read on the next sync. Tap to read it now.")
            else -> Status(Level.GOOD, "AniList is linked as ${a.name}", "Chapters Mihon sends to AniList from any device reach your trackers as volumes and issues.")
        }
    }

    fun audible(s: Snapshot, now: Long = System.currentTimeMillis()): Status? {
        val a = s.audible ?: return null
        val label = "Audible ${a.store}"
        val error = s.audibleError
        return when {
            !a.enabled -> Status(Level.OFF, "Audible sync is off", "Nothing is read from $label until you switch it back on.")
            error != null -> Status(Level.BAD, "Audible needs attention", error)
            s.audibleOkAt == 0L -> Status(Level.WARN, "Audible not checked yet", "$label is checked on the next sync. Tap to check now.")
            now - s.audibleOkAt > STALE_MS -> Status(Level.WARN, "Audible not checked lately", "The last good check of $label was a while ago. Tap to check now.")
            else -> Status(Level.GOOD, "Audible is healthy", "What you're listening to on $label is syncing.")
        }
    }

    fun describeAudible(e: Exception): String = when (e) {
        is AuthException -> "Audible needs you to sign in again. Tap Sign in on the Audible page."
        is NetworkException -> "Couldn't reach Audible (${e.message ?: "offline"}). It's retried on the next sync."
        else -> e.message ?: "Something went wrong reading your Audible library."
    }

    fun libby(s: Snapshot, now: Long = System.currentTimeMillis()): Status? {
        val l = s.libby ?: return null
        val label = l.name ?: "Libby"
        val error = s.libbyError
        return when {
            !l.enabled -> Status(Level.OFF, "Libby sync is off", "Nothing is read from $label until you switch it back on.")
            error != null -> Status(Level.BAD, "Libby needs attention", error)
            s.libbyOkAt == 0L -> Status(Level.WARN, "Libby not checked yet", "$label is checked on the next sync. Tap to check now.")
            now - s.libbyOkAt > STALE_MS -> Status(Level.WARN, "Libby not checked lately", "The last good check of $label was a while ago. Tap to check now.")
            else -> Status(Level.GOOD, "Libby is healthy", "Loans, reading progress, and highlights from $label are syncing.")
        }
    }

    fun describeLibby(e: Exception): String = when (e) {
        is AuthException -> "Your Libby session expired. Tap Link on the Libby card to reconnect."
        is NetworkException -> "Couldn't reach Libby (${e.message ?: "offline"}). It's retried on the next sync."
        else -> e.message ?: "Something went wrong reading your Libby shelf."
    }
}
