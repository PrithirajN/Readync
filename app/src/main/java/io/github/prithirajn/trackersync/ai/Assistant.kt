package io.github.prithirajn.trackersync.ai

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.source.Clipping
import io.github.prithirajn.trackersync.sync.Matcher
import io.github.prithirajn.trackersync.trackers.RemoteBook

/** A language model that runs on the phone: a prompt in, its reply out. Blocking; call off the main thread. */
interface TextModel {
    fun generate(prompt: String): String
}

/**
 * Where the app gets the on-device model, when you've set one up (Services → On-device AI).
 * Null when there's none, or it's switched off: every AI feature is then simply not offered.
 */
object Ai {
    @Volatile var provider: (() -> TextModel?)? = null

    fun model(): TextModel? = runCatching { provider?.invoke() }.getOrNull()
}

/**
 * What the on-device model is asked, and how its answers are checked. Small models make things
 * up, so every answer is a suggestion you approve, and answers that don't hold up (a match that
 * isn't in the list, a "cleaned" text with new words in it) are thrown away.
 */
object Assistant {
    /** The most text one prompt carries (small models have short memories). */
    const val MAX_PROMPT_CHARS = 5000

    // ---- 1. Matching: which listing is the same book ----

    /**
     * The listing among [candidates] that is the same book as [title] by [author] (any edition),
     * or null when none is, or the model's answer doesn't make sense.
     */
    fun pickMatch(m: TextModel, title: String, author: String?, candidates: List<RemoteBook>): RemoteBook? {
        if (candidates.isEmpty()) return null
        val list = candidates.take(8)
        val prompt = buildString {
            appendLine("You compare book listings. Decide which listing below is the same book as this one.")
            appendLine("Any edition, format or translation of the same work counts. A different book in the same series, a summary, a study guide or a box set does not.")
            appendLine()
            appendLine("The book: \"$title\"" + (author?.let { " by $it" } ?: ""))
            appendLine()
            appendLine("Listings:")
            list.forEachIndexed { i, b ->
                append("${i + 1}. \"${b.title}\"")
                b.author?.let { append(" by $it") }
                val extra = listOfNotNull(b.year?.toString(), b.pages?.let { "$it pages" }, b.format).joinToString(", ")
                if (extra.isNotEmpty()) append(" ($extra)")
                appendLine()
            }
            appendLine()
            append("Reply with only the number of the matching listing, or 0 if none of them is the same book.")
        }
        val reply = runCatching { m.generate(prompt) }.getOrNull() ?: return null
        val n = Regex("\\b(\\d{1,2})\\b").find(reply)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val pick = list.getOrNull(n - 1) ?: return null
        // A sanity check the model can't talk its way past: the titles must have something in common.
        val t = Matcher.plainTitle(title)
        return pick.takeIf { Matcher.sameBook(t, author, it.title, it.author) || Matcher.score(t, author, it) >= 0.35 || sharesWords(t, it.title) }
    }

    private fun words(s: String): Set<String> =
        Regex("[\\p{L}\\p{N}]{3,}").findAll(s.lowercase()).map { it.value }.filter { it !in STOP }.toSet()

    private val STOP = setOf("the", "and", "for", "with", "book", "novel", "edition", "volume", "series", "from", "that", "this")

    private fun sharesWords(a: String, b: String): Boolean = words(a).intersect(words(b)).isNotEmpty()

    // ---- 2. Descriptions: tidied, or short ----

    /**
     * The description with the clutter taken out (review quotes, "bestselling author of…", award
     * lists, links, series marketing), the book's own words kept. Null when the answer adds words
     * the original didn't have, or isn't shorter.
     */
    fun tidyDescription(m: TextModel, text: String): String? {
        val src = text.take(MAX_PROMPT_CHARS - 800)
        val prompt = """
            Clean up this book description. Remove only clutter: review quotes and praise, "bestselling author of" lines, awards, sales claims, links, and lines advertising other books or the series.
            Keep the description of the book itself, in its original words and paragraphs. Do not add, summarise or rewrite anything.
            Reply with the cleaned description only.

            Description:
            $src
        """.trimIndent()
        val out = clean(runCatching { m.generate(prompt) }.getOrNull() ?: return null)
        if (out.length < 40 || out.length > src.length + 20) return null
        // Nearly every word must come from the original: a tidy-up, not a rewrite.
        if (newWordsShare(out, src) > 0.08) return null
        return out
    }

    /** Two sentences on what the book is about, for a quick look. Null when the answer is unusable. */
    fun shortDescription(m: TextModel, title: String, text: String): String? {
        val prompt = """
            In at most two sentences, say what this book is about, using only the description below. No praise, no spoilers, nothing that isn't in it.
            Reply with the two sentences only.

            Book: $title
            Description:
            ${text.take(MAX_PROMPT_CHARS - 800)}
        """.trimIndent()
        val out = clean(runCatching { m.generate(prompt) }.getOrNull() ?: return null)
        return out.takeIf { it.length in 20..400 }
    }

    // ---- 3. Highlights: summary, themes, a fuller note ----

    data class HighlightSummary(val summary: String, val themes: List<Pair<String, List<Int>>>)

    /**
     * What your highlights in a book add up to, and the themes they fall under (each with the
     * numbers of its highlights, 1-based as listed). Null when the answer can't be read.
     */
    fun summarizeHighlights(m: TextModel, book: Book, clips: List<Clipping>): HighlightSummary? {
        if (clips.isEmpty()) return null
        val listed = fit(clips.mapIndexed { i, c -> "${i + 1}. \"${c.text.trim()}\"" + (c.note?.takeIf { it.isNotBlank() }?.let { " (my note: ${it.trim()})" } ?: "") }, MAX_PROMPT_CHARS - 1200)
        val prompt = buildString {
            appendLine("These are passages I highlighted in \"${book.displayTitle}\"" + (book.author?.let { " by $it" } ?: "") + ", with my notes.")
            appendLine()
            listed.forEach { appendLine(it) }
            appendLine()
            appendLine("1. In 3 to 5 sentences, say what my highlights show I noticed and cared about in this book. Use only the passages above; don't add plot details.")
            appendLine("2. Group the highlights into 2 to 5 themes.")
            appendLine()
            appendLine("Answer in exactly this format:")
            appendLine("SUMMARY: <the sentences>")
            appendLine("THEMES:")
            appendLine("- <theme name>: <highlight numbers, comma separated>")
        }
        val reply = runCatching { m.generate(prompt) }.getOrNull() ?: return null
        return parseSummary(reply, listed.size)
    }

