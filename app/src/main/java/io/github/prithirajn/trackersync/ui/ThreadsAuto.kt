package io.github.prithirajn.trackersync.ui

import android.content.Context
import android.graphics.Bitmap
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.data.Store
import io.github.prithirajn.trackersync.sync.Highlights
import io.github.prithirajn.trackersync.sync.KoHighlightSync
import io.github.prithirajn.trackersync.sync.ThreadsClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * New highlights, quotes and notes posted to Threads by themselves, after each sync, from the
 * books you've turned it on for: a reading session's highlights together as one post (a card each,
 * to swipe through, your notes on their cards), or one alone as a single card with your note as
 * its words. Layouts picked at random; a few posts a day at most; never in your quiet hours.
 */
object ThreadsAuto {
    private val busy = AtomicBoolean(false)
    private const val DAY = 86_400L
    /** Posts per run: each takes Threads a little while (fetching the picture). */
    private const val PER_RUN = 2
    private const val MAX_TRIES = 3
    /** Highlights further apart than this are separate reading sessions. */
    private const val GAP_S = 2 * 3600L
    /** A session is over when nothing new has come in from it for this long. */
    private const val SETTLE_S = 30 * 60L
    /** Cards in one post (more go in the next). */
    private const val MAX_CARDS = 10

    /** A post's picture: a look, and for the Book card which of its layouts. Kept as "BOOK:1", "WORDPRINT"… */
    private data class Style(val look: QuoteCard.Look, val variant: Int? = null) {
        val key get() = look.name + (variant?.let { ":$it" } ?: "")
        val label get() = look.label + (variant?.let { " ${it + 1}" } ?: "")
    }

    /**
     * The styles posts are drawn in: the Book card's layouts (the book's cover with the passage);
     * or, when you've asked for any look, the others too (the game window only for web novels and
     * progression fantasy).
     */
    private fun stylesFor(s: Snapshot, book: Book): List<Style> {
        val book3 = (0 until QuoteCard.BOOK_LAYOUTS).map { Style(QuoteCard.Look.BOOK, it) }
        if (!s.threadsAnyLook) return book3
        val others = listOf(
            QuoteCard.Look.SPOTLIGHT, QuoteCard.Look.HIGHLIGHTER, QuoteCard.Look.SPINE, QuoteCard.Look.EDITORIAL,
            QuoteCard.Look.TYPEWRITER, QuoteCard.Look.INDEX_CARD, QuoteCard.Look.WORDPRINT,
        ).map { Style(it) }
        val gamey = s.webNovels.containsKey(book.document) || "SPIRE" in BookShot.sectorsFor(s, book)
        return book3 + others + (if (gamey) listOf(Style(QuoteCard.Look.SYSTEM)) else emptyList())
    }

    private fun isNote(id: String) = KoHighlightSync.isNote(id)

    /** Every highlight and note here now, with its book (books held back left out). */
    private fun items(s: Snapshot): List<Pair<Highlights.Item, Book>> =
        Highlights.all(s).mapNotNull { i -> s.books[i.clip.document]?.takeIf { !it.held }?.let { i to it } }

    /**
     * Turns posting on for one book (off to start with): its highlights so far count as seen, so
     * only what comes in from now is posted.
     */
    fun setBook(store: Store, document: String, on: Boolean) = store.update { s ->
        if (on) s.copy(
            threadsBooks = s.threadsBooks + document,
            threadsSeen = s.threadsSeen + Highlights.forBook(s, document).map { it.clip.id },
            threadsError = null,
        ) else s.copy(threadsBooks = s.threadsBooks - document)
    }

    /** What's waiting to be posted (oldest first). */
    fun waiting(s: Snapshot): List<Pair<Highlights.Item, Book>> = items(s)
        .filter { (i, _) ->
            val id = i.clip.id
            i.clip.document in s.threadsBooks &&
                id !in s.threadsSeen && id !in s.threadsPosted && (s.threadsFails[id] ?: 0) < MAX_TRIES &&
                (s.threadsNotes || !isNote(id)) && i.clip.text.isNotBlank()
        }
        .sortedBy { it.first.clip.createdAt }

