package io.github.prithirajn.trackersync.trackers

import io.github.prithirajn.trackersync.data.Link
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Visibility
import org.json.JSONObject

/** A search hit on a tracker. */
data class RemoteBook(
    val id: String,
    val title: String,
    val author: String?,
    val uuid: String? = null,
    val coverUrl: String? = null,
    val pages: Int? = null,
    /** How many ratings the tracker shows; breaks ties between editions with the same title. */
    val popularity: Int? = null,
    // Shown when picking a match by hand, to tell editions apart. Each tracker fills what it has.
    /** Average rating out of 5. */
    val rating: Double? = null,
    val ratingsCount: Int? = null,
    val reviewsCount: Int? = null,
    /** People who've read or shelved it (Hardcover). */
    val readers: Int? = null,
    val year: Int? = null,
    /** Paperback, Kindle Edition, Audiobook… */
    val format: String? = null,
    val editions: Int? = null,
    /** Already on one of the user's shelves on this tracker (set by the app, not the tracker). */
    val onShelf: Boolean = false,
)

/** "12,345", "12.3k", "1.2M" → a number; null when there's none. */
fun parseCount(raw: String?): Int? {
    val t = raw?.trim()?.replace(",", "")?.lowercase() ?: return null
    val m = Regex("^(\\d+(?:\\.\\d+)?)\\s*([km])?$").find(t) ?: return null
    val n = m.groupValues[1].toDoubleOrNull() ?: return null
    val mult = when (m.groupValues[2]) { "k" -> 1_000.0; "m" -> 1_000_000.0; else -> 1.0 }
    return Math.round(n * mult).toInt()
}

/** First of [keys] that holds a number (or a numeric string). For APIs whose field names vary. */
fun JSONObject.firstNumber(vararg keys: String): Double? {
    for (k in keys) {
        when (val v = opt(k)) {
            is Number -> return v.toDouble()
            is String -> v.trim().toDoubleOrNull()?.let { return it }
        }
    }
    return null
}

/** A four-digit year from the first of [keys] that has one ("2016", "2016-05-03", 2016). */
fun JSONObject.firstYear(vararg keys: String): Int? {
    for (k in keys) {
        val v = opt(k) ?: continue
        if (v == JSONObject.NULL) continue
        Regex("\\b(1[5-9]\\d\\d|20\\d\\d)\\b").find(v.toString())?.let { return it.groupValues[1].toInt() }
    }
    return null
}

/** What the tracker shows for a linked book. [percent] is null where it can't be read back. */
data class RemoteState(val status: ReadStatus?, val percent: Int?)

/** How a tracker signs in. */
enum class SignInKind { PASSWORD, WEBVIEW, TOKEN, DEVICE_CODE }

/** A pairing started on a tracker: the user approves [userCode] at [verificationUri]. */
data class DeviceLink(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
)

sealed class DevicePoll {
    data class Linked(val result: SignInResult) : DevicePoll()
    object Pending : DevicePoll()
    data class Failed(val message: String) : DevicePoll()
}

/** What "Only me" can do on a tracker. */
enum class PrivacySupport {
    /** The progress request itself has a visibility flag (Pagebound). */
    PER_UPDATE_FLAG,

    /** No flag: Only me sends status changes and skips progress posts (StoryGraph, Goodreads). */
    SKIP_PROGRESS,

    /** Progress is sent privately; a public mode isn't known yet (Fable). */
    PRIVATE_ONLY,
}

/**
 * A tracker's login, as a JSON object the adapter reads and updates (refreshed tokens,
 * new cookies). The sync engine saves it back encrypted when [changed] is set.
 */
class Session(json: String?) {
    val data: JSONObject = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    /** The service's "Who can see updates" setting for this pass; not saved with the login. */
    var visibility: Visibility = Visibility.PRIVATE
    var changed: Boolean = false
        private set

    fun put(key: String, value: Any?) {
        data.put(key, value ?: JSONObject.NULL)
        changed = true
    }

    fun encode(): String = data.toString()
}

/** What a tracker takes from the finish flow. */
enum class ReviewSupport { NONE, RATING, RATING_AND_TEXT }

/** Where highlights can go on a tracker. */
enum class HighlightSupport {
    NONE,

    /** A private place, like StoryGraph's reading journal. */
    PRIVATE_JOURNAL,

    /** Only a public post (Goodreads status, Pagebound forum): needs "Everyone". */
    PUBLIC_POST,
}

data class SignInResult(val credential: String, val account: String)

/**
 * A book on the user's own shelves on a tracker that can act as a source. [fraction] is 0–1;
 * [updatedAt] is unix seconds.
 */