    internal fun parseSummary(reply: String, count: Int): HighlightSummary? {
        val text = reply.replace("\r", "")
        val summary = Regex("(?is)SUMMARY:\\s*(.+?)(?:\\n\\s*THEMES:|$)").find(text)?.groupValues?.get(1)?.let { clean(it) }
            ?: clean(text.substringBefore("THEMES:")).takeIf { it.isNotBlank() }
            ?: return null
        val themes = Regex("(?m)^\\s*[-*•]\\s*([^:\\n]{2,40}):\\s*([\\d,\\s]+)$").findAll(text.substringAfter("THEMES:", "")).mapNotNull { mt ->
            val nums = Regex("\\d+").findAll(mt.groupValues[2]).map { it.value.toInt() }.filter { it in 1..count }.distinct().toList()
            if (nums.isEmpty()) null else mt.groupValues[1].trim().trim('*', '"') to nums
        }.toList()
        return HighlightSummary(summary, themes)
    }

    /**
     * A short note on a highlight, written out as a fuller journal entry (first person, a few
     * sentences), keeping your meaning. Null when unusable.
     */
    fun expandNote(m: TextModel, title: String, quote: String, note: String): String? {
        val prompt = """
            I highlighted this passage in "$title":
            "${quote.trim().take(1500)}"

            My quick note: ${note.trim().take(500)}

            Rewrite my note as a short journal entry of 2 to 4 sentences, in first person, in a natural voice. Keep my meaning and opinions; don't add facts about the book that aren't in the passage or my note.
            Reply with the entry only.
        """.trimIndent()
        val out = clean(runCatching { m.generate(prompt) }.getOrNull() ?: return null)
        return out.takeIf { it.length in 20..1200 }
    }

    // ---- 4. A review to start from ----