    /**
     * The post's words. With its picture (which shows the passage): your note, then the book, so
     * nothing is said twice. Without one: the passage too.
     */
    internal fun postText(i: Highlights.Item, book: Book, withNote: Boolean, picture: Boolean = true): String {
        val c = i.clip
        if (picture) {
            val name = QuoteCard.shortTitle(book.displayTitle)
            val by = book.author?.let { " by $it" } ?: ""
            // The book named a different way each time.
            val bookLine = listOf("\uD83D\uDCD6 $name$by", "From $name$by", "Reading $name$by", "\u2014 $name$by", "\uD83D\uDCDA $name$by", "Currently: $name$by").random()
            // A note on its own is the picture: the post just says what it's on.
            if (isNote(c.id)) return listOf("My note, reading $name$by", "A note to myself, reading $name$by", "Margin note from $name$by", "Thinking about $name$by").random()
            val note = c.note?.trim()?.takeIf { withNote && it.isNotEmpty() } ?: return bookLine
            return note.take(ThreadsClient.MAX_TEXT - bookLine.length - 3).trimEnd() + "\n\n" + bookLine
        }
        val by = "— " + book.displayTitle + (book.author?.let { ", $it" } ?: "")
        if (isNote(c.id)) {
            val tail = "\n\n— my note on " + book.displayTitle + (book.author?.let { ", $it" } ?: "")
            return c.text.trim().take(ThreadsClient.MAX_TEXT - tail.length - 1).trimEnd() + tail
        }
        val note = c.note?.trim()?.takeIf { withNote && it.isNotEmpty() }?.let { "\n\nMy note: $it" } ?: ""
        val room = ThreadsClient.MAX_TEXT - by.length - 4
        var quote = io.github.prithirajn.trackersync.trackers.quoted(c.text.trim())
        val withAll = quote + "\n\n" + by + note
        if (withAll.length <= ThreadsClient.MAX_TEXT) return withAll
        // Too long: the note shortened first, then the passage.
        val noteRoom = ThreadsClient.MAX_TEXT - quote.length - by.length - 2
        if (note.isNotEmpty() && noteRoom > 40) return quote + "\n\n" + by + note.take(noteRoom - 1).trimEnd() + "…"
        if (quote.length > room) quote = quote.take(room - 2).trimEnd() + "…”"
        return quote + "\n\n" + by
    }

    /** What the picture shows, for screen readers: the passage, and the book. */
    private fun altText(i: Highlights.Item, book: Book): String =
        (if (isNote(i.clip.id)) "A note on " else "A quote from ") + book.displayTitle + (book.author?.let { " by $it" } ?: "") + ": " +
            io.github.prithirajn.trackersync.trackers.quoted(i.clip.text.trim()) +
            (i.clip.note?.takeIf { it.isNotBlank() && !isNote(i.clip.id) }?.let { " My note: $it" } ?: "")

    /** The picture, in [style]; with your note on it when [noteOnCard] (a session's cards). */
    private fun picture(context: Context, s: Snapshot, i: Highlights.Item, book: Book, style: Style, noteOnCard: Boolean = false): Bitmap {
        val c = i.clip
        val note = isNote(c.id)
        val content = QuoteCard.Content(
            text = c.text,
            // Alone, your note is the post's own words, so it isn't on the picture as well; in a session, each note is on its card.
            note = if (noteOnCard && !note && s.threadsWithNote) c.note?.takeIf { it.isNotBlank() } else null,
            title = book.displayTitle,
            author = book.author,
            where = c.chapter?.takeIf { it.isNotBlank() } ?: io.github.prithirajn.trackersync.sync.HighlightPosition.label(c),
            percent = c.percent,
            page = c.pageIn(book.pageCount),
            standalone = note,
        )
        val cover = book.coverUrl?.let { runCatching { CoverCache.load(context, it, 1400) }.getOrNull() }
        val seed = book.coverUrl?.let { runCatching { CoverAccent.of(context, it) }.getOrNull() }
        return QuoteCard.render(context, content, style.look, cover, seed, mark = BookShot.markOn(context), size = QuoteCard.Size.POST, variant = style.variant)
    }