data class LibraryBook(
    val id: String,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val status: ReadStatus?,
    val fraction: Double?,
    val updatedAt: Long,
    /** Every id the tracker gives this book ("book:7", "work:70"…), to recognise it later. */
    val keys: Set<String> = emptySet(),
    /** When it was finished there (unix seconds), for finished books. */
    val finishedAt: Long? = null,
    val startedAt: Long? = null,
    /** Your rating there (stars out of 5), when the tracker shares it. */
    val rating: Double? = null,
    /** Print page count, when the tracker has one. */
    val pageCount: Int? = null,
    /** "ebook", "audiobook", "physical"… as the tracker names it. */
    val mediaKind: String? = null,
    val description: String? = null,
    /** Your review there, when the tracker shares it (for bringing your history in). */
    val review: String? = null,
)

interface Tracker {
    /**
     * The smallest rating step this tracker takes (stars). Ratings here can be finer (Seekquel
     * gives tenths), so each tracker gets the nearest step it has: 4.3 is 4.5 at half stars and
     * 4.25 at quarter stars; exact halfway rounds up.
     */
    val ratingStep: Double get() = 0.5

    /** [rating] at this tracker's step, between one step and 5. */
    fun stepRating(rating: Double): Double {
        val n = Math.round(rating / ratingStep + 1e-9).toDouble()
        // Rounded to hundredths so 43 × 0.1 is sent as 4.3, not 4.300000000000001.
        return (Math.round(n * ratingStep * 100) / 100.0).coerceIn(ratingStep, 5.0)
    }

    val id: String
    val name: String
    val signInKind: SignInKind
    val privacy: PrivacySupport

    /** True when [state] returns the remote percentage (enables never-go-backwards). */
    val readsProgress: Boolean
    val statuses: Set<ReadStatus>

    /** For WEBVIEW sign-in: the page to open, and the cookie that proves the user is signed in. */
    val signInUrl: String? get() = null
    val cookieUrl: String? get() = null
    val sessionCookie: String? get() = null

    /** One line under the privacy setting explaining what Only me means here. */
    val privacyNote: String

    fun signInWithPassword(email: String, password: String): SignInResult =
        throw UnsupportedOperationException()

    fun signInWithCookies(cookieHeader: String, userAgent: String): SignInResult =
        throw UnsupportedOperationException()

    fun signInWithToken(token: String): SignInResult = throw UnsupportedOperationException()

    /** For TOKEN sign-in: where to get the token, shown on the sign-in sheet. */
    val tokenHelp: String get() = ""

    fun beginDeviceLink(): DeviceLink = throw UnsupportedOperationException()
    fun pollDeviceLink(deviceCode: String): DevicePoll = throw UnsupportedOperationException()

    /**
     * True when the tracker knows books by CrossPoint's own document id (Seekquel speaks
     * KOSync), so nothing is searched: every book links to itself.
     */
    val matchesByDocument: Boolean get() = false

    /** For DEVICE_CODE sign-in: where the user enters the code. */
    val pairingHelp: String get() = ""

    /**
     * For [matchesByDocument] trackers: the user picked [book] by hand, so tell the tracker
     * this document is that book. Others link locally and need nothing here.
     */
    fun linkToBook(session: Session, document: String, title: String?, author: String?, book: RemoteBook, percent: Int) {}

    fun search(session: Session, title: String, author: String?): List<RemoteBook>

    /**
     * Books already on the user's reading and to-read shelves. Matching tries these first,
     * so the edition the user already shelved wins over another edition from search.
     */
    fun shelfCandidates(session: Session): List<RemoteBook> = emptyList()
    fun state(session: Session, link: Link): RemoteState
    fun setStatus(session: Session, link: Link, status: ReadStatus)
    fun pushProgress(session: Session, link: Link, percent: Int, visibility: Visibility)

    /**
     * For a tracker that doesn't report progress to apps ([readsProgress] false): the percentage
     * its own site shows, read the way the page gets it, for the "What each tracker says" view.
     * Best effort; null when it can't be found. Never used to decide what's sent.
     */
    fun peekPercent(session: Session, link: Link): Int? = null

    /**
     * For a tracker that keeps a daily reading streak (Fable): marks the book as read today when
     * you read it but the whole-number percentage didn't move, so no progress went out. Once a
     * day per book; true when it was sent just now (not when it already had today).
     */
    fun readToday(session: Session, link: Link): Boolean = false

    /**
     * True when each highlight entry also sets your reading progress there (a Goodreads status
     * update, a StoryGraph journal entry): entries go out at each highlight's own position, and
     * your real position is sent again after them.
     */
    val highlightEntriesSetProgress: Boolean get() = false

    /**
     * After adding a book: which of its details the tracker didn't keep ("cover", "description"…),
     * read back from it, or null when it can't tell. Named in the Add sheet and the activity log.
     */
    fun missingAfterAdd(session: Session, document: String, remoteId: String, details: io.github.prithirajn.trackersync.source.BookDetails): List<String>? = null