    /**
     * A first draft of your review, from your rating, highlights and notes (Markdown, first
     * person, no spoilers beyond what you highlighted). You edit it before anything is sent.
     */
    fun draftReview(m: TextModel, book: Book, rating: Double?, clips: List<Clipping>): String? {
        val listed = fit(clips.map { c -> "- \"${c.text.trim()}\"" + (c.note?.takeIf { it.isNotBlank() }?.let { " (my note: ${it.trim()})" } ?: "") }, MAX_PROMPT_CHARS - 1500)
        val prompt = buildString {
            appendLine("Write a first draft of my review of \"${book.displayTitle}\"" + (book.author?.let { " by $it" } ?: "") + ".")
            rating?.let { appendLine("My rating: ${"%.1f".format(java.util.Locale.US, it)} out of 5.") }
            if (listed.isNotEmpty()) {
                appendLine("Passages I highlighted and my notes:")
                listed.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("Write 2 short paragraphs in first person, in a natural, personal voice, matching my rating. Base it only on my highlights and notes; don't invent plot details, characters or events. No headings, no star symbols.")
            append("Reply with the review only.")
        }
        val out = clean(runCatching { m.generate(prompt) }.getOrNull() ?: return null)
        return out.takeIf { it.length in 40..4000 }
    }

    // ---- 5. A page photo: the passage you meant, cleaned ----

    /**
     * The passage on a photographed page that you mean, from a few words you remember of it.
     * Only text that is really on the page comes back (checked against it); else null.
     */
    fun findPassage(m: TextModel, pageText: String, hint: String): String? {
        val page = pageText.take(MAX_PROMPT_CHARS - 800)
        val prompt = """
            Below is the text of a book page, read from a photo (it may have small reading errors and broken lines).
            I highlighted one passage on it. It contains or is about: "${hint.trim().take(300)}"

            Copy that passage exactly as it appears on the page: the whole sentence or sentences, nothing more.
            Reply with the passage only.

            Page:
            $page
        """.trimIndent()
        val out = clean(runCatching { m.generate(prompt) }.getOrNull() ?: return null).trim('"', '“', '”')
        if (out.length < 8) return null
        // It must really be on the page (allowing for the line breaks and hyphens of the photo).
        return out.takeIf { newWordsShare(out, page) <= 0.1 }
    }

    /**
     * The passage with the photo's reading errors fixed: words split across lines joined, stray
     * characters and line breaks removed, wording kept. Null when the answer changes the words.
     */
    fun cleanScan(m: TextModel, text: String): String? {
        val prompt = """
            This passage was read from a photo of a book page and has reading errors: broken lines, words split with hyphens, stray characters, letters misread.
            Fix only those errors. Keep the author's exact wording and punctuation. Do not rewrite, add or remove sentences.
            Reply with the corrected passage only.

            Passage:
            ${text.trim().take(MAX_PROMPT_CHARS - 800)}
        """.trimIndent()
        val out = clean(runCatching { m.generate(prompt) }.getOrNull() ?: return null).trim('"', '“', '”')
        if (out.length < text.trim().length / 2) return null
        // Letter by letter it must stay close: misread letters fixed, nothing rewritten.
        return out.takeIf { similarity(letters(out), letters(text)) >= 0.85 }
    }

    private fun letters(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** 1 for the same letters, toward 0 the more edits it takes to turn one into the other. */
    internal fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val x = a.take(3000); val y = b.take(3000)
        var prev = IntArray(y.length + 1) { it }
        var cur = IntArray(y.length + 1)
        for (i in 1..x.length) {
            cur[0] = i
            for (j in 1..y.length) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return 1.0 - prev[y.length].toDouble() / maxOf(x.length, y.length)
    }

    // ---- Reading-history files: which column is which ----

    /**
     * Which of a CSV's columns holds each thing a reading history needs, for an export whose
     * column names aren't known (another tracker's, a spreadsheet of your own). The model sees
     * the header and a few rows; its answer is a column number per field, and anything it names
     * that isn't a real column is dropped. The caller checks the rest against the values.
     */
    fun csvColumns(m: TextModel, header: List<String>, rows: List<List<String>>): Map<io.github.prithirajn.trackersync.sync.HistoryImport.Field, Int>? {
        if (header.isEmpty()) return null
        val Field = io.github.prithirajn.trackersync.sync.HistoryImport.Field.entries
        val cols = header.take(40)
        val prompt = buildString {
            appendLine("This is a CSV export of someone's books from a reading app. Work out which column holds each kind of information.")
            appendLine()
            appendLine("Columns, with example values:")
            cols.forEachIndexed { i, h ->
                val ex = rows.mapNotNull { it.getOrNull(i)?.trim()?.takeIf { v -> v.isNotEmpty() }?.take(40) }.distinct().take(3)
                appendLine("${i + 1}. \"${h.take(60)}\"" + (if (ex.isEmpty()) " (empty)" else ": " + ex.joinToString(" | ")))
            }
            appendLine()
            appendLine("Kinds of information:")
            for (f in Field) appendLine("- ${f.name}: ${f.label}")
            appendLine()
            appendLine("Reply with one line per kind, as KIND=column number, using 0 when no column holds it. For example: TITLE=2")
            append("Use each column for one kind at most. Reply with those lines only.")
        }.take(MAX_PROMPT_CHARS)
        val reply = runCatching { m.generate(prompt) }.getOrNull() ?: return null
        val out = LinkedHashMap<io.github.prithirajn.trackersync.sync.HistoryImport.Field, Int>()
        for (f in Field) {
            val n = Regex("(?im)\\b${f.name}\\b\\W{0,4}(\\d{1,2})").find(reply)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (n in 1..cols.size && out.values.none { it == n - 1 }) out[f] = n - 1
        }
        return out.takeIf { it.isNotEmpty() }
    }

    // ---- 6. Asking about your library ----

    /**
     * An answer to a question about your reading ("what did I highlight about grief?", "which
     * fantasy books did I abandon?"), from your library and highlights: the books and passages
     * most related to the question are given to the model, and it answers from those only.
     */
    fun ask(m: TextModel, question: String, books: Collection<Book>, clips: List<Clipping>, now: Long = System.currentTimeMillis()): String? {
        val q = question.trim()
        if (q.isEmpty()) return null
        val prompt = askPrompt(q, books, clips, now)
        val out = clean(runCatching { m.generate(prompt) }.getOrNull() ?: return null)
        return out.takeIf { it.isNotBlank() }
    }

    /** The prompt for [ask]: the library as short lines, then the highlights that relate to the question. */
    internal fun askPrompt(question: String, books: Collection<Book>, clips: List<Clipping>, now: Long): String {
        val qWords = words(question)
        val byDoc = books.associateBy { it.document }
        val fmt = java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.US)
        fun line(b: Book): String = buildString {
            append("- \"${b.displayTitle}\"")
            b.author?.let { append(" by $it") }
            append(" — ")
            append(when (b.status) {
                ReadStatus.FINISHED -> "finished" + (b.finishedAt?.let { " " + fmt.format(java.util.Date(it * 1000)) } ?: "")
                ReadStatus.DNF -> "abandoned at ${b.percent}%"
                ReadStatus.PAUSED -> "paused at ${b.percent}%"
                ReadStatus.TO_READ -> "want to read"
                else -> "reading, ${b.percent}%"
            })
            b.rating?.let { append(", rated ${"%.1f".format(java.util.Locale.US, it)}/5") }
            b.titleTags.takeIf { it.isNotEmpty() }?.let { append(", tags: ${it.take(4).joinToString(", ")}") }
            b.description?.let { d -> if (qWords.isNotEmpty() && words(d).intersect(qWords).isNotEmpty()) append(", about: ${d.take(160).replace('\n', ' ')}") }
        }
        // Books related to the question first, then the most recent; highlights that share its words.
        val ranked = books.sortedWith(compareByDescending<Book> { b ->
            words(listOfNotNull(b.title, b.author, b.description?.take(400), b.titleTags.joinToString(" ")).joinToString(" ")).intersect(qWords).size
        }.thenByDescending { it.timestamp })
        val bookLines = fit(ranked.map(::line), 2400)
        val clipLines = fit(
            clips.map { c -> c to words(c.text + " " + (c.note ?: "")).intersect(qWords).size }
                .filter { it.second > 0 || qWords.isEmpty() }
                .sortedByDescending { it.second }
                .map { (c, _) -> "- \"${c.text.trim().take(400)}\"" + (c.note?.takeIf { it.isNotBlank() }?.let { " (my note: ${it.trim().take(200)})" } ?: "") + " — from \"${byDoc[c.document]?.displayTitle ?: "a book"}\"" },
            1600,
        )
        return buildString {
            appendLine("You answer questions about my reading, using only the information below. If it doesn't say, answer that you can't tell from my library. Be brief; name the books.")
            appendLine("Today is ${java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US).format(java.util.Date(now))}.")
            appendLine()
            appendLine("My books (${books.size} in all${if (bookLines.size < books.size) "; the ${bookLines.size} most related are listed" else ""}):")
            bookLines.forEach { appendLine(it) }
            if (clipLines.isNotEmpty()) {
                appendLine()
                appendLine("My highlights that relate to the question:")
                clipLines.forEach { appendLine(it) }
            }
            appendLine()
            append("Question: $question")
        }
    }

    // ---- helpers ----

    /** As many of [lines] as fit in [budget] characters, in order. */
    // ---- 7. Spoilers: which sentences of a review give the story away ----

    /** A sentence's place in the text: [start, end) of its characters, without the space after it. */
    internal data class Span(val start: Int, val end: Int)

    /** The sentences of [text], line by line (Markdown marks and line breaks stay where they are). */
    internal fun sentences(text: String): List<Span> =
        Regex("[^.!?\\n]+(?:[.!?]+[\"'\u201D\u2019)\\]*|]*)?").findAll(text).mapNotNull { mt ->
            val raw = mt.value
            val lead = raw.length - raw.trimStart().length
            val body = raw.trim()
            if (body.count { it.isLetterOrDigit() } < 3) null else Span(mt.range.first + lead, mt.range.first + lead + body.length)
        }.toList()

    /**
     * The sentences of a review that reveal what happens (an ending, a death, a twist, who did it),
     * as indexes into [sentences]. Empty when none does; null when the answer can't be read.
     */
    fun spoilerSentences(m: TextModel, title: String, review: String): List<Int>? {
        val spans = sentences(review).take(40)
        if (spans.isEmpty()) return emptyList()
        val prompt = buildString {
            appendLine("These are the sentences of a review of the book \"$title\".")
            appendLine("Which sentences are spoilers? A spoiler reveals what happens in the story: the ending, a death, a twist, a secret, who did it, or how a relationship turns out.")
            appendLine("Opinions, themes, the writing style and the opening situation are not spoilers.")
            appendLine()
            spans.forEachIndexed { i, sp -> appendLine("${i + 1}. ${review.substring(sp.start, sp.end).take(400)}") }
            appendLine()
            append("Reply with only the numbers of the spoiler sentences, comma separated, or 0 if there are none.")
        }
        val reply = clean(runCatching { m.generate(prompt) }.getOrNull() ?: return null)
        val nums = Regex("\\d+").findAll(reply).map { it.value.toInt() }.toList()
        if (nums.isEmpty()) return null
        return nums.filter { it in 1..spans.size }.distinct().sorted().map { it - 1 }
    }

    // ---- Threads: what a post should be marked, tagged and say ----

    /**
     * Whether any of [passages] from [title] gives the story away (a death, the ending, a twist, a
     * secret, who did it). True or false; null when the answer can't be read.
     */
    fun givesAway(m: TextModel, title: String, passages: List<String>): Boolean? {
        if (passages.isEmpty()) return false
        val prompt = buildString {
            appendLine("These passages are from the book \"$title\". A reader is about to share them publicly.")
            appendLine("Does any of them give the story away? A spoiler reveals what happens: the ending, a death, a twist, a secret, who did it, or how a relationship turns out.")
            appendLine("Beautiful lines, opinions, jokes, descriptions and the opening situation are not spoilers.")
            appendLine()
            passages.take(10).forEachIndexed { i, p -> appendLine("${i + 1}. ${p.trim().take(400)}") }
            appendLine()
            append("Reply with only YES or NO.")
        }
        val reply = clean(runCatching { m.generate(prompt) }.getOrNull() ?: return null).trim().uppercase()
        return when {
            reply.startsWith("YES") -> true
            reply.startsWith("NO") -> false
            else -> null
        }
    }

    /** The topics a book's post can be tagged with. */
    val TOPICS = listOf(
        "Fantasy", "Science Fiction", "LitRPG", "Horror", "Romance", "Romantasy", "Thriller", "Mystery", "Literary Fiction",
        "Historical Fiction", "Young Adult", "Nonfiction", "Memoir", "Self Help", "Poetry", "Manga", "Comics", "Classics", "Books",
    )

    /** One of [TOPICS] for [book], from its title, author and description; null when the answer isn't one of them. */
    fun pickTopic(m: TextModel, book: Book): String? {
        val prompt = buildString {
            appendLine("Book: \"${book.title ?: "?"}\"" + (book.author?.let { " by $it" } ?: ""))
            book.description?.takeIf { it.isNotBlank() }?.let { appendLine("About it: " + it.take(800)) }
            appendLine()
            appendLine("Which one of these fits it best? " + TOPICS.joinToString(", "))
            append("Reply with only that one, exactly as written.")
        }
        val reply = clean(runCatching { m.generate(prompt) }.getOrNull() ?: return null).trim().trim('.', '"', '\'')
        return TOPICS.firstOrNull { it.equals(reply, ignoreCase = true) } ?: TOPICS.firstOrNull { reply.contains(it, ignoreCase = true) }
    }

    /**
     * A short opening line for sharing [passages] from [title] (one or several from one sitting), in
     * a reader's own voice: under 120 characters, no hashtags, no quoting the passages, nothing about
     * what happens. Null when what comes back doesn't keep to that.
     */
    fun postCaption(m: TextModel, title: String, author: String?, passages: List<String>): String? {
        val prompt = buildString {
            appendLine("I'm reading \"$title\"" + (author?.let { " by $it" } ?: "") + " and sharing " + (if (passages.size == 1) "this passage" else "these ${passages.size} passages") + " on Threads:")
            passages.take(10).forEach { appendLine("- " + it.trim().take(300)) }
            appendLine()
            appendLine("Write the one-line caption I'd put above it: casual, warm, in my own voice, under 120 characters.")
            appendLine("No hashtags. Don't quote the passages. Don't reveal anything about the plot. You may name the book.")
            append("Reply with only the caption.")
        }
        val reply = clean(runCatching { m.generate(prompt) }.getOrNull() ?: return null).lines().firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '\u201C', '\u201D') ?: return null
        // Held to what was asked: short, no hashtags, no lines lifted from the passages.
        if (reply.length !in 8..140 || reply.contains('#')) return null
        val lifted = passages.any { p -> p.length > 30 && reply.lowercase().contains(p.trim().lowercase().take(30)) }
        return reply.takeIf { !lifted }
    }

