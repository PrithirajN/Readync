package io.github.prithirajn.readync.ui

import android.content.Context
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.sync.Socials
import io.github.prithirajn.readync.sync.XQueue

/**
 * What goes up next, across your social networks: when (as near as the background checks allow),
 * where, and what. For the dashboard on Services.
 */
object UpcomingPosts {
    /** One post to come: its time ([roughly] when the time is a "not before" with a random wait in it), where it goes, and what it is. */
    data class Upcoming(
        val at: Long, val where: String, val what: String, val roughly: Boolean = false,
        /** Which post it is, to look at, change, post now or take out. */
        val ref: Ref? = null,
        /** The first passage in it, to show under it. */
        val snippet: String? = null,
    )

    /** Which waiting post an [Upcoming] is. */
    sealed interface Ref {
        /** A Threads post (and what follows it): its highlights, the first naming the post. */
        data class Threads(val ids: List<String>) : Ref
        /** A post waiting for X, by its folder in the queue. */
        data class X(val path: String) : Ref
        /** The full edition's own network: its key and any highlights in it. */
        data class Pro(val key: String, val ids: List<String>) : Ref
    }

    fun next(context: Context, s: Snapshot, limit: Int = 3, now: Long = System.currentTimeMillis() / 1000): List<Upcoming> {
        // Another phone does the posting: nothing goes up from this one.
        if (!io.github.prithirajn.readync.sync.PostingDevice.isThis(context, s)) return emptyList()
        val out = ArrayList<Upcoming>()
        val c = Socials.config(s)
        // Threads, and the networks that follow it.
        val followers = listOfNotNull(
            "Bluesky".takeIf { c.bluesky.on && c.bluesky.did != null },
            "Mastodon".takeIf { c.mastodon.on && c.mastodon.account != null },
        )
        val where = (listOf("Threads") + followers).joinToString(" + ")
        runCatching { ThreadsAuto.plan(s, now) }.getOrDefault(emptyList()).take(limit).forEach { (t, x) ->
            val n = x.items.size
            out += Upcoming(
                t, where, "$n highlight${if (n == 1) "" else "s"} from “${QuoteCard.shortTitle(x.book.displayTitle)}”",
                ref = Ref.Threads(x.items.map { it.clip.id }), snippet = x.items.first().clip.text,
            )
        }
        // X: the oldest waiting post, at the daily time.
        if (c.x.on) runCatching {
            val waiting = XQueue.count(context)
            if (waiting > 0) {
                val zone = java.time.ZoneId.systemDefault()
                var at = java.time.ZonedDateTime.now(zone).withHour(c.x.hour).withMinute(c.x.minute).withSecond(0)
                if (at.toEpochSecond() <= now) at = at.plusDays(1)
                val oldest = XQueue.oldest(context)
                val label = oldest?.let { XQueue.label(it.first) }?.takeIf { it.isNotBlank() } ?: "the oldest waiting post"
                out += Upcoming(
                    at.toEpochSecond(), "X (one tap)", label.replaceFirstChar { it.uppercase() } + if (waiting > 1) " (+${waiting - 1} waiting)" else "",
                    ref = oldest?.let { Ref.X(it.first.path) }, snippet = oldest?.second,
                )
            }
        }
        // The full edition's own (Instagram).
        out += runCatching { ProPosts.upcoming(context, s, now) }.getOrDefault(emptyList())
        return out.sortedBy { it.at }.take(limit)
    }

    /** "in 40 min", "today 7:30 pm", "tomorrow 9:10 am", "Sat 6:00 pm"; with "after" for a not-before time. */
    fun whenText(u: Upcoming, now: Long = System.currentTimeMillis() / 1000): String {
        val zone = java.time.ZoneId.systemDefault()
        val t = java.time.Instant.ofEpochSecond(u.at).atZone(zone)
        val today = java.time.LocalDate.now(zone)
        val clock = java.time.format.DateTimeFormatter.ofPattern("h:mm a").format(t).lowercase()
        val text = when {
            u.at - now < 60 * 60 && u.at >= now -> if (u.at - now < 5 * 60) "next check" else "in ${(u.at - now) / 60} min"
            u.at < now -> "next check"
            t.toLocalDate() == today -> "today $clock"
            t.toLocalDate() == today.plusDays(1) -> "tomorrow $clock"
            else -> t.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) + " $clock"
        }
        return if (u.roughly && text != "next check") "after $text" else text
    }
}