    /** Whether a book it doesn't have can be added to it from the app. */
    val addsBooks: Boolean get() = false

    /** What adding a book there means ("Added to StoryGraph's catalogue; volunteer librarians may review it"). */
    val addBookNote: String? get() = null

    /**
     * Creates the book on the tracker from [details] (for [document], the book here) and returns
     * it, to match the book to. Throws when the tracker refuses.
     */
    fun addBook(session: Session, document: String, details: io.github.prithirajn.trackersync.source.BookDetails): RemoteBook =
        throw io.github.prithirajn.trackersync.net.ServiceException("$name doesn't let apps add books")

    /** The tracker's own add-a-book page, to open in the app filled in when adding through its form fails. */
    val addBookPage: String? get() = null

    /** The new book's id in the address the site goes to after it's created. */
    val addedBookId: Regex? get() = null

    /** The tracker's own book id for what [addedBookId] found (Hardcover's addresses carry a name, not the id). */
    fun resolveAddedId(session: Session, found: String): String = found

    /** The sign-in cookies to open [addBookPage] as you: (site address, "name=value") pairs. */
    fun webCookies(session: Session): List<Pair<String, String>> = emptyList()

    /** Whether a highlight sent on its own (with its id kept) can be deleted there. */
    val deletesHighlights: Boolean get() = false

    /** Deletes the entry [remoteId] that a highlight became. */
    fun deleteHighlight(session: Session, remoteId: String) {
        throw io.github.prithirajn.trackersync.net.ServiceException("$name can't delete highlights from apps")
    }

    /** Whether a progress update can carry new highlights in the same entry ([pushProgressWithHighlights]). */
    val progressCarriesHighlights: Boolean get() = false

    /** Progress and [clips] in one entry; false when they don't fit, so the caller sends progress alone. */
    fun pushProgressWithHighlights(
        session: Session, link: Link, percent: Int, visibility: Visibility,
        clips: List<io.github.prithirajn.trackersync.source.Clipping>,
    ): Boolean = false

    /** True when [library] can list the user's books, so they can be sent on to other trackers. */
    val readsLibrary: Boolean get() = false

    /** The user's books with their status and progress. Only for [readsLibrary] trackers. */
    fun library(session: Session): List<LibraryBook> = emptyList()

    /**
     * The library ids (as in [LibraryBook.keys]) of a book this app sent there, so it's
     * recognised when the library is read back. Empty until the tracker has matched it.
     */
    fun libraryKeys(session: Session, link: Link): Set<String> = emptySet()

    /** Ratings (out of 5) and review text, from the finish flow. */
    val reviews: ReviewSupport get() = ReviewSupport.NONE

    /** Saves a rating and/or review text; a null leaves that part as it is there. */
    fun setReview(session: Session, link: Link, rating: Double?, text: String?): Unit =
        throw UnsupportedOperationException()

    /** True when [setDates] puts real start and finish days on the tracker. */
    val takesDates: Boolean get() = false

    /** The days you started and finished this book (null leaves that date as it is). */
    fun setDates(session: Session, link: Link, started: java.time.LocalDate?, finished: java.time.LocalDate?) {}

    /** Editions with this ISBN (13 or 10 digits), for adding a physical book by its barcode. */
    fun findByIsbn(session: Session, isbn: String): List<RemoteBook> = emptyList()

    val highlights: HighlightSupport get() = HighlightSupport.NONE

    /**
     * True when highlights go out as posts whose audience is set on the post itself (Fable), so
     * they don't depend on "Who can see updates" (which this tracker doesn't let apps choose).
     */
    val highlightsIgnoreVisibility: Boolean get() = false

    /** One line under the highlights switch saying where they go. */
    val highlightsNote: String get() = ""

    /** True when each highlight is its own entry there anyway (so each is sent alone, keeping its id). */
    val oneEntryPerHighlight: Boolean get() = false

    /** True when [postHighlight] can replace an entry it sent before, given that entry's id. */
    val updatesHighlights: Boolean get() = false

    /**
     * Sends one highlight as its own entry and returns the entry's id there (when known), or,
     * with [previousId], replaces that entry where the tracker allows; otherwise posts again.
     */
    fun postHighlight(
        session: Session,
        link: Link,
        percent: Int,
        clip: io.github.prithirajn.trackersync.source.Clipping,
        previousId: String?,
    ): String? {
        postHighlights(session, link, percent, listOf(clip))
        return null
    }

    /** Sends a batch of new highlights for one book as one entry (or a few, if long). */
    fun postHighlights(session: Session, link: Link, percent: Int, entries: List<io.github.prithirajn.trackersync.source.Clipping>): Unit =
        throw UnsupportedOperationException()
}