    /** [text] with the sentences at [indexes] hidden as spoilers (||…||), skipping ones already hidden. */
    fun hideSpoilers(text: String, indexes: List<Int>): String {
        val spans = sentences(text)
        val out = StringBuilder(text)
        for (i in indexes.distinct().sortedDescending()) {
            val sp = spans.getOrNull(i) ?: continue
            val body = text.substring(sp.start, sp.end)
            if (body.startsWith("||") || text.substring(0, sp.start).count { it == '|' } % 4 != 0) continue
            out.replace(sp.start, sp.end, "||$body||")
        }
        return out.toString()
    }

    // ---- 8. A messy title from a file: title, author, series ----

    data class CleanTitle(val title: String, val author: String?, val series: String?, val number: String?)

    /**
     * A book file's messy title and author ("the_doomsday_reaver_v2 [LitRPG] - icohalliday",
     * "Clarke, Susanna - Piranesi (epub)") as a clean title, author and series. Every word must
     * come from what you had: it tidies, it doesn't invent. Null when unusable or unchanged.
     */
    fun tidyTitle(m: TextModel, title: String, author: String?): CleanTitle? {
        val prompt = """
            A book file has this title and author, which may be messy (underscores, file names, tags in brackets, series names, the author inside the title, "Last, First" order).
            Title: $title
            Author: ${author ?: "(none)"}

            Give the book's clean title (without the series and without tags), its author as "First Last", and its series name and number if the text says them.
            Use only words that appear above. Write NONE for anything that isn't there.
            Answer in exactly this format:
            TITLE: <title>
            AUTHOR: <author>
            SERIES: <series>
            NUMBER: <number>
        """.trimIndent()
        val reply = clean(runCatching { m.generate(prompt) }.getOrNull() ?: return null)
        return parseCleanTitle(reply, title, author)
    }