    /**
     * The picture put up for Threads to copy, and the style it was drawn in. Always a picture: if
     * [first] can't be drawn (out of memory on a big cover, say), another layout is tried, then the
     * plain Spotlight. A failed upload throws, so the highlight waits for the next try.
     */
    private fun pictureUrl(context: Context, s: Snapshot, i: Highlights.Item, book: Book, first: Style, styles: List<Style>, noteOnCard: Boolean = false): Pair<String, Style> {
        val order = (listOf(first) + styles.filter { it != first }.shuffled().take(2) + Style(QuoteCard.Look.SPOTLIGHT)).distinct()
        var last: Throwable? = null
        for (style in order) {
            val bmp = try { picture(context, s, i, book, style, noteOnCard) } catch (t: Throwable) { last = t; continue }
            val png = java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bmp.recycle()
            return ThreadsClient.upload(png) to style
        }
        throw Exception("Couldn't draw the picture" + (last?.message?.let { ": $it" } ?: ""))
    }

    // ---- reading sessions ----

    /** One book's highlights from one sitting, posted together. */
    class Session(val book: Book, val items: List<Highlights.Item>)

    /**
     * What's waiting, as sessions: per book, in the order they were made, a new one wherever two
     * are more than two hours apart (or every one on its own, when you've asked for that), at most
     * [MAX_CARDS] to a post. Oldest first.
     */
    fun sessions(s: Snapshot): List<Session> {
        val out = ArrayList<Session>()
        for ((_, list) in waiting(s).groupBy { it.second.document }) {
            val book = list.first().second
            var cur = ArrayList<Highlights.Item>()
            var last = 0L
            for ((i, _) in list.sortedBy { it.first.clip.createdAt }) {
                val t = i.clip.createdAt
                if (cur.isNotEmpty() && (!s.threadsPerSession || t - last > GAP_S || cur.size >= MAX_CARDS)) { out += Session(book, cur); cur = ArrayList() }
                cur += i
                last = t
            }
            if (cur.isNotEmpty()) out += Session(book, cur)
        }
        return out.sortedBy { it.items.first().clip.createdAt }
    }

    /**
     * Over and ready to post: nothing new from it for half an hour since the last of it came in,
     * plus a few minutes chosen for each session (so posts don't go up like clockwork).
     */
    private fun ready(s: Snapshot, x: Session, now: Long): Boolean {
        val arrived = x.items.maxOf { s.threadsArrived[it.clip.id] ?: now }
        val jitter = ((x.items.first().clip.id.hashCode() and 0x7FFFFFFF) % (20 * 60)).toLong()
        return now >= arrived + (if (s.threadsPerSession) SETTLE_S else 5 * 60L) + jitter
    }

    /** "this evening's", from when the session started. */
    private fun partOfDay(sec: Long): String {
        val h = java.time.Instant.ofEpochSecond(sec).atZone(java.time.ZoneId.systemDefault()).hour
        return when { h < 5 -> "late-night"; h < 12 -> "this morning's"; h < 17 -> "this afternoon's"; h < 21 -> "this evening's"; else -> "tonight's" }
    }

    /** A session post's words, worded a different way each time: {n} highlights, {when} the reading, {name}{by} the book. */
    internal fun sessionText(x: Session): String {
        val name = QuoteCard.shortTitle(x.book.displayTitle)
        val by = x.book.author?.let { " by $it" } ?: ""
        val n = x.items.size
        val whenRead = partOfDay(x.items.first().clip.createdAt).let { if (it == "late-night") "a late-night" else it }
        return listOf(
            "$n highlights from $whenRead reading of $name$by \uD83D\uDCD6",
            "Lines that stopped me in $name$by",
            "${whenRead.replaceFirstChar { it.uppercase() }} reading, in quotes: $name$by \uD83D\uDCDA",
            "$n passages worth keeping from $name$by",
            "Underlined in $name$by",
            "From my reading session with $name$by",
            "The bits of $name$by I couldn't let go of",
            "$n moments from $name$by \u2192 swipe",
        ).random()
    }