/** Formats highlights the same way for every tracker; splits into parts of at most [limit] chars. */
fun formatHighlights(entries: List<io.github.prithirajn.trackersync.source.Clipping>, limit: Int): List<String> {
    val blocks = entries.sortedBy { it.createdAt }.map { c ->
        buildString {
            append(quoted(c.text))
            c.note?.trim()?.takeIf { it.isNotEmpty() }?.let { append("\nNote: ").append(it) }
            val where = listOfNotNull(
                c.chapter?.trim()?.takeIf { it.isNotEmpty() },
                io.github.prithirajn.trackersync.sync.HighlightPosition.label(c),
            ).joinToString(" \u00B7 ")
            if (where.isNotEmpty()) append("\n\u2014 ").append(where)
        }
    }
    val parts = ArrayList<String>()
    val cur = StringBuilder()
    for (b in blocks) {
        val piece = if (b.length > limit) b.take(limit - 1) + "\u2026" else b
        if (cur.isNotEmpty() && cur.length + 2 + piece.length > limit) {
            parts.add(cur.toString()); cur.setLength(0)
        }
        if (cur.isNotEmpty()) cur.append("\n\n")
        cur.append(piece)
    }
    if (cur.isNotEmpty()) parts.add(cur.toString())
    return parts
}

object Trackers {
    val all: List<Tracker> =
        listOf(HardcoverTracker, FableTracker, PageboundTracker, StoryGraphTracker, GoodreadsTracker, SeekquelTracker)
    fun byId(id: String): Tracker? = all.firstOrNull { it.id == id }
}

/** The reason a server gave for refusing a request ("message", "error", "errors"), shortened. */
fun serverReason(body: String?): String? {
    if (body.isNullOrBlank()) return null
    val o = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return body.trim().takeIf { it.length in 1..160 && !it.startsWith("<") }
    fun text(v: Any?): String? = when (v) {
        null, org.json.JSONObject.NULL -> null
        is String -> v
        is org.json.JSONArray -> (0 until v.length()).mapNotNull { text(v.opt(it)) }.joinToString("; ")
        is org.json.JSONObject -> v.keys().asSequence().mapNotNull { k -> text(v.opt(k))?.let { "$k $it" } }.joinToString("; ")
        else -> v.toString()
    }?.takeIf { it.isNotBlank() }
    return (text(o.opt("message")) ?: text(o.opt("error")) ?: text(o.opt("errors")))?.take(160)
}

/**
 * A percentage (0–100) found anywhere in [v] under a key that names one ("current_percentage",
 * "percent", "percentComplete"…), preferring the deepest, most specific match; a page/total
 * pair counts too. Null when there's none.
 */
fun findPercent(v: Any?, depth: Int = 0): Int? {
    if (depth > 8) return null
    when (v) {
        is org.json.JSONObject -> {
            for (k in v.keys()) {
                val key = k.lowercase()
                if ((key.contains("percent") || key == "progress_percentage") && !key.contains("rating")) {
                    val n = v.opt(k)
                    val d = when (n) { is Number -> n.toDouble(); is String -> n.toDoubleOrNull(); else -> null }
                    if (d != null && d in 0.0..100.0) return Math.round(if (d <= 1.0 && key.contains("fraction")) d * 100 else d).toInt()
                }
            }
            for (k in v.keys()) findPercent(v.opt(k), depth + 1)?.let { return it }
        }
        is org.json.JSONArray -> for (i in 0 until v.length()) findPercent(v.opt(i), depth + 1)?.let { return it }
    }
    return null
}

/** A passage in quotation marks, cleanly stripping doubled or tripled marks from dialogue highlights. */
fun quoted(text: String): String {
    // Invisible characters at the ends (zero-width spaces, byte-order marks) don't hide a quote mark.
    var t = text.trim { it.isWhitespace() || it in "\u200B\u200C\u200D\u2060\uFEFF" }
    if (t.isEmpty()) return ""
    val quotes = "\"\u201C\u201D\u201E\u201F\u2018\u2019\u201A\u201B'\u00AB\u00BB\u2033"
    val startsWithQuote = t.first() in quotes

    // If it already starts with a quotation mark, strip any trailing redundant duplicates
    if (startsWithQuote) {
        while (t.length > 1 && t.last() in quotes && t[t.length - 2] in quotes) {
            t = t.substring(0, t.length - 1).trimEnd()
        }
        return t
    }

    // Text did not start with a quote, but may end with dialogue quotes: strip them before wrapping
    while (t.isNotEmpty() && t.last() in quotes) {
        t = t.substring(0, t.length - 1).trimEnd()
    }
    return "\u201C$t\u201D"
}