    internal fun parseCleanTitle(reply: String, title: String, author: String?): CleanTitle? {
        fun field(name: String): String? = Regex("(?im)^\\s*$name\\s*:\\s*(.+)$").find(reply)?.groupValues?.get(1)?.trim()?.trim('"', '*')
            ?.takeIf { it.isNotEmpty() && !it.equals("none", true) && !it.equals("n/a", true) && !it.startsWith("(") }
        val source = (title + " " + (author ?: "")).replace('_', ' ')
        val have = Regex("[\\p{L}\\p{N}]+").findAll(source.lowercase()).map { it.value }.toSet()
        fun fromSource(v: String?) = v == null || Regex("[\\p{L}\\p{N}]+").findAll(v.lowercase()).all { it.value in have }
        val t = field("TITLE") ?: return null
        val a = field("AUTHOR")
        val sr = field("SERIES")
        val n = field("NUMBER")?.let { Regex("\\d+(?:\\.\\d+)?").find(it)?.value }
        if (!fromSource(t) || !fromSource(a) || !fromSource(sr) || (n != null && n !in source)) return null
        if (t.count { it.isLetterOrDigit() } < 2) return null
        val out = CleanTitle(t, a, sr?.takeIf { !it.equals(t, true) }, n)
        // Nothing to tidy: no suggestion.
        if (out.title == title.trim() && (out.author ?: "") == (author ?: "").trim()) return null
        return out
    }

    // ---- 9. Catch me up: back to a book after a while ----

    /**
     * Where you were in a book you're coming back to: a few sentences in second person from your
     * own highlights and notes up to where you are (nothing past it), so it can't spoil anything.
     */
    fun catchUp(m: TextModel, book: Book, clips: List<Clipping>, daysAway: Int?): String? {
        val upTo = clips.filter { (it.percent ?: 0.0) <= book.percentage + 0.005 }.sortedBy { it.percent ?: 0.0 }
        if (upTo.isEmpty()) return null
        val listed = fit(upTo.map { c ->
            val at = c.percent?.let { " (at ${Math.round(it * 100)}%)" } ?: ""
            "- \"${c.text.trim()}\"$at" + (c.note?.takeIf { it.isNotBlank() }?.let { " my note: ${it.trim()}" } ?: "")
        }.reversed(), MAX_PROMPT_CHARS - 1200).reversed()
        val prompt = buildString {
            appendLine("I'm coming back to \"${book.displayTitle}\"" + (book.author?.let { " by $it" } ?: "") + ". I'm ${book.percent}% through" + (daysAway?.takeIf { it > 1 }?.let { " and haven't read it for $it days" } ?: "") + ".")
            appendLine("These are the passages I highlighted so far, in order, with my notes:")
            listed.forEach { appendLine(it) }
            appendLine()
            appendLine("In 3 or 4 sentences, remind me where I was: what was going on and what I was paying attention to, using only these passages and notes. Speak to me as \"you\". Don't guess what happens next or add anything that isn't in them.")
            append("Reply with the sentences only.")
        }
        val out = clean(runCatching { m.generate(prompt) }.getOrNull() ?: return null)
        return out.takeIf { it.length in 30..1500 }
    }

    // ---- 10. Your month in reading ----

    /**
     * A short, shareable recap of the month: what you finished (with your ratings), pages read,
     * highlights, what you're in the middle of. Every number in it must be one you were given.
     */
    fun monthRecap(m: TextModel, month: String, finished: List<Book>, reading: List<Book>, pages: Int?, highlights: Int): String? {
        val facts = buildList {
            add("Month: $month")
            add("Books finished: ${finished.size}")
            finished.take(15).forEach { b ->
                add("- finished \"${b.displayTitle}\"" + (b.author?.let { " by $it" } ?: "") + (b.rating?.let { " (my rating: ${fmtRating(it)} of 5)" } ?: ""))
            }
            pages?.takeIf { it > 0 }?.let { add("Pages read: $it") }
            if (highlights > 0) add("Passages highlighted: $highlights")
            reading.take(6).forEach { b -> add("- still reading \"${b.displayTitle}\" (${b.percent}% through)") }
        }
        val prompt = buildString {
            appendLine("Write a short, warm recap of my month in reading, in first person, 3 to 5 sentences, for sharing with friends.")
            appendLine("Use only these facts; don't invent books, numbers or opinions I didn't give:")
            facts.forEach { appendLine(it) }
            append("Reply with the recap only.")
        }
        val out = clean(runCatching { m.generate(prompt) }.getOrNull() ?: return null)
        if (out.length !in 30..1500) return null
        // Every number it uses must be one of the facts (a small model likes to make up totals).
        val allowed = Regex("\\d+(?:\\.\\d+)?").findAll(facts.joinToString(" ")).map { it.value }.toSet() +
            facts.joinToString(" ").let { f -> Regex("\\d{1,3}(?:,\\d{3})+").findAll(f).map { it.value.replace(",", "") }.toSet() }
        val used = Regex("\\d[\\d,]*(?:\\.\\d+)?").findAll(out).map { it.value.trimEnd(',').replace(",", "") }.toList()
        if (used.any { it !in allowed && it.replace(",", "") !in allowed }) return null
        return out
    }

    private fun fmtRating(r: Double) = if (r % 1.0 == 0.0) r.toInt().toString() else "%.1f".format(java.util.Locale.US, r)

    // ---- 11. What to read next ----

    data class NextRead(val book: Book, val why: String)

