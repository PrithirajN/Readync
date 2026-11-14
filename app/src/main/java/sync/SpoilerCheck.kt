package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.source.Clipping
import io.github.prithirajn.readync.trackers.HighlightSupport
import io.github.prithirajn.readync.trackers.Tracker
import io.github.prithirajn.readync.trackers.Trackers

/**
 * Check highlights before sending (off unless you turn it on): with it on, a new highlight waits
 * until you mark it fine, a spoiler or not to send. Nothing is worked out for you; no AI.
 *
 * A spoiler then goes to each tracker as best it can take one: as a spoiler where the tracker has
 * that setting (Fable, Pagebound), as it is to private journals (Hardcover, StoryGraph, Seekquel,
 * BookOrbit, Readest: only you see them), and to public posts with no spoiler setting (Goodreads,
 * Micro.blog) held back or with a warning above it, as you chose. "Not to send" is kept here only.
 */
object SpoilerCheck {
    const val OK = "ok"
    const val SPOILER = "spoiler"
    const val SKIP = "skip"

    /** Trackers whose posts have a spoiler setting of their own. */
    private val SPOILER_SETTING = setOf("fable", "pagebound")

    fun mark(s: Snapshot, id: String): String? = s.clipChecked[id]
    fun isSpoiler(s: Snapshot, id: String): Boolean = s.clipChecked[id] == SPOILER

    /** Whether a highlight is kept from trackers and Threads: marked not to send, or waiting to be checked. */
    fun blocked(s: Snapshot, id: String): Boolean {
        val m = s.clipChecked[id]
        return m == SKIP || (s.checkHighlights && m == null)
    }

    fun hasSpoilerSetting(t: Tracker) = t.id in SPOILER_SETTING

    /**
     * [c] as it should go to [t]: flagged as a spoiler, with a warning, as it is, or null when it's
     * held back from [t] (a spoiler for a public tracker without a spoiler setting, set to hold).
     */
    fun prepare(t: Tracker, s: Snapshot, c: Clipping): Clipping? {
        if (!isSpoiler(s, c.id)) return c
        return when {
            hasSpoilerSetting(t) -> c.copy(spoiler = true)
            t.highlights == HighlightSupport.PRIVATE_JOURNAL -> c
            s.spoilerPublic == "warn" -> c.copy(spoiler = true, spoilerWarning = true)
            else -> null
        }
    }

    /**
     * Highlights waiting to be checked: not marked yet, and still to go to a tracker that sends
     * highlights (or to be posted to Threads). Ids, newest first.
     */
    fun waiting(s: Snapshot): List<String> {
        if (!s.checkHighlights) return emptyList()
        val sending = Trackers.all.filter { t ->
            val svc = s.service(t.id)
            svc.linked && svc.enabled && svc.highlights && t.highlights != HighlightSupport.NONE
        }
        return Highlights.all(s).asSequence()
            .map { it.clip }
            .filter { c -> c.id !in s.clipChecked && !KoHighlightSync.isNote(c.id) && s.books[c.document]?.held != true }
            .filter { c ->
                val toTracker = sending.any { t ->
                    c.createdAt >= s.service(t.id).highlightsSince && "${t.id}|${c.id}" !in s.sentClippings && s.link(t.id, c.document)?.confirmed == true
                }
                toTracker || (c.document in s.threadsBooks && c.id !in s.threadsSeen)
            }
            .sortedByDescending { it.createdAt }
            .map { it.id }
            .toList()
    }

    /**
     * Marks [ids] [what]. A highlight already sent to a tracker with a spoiler setting that edits
     * its posts is sent again there, so the change shows.
     */
    fun set(s: Snapshot, ids: Collection<String>, what: String?): Snapshot {
        val marks = if (what == null) s.clipChecked - ids.toSet() else s.clipChecked + ids.associateWith { what }
        val changed = ids.filter { (s.clipChecked[it] == SPOILER) != (what == SPOILER) }
        val again = Trackers.all.filter { hasSpoilerSetting(it) && it.updatesHighlights }.flatMap { t ->
            changed.map { "${t.id}|$it" }.filter { it in s.sentClippings }
        }
        return s.copy(clipChecked = marks, resend = s.resend + again)
    }
}
