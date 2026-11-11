package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.ProgressAsk
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.data.Store
import io.github.prithirajn.trackersync.trackers.Trackers
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Each book's main source (its "primary"): where its position is trusted from.
 *
 * - A book's primary is the source it first came in from (CrossPoint for CrossPoint's books,
 *   Kindle for one first seen on Kindle…). The order below breaks ties and can grow.
 * - When the primary reports a new position during a sync, the other sources aren't looked at
 *   for that book in that sync (saving their per-book checks, like KOSync's).
 * - A position from another source that's further along isn't taken by itself: the book shows a
 *   card to keep the position here, update to the new one, or (where that source can be written
 *   to) move that source back to the position here. Trackers get nothing for the book until then.
 * - The other sources are ranked for each book (secondary, tertiary…): one whose position you take
 *   moves up, so its reports win over lower-ranked ones.
 * - A source that keeps being further along takes over as the primary, and the old primary
 *   becomes the secondary: when it's more than 10% ahead, or reports reading on 3 days in a row.
 *
 * The sources' own code is unchanged: each one's changes pass through [gate] (on the store),
 * which keeps or holds back each moved book.
 */
object Primary {

    /** The sources, in the order they're trusted when nothing else says (can grow). */
    val ORDER = listOf("crosspoint", "kosync", "koreader", "seekquel", "kindle", "libby", "mihon", "moonreader", "audible")

    /** More than this far ahead (0–1), a source takes over as the primary. */
    const val TAKEOVER_LEAD = 0.10

    /** On this many days in a row of reading further, a source takes over as the primary. */
    const val TAKEOVER_DAYS = 3

    /** For sources that report books from the trackers they came from: the book's own origin is the source. */
    const val BY_ORIGIN = "*origin"

    fun name(id: String): String = when (id) {
        "crosspoint" -> "CrossPoint"
        "kosync" -> "KOSync"
        "koreader" -> "KOReader"
        "seekquel" -> "Seekquel"
        "kindle" -> "Kindle"
        "libby" -> "Libby"
        "mihon" -> "Mihon"
        "moonreader" -> "Moon+ Reader"
        "audible" -> "Audible"
        else -> Trackers.byId(id)?.name ?: id
    }

    /** Sources a position can be written back to (for "move it back"). */
    fun canMoveBack(id: String): Boolean = id == "crosspoint" || id == "kosync" || id == "seekquel"

    private val current = ThreadLocal<String?>()

    /** Books whose primary reported a new position in this sync: other sources aren't looked at for them. */
    val fresh: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** What changed hands this sync, for the log. */
    val notes = ConcurrentLinkedQueue<String>()

    /** Runs [block] as [source]: every change it makes to the store passes through [gate]. */
    fun <T> within(source: String, block: () -> T): T {
        val prev = current.get()
        current.set(source)
        try {
            return block()
        } finally {
            current.set(prev)
        }
    }

    /** A new sync: nothing reported yet. */
    fun startSync() = fresh.clear()

    /** The primary a book's origin implies, when it hasn't one of its own yet. */
    internal fun inferred(origin: String?): String? = when {
        origin == null -> "crosspoint"
        origin in ORDER -> origin
        origin.startsWith("history:") -> null
        Trackers.byId(origin) != null -> origin
        else -> null
    }

    fun of(s: Snapshot, document: String): String? = s.primaries[document] ?: s.books[document]?.let { inferred(it.origin) }

    /** Where [source] stands for a book: 0 its primary, then its ranked others, then the default order. */
    fun rank(s: Snapshot, document: String, source: String): Int {
        if (of(s, document) == source) return 0
        val ranked = s.sourceRanks[document] ?: emptyList()
        ranked.indexOf(source).takeIf { it >= 0 }?.let { return 1 + it }
        return 100 + ORDER.indexOf(source).let { if (it < 0) ORDER.size else it }
    }

    /** The book's sources by trust: primary, secondary, tertiary… (for showing). */
    fun ranking(s: Snapshot, document: String): List<String> =
        listOfNotNull(of(s, document)) + (s.sourceRanks[document] ?: emptyList()).filter { it != of(s, document) }

    private fun moved(a: Book, b: Book) =
        kotlin.math.abs(a.percentage - b.percentage) > 0.0005 || (b.status != a.status && (b.status == ReadStatus.FINISHED || a.status == ReadStatus.FINISHED))

    /** [b] with its position put back to [a]'s (everything else it learned kept). */
    private fun positionOf(a: Book, b: Book) = b.copy(
        percentage = a.percentage, status = a.status, timestamp = a.timestamp, currentPage = a.currentPage,
        finishedAt = a.finishedAt, manualAt = a.manualAt, held = a.held,
    )

    private fun today() = LocalDate.now().toEpochDay()

    /** [s] with [source]'s run of days for [doc] counted for today; and the count. */
    private fun streak(streaks: MutableMap<String, String>, doc: String, source: String): Int {
        val key = "$doc|$source"
        val (day, count) = streaks[key]?.split(':')?.let { (it.getOrNull(0)?.toLongOrNull() ?: 0L) to (it.getOrNull(1)?.toIntOrNull() ?: 0) } ?: (0L to 0)
        val t = today()
        val next = when (day) {
            t -> count
            t - 1 -> count + 1
            else -> 1
        }
        streaks[key] = "$t:$next"
        return next
    }

    /**
     * The store's check on every change made while a source runs ([within]): books it moved that
     * aren't its own are held back (asked about, or taken over).
     */
    fun gate(old: Snapshot, new: Snapshot): Snapshot {
        val src = current.get() ?: return new
        if (old.books === new.books) return new
        var books: HashMap<String, Book>? = null
        var primaries: HashMap<String, String>? = null
        var ranks: HashMap<String, List<String>>? = null
        var streaks: HashMap<String, String>? = null
        var asks: HashMap<String, ProgressAsk>? = null
        var pending: HashMap<String, io.github.prithirajn.trackersync.data.Pending>? = null
        var toCp = new.toCrossPoint
        var toKo = new.toKosync
        fun books() = books ?: HashMap(new.books).also { books = it }
        fun primaries() = primaries ?: HashMap(new.primaries).also { primaries = it }
        fun ranks() = ranks ?: HashMap(new.sourceRanks).also { ranks = it }
        fun streaks() = streaks ?: HashMap(new.sourceStreaks).also { streaks = it }
        fun asks() = asks ?: HashMap(new.progressAsks).also { asks = it }
        fun pending() = pending ?: HashMap(new.pending).also { pending = it }
        /** Nothing sent anywhere for [doc] because of this change. */
        fun unqueue(doc: String) {
            val p = pending()
            for (k in new.pending.keys) if (k.endsWith("|$doc")) {
                val was = old.pending[k]
                if (was == null) p.remove(k) else p[k] = was
            }
            if (doc in toCp && doc !in old.toCrossPoint) toCp = toCp - doc
            if (doc in toKo && doc !in old.toKosync) toKo = toKo - doc
        }

        for ((doc, nb) in new.books) {
            val source = if (src == BY_ORIGIN) nb.origin ?: continue else src
            val ob = old.books[doc]
            if (ob == null) {
                // First seen: where it came from is its primary.
                if (!new.primaries.containsKey(doc) && inferred(nb.origin) != source) primaries()[doc] = source
                continue
            }
            val primary = of(old, doc)
            if (primary == null) {
                if (moved(ob, nb)) { primaries()[doc] = source; fresh += doc }
                continue
            }
            if (source == primary) {
                if (moved(ob, nb)) fresh += doc
                continue
            }
            if (!moved(ob, nb)) {
                // Another source changing only the status (Reading, Paused…): the primary's stays.
                if (nb.status != ob.status || nb.held != ob.held) books()[doc] = nb.copy(status = ob.status, held = ob.held)
                continue
            }
            // Another source moved it: held back for now.
            books()[doc] = positionOf(ob, nb)
            unqueue(doc)
            // The primary already reported in this sync, or this is behind where the book is: not looked at.
            if (doc in fresh) continue
            if (nb.percentage < ob.percentage - 0.0005 && nb.status != ReadStatus.FINISHED) continue
            val percent = Math.round(nb.percentage * 100).toInt()
            if (new.progressDenied[doc] == percent) continue
            // A source you said to always use: its position is taken, no question.
            if (trusted(new, doc, source)) {
                books()[doc] = nb
                for (k in new.pending.keys) if (k.endsWith("|$doc")) pending()[k] = new.pending[k]!!
                if (doc in new.toCrossPoint) toCp = toCp + doc
                if (doc in new.toKosync) toKo = toKo + doc
                ranks()[doc] = (listOf(source) + (new.sourceRanks[doc] ?: emptyList())).filter { it != primary }.distinct()
                asks().remove(doc)
                continue
            }
            val days = streak(streaks(), doc, source)
            val lead = nb.percentage - ob.percentage
            // A main source you picked yourself isn't taken over.
            if (doc !in new.pinnedPrimary && (lead > TAKEOVER_LEAD || days >= TAKEOVER_DAYS)) {
                // It takes over: its position stands, and the old primary becomes the secondary.
                books()[doc] = nb
                for (k in new.pending.keys) if (k.endsWith("|$doc")) pending()[k] = new.pending[k]!!
                if (doc in new.toCrossPoint) toCp = toCp + doc
                if (doc in new.toKosync) toKo = toKo + doc
                primaries()[doc] = source
                ranks()[doc] = (listOf(primary) + (new.sourceRanks[doc] ?: emptyList())).filter { it != source }.distinct()
                asks().remove(doc)
                streaks().keys.removeAll { it.startsWith("$doc|") }
                fresh += doc
                notes += "${nb.displayTitle}: ${name(source)} is now the main source" +
                    (if (lead > TAKEOVER_LEAD) " (${Math.round(lead * 100)}% further along)" else " (read further there $days days in a row)") +
                    "; ${name(primary)} comes next"
                continue
            }
            // Asked about, unless a higher-ranked source already is.
            val existing = asks()[doc]
            if (existing == null || existing.source == source || rank(new, doc, source) < rank(new, doc, existing.source)) {
                asks()[doc] = ProgressAsk(nb.percentage, nb.status, nb.timestamp, source)
            }
        }
        if (books == null && primaries == null && asks == null && pending == null && streaks == null) return new
        return new.copy(
            books = books ?: new.books, primaries = primaries ?: new.primaries, sourceRanks = ranks ?: new.sourceRanks,
            sourceStreaks = streaks ?: new.sourceStreaks, progressAsks = asks ?: new.progressAsks, pending = pending ?: new.pending,
            toCrossPoint = toCp, toKosync = toKo,
        )
    }

    /** Whether [source]'s positions for [doc] are taken without asking. */
    fun trusted(s: Snapshot, doc: String, source: String) = source in s.trustedSources || "$doc|$source" in s.trustedFor

    /** Sources [doc] could have a position from: its ranking, then the connected ones. */
    fun candidates(s: Snapshot, doc: String): List<String> {
        val connected = buildList {
            if (s.account != null) add("crosspoint")
            if (s.kosync != null) add("kosync")
            if (s.koHl != null || s.koLocal != null) add("koreader")
            if (s.service("seekquel").linked) add("seekquel")
            if (s.kindle != null) add("kindle")
            if (s.libby != null) add("libby")
            add("mihon")
            if (s.moon != null) add("moonreader")
            if (s.audible != null) add("audible")
        }
        val origin = s.books[doc]?.origin
        // Mihon only for manga and comics.
        return (ranking(s, doc) + connected.filter { it != "mihon" || origin == "mihon" || origin == "anilist" || origin == "logged" }).distinct()
    }

    /**
     * You pick [doc]'s main source ([source]), and it stays until you change it; null hands it back
     * to the automatic rule (where it came from, and takeovers).
     */
    fun choose(store: Store, doc: String, source: String?) = store.update { s ->
        if (source == null) return@update s.copy(pinnedPrimary = s.pinnedPrimary - doc)
        val old = of(s, doc)
        s.copy(
            primaries = s.primaries + (doc to source),
            pinnedPrimary = s.pinnedPrimary + doc,
            sourceRanks = s.sourceRanks + (doc to (listOfNotNull(old) + (s.sourceRanks[doc] ?: emptyList())).filter { it != source }.distinct()),
            sourceStreaks = s.sourceStreaks.filterKeys { !it.startsWith("$doc|") },
        )
    }

    /** "Always use [source]": for [doc], or every book when null. Its waiting position (if any) is taken now. */
    fun trust(store: Store, doc: String?, source: String) {
        store.update { s -> if (doc == null) s.copy(trustedSources = s.trustedSources + source) else s.copy(trustedFor = s.trustedFor + "$doc|$source") }
        val waiting = store.snapshot.progressAsks.filter { (d, a) -> a.source == source && (doc == null || d == doc) }.keys
        for (d in waiting) ProgressOverride.acceptSeekquel(store, d)
    }

    /** Back to asking about [source]'s positions (for [doc], or every book when null). */
    fun untrust(store: Store, doc: String?, source: String) = store.update { s ->
        if (doc == null) s.copy(trustedSources = s.trustedSources - source) else s.copy(trustedFor = s.trustedFor - "$doc|$source")
    }

    /** Every waiting card answered the same way: keep the positions here, or take the new ones. */
    fun keepAll(store: Store) { for (d in store.snapshot.progressAsks.keys.toList()) keep(store, d) }

    fun updateAll(store: Store) { for (d in store.snapshot.progressAsks.keys.toList()) runCatching { ProgressOverride.acceptSeekquel(store, d) } }

    /** [source] moved up for [doc]: right after its primary. */
    internal fun promoted(s: Snapshot, doc: String, source: String): Snapshot {
        if (of(s, doc) == source) return s
        val ranked = listOf(source) + (s.sourceRanks[doc] ?: emptyList()).filter { it != source }
        return s.copy(sourceRanks = s.sourceRanks + (doc to ranked))
    }

    /** "Keep": the position here stays; that source's report isn't asked about again until it moves on. */
    fun keep(store: Store, document: String) = store.update { s ->
        val ask = s.progressAsks[document] ?: return@update s
        s.copy(
            progressAsks = s.progressAsks - document,
            progressDenied = s.progressDenied + (document to ask.percent),
            sourceStreaks = s.sourceStreaks - "$document|${ask.source}",
        )
    }

    /**
     * "Move it back": the source is set to the position here (CrossPoint and KOSync are written
     * to; Seekquel gets it sent, even though it's further along). The position here stays.
     */
    fun moveBack(store: Store, document: String) {
        val ask = store.snapshot.progressAsks[document] ?: return
        keep(store, document)
        when (ask.source) {
            "crosspoint" -> store.update { it.copy(toCrossPoint = it.toCrossPoint + document) }
            "kosync" -> store.update { it.copy(toKosync = it.toKosync + document) }
            "seekquel" -> {
                store.update { it.copy(moveBack = it.moveBack + "seekquel|$document") }
                SyncEngine.queue(store, "seekquel", listOf(document))
            }
        }
    }
}