    /**
     * Up to three books from your to-read and paused shelves that fit what you've loved lately,
     * each with a reason. Only books from the list come back.
     */
    fun nextReads(m: TextModel, candidates: List<Book>, loved: List<Book>): List<NextRead>? {
        val list = candidates.take(15)
        if (list.isEmpty()) return null
        val prompt = buildString {
            appendLine("Help me choose my next book.")
            if (loved.isNotEmpty()) {
                appendLine("Books I loved recently:")
                loved.take(8).forEach { b -> appendLine("- \"${b.displayTitle}\"" + (b.author?.let { " by $it" } ?: "") + (b.rating?.let { " (${fmtRating(it)} of 5)" } ?: "")) }
            }
            appendLine("Books waiting on my shelves:")
            list.forEachIndexed { i, b ->
                append("${i + 1}. \"${b.displayTitle}\"" + (b.author?.let { " by $it" } ?: ""))
                if (b.status == ReadStatus.PAUSED) append(" (paused at ${b.percent}%)")
                b.description?.let { d -> append(": " + d.replace(Regex("\\s+"), " ").take(160)) }
                appendLine()
            }
            appendLine()
            appendLine("Pick the 3 that best fit what I loved. For each, give its number and one short reason based only on the information above.")
            append("Answer in exactly this format, one per line:\n<number>: <reason>")
        }
        val reply = clean(runCatching { m.generate(prompt) }.getOrNull() ?: return null)
        val picks = Regex("(?m)^\\s*(?:#|No\\.\\s*)?(\\d{1,2})[.):\\-]\\s*(.+)$").findAll(reply).mapNotNull { mt ->
            val b = list.getOrNull(mt.groupValues[1].toInt() - 1) ?: return@mapNotNull null
            val why = mt.groupValues[2].trim().removePrefix("\"${b.displayTitle}\"").trim(' ', '-', ':', '\u2014')
            NextRead(b, why.take(300).ifEmpty { "Fits what you've enjoyed." })
        }.distinctBy { it.book.document }.take(3).toList()
        return picks.ifEmpty { null }
    }

    // ---- 12. Context-Aware Spoiler Shield ----

    data class SpoilerShieldResult(
        val hasSpoilers: Boolean,
        val shieldedText: String,
        val spoilerSentenceIndices: List<Int>,
        val spoilerCount: Int
    )

    /**
     * Scans [text] (a review, reading note, or journal entry) for spoilers regarding [title].
     * Automatically wraps any spoiler sentences with `||...||` spoiler tags and returns
     * the shielded result.
     */
    fun detectAndShieldSpoilers(m: TextModel, title: String, text: String): SpoilerShieldResult {
        if (text.isBlank()) return SpoilerShieldResult(false, text, emptyList(), 0)
        val indices = spoilerSentences(m, title, text) ?: emptyList()
        val shielded = if (indices.isNotEmpty()) hideSpoilers(text, indices) else text
        return SpoilerShieldResult(
            hasSpoilers = indices.isNotEmpty(),
            shieldedText = shielded,
            spoilerSentenceIndices = indices,
            spoilerCount = indices.size
        )
    }

    // ---- 13. Smart Social Caption & Quote Card Generator (Fable & Pagebound) ----

    data class HighlightCaption(
        val category: String, // "aesthetic", "discussion", "reaction", "fable_club"
        val caption: String,
        val tags: List<String> = emptyList()
    )

    /**
     * Generates creative, high-engagement social captions and discussion prompts for a highlighted quote,
     * formatted for Fable book clubs, Pagebound discussions, and quote sharing.
     */
    fun generateHighlightCaptions(m: TextModel, quote: String, bookTitle: String, author: String?): List<HighlightCaption> {
        val q = quote.trim().take(1200)
        if (q.isBlank()) return emptyList()
        val prompt = buildString {
            appendLine("I highlighted this passage in \"$bookTitle\"" + (author?.let { " by $it" } ?: "") + ":")
            appendLine("\"$q\"")
            appendLine()
            appendLine("Generate 4 distinct social captions for sharing this quote to book communities (Fable, Pagebound):")
            appendLine("1. AESTHETIC: A short evocative or poetic caption (1-2 sentences)")
            appendLine("2. DISCUSSION: A thought-provoking book club question reflecting on the quote")
            appendLine("3. REACTION: A candid, authentic reader reaction")
            appendLine("4. FABLE_CLUB: A concise club post prompt with 2-3 hashtags")
            appendLine()
            appendLine("Format your answer exactly as:")
            appendLine("AESTHETIC: <caption text> | TAGS: <#tag1, #tag2>")
            appendLine("DISCUSSION: <caption text> | TAGS: <#tag1, #tag2>")
            appendLine("REACTION: <caption text> | TAGS: <#tag1, #tag2>")
            appendLine("FABLE_CLUB: <caption text> | TAGS: <#tag1, #tag2>")
        }
        val reply = clean(runCatching { m.generate(prompt) }.getOrNull() ?: return emptyList())
        return parseHighlightCaptions(reply)
    }

    internal fun parseHighlightCaptions(reply: String): List<HighlightCaption> {
        val lines = reply.lines()
        val result = mutableListOf<HighlightCaption>()
        val categories = listOf("AESTHETIC", "DISCUSSION", "REACTION", "FABLE_CLUB")
        for (cat in categories) {
            val line = lines.firstOrNull { it.trimStart().startsWith("$cat:", ignoreCase = true) } ?: continue
            val content = line.substringAfter(":").trim()
            val text = content.substringBefore("| TAGS:").substringBefore("|TAGS:").trim()
            val tagsPart = if (content.contains("TAGS:", ignoreCase = true)) {
                content.substringAfter("TAGS:").trim()
            } else ""
            val tags = Regex("#[\\p{L}\\p{N}_]+").findAll(tagsPart).map { it.value }.toList()
            if (text.isNotBlank()) {
                result.add(HighlightCaption(cat.lowercase(), text, tags))
            }
        }
        return result
    }

    // ---- 14. Cross-Platform Review Adapter ----

    data class TrackerReviewBundle(
        val goodreadsReview: String,
        val storyGraphReview: String,
        val storyGraphMoods: List<String>,
        val storyGraphPacing: String, // "fast", "medium", "slow"
        val fableClubPost: String
    )

