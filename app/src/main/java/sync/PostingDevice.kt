package io.github.prithirajn.readync.sync

import android.content.Context
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.data.Store
import java.io.File

/**
 * Which phone sends. Each phone keeps its own copy of your data, so with Readync on two phones both
 * would send the same highlights to your trackers, the same progress updates, Notion pages and posts.
 * So one phone sends (progress, highlights, reviews, Readwise and Notion, and posts) and any other
 * only reads and shows everything.
 *
 * With one phone, nothing to see: the phone quietly becomes the one that sends. Only a phone that
 * finds another phone's choice in its data (a backup restored with "Readync stays on my other phone
 * too", or a test build) holds back, and says so on Services, with a way to take over.
 *
 * This phone's id lives in no-backup storage, so a restored backup never brings the old phone's.
 */
object PostingDevice {
    private const val KEY = "post.device"
    private const val NAME_KEY = "post.deviceName"

    @Volatile private var cached: String? = null

    fun id(context: Context): String = cached ?: synchronized(this) {
        cached ?: run {
            val f = File(context.noBackupFilesDir, "device-id")
            val got = runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.length >= 16 }
                ?: java.util.UUID.randomUUID().toString().also { runCatching { f.writeText(it) } }
            cached = got
            got
        }
    }

    /**
     * At start: this phone's id read, and with no phone chosen yet (one phone, as almost always), this
     * one chosen without a word. A test build (debug) never takes over by itself.
     */
    fun start(context: Context, store: Store) {
        id(context)
        if (!chosen(store.snapshot) && !io.github.prithirajn.readync.BuildConfig.DEBUG) store.update { if (chosen(it)) it else claim(context, it) }
    }

    /** Whether this phone sends (true until the app has started, so nothing is held back by mistake). */
    fun isThis(s: Snapshot): Boolean = cached?.let { s.extras[KEY] == it } ?: true

    fun isThis(context: Context, s: Snapshot): Boolean = s.extras[KEY] == id(context)

    /** Whether any phone has been chosen yet. */
    fun chosen(s: Snapshot): Boolean = !s.extras[KEY].isNullOrBlank()

    /** The sending phone's name, as it was when it was chosen. */
    fun name(s: Snapshot): String? = s.extras[NAME_KEY]?.takeIf { it.isNotBlank() }

    /** [s] with this phone made the one that sends. */
    fun claim(context: Context, s: Snapshot): Snapshot {
        val model = listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL).filter { !it.isNullOrBlank() }
            .let { parts -> if (parts.size == 2 && parts[1].startsWith(parts[0], ignoreCase = true)) parts[1] else parts.joinToString(" ") }
            .replaceFirstChar { it.uppercase() }
        return s.copy(extras = s.extras + (KEY to id(context)) + (NAME_KEY to model))
    }

    /**
     * This phone taking over from another: what that phone already sent isn't sent again from here
     * (the highlights there are counted as sent, the waiting queue let go), then this phone sends.
     */
    fun takeOver(context: Context, s: Snapshot): Snapshot {
        val clips = runCatching { Highlights.all(s).map { it.clip.id } }.getOrDefault(emptyList())
        val trackers = s.services.filterValues { it.linked }.keys
        val sent = s.sentClippings + trackers.flatMap { t -> clips.map { "$t|$it" } }
        return claim(context, s.copy(sentClippings = sent, pending = emptyMap(), readwiseDone = s.readwiseDone + clips, notionDone = s.notionDone + clips))
    }

    /** [s] (a backup restored onto a second phone) with the other phone kept as the one that sends. */
    fun keep(s: Snapshot): Snapshot = if (chosen(s)) s else s.copy(extras = s.extras + (KEY to "another-phone"))
}