    /** The post's topic: by the book's kind when set to that (LitRPG, Fantasy, Science Fiction, Horror; else Books), yours, or none. */
    fun topicFor(s: Snapshot, book: Book): String? = when (s.threadsTopic) {
        "" -> null
        "genre" -> {
            val sectors = BookShot.sectorsFor(s, book)
            when {
                "SPIRE" in sectors -> "LitRPG"
                "VOID" in sectors -> "Horror"
                "CITADEL" in sectors -> "Fantasy"
                "ORBIT" in sectors || "CHRONOS" in sectors -> "Science Fiction"
                s.webNovels.containsKey(book.document) -> "Web Novels"
                else -> "Books"
            }
        }
        else -> s.threadsTopic
    }

    // ---- the on-device model's help (when one is set up, and you've left it on) ----

    private fun model(s: Snapshot): io.github.prithirajn.trackersync.ai.TextModel? =
        if (s.threadsAi || s.threadsAiCaptions) io.github.prithirajn.trackersync.ai.Ai.model() else null

    /** Whether these passages give the story away (then the post goes up behind Threads' spoiler tap). False without a model. */
    private fun spoils(s: Snapshot, book: Book, passages: List<String>): Boolean {
        if (!s.threadsAi) return false
        val m = model(s) ?: return false
        return runCatching { io.github.prithirajn.trackersync.ai.Assistant.givesAway(m, book.displayTitle, passages) }.getOrNull() == true
    }

    /**
     * The topic, with the model's pick for a book the genres don't cover (Romance, Thriller…), asked
     * once per book and kept.
     */
    fun topic(store: Store, book: Book): String? {
        val s = store.snapshot
        val t = topicFor(s, book)
        if (s.threadsTopic != "genre" || t != "Books" || !s.threadsAi) return t
        s.threadsTopics[book.document]?.let { return it }
        val m = model(s) ?: return t
        val picked = runCatching { io.github.prithirajn.trackersync.ai.Assistant.pickTopic(m, book) }.getOrNull() ?: return t
        store.update { it.copy(threadsTopics = it.threadsTopics + (book.document to picked)) }
        return picked
    }

    /** The model's opening line for these passages, when you've asked for that; null otherwise (the usual wording is used). */
    private fun aiCaption(s: Snapshot, book: Book, passages: List<String>): String? {
        if (!s.threadsAiCaptions) return null
        val m = model(s) ?: return null
        return runCatching { io.github.prithirajn.trackersync.ai.Assistant.postCaption(m, QuoteCard.shortTitle(book.displayTitle), book.author, passages) }.getOrNull()
    }

    /** "📖 Name by Author", under a caption the model wrote (so the book's always named). */
    private fun bookLine(book: Book) = "\uD83D\uDCD6 " + QuoteCard.shortTitle(book.displayTitle) + (book.author?.let { " by $it" } ?: "")

    // ---- a finished (or not finished) book's rating and review ----

    /** Offered once a book is finished, or you've stopped reading it. */
    fun canReview(book: Book): Boolean =
        book.status == io.github.prithirajn.trackersync.data.ReadStatus.FINISHED || book.status == io.github.prithirajn.trackersync.data.ReadStatus.DNF

    /** "⭐⭐⭐⭐½" for 4.5. */
    fun starEmoji(rating: Double): String {
        val whole = rating.toInt().coerceIn(0, 5)
        return "\u2B50".repeat(whole) + if (rating - whole >= 0.5) "\u00BD" else ""
    }