    /**
     * Adapts a single raw review or set of reading notes into three distinct tracker formats:
     * - Goodreads: Comprehensive review with first-person perspective.
     * - StoryGraph: Emotion- and tone-focused review with mood tags and pacing.
     * - Fable: Conversational book-club discussion post (under 280 chars).
     */
    fun adaptReviewForTrackers(m: TextModel, rawReview: String, rating: Double?, book: Book): TrackerReviewBundle? {
        val raw = rawReview.trim().take(2000)
        if (raw.isBlank()) return null
        val prompt = buildString {
            appendLine("Adapt this reader's review of \"${book.displayTitle}\"" + (book.author?.let { " by $it" } ?: "") + (rating?.let { " (rating: ${fmtRating(it)}/5)" } ?: "") + " into 3 tracker formats:")
            appendLine()
            appendLine("Raw Review:")
            appendLine(raw)
            appendLine()
            appendLine("Requirements:")
            appendLine("1. GOODREADS: Complete review with structure and reading takeaway.")
            appendLine("2. STORYGRAPH: Tone-focused review, pacing (fast, medium, or slow), and 2-4 mood tags from: [adventurous, dark, emotional, funny, hopeful, mysterious, reflective, relaxing, sad, tense].")
            appendLine("3. FABLE: Short book club post (under 280 chars) to spark discussion.")
            appendLine()
            appendLine("Answer in exactly this format:")
            appendLine("GOODREADS:")
            appendLine("<goodreads text>")
            appendLine("STORYGRAPH_PACING: <fast|medium|slow>")
            appendLine("STORYGRAPH_MOODS: <comma-separated moods>")
            appendLine("STORYGRAPH:")
            appendLine("<storygraph text>")
            appendLine("FABLE:")
            appendLine("<fable text>")
        }
        val reply = clean(runCatching { m.generate(prompt) }.getOrNull() ?: return null)
        return parseTrackerReviewBundle(reply, rawReview)
    }

