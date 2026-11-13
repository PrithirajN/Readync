package io.github.prithirajn.trackersync.ui

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.BookFacts
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.sync.Matcher

/**
 * The words to go with a picture that already shows the passage (a quote card, a book photo): the
 * passage isn't said again. Your note when there is one, the book in short ("The Gate of the Feral
 * Gods by Matt Dinniman · Dungeon Crawler Carl #4"), then a few tags people follow: the series (or
 * the book), its genre, and the reading community's own.
 */
object SocialCaption {

    /** "Dungeon Crawler Carl" -> "#DungeonCrawlerCarl"; null when nothing's left of it. */
    fun tag(words: String): String? {
        val parts = words.replace(Regex("[’'`]"), "").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val t = parts.joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
        return if (t.length in 3..40 && !t.all { it.isDigit() }) "#$t" else null
    }

    /** A tag for the book's main genre, as readers tag it. */
    private fun genreTag(f: BookFacts?, b: Book): String? {
        val g = (f?.genres.orEmpty() + f?.tropes.orEmpty()).joinToString(" | ").lowercase()
        return when {
            "litrpg" in g || "gamelit" in g -> "#LitRPG"
            b.isMangaOrComic && "manga" in g || b.document.startsWith("manga:") -> "#Manga"
            b.isMangaOrComic || "comic" in g || "graphic novel" in g -> "#Comics"
            "grimdark" in g -> "#Grimdark"
            "science fiction" in g || "sci-fi" in g || "space opera" in g -> "#SciFi"
            "horror" in g -> "#Horror"
            "fantasy" in g -> "#Fantasy"
            "romance" in g || "romantasy" in g -> "#Romance"
            "thriller" in g -> "#Thriller"
            "mystery" in g || "crime" in g -> "#Mystery"
            "mythology" in g -> "#Mythology"
            "non-fiction" in g || "nonfiction" in g -> "#Nonfiction"
            else -> null
        }
    }

    /**
     * The caption. [x] is for X: three tags with the community's (#BookTwitter), cut to fit 280.
     * Threads takes one topic, so it gets the series' (or the book's) alone.
     */
    fun forPicture(book: Book?, facts: BookFacts?, note: String?, x: Boolean): String {
        val b = book ?: return note?.trim().orEmpty()
        val title = Matcher.coreTitle(b.displayTitle).ifBlank { b.displayTitle }
        val series = facts?.series?.trim()?.takeIf { it.isNotEmpty() }
        val number = facts?.seriesNumber?.trim()?.takeIf { it.isNotEmpty() }
        val bookLine = buildString {
            append("📖 ").append(title)
            b.author?.let { append(" by ").append(it.substringBefore(',').trim()) }
            if (series != null && !title.contains(series, ignoreCase = true)) append(" · ").append(series).append(number?.let { " #$it" } ?: "")
        }
        val lead = when {
            !note.isNullOrBlank() -> note.trim()
            b.status == ReadStatus.READING -> "Reading this right now."
            b.status == ReadStatus.FINISHED -> "One of the lines that stayed with me."
            else -> null
        }
        val tags = if (x) listOfNotNull(tag(series ?: title), genreTag(facts, b), if (b.status == ReadStatus.READING) "#CurrentlyReading" else "#BookTwitter").distinct()
        else listOfNotNull(tag(series ?: title))
        val tail = (if (tags.isEmpty()) "" else "\n\n" + tags.joinToString(" "))
        val head = listOfNotNull(lead, bookLine).joinToString("\n\n")
        // Room for the tags kept: what's cut is the note's end, never the book or the tags.
        return if (x) XPost.fit(head, 280 - tail.length) + tail else head + tail
    }
}