    /** "My review of The Gate of the Feral Gods by Matt Dinniman ⭐⭐⭐⭐½" (and where you stopped, for one you didn't finish). */
    /** Ways to open a review post: {name}, {by} (" by Author", or nothing), {pct} (where you stopped). */
    private val FINISHED_OPENERS = listOf(
        "My review of {name}{by}",
        "Just finished {name}{by}. Here's what I thought.",
        "Finished {name}{by}! My thoughts:",
        "{name}{by}: my review",
        "Turned the last page of {name}{by}. Review below \uD83D\uDC47",
        "Reviewing {name}{by}",
        "Thoughts on {name}{by} \uD83D\uDCDA",
        "Done with {name}{by}. Was it worth it?",
        "\uD83D\uDCD6 {name}{by}: read, rated, reviewed.",
        "Another one read: {name}{by}",
        "The verdict on {name}{by}",
        "Closing the book on {name}{by}",
    )
    private val DNF_OPENERS = listOf(
        "Put down {name}{by} at {pct}%. Here's why.",
        "DNF at {pct}%: {name}{by}",
        "Not for me: {name}{by} (stopped at {pct}%)",
        "My verdict on {name}{by}, set aside at {pct}%",
        "Gave up on {name}{by} at {pct}%. My thoughts:",
        "{name}{by}: did not finish ({pct}%)",
        "Life's too short. Stopped {name}{by} at {pct}%.",
    )
    /** Ways to show the stars: {stars} the emoji, {n} the number. */
    private val STAR_LINES = listOf("{stars}", "{stars} ({n}/5)", "Rating: {stars}", "{n}/5 {stars}", "My rating: {stars}")

    /** How many wordings a review post has (each its own opener and way of showing the stars). */
    fun reviewWordings(book: Book): Int =
        (if (book.status == io.github.prithirajn.trackersync.data.ReadStatus.DNF) DNF_OPENERS.size else FINISHED_OPENERS.size) * STAR_LINES.size

    /**
     * The review post's words in wording [variant] (the opener, then the stars on their own line),
     * e.g. "Just finished The Gate of the Feral Gods by Matt Dinniman. Here's what I thought." / "⭐⭐⭐⭐½ (4.5/5)".
     */
    fun reviewText(book: Book, variant: Int = 0): String {
        val dnf = book.status == io.github.prithirajn.trackersync.data.ReadStatus.DNF
        val openers = if (dnf) DNF_OPENERS else FINISHED_OPENERS
        val v = variant.mod(openers.size * STAR_LINES.size)
        val opener = openers[v % openers.size]
            .replace("{name}", QuoteCard.shortTitle(book.displayTitle))
            .replace("{by}", book.author?.let { " by $it" } ?: "")
            .replace("{pct}", book.percent.toString())
        val stars = book.rating?.let { r ->
            val n = if (r % 1.0 == 0.0) r.toInt().toString() else r.toString()
            STAR_LINES[(v / openers.size) % STAR_LINES.size].replace("{stars}", starEmoji(r)).replace("{n}", n)
        }
        return opener + (stars?.let { "\n$it" } ?: "")
    }

    /** The review's slides, in colour scheme [theme]. */
    fun reviewSlides(context: Context, book: Book, theme: Int): List<Bitmap> {
        val dnf = book.status == io.github.prithirajn.trackersync.data.ReadStatus.DNF
        val cover = book.coverUrl?.let { runCatching { CoverCache.load(context, it, 1400) }.getOrNull() }
        val seed = book.coverUrl?.let { runCatching { CoverAccent.of(context, it) }.getOrNull() }
        return QuoteCard.reviewSlides(
            context, QuoteCard.Review(book.displayTitle, book.author, book.rating, book.review.orEmpty(), if (dnf) book.percent else null),
            cover, seed, theme,
        )
    }

    /** Posts the book's rating and review to Threads: the words with your stars, the review as slides. Returns null when done, else why not. */
    fun postReview(context: Context, store: Store, document: String, theme: Int, wording: Int = 0): String? {
        val s = store.snapshot
        val book = s.books[document] ?: return "That book isn't here any more."
        val token = s.threadsToken ?: return "Connect Threads first (Services \u203A Threads)."
        val user = s.threadsUserId ?: return "Connect Threads first (Services \u203A Threads)."
        if (book.rating == null && book.review.isNullOrBlank()) return "Rate it or write a review first."
        return try {
            val slides = reviewSlides(context, book, theme)
            val alt = "Review of " + book.displayTitle + (book.rating?.let { ", ${it} out of 5 stars" } ?: "") + ". "
            // Uploaded one after another, so the slides keep their order (the book first, then the review in sequence).
            val images = slides.mapIndexed { i, bmp ->
                val png = java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                bmp.recycle()
                ThreadsClient.upload(png) to (if (i == 0) alt.trim() else alt + QuoteCard.cleanReview(book.review.orEmpty()).take(900))
            }
            val spoiler = !book.review.isNullOrBlank() && spoils(s, book, listOf(QuoteCard.cleanReview(book.review.orEmpty())))
            ThreadsClient.postCarousel(user, token, reviewText(book, wording), images, topic(store, book), spoiler)
            store.update { it.copy(threadsReviewed = it.threadsReviewed + (document to System.currentTimeMillis() / 1000), threadsError = null) }
            store.log(null, "Posted your review of \u201C${book.displayTitle}\u201D to Threads")
            null
        } catch (e: Exception) {
            val signIn = (e as? ThreadsClient.ThreadsException)?.signIn == true
            if (signIn) "Threads needs a new token: paste one under Services \u203A Threads." else (e.message ?: "Couldn't post to Threads")
        }
    }