    internal fun parseTrackerReviewBundle(reply: String, fallback: String): TrackerReviewBundle? {
        val text = reply.replace("\r", "")
        val gr = Regex("(?is)GOODREADS:\\s*(.+?)(?:\\n\\s*STORYGRAPH_PACING:|$)").find(text)?.groupValues?.get(1)?.trim() ?: fallback
        val pacing = Regex("(?im)^\\s*STORYGRAPH_PACING:\\s*([a-zA-Z]+)").find(text)?.groupValues?.get(1)?.lowercase()?.trim() ?: "medium"
        val moodsStr = Regex("(?im)^\\s*STORYGRAPH_MOODS:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim() ?: ""
        val moods = moodsStr.split(",").map { it.trim().lowercase().trim('"', '\'') }.filter { it.isNotBlank() }
        val sg = Regex("(?is)STORYGRAPH:\\s*(.+?)(?:\\n\\s*FABLE:|$)").find(text)?.groupValues?.get(1)?.trim() ?: fallback
        val fable = Regex("(?is)FABLE:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim() ?: fallback.take(280)
        return TrackerReviewBundle(
            goodreadsReview = gr,
            storyGraphReview = sg,
            storyGraphMoods = if (moods.isNotEmpty()) moods else listOf("reflective", "emotional"),
            storyGraphPacing = if (pacing in listOf("fast", "medium", "slow")) pacing else "medium",
            fableClubPost = fable
        )
    }

    // ---- 15. Pace Forecaster ("Will I Finish This Week?") ----

    data class PaceForecast(
        val pagesRemaining: Int,
        val estimatedDaysRemaining: Int,
        val pagesPerDayNeededForTarget: Int,
        val willFinishByTarget: Boolean,
        val coachSummary: String
    )

    /**
     * Computes remaining reading requirements and estimates finish date based on current daily pace
     * and an optional target deadline in days (default 7 for "this week").
     */
    fun forecastPaceAndFinish(
        m: TextModel?,
        book: Book,
        currentPagesPerDay: Int,
        targetDays: Int = 7
    ): PaceForecast {
        val total = (book.pageCount ?: 350).coerceAtLeast(1)
        val current = book.currentPage ?: Math.round((book.percentage * total)).toInt().coerceIn(0, total)
        val remaining = (total - current).coerceAtLeast(0)
        val pace = currentPagesPerDay.coerceAtLeast(1)
        val days = kotlin.math.ceil(remaining.toDouble() / pace).toInt()
        val needed = if (targetDays > 0) kotlin.math.ceil(remaining.toDouble() / targetDays).toInt() else remaining
        val willFinish = days <= targetDays

        val fallbackAdvice = if (remaining == 0) {
            "You've already finished \"${book.displayTitle}\"!"
        } else if (willFinish) {
            "At your current pace of $pace pages/day, you'll easily finish \"${book.displayTitle}\" in $days day${if (days == 1) "" else "s"}, well within your $targetDays-day target."
        } else {
            "With $remaining pages left, you'll need around $needed pages/day (up from $pace) to finish within $targetDays days."
        }

        var aiCoach = fallbackAdvice
        if (m != null && remaining > 0) {
            val prompt = """
                You are a friendly, encouraging reading coach. In 2 short sentences, summarize this reader's pace and give advice on finishing:
                Book: "${book.displayTitle}"
                Pages remaining: $remaining of $total
                Current pace: $pace pages/day
                Days left to finish at current pace: $days
                Target deadline: $targetDays days ($needed pages/day needed)
                Will finish on time: $willFinish
                Use only these numbers. Reply with the 2 sentences only.
            """.trimIndent()
            val reply = clean(runCatching { m.generate(prompt) }.getOrNull() ?: "")
            if (reply.length in 25..500) {
                aiCoach = reply
            }
        }

        return PaceForecast(
            pagesRemaining = remaining,
            estimatedDaysRemaining = days,
            pagesPerDayNeededForTarget = needed,
            willFinishByTarget = willFinish,
            coachSummary = aiCoach
        )
    }

    // ---- 16. Sync Conflict Explainer ----

    data class SourcePosition(
        val sourceName: String,
        val percent: Int,
        val page: Int? = null,
        val lastReadTimestamp: Long,
        val deviceName: String? = null
    )

    data class SyncConflictExplanation(
        val recommendedSource: String,
        val recommendedPercent: Int,
        val explanation: String
    )

    /**
     * Analyzes divergent reading positions reported across reading sources (Kindle, CrossPoint,
     * KOSync, Libby) and trackers, and explains the divergence in plain English with a recommendation.
     */
    fun explainSyncConflict(
        m: TextModel?,
        bookTitle: String,
        positions: List<SourcePosition>
    ): SyncConflictExplanation {
        if (positions.isEmpty()) {
            return SyncConflictExplanation("None", 0, "No source positions to compare.")
        }
        if (positions.size == 1) {
            val s = positions.first()
            return SyncConflictExplanation(s.sourceName, s.percent, "Only ${s.sourceName} reported progress at ${s.percent}%.")
        }

        val sortedByTime = positions.sortedByDescending { it.lastReadTimestamp }
        val latest = sortedByTime.first()
        val maxPercent = positions.maxByOrNull { it.percent } ?: latest

        val rec = if (latest.percent >= maxPercent.percent - 5) latest else maxPercent
        val fallback = "${rec.sourceName} has the most advanced progress at ${rec.percent}%, while other sources lag behind. Adopting ${rec.sourceName}'s position keeps your reading history consistent."

        var explanation = fallback
        if (m != null) {
            val fmt = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US)
            val prompt = buildString {
                appendLine("Explain the reading progress conflict for \"$bookTitle\" in 2 concise sentences and recommend which position to use:")
                positions.forEach { p ->
                    val dev = p.deviceName?.let { " ($it)" } ?: ""
                    val pg = p.page?.let { ", page $it" } ?: ""
                    val dt = fmt.format(java.util.Date(p.lastReadTimestamp * 1000))
                    appendLine("- ${p.sourceName}$dev: ${p.percent}%$pg, synced $dt")
                }
                appendLine()
                appendLine("Recommend ${rec.sourceName} at ${rec.percent}%. Reply with the 2 explanatory sentences only.")
            }
            val reply = clean(runCatching { m.generate(prompt) }.getOrNull() ?: "")
            if (reply.length in 25..500) {
                explanation = reply
            }
        }

        return SyncConflictExplanation(
            recommendedSource = rec.sourceName,
            recommendedPercent = rec.percent,
            explanation = explanation
        )
    }

    // ---- 17. Mood & Vibe Shelf Matcher ----

    data class VibeMatch(
        val book: Book,
        val vibeScore: Double, // 0.0 to 1.0
        val matchReason: String
    )

    /**
     * Performs semantic shelf matching against to-read or paused books using natural-language vibe,
     * mood, or atmosphere queries (e.g. "atmospheric rainy gothic horror", "fast witty sci-fi space heist").
     */
    fun searchByMoodAndVibe(
        m: TextModel,
        vibeQuery: String,
        candidates: List<Book>
    ): List<VibeMatch> {
        val list = candidates.take(12)
        if (list.isEmpty() || vibeQuery.isBlank()) return emptyList()

        val prompt = buildString {
            appendLine("Match the reader's mood/vibe query against candidate books from their shelf:")
            appendLine("VIBE QUERY: \"$vibeQuery\"")
            appendLine()
            appendLine("Candidates:")
            list.forEachIndexed { i, b ->
                append("${i + 1}. \"${b.displayTitle}\"" + (b.author?.let { " by $it" } ?: ""))
                b.description?.let { d -> append(": " + d.replace(Regex("\\s+"), " ").take(140)) }
                b.titleTags.takeIf { it.isNotEmpty() }?.let { append(" [${it.joinToString(", ")}]") }
                appendLine()
            }
            appendLine()
            appendLine("Select the top 3 best matching books. For each, give the candidate number, a fit score (0.0 to 1.0), and 1 short sentence why it fits the vibe.")
            appendLine("Answer in exactly this format, one per line:")
            append("<number> | <score> | <reason>")
        }
        val reply = clean(runCatching { m.generate(prompt) }.getOrNull() ?: return emptyList())
        return parseVibeMatches(reply, list)
    }

    internal fun parseVibeMatches(reply: String, candidates: List<Book>): List<VibeMatch> {
        return Regex("(?m)^\\s*(?:#|No\\.\\s*)?(\\d{1,2})\\s*\\|\\s*([0-9.]+)\\s*\\|\\s*(.+)$").findAll(reply).mapNotNull { mt ->
            val idx = mt.groupValues[1].toIntOrNull()?.minus(1) ?: return@mapNotNull null
            val book = candidates.getOrNull(idx) ?: return@mapNotNull null
            val score = mt.groupValues[2].toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.8
            val reason = mt.groupValues[3].trim().take(300)
            VibeMatch(book, score, reason)
        }.distinctBy { it.book.document }.take(3).toList()
    }

    private fun fit(lines: List<String>, budget: Int): List<String> {
        val out = ArrayList<String>()
        var used = 0
        for (l in lines) {
            if (used + l.length + 1 > budget) break
            out += l
            used += l.length + 1
        }
        return out
    }

    /** The share of words in [out] that [source] doesn't have (0 = all from the source). */
    internal fun newWordsShare(out: String, source: String): Double {
        val src = Regex("[\\p{L}\\p{N}]+").findAll(source.lowercase().replace(Regex("-\\s*\\n\\s*"), "")).map { it.value }.toSet()
        val ws = Regex("[\\p{L}\\p{N}]+").findAll(out.lowercase()).map { it.value }.toList()
        if (ws.isEmpty()) return 1.0
        return ws.count { it !in src }.toDouble() / ws.size
    }

    /** The reply without the bits small models add: "Here is…" lead-ins, code fences, end-of-turn tags. */
    internal fun clean(reply: String): String {
        var t = reply.replace("\r", "")
        // A model's reasoning (Qwen3, DeepSeek), when it shows it, isn't the answer.
        t = t.replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("(?s)^.*</think>"), "")
        t = t.replace(Regex("<(?:end_of_turn|start_of_turn|eos|/s|\\|im_end\\||\\|eot_id\\|)>[^\\n]*"), "")
        t = t.replace(Regex("^```[a-z]*\\n?|```$", RegexOption.MULTILINE), "")
        t = t.replace(Regex("(?i)^\\s*(here is|here's|sure[,!]?|certainly[,!]?)[^\\n]*:\\s*\\n"), "")
        return t.trim()
    }
}
