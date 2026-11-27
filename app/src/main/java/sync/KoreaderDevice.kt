package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.data.Snapshot

/**
 * Telling KOReader's positions from a CrossPoint device's. Every position carries the sending
 * app's name and id: a CrossPoint device always sends "CrossPoint" / "crosspoint-reader" (CrossInk
 * builds an id starting "crossink-"), crosspoint-sync's own copies and imports say so, and this
 * app sends "Tracker Sync". Anything else came through KOReader's sync (KOReader, or an app
 * using the same sync, like Readest).
 */
object KoreaderDevice {

    private val notKoreaderNames = setOf("crosspoint", "crosspoint sync", "tracker sync", "readync", "bookorbit")
    private val notKoreaderIds = listOf("crosspoint-", "crossink-", "tracker-sync", "readync", "connector:")

    /** Whether a position from [device] / [deviceId] came from KOReader's side. Unknown (no name) isn't. */
    fun isKoreader(device: String?, deviceId: String?): Boolean {
        val name = device?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        if (name in notKoreaderNames) return false
        val id = deviceId?.trim()?.lowercase().orEmpty()
        return notKoreaderIds.none { id.startsWith(it) }
    }

    /** [s] with [docs] marked as read in KOReader. */
    fun seen(s: Snapshot, docs: Collection<String>, now: Long = System.currentTimeMillis()): Snapshot {
        if (docs.isEmpty() || s.koreaderDocs.containsAll(docs)) return s
        return s.copy(koreaderDocs = s.koreaderDocs + docs, koSeenAt = s.koSeenAt.takeIf { it > 0 } ?: now)
    }

    /** KOReader's highlights aren't set up here (neither a folder nor a plugin's storage). */
    fun highlightsMissing(s: Snapshot) = s.koHl == null && s.koLocal == null
}