    /**
     * One session up: alone, a single card with your note as the words (as [run] does for one);
     * together, a card each in different layouts, in the order they were made, your notes on their
     * cards. Returns null when posted, else what went wrong (its highlights tried again next time).
     */
    private fun postSession(context: Context, store: Store, x: Session, token: String): String? {
        val st = store.snapshot
        val ids = x.items.map { it.clip.id }
        val styles = stylesFor(st, x.book)
        return try {
            val user = st.threadsUserId ?: return "Connect Threads first."
            val usedKey: String
            if (x.items.size == 1) {
                val item = x.items[0]
                val wanted = (styles.filter { it.key != st.threadsLastLook }.ifEmpty { styles }).random()
                // Always with its picture (never the words alone).
                val (imageUrl, look) = pictureUrl(context, st, item, x.book, wanted, styles)
                // Your note stays your words; without one, the model's line when you've asked for it.
                val words = if (item.clip.note.isNullOrBlank() && !isNote(item.clip.id))
                    aiCaption(st, x.book, listOf(item.clip.text))?.let { it + "\n\n" + bookLine(x.book) } ?: postText(item, x.book, st.threadsWithNote, picture = true)
                    else postText(item, x.book, st.threadsWithNote, picture = true)
                ThreadsClient.post(user, token, words, imageUrl, altText(item, x.book), topic(store, x.book), spoils(st, x.book, listOf(item.clip.text)))
                usedKey = look.key
            } else {
                // A different layout for each card (none the same as the last post's first).
                val order = styles.filter { it.key != st.threadsLastLook }.shuffled().let { l -> (l + styles.shuffled()).take(x.items.size) }
                // One after another, in the order they were made, so the cards keep it.
                val images = x.items.mapIndexed { k, item ->
                    pictureUrl(context, st, item, x.book, order[k], styles, noteOnCard = true).first to altText(item, x.book)
                }
                val passages = x.items.map { it.clip.text }
                val words = aiCaption(st, x.book, passages)?.let { it + "\n\n" + bookLine(x.book) } ?: sessionText(x)
                ThreadsClient.postCarousel(user, token, words, images, topic(store, x.book), spoils(st, x.book, passages))
                usedKey = order.last().key
            }
            val at = System.currentTimeMillis() / 1000
            store.update { s ->
                s.copy(
                    threadsPosted = s.threadsPosted + ids.associateWith { at }, threadsFails = s.threadsFails - ids.toSet(),
                    threadsLastLook = usedKey, threadsError = null, threadsPostLog = (s.threadsPostLog + at).takeLast(100),
                )
            }
            store.log(null, "Posted ${ids.size} highlight${if (ids.size == 1) "" else "s"} from \u201C${x.book.displayTitle}\u201D to Threads")
            null
        } catch (e: Exception) {
            val signIn = (e as? ThreadsClient.ThreadsException)?.signIn == true
            val problem = if (signIn) "Threads needs a new token: paste one under Services \u203A Threads." else (e.message ?: "Couldn't post to Threads")
            store.update { s -> s.copy(threadsFails = s.threadsFails + ids.associateWith { (s.threadsFails[it] ?: 0) + 1 }, threadsError = problem) }
            store.log(null, "Threads: ${e.message}", error = true)
            problem
        }
    }

    /**
     * Posts what's waiting (or just [only], when given, even with posting off). Returns what
     * happened in a line, or null when there was nothing to do. Blocking; network.
     */
    fun run(context: Context, store: Store, only: String? = null): String? {
        val s0 = store.snapshot
        if (s0.threadsToken == null || s0.threadsUserId == null) return null
        if (only == null && s0.threadsBooks.isEmpty()) return null
        if (!busy.compareAndSet(false, true)) return null
        try {
            val now = System.currentTimeMillis() / 1000
            // The token renewed every month (it lasts 60 days, and can be renewed once a day old).
            var token: String = s0.threadsToken!!
            if (now - s0.threadsTokenAt > 30 * DAY) {
                runCatching { ThreadsClient.refresh(token) }.onSuccess { t ->
                    token = t
                    store.update { it.copy(threadsToken = t, threadsTokenAt = now) }
                }
            }
            // When each waiting highlight first came in (to tell when its session is over); posted ones forgotten.
            store.update { x ->
                val w = waiting(x).map { it.first.clip.id }.toSet()
                x.copy(threadsArrived = x.threadsArrived.filterKeys { it in w } + w.filter { it !in x.threadsArrived }.associateWith { now })
            }
            val s = store.snapshot
            if (only == null) {
                // Never in your quiet hours; it waits until morning.
                if (s.settings.isQuiet(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))) return null
                val today = s.threadsPostLog.count { it > now - DAY }
                var budget = minOf(PER_RUN, (s.threadsPerDay - today).coerceAtLeast(0))
                if (budget == 0) return null
                var posted = 0
                var problem: String? = null
                // Oldest first; a book's later session waits for its earlier one.
                val waitingBooks = HashSet<String>()
                for (x in sessions(s)) {
                    if (budget == 0) break
                    if (x.book.document in waitingBooks || !ready(s, x, now)) { waitingBooks += x.book.document; continue }
                    problem = postSession(context, store, x, token)
                    if (problem != null) break
                    posted++; budget--
                }
                return problem ?: if (posted > 0) "Posted $posted to Threads" else null
            }
            val todo = items(s).filter { it.first.clip.id == only }
            if (todo.isEmpty()) return null
            var posted = 0
            var problem: String? = null
            for ((item, book) in todo) {
                val id = item.clip.id
                val st = store.snapshot
                // A style picked at random, never the one used last.
                val styles = stylesFor(st, book)
                var look = (styles.filter { it.key != st.threadsLastLook }.ifEmpty { styles }).random()
                try {
                    // Always with its picture (never the words alone).
                    val (imageUrl, drawn) = pictureUrl(context, st, item, book, look, styles)
                    look = drawn
                    ThreadsClient.post(st.threadsUserId!!, token, postText(item, book, st.threadsWithNote, picture = true), imageUrl, altText(item, book), topic(store, book), spoils(st, book, listOf(item.clip.text)))
                    posted++
                    store.update { x ->
                        x.copy(
                            threadsPosted = x.threadsPosted + (id to System.currentTimeMillis() / 1000),
                            threadsFails = x.threadsFails - id, threadsLastLook = look.key, threadsError = null,
                            threadsPostLog = (x.threadsPostLog + System.currentTimeMillis() / 1000).takeLast(100),
                        )
                    }
                    store.log(null, "Posted a highlight from “${book.displayTitle}” to Threads (${look.label})")
                } catch (e: Exception) {
                    val signIn = (e as? ThreadsClient.ThreadsException)?.signIn == true
                    problem = if (signIn) "Threads needs a new token: paste one under Services › Threads." else (e.message ?: "Couldn't post to Threads")
                    store.update { x -> x.copy(threadsFails = x.threadsFails + (id to ((x.threadsFails[id] ?: 0) + 1)), threadsError = problem) }
                    store.log(null, "Threads: ${e.message}", error = true)
                    // The rest wait, so highlights still go up in the order they were made.
                    break
                }
            }
            return problem ?: "Posted $posted to Threads"
        } finally {
            busy.set(false)
        }
    }
}
