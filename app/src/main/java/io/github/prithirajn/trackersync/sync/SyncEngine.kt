package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.Link
import io.github.prithirajn.trackersync.data.LinkSource
import io.github.prithirajn.trackersync.data.Pending
import io.github.prithirajn.trackersync.data.ProgressAsk
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.SendEvent
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.data.Store
import io.github.prithirajn.trackersync.data.Visibility
import io.github.prithirajn.trackersync.net.AuthException
import io.github.prithirajn.trackersync.net.NetworkException
import io.github.prithirajn.trackersync.net.ServiceException
import io.github.prithirajn.trackersync.net.SkipException
import io.github.prithirajn.trackersync.source.Change
import io.github.prithirajn.trackersync.source.Clipping
import io.github.prithirajn.trackersync.source.CrossPointClient
import io.github.prithirajn.trackersync.source.KosyncClient
import io.github.prithirajn.trackersync.source.LibbyClient
import io.github.prithirajn.trackersync.source.OpenLibrary
import io.github.prithirajn.trackersync.trackers.HighlightSupport
import io.github.prithirajn.trackersync.trackers.LibraryBook
import io.github.prithirajn.trackersync.trackers.ReviewSupport
import io.github.prithirajn.trackersync.trackers.PrivacySupport
import io.github.prithirajn.trackersync.trackers.RemoteBook
import io.github.prithirajn.trackersync.trackers.SeekquelTracker
import io.github.prithirajn.trackersync.trackers.Session
import io.github.prithirajn.trackersync.trackers.Tracker
import io.github.prithirajn.trackersync.trackers.Trackers
import java.util.concurrent.locks.ReentrantLock

/**
 * One sync pass: read what changed on crosspoint-sync, then bring each linked tracker up to
 * date. Blocking; call it off the main thread. Rules follow ShelfSync's sync engine:
 * only move forward, never overwrite a finished/DNF book, and respect each tracker's privacy.
 */
class SyncEngine(private val store: Store) {

    /** What a pass did. [finished] lists books that became Finished during it (for the finish flow). */
    data class Result(
        val message: String,
        val needsAttention: List<String> = emptyList(),
        val finished: List<String> = emptyList(),
        val failed: Boolean = false,
    )

    /** Highlights posted by hand in this sync, per tracker and book (to keep to a few each time). */
    private val byHandSent = HashMap<String, Int>()

    /** Set when a request failed because the phone had no connection (not because a service was down). */
    @Volatile private var offlineThisRun = false

    /**
     * A request that couldn't get through: logged as a problem, unless the phone itself was
     * offline (no DNS, no route), when the whole sync is marked offline instead.
     */
    private fun netFail(service: String?, message: String, e: Throwable) {
        when {
            !isOffline(e) -> store.log(service, message, error = true)
            reallyOffline() -> offlineThisRun = true
            // Online, but this one site couldn't be found: blocked, not offline.
            else -> store.log(service, blockedHost(e, message), error = true)
        }
    }

    /** Whether the phone couldn't reach the internet at all this sync (checked once, by finding two well-known sites). */
    private var onlineThisRun: Boolean? = null

    private fun reallyOffline(): Boolean {
        onlineThisRun?.let { return !it }
        val ok = (probe ?: { listOf("www.google.com", "one.one.one.one").any { h -> runCatching { java.net.InetAddress.getByName(h) }.isSuccess } })()
        onlineThisRun = ok
        return !ok
    }

    /**
     * The phone is online but couldn't find one site's address: almost always a Private DNS or an
     * ad blocker (AdGuard, NextDNS, Blokada, RethinkDNS…) blocking it. Libby's server is called
     * "sentry", which blocklists aimed at the Sentry crash-report service catch by mistake.
     */
    private fun blockedHost(e: Throwable, fallback: String): String {
        var t: Throwable? = e
        var host: String? = null
        while (t != null && host == null) {
            val m = t.message.orEmpty()
            host = Regex("\"([^\"]+)\"").find(m)?.groupValues?.get(1)
                ?: m.takeIf { t is java.net.UnknownHostException }?.substringBefore(':')?.trim()?.takeIf { it.isNotEmpty() && ' ' !in it }
            t = t.cause
        }
        val h = host ?: return "$fallback (your phone couldn't find the site; a Private DNS or ad blocker may be blocking it)"
        val hint = if (h.contains("sentry", true)) " Its name looks like the Sentry crash-report service, so blocklists catch it by mistake." else ""
        return "Your phone is online but couldn't find $h: a Private DNS or ad blocker is probably blocking it. Allow $h (or ${h.split('.').takeLast(2).joinToString(".")}) there.$hint"
    }

    /** Notes what the sync is on now, for the "Syncing…" line. */
    private fun step(what: String) = store.update { it.copy(syncStep = what) }

    fun run(): Result {
        if (!lock.tryLock()) return Result("A sync is already running")
        try {
            store.update { it.copy(running = true) }
            val finishedBefore = store.snapshot.books.values.filter { it.status == ReadStatus.FINISHED }.map { it.document }.toSet()
            val result = try {
                var sourceErrorMsg: String? = null
                var sourceNeedsAuth = false
                if (store.snapshot.account != null) {
                    try {
                        pullChanges()
                        store.update { it.copy(sourceOkAt = System.currentTimeMillis(), sourceError = null) }
                    } catch (e: AuthException) {
                        val desc = Health.describeSource(e)
                        store.update { it.copy(sourceError = desc) }
                        store.log(null, "CrossPoint sign-in failed: ${e.message}", error = true)
                        sourceErrorMsg = e.message ?: "crosspoint-sync sign-in failed"
                        sourceNeedsAuth = true
                    } catch (e: NetworkException) {
                        val desc = Health.describeSource(e)
                        store.update { it.copy(sourceError = desc) }
                        netFail(null, "CrossPoint offline: ${e.message}", e)
                        sourceErrorMsg = "Couldn't reach CrossPoint server"
                    } catch (e: Exception) {
                        val desc = Health.describeSource(e)
                        store.update { it.copy(sourceError = desc) }
                        store.log(null, "CrossPoint: ${e.message}", error = true)
                        sourceErrorMsg = e.message ?: "CrossPoint error"
                    }
                }
                libraries.clear()
                step("Kindle")
                pullKindle()
                step("Libby")
                pullLibby()
                step("Audible")
                pullAudible()
                step("Manga")
                pullManga()
                step("Sources")
                pullSources()
                step("KOSync")
                pullKosync()
                step("Moon+ Reader")
                runCatching { pullMoon() }
                runCatching { Handoff.find(store) }
                runCatching { pushToCrossPoint() }
                refreshClips()
                runCatching { deleteHighlights() }
                val highlights = try {
                    pushHighlights()
                } catch (e: Exception) {
                    store.log(null, "Highlights: ${e.message}", error = true)
                    0
                }
                step("Sending to trackers")
                val progress = pushPending()
                // Books matched just now, while their progress was sent: their highlights go in this
                // same sync rather than waiting for the next one.
                val highlightsAfter = try {
                    pushHighlights()
                } catch (e: Exception) {
                    store.log(null, "Highlights: ${e.message}", error = true)
                    0
                }
                val reviews = runCatching { pushReviews() }.getOrDefault(0)
                runCatching { adoptTrackerNames(store) }
                // The slower extras come after your progress has gone out, so a slow or unreachable
                // site never holds up the trackers; anything they queue goes out at the end.
                step("Extras")
                runCatching { pullRatings() }
                runCatching { refreshSerials() }
                step("Web novels")
                runCatching { findWebNovels() }
                step("New releases")
                runCatching { Releases.check(store) }
                step("SFF Radar")
                runCatching { SffRadar.refresh(store, force = false) }
                // A probe that came back while you were away has its briefing ready when you look.
                runCatching { SffRadar.claimProbe(store) }
                runCatching { val t = System.currentTimeMillis() / 1000; store.update { it.copy(sffSky = SffChart.tick(it, t)) } }
                if (store.snapshot.pending.isNotEmpty()) {
                    step("Sending to trackers")
                    runCatching { pushPending() }
                }
                var msg = progress.message
                val allHighlights = highlights + highlightsAfter
                if (allHighlights > 0) msg += " · $allHighlights highlight${if (allHighlights == 1) "" else "s"}"
                if (reviews > 0) msg += " · $reviews review${if (reviews == 1) "" else "s"}"

                val attention = ArrayList(progress.needsAttention)
                if (sourceNeedsAuth && "CrossPoint" !in attention) {
                    attention.add(0, "CrossPoint")
                }
                if (sourceErrorMsg != null && progress.needsAttention.isEmpty() && !progress.failed && msg == "Up to date") {
                    msg = "$sourceErrorMsg (trackers up to date)"
                }
                progress.copy(message = msg, needsAttention = attention)
            } catch (e: AuthException) {
                store.log(null, e.message ?: "crosspoint-sync sign-in failed", error = true)
                Result(e.message ?: "crosspoint-sync sign-in failed", failed = true)
            } catch (e: NetworkException) {
                netFail(null, "Offline: ${e.message}", e)
                Result("Couldn't reach the network; will retry")
            } catch (e: Exception) {
                store.log(null, "Sync failed: ${e.message}", error = true)
                Result("Sync failed: ${e.message}", failed = true)
            } finally {
                // The phone was offline: one quiet line, not a problem per tracker (it all catches up).
                if (offlineThisRun) {
                    if (store.snapshot.log.firstOrNull()?.message != OFFLINE_NOTE) store.log(null, OFFLINE_NOTE)
                    offlineThisRun = false
                }
                onlineThisRun = null
                store.update { Shelves.recordPages(it).copy(running = false, syncStep = null, lastRun = System.currentTimeMillis()) }
            }
            // Newly finished books, for the "rate it" prompt and notification.
            val finishedNow = store.snapshot.books.values
                .filter { it.status == ReadStatus.FINISHED && it.document !in finishedBefore && !it.reviewAsked && !it.held }
                .map { it.document }
            return result.copy(finished = finishedNow)
        } finally {
            lock.unlock()
        }
    }

    /**
     * Isolated, source-aware sync for a single book.
     * 1. Pulls fresh reading progress and highlights from that book's specific reading source
     *    (Kindle Whispersync, Libby, Audible, CrossPoint, KOSync, etc.)
     * 2. Pushes the latest progress, dates, and highlights exclusively to linked trackers for this book.
     */
    fun runForBook(document: String): Result {
        if (!lock.tryLock()) return Result("A sync is already running")
        try {
            store.update { it.copy(running = true) }
            val initialBook = store.snapshot.books[document]
            val bookTitle = initialBook?.displayTitle ?: title(document)

            // Step 1: Inbound pull from the book's specific reading source
            val origin = initialBook?.origin
            val isKindle = origin == "kindle" || document.startsWith("kindle:") ||
                (store.snapshot.kindle != null && initialBook?.aliases?.any { it.startsWith("kindle:") } == true)
            val isLibby = origin == "libby" || document.startsWith("libby:") ||
                initialBook?.aliases?.any { it.startsWith("libby:") } == true
            val isAudible = origin == "audible" || document.startsWith("audible:") ||
                initialBook?.aliases?.any { it.startsWith("audible:") } == true

            when {
                isKindle && store.snapshot.kindle?.enabled == true -> {
                    step("Checking Kindle for $bookTitle")
                    runCatching { pullKindle(force = true) }
                }
                isLibby && store.snapshot.libby?.enabled == true -> {
                    step("Checking Libby for $bookTitle")
                    runCatching { pullLibby() }
                }
                isAudible && store.snapshot.audible?.enabled == true -> {
                    step("Checking Audible for $bookTitle")
                    runCatching { pullAudible() }
                }
                origin == null -> {
                    // CrossPoint reader book: check CrossPoint and KOSync if linked
                    if (store.snapshot.account != null) {
                        step("Checking CrossPoint for $bookTitle")
                        runCatching { pullChanges() }
                    }
                    if (store.snapshot.kosync != null) {
                        step("Checking KOSync for $bookTitle")
                        runCatching { pullKosync() }
                    }
                }
            }

            refreshClips()
            runCatching { deleteHighlights() }

            val book = store.snapshot.books[document] ?: initialBook
            val titleToSend = book?.displayTitle ?: bookTitle
            step("Sending $titleToSend to trackers")

            val highlights = try {
                pushHighlights(onlyDocument = document)
            } catch (e: Exception) {
                store.log(null, "Highlights: ${e.message}", error = true)
                0
            }

            val progress = pushPending(onlyDocument = document)

            val highlightsAfter = try {
                pushHighlights(onlyDocument = document)
            } catch (e: Exception) {
                0
            }

            val reviews = runCatching { pushReviews(onlyDocument = document) }.getOrDefault(0)

            var msg = "$titleToSend: ${progress.message}"
            val allHighlights = highlights + highlightsAfter
            if (allHighlights > 0) msg += " · $allHighlights highlight${if (allHighlights == 1) "" else "s"}"
            if (reviews > 0) msg += " · $reviews review${if (reviews == 1) "" else "s"}"

            store.log(null, msg)
            return progress.copy(message = msg)
        } catch (e: AuthException) {
            store.log(null, e.message ?: "Sign-in failed", error = true)
            return Result(e.message ?: "Sign-in failed", failed = true)
        } catch (e: NetworkException) {
            netFail(null, "Offline: ${e.message}", e)
            return Result("Couldn't reach tracker; will retry")
        } catch (e: Exception) {
            store.log(null, "Sync failed: ${e.message}", error = true)
            return Result("Sync failed: ${e.message}", failed = true)
        } finally {
            store.update { it.copy(running = false, syncStep = null, lastRun = System.currentTimeMillis()) }
            lock.unlock()
        }
    }

    /**
     * Ongoing web novels you've caught up with are checked on their site once a day: when the
     * story is complete, the book is sent again so trackers get Finished.
     */
    private fun refreshSerials() {
        val now = System.currentTimeMillis() / 1000
        for ((doc, w) in store.snapshot.webNovels) {
            val book = store.snapshot.books[doc] ?: continue
            if (!w.ongoing || book.percent < 95 || now - w.checkedAt < DAY) continue
            val fresh = io.github.prithirajn.trackersync.source.WebNovels.fetch(w.url)?.web
            store.update { s -> s.copy(webNovels = s.webNovels + (doc to (fresh ?: w.copy(checkedAt = now)))) }
            // New chapters since you caught up: news for New & upcoming.
            val was = w.chapters
            val nowCh = fresh?.chapters
            if (fresh != null && was != null && nowCh != null && nowCh > was) Releases.webNovelGrew(store, doc, book.displayTitle, was, nowCh, fresh.url, fresh.site)
            if (fresh != null && fresh.complete == true) {
                store.log(null, "${book.displayTitle} is complete on ${fresh.site}")
                for (t in Trackers.all) if (store.snapshot.service(t.id).let { sv -> sv.linked && sv.enabled }) queue(store, t.id, listOf(doc))
            }
        }
    }

    /** Reads the change feed and library; queues each changed book for each linked tracker. */
    private fun pullChanges() {
        val account = store.snapshot.account ?: return
        val client = CrossPointClient(account)
        val startCursor = store.snapshot.cursor
        var cursor = startCursor
        val changed = LinkedHashMap<String, Change>()
        var pages = 0
        do {
            val page = client.changes(cursor, DEVICE_ID)
            for (c in page.changes) changed[c.document] = c
            // Every reported position is kept for a while, to place highlights in the book.
            if (page.changes.isNotEmpty()) store.update { s -> HighlightPosition.record(s, page.changes) }
            cursor = page.cursor
            pages++
        } while (page.more && pages < 40)

        val needLibrary = changed.isNotEmpty() || store.snapshot.books.isEmpty()
        val library = if (needLibrary) client.library() else emptyList()
        val now = System.currentTimeMillis()

        store.update { s ->
            val books = HashMap(s.books)
            val removed = HashMap(s.removedBooks)
            // Books seen here for the first time (from the library list or the change feed).
            val newBooks = HashSet<String>()
            for (item in library) {
                val old = books[item.document]
                if (old == null) {
                    // Removed by you: stays out until you read further than where you left it.
                    val remPct = removed[item.document]
                    if (remPct != null) {
                        if (item.percentage <= remPct + 0.001) continue
                        removed.remove(item.document)
                    }
                    newBooks.add(item.document)
                }
                // A newer position from elsewhere (a KOSync server) isn't undone by an older one here.
                val keepOld = old != null && old.timestamp > item.timestamp
                // Progress you set by hand also keeps its status until you read further on CrossPoint.
                val manual = keepOld && old!!.manualAt != null && item.timestamp <= old.manualAt!!
                books[item.document] = Book(
                    document = item.document,
                    title = item.title,
                    author = item.author,
                    percentage = if (keepOld) old!!.percentage else item.percentage,
                    status = if (manual) old!!.status else item.status,
                    timestamp = if (keepOld) old!!.timestamp else item.timestamp,
                    aliases = old?.aliases ?: emptyList(),
                    coverUrl = item.coverUrl ?: old?.coverUrl,
                    description = old?.description,
                    descriptionSource = old?.descriptionSource,
                    detailsFetched = old?.detailsFetched ?: false,
                    // A book that got longer keeps the larger page count worked out for it.
                    pageCount = if (item.document in s.grewAt && (old?.pageCount ?: 0) > (item.pageCount ?: 0)) old?.pageCount else item.pageCount ?: old?.pageCount,
                    pagesRead = old?.pagesRead,
                    finishedAt = old?.finishedAt,
                    startedAt = old?.startedAt,
                    rating = old?.rating,
                    review = old?.review,
                    reviewAsked = old?.reviewAsked ?: false,
                    manualAt = old?.manualAt,
                    currentPage = if (manual) old!!.currentPage else null,
                )
            }
            val grew = HashMap(s.grewAt)
            for (c in changed.values) {
                val old = books[c.document]
                val remPct = removed[c.document]
                if (old == null && remPct != null) {
                    if (c.percentage <= remPct + 0.001) {
                        // Stays removed! No new progress since user removed it.
                        continue
                    } else {
                        // Additional progress made: re-add to library
                        removed.remove(c.document)
                    }
                }
                if (old == null) newBooks.add(c.document)
                val base = old ?: Book(c.document, null, null, c.percentage, null, c.timestamp)
                books[c.document] = if (old != null && old.timestamp > c.timestamp) base.copy(aliases = c.aliases)
                else {
                    // The book got longer (a web novel's new chapters): read on, yet a lower percentage.
                    val longer = old != null && grewLonger(s.positions[c.document] ?: emptyList(), old.percentage, c)
                    if (longer) grew[c.document] = c.timestamp
                    base.copy(
                        percentage = c.percentage, timestamp = c.timestamp, aliases = c.aliases, currentPage = null,
                        // Its page count, when crosspoint-sync still has the old one, grows to match: pages read never fall.
                        pageCount = if (longer && old!!.pageCount != null && base.pageCount == old.pageCount && c.percentage > 0.01)
                            Math.round(old.pageCount!! * old.percentage / c.percentage).toInt() else base.pageCount,
                        status = if (longer && base.status == ReadStatus.FINISHED) ReadStatus.READING else base.status,
                    )
                }
                for (alias in c.aliases) books.remove(alias)
            }

            // On the very first pull, only recent books are queued, so a new install
            // doesn't rewrite years of reading history on every tracker.
            val firstPull = startCursor == 0L
            val pending = HashMap(s.pending)
            val links = HashMap(s.links)
            // "Auto-sync new books" off: every book seen for the first time starts paused on each
            // tracker (whether it came from the library list or the change feed), until you turn it on.
            if (!s.settings.autoEnableNewBooks) {
                for (doc in newBooks) {
                    if (doc !in books) continue
                    for (t in Trackers.all) {
                        val key = Link.key(t.id, doc)
                        if (links[key] == null) links[key] = Link(t.id, doc, null, source = LinkSource.NONE, note = "Not synced")
                    }
                }
            }
            for (c in changed.values) {
                if (c.document in removed && c.document !in books) continue
                // Links made under a merged hash move to the canonical one.
                for (alias in c.aliases) {
                    for (t in Trackers.all) {
                        links.remove(Link.key(t.id, alias))?.let { links[Link.key(t.id, c.document)] = it.copy(document = c.document) }
                        pending.remove(Link.key(t.id, alias))
                    }
                }
                if (firstPull && now / 1000 - c.timestamp > RECENT_DAYS * DAY) continue
                for (t in Trackers.all) {
                    val svc = s.service(t.id)
                    if (!svc.linked || !svc.enabled) continue
                    if (links[Link.key(t.id, c.document)]?.source == LinkSource.NONE) continue
                    val key = Link.key(t.id, c.document)
                    pending[key] = pending[key]?.copy(tries = 0) ?: Pending(t.id, c.document, now)
                }
            }
            s.copy(books = books, pending = pending, links = links, cursor = cursor, grewAt = grew, removedBooks = removed)
        }
        for (c in changed.values) if (store.snapshot.grewAt[c.document] == c.timestamp) {
            store.log(null, "${title(c.document)} got longer (new chapters): now ${Math.round(c.percentage * 100)}% of the longer book")
        }
        if (changed.isNotEmpty()) store.log(null, "${changed.size} book${if (changed.size == 1) "" else "s"} changed on CrossPoint")
        // Pages read and finish dates for the Library's numbers: after reading, or once at first.
        if (needLibrary || store.snapshot.books.values.none { it.pagesRead != null }) fillActivity(client)
        fillCovers(client)
    }

    /**
     * Brings in books from trackers that can act as a source (Seekquel), when the user turned
     * that on: books marked Reading there, and later changes to ones already brought in.
     * Each tracker on its own: one failing doesn't stop CrossPoint's books from syncing.
     */
    /** Adds to a book's history for one tracker (the last [SENT_KEEP] kept). */
    private fun recordSent(service: String, document: String, ev: SendEvent) = store.update { s ->
        val key = Link.key(service, document)
        s.copy(sent = s.sent + (key to ((s.sent[key] ?: emptyList()) + ev).takeLast(SENT_KEEP)))
    }

    /** Libraries read this pass, so ratings don't fetch them twice. */
    private val libraries = HashMap<String, List<LibraryBook>>()

    /**
     * Your Seekquel ratings, copied to the same books here so they go to your other trackers;
     * read even while Seekquel's sync is switched off. Seekquel has no review text to read.
     */
    private fun pullRatings() {
        val t = SeekquelTracker
        val svc = store.snapshot.service(t.id)
        if (!svc.linked || svc.needsReauth) return
        val items = libraries[t.id] ?: try {
            val session = Session(svc.credential)
            t.library(session).also {
                if (session.changed) store.updateService(t.id) { s -> s.copy(credential = session.encode()) }
            }
        } catch (e: AuthException) {
            store.updateService(t.id) { it.copy(needsReauth = true, lastError = e.message) }
            return
        }
        importRatings(t, items)
    }

    /**
     * Seekquel is the source of your ratings and reading dates. Its rating replaces the one here,
     * except one you just set here that's on its way to Seekquel (Seekquel's sync on and the
     * book matched there). Its start and finish days replace CrossPoint's, and go to the
     * trackers that take dates.
     */
    internal fun importRatings(source: Tracker, items: List<LibraryBook>) {
        val rated = ArrayList<String>()
        val dated = ArrayList<String>()
        val now = System.currentTimeMillis()
        store.update { s ->
            val books = HashMap(s.books)
            val links = HashMap(s.links)
            val pending = HashMap(s.pending)
            val seen = HashMap(s.seekquelRatings)
            val seenDates = HashMap(s.seekquelDates)
            val asks = HashMap(s.progressAsks)
            val denied = HashMap(s.progressDenied)
            val matched = HashSet<String>()
            val svc = s.service(source.id)
            val sending = svc.linked && svc.enabled && !svc.needsReauth
            for (item in items) {
                val doc = ratedDocument(s, source, item) ?: continue
                var b = books[doc] ?: continue
                // ---- rating ----
                val r = item.rating
                val last = seen[doc]
                val ownLink = links[Link.key(source.id, doc)]?.takeIf { it.externalId != null && it.source != LinkSource.NONE }
                // Changed here (Seekquel still has what it had) and about to be sent there: yours stays.
                val yoursOnItsWay = sending && ownLink != null && b.rating != null && b.rating != last && (r == last || r == null)
                if (!yoursOnItsWay) {
                    if (r != null) {
                        seen[doc] = r
                        if (b.rating != r) {
                            b = b.copy(rating = r, reviewAsked = true)
                            rated += b.displayTitle
                        }
                    } else if (last != null) {
                        // Cleared on Seekquel: cleared here too, if you hadn't changed it here.
                        seen.remove(doc)
                        if (b.rating == last) b = b.copy(rating = null)
                    }
                    // Seekquel already has it: nothing to send back.
                    if (ownLink != null && b.rating == r) links[ownLink.key] = ownLink.copy(reviewSent = reviewPrint(b))
                }
                // ---- start and finish days ----
                if (item.startedAt != null || item.finishedAt != null) {
                    val started = item.startedAt ?: b.startedAt
                    val finished = item.finishedAt ?: b.finishedAt
                    seenDates[doc] = "${item.startedAt ?: ""}|${item.finishedAt ?: ""}"
                    if (started != b.startedAt || finished != b.finishedAt) {
                        b = b.copy(startedAt = started, finishedAt = finished)
                        dated += b.displayTitle
                        if (!b.held) for (t in Trackers.all) {
                            if (!t.takesDates || t.id == source.id || t.id == b.origin) continue
                            val ts = s.service(t.id)
                            if (!ts.linked || !ts.enabled) continue
                            val key = Link.key(t.id, doc)
                            if (links[key]?.source == LinkSource.NONE) continue
                            pending[key] = pending[key]?.copy(tries = 0) ?: Pending(t.id, doc, now)
                        }
                    }
                }
                // ---- position: Seekquel comes after your own entries, before CrossPoint and KOSync ----
                matched += doc
                val ask = progressAsk(b, item, links[Link.key(source.id, doc)], pending.containsKey(Link.key(source.id, doc)), denied[doc])
                if (ask != null) asks[doc] = ask else asks.remove(doc)
                // A denied position that Seekquel has since moved on from is forgotten.
                item.fraction?.let { f -> denied[doc]?.let { d -> if (d != Math.round(f * 100).toInt()) denied.remove(doc) } }
                books[doc] = b
            }
            // Books Seekquel no longer has: nothing to ask.
            asks.keys.retainAll(matched)
            s.copy(
                books = books, links = links, pending = pending, seekquelRatings = seen, seekquelDates = seenDates,
                progressAsks = asks, progressDenied = denied,
            )
        }
        fun names(l: List<String>) = l.take(3).joinToString(", ") + if (l.size > 3) " and ${l.size - 3} more" else ""
        if (rated.isNotEmpty()) store.log(source.id, "Rating from ${source.name}: ${names(rated)}")
        if (dated.isNotEmpty()) store.log(source.id, "Reading dates from ${source.name}: ${names(dated)}")
    }

    /**
     * Seekquel's position for [b], when it should be offered: it differs from the one here by a
     * point or more, wasn't put there by this app (what was last sent to Seekquel, or is about
     * to be), isn't one you turned down, and isn't older than progress you set yourself.
     */
    private fun progressAsk(b: Book, item: LibraryBook, link: Link?, sendingThere: Boolean, denied: Int?): ProgressAsk? {
        val f = item.fraction ?: return null
        val theirs = Math.round(f * 100).toInt()
        if (kotlin.math.abs(theirs - b.percent) < 1) return null
        if (b.status == ReadStatus.FINISHED && theirs >= 98) return null
        if (denied == theirs) return null
        if (sendingThere || link?.lastPercent == theirs) return null
        // Libby books carry Libby's own position: no tracker's position is offered over it.
        if (b.origin == "libby" || b.document.startsWith("libby:") || b.origin == "audible" || b.document.startsWith("audible:") || b.origin == "mihon" || b.origin == "anilist" || b.origin == "logged") return null
        val isKindle = b.origin == "kindle" || b.document.startsWith("kindle:") ||
            (store.snapshot.kindle != null && b.aliases.any { it.startsWith("kindle:") })
        if (isKindle) return null
        // Your own entries come first: a physical book's, or progress set by hand.
        val yours = if (ManualBooks.isManual(b)) b.timestamp else b.manualAt?.takeIf { it == b.timestamp }
        if (yours != null && item.updatedAt <= yours) return null
        return ProgressAsk(f, item.status, item.updatedAt)
    }

    /** The book here that a library entry is: brought in from it, sent to it, or the same title. */
    private fun ratedDocument(s: Snapshot, source: Tracker, item: LibraryBook): String? {
        importedDocument(source.id, item.id).takeIf { it in s.books }?.let { return it }
        s.links.values.firstOrNull { it.service == source.id && it.libraryKeys.any { k -> k in item.keys } && it.document in s.books }
            ?.let { return it.document }
        val same = s.books.values.filter { b ->
            b.title != null && b.origin != source.id &&
                (Matcher.sameBook(item.title, item.author, b.title, b.author) ||
                    Matcher.score(item.title, item.author, RemoteBook(b.document, b.title, b.author)) >= SAME_BOOK)
        }
        return same.singleOrNull()?.document
    }

    private fun pullSources() {
        for (t in Trackers.all) {
            if (!t.readsLibrary) continue
            val svc = store.snapshot.service(t.id)
            val bridgeForKindle = t.id == "goodreads" && store.snapshot.kindle != null
            if (!svc.linked || !svc.enabled || svc.needsReauth || (!svc.importReading && !bridgeForKindle)) continue
            try {
                val session = Session(svc.credential)
                learnLibraryKeys(t, session)
                // Read only to carry Kindle progress over (bringing in its books is off): nothing else is imported.
                importLibrary(t, t.library(session).also { libraries[t.id] = it }, bridgeOnly = !svc.importReading)
            } catch (e: AuthException) {
                store.updateService(t.id) { it.copy(needsReauth = true, lastError = e.message) }
                store.log(t.id, e.message ?: "Sign in again", error = true)
            } catch (e: NetworkException) {
                netFail(t.id, "Couldn't reach ${t.name} for your reading; will retry", e)
            } catch (e: Exception) {
                store.log(t.id, "Couldn't read your ${t.name} library (${e.message})", error = true)
            }
        }
    }

    /**
     * Asks [source] which of its books each CrossPoint book became, a few per pass and once
     * each, so those books aren't brought back in as copies.
     */
    private fun learnLibraryKeys(source: Tracker, session: Session) {
        val todo = store.snapshot.links.values
            .filter { it.service == source.id && it.source != LinkSource.NONE && it.libraryKeys.isEmpty() }
            .filter { store.snapshot.books[it.document]?.origin.let { o -> o == null || o == ManualBooks.ORIGIN } }
            .take(KEY_LOOKUPS_PER_RUN)
        for (link in todo) {
            val keys = runCatching { source.libraryKeys(session, link) }.getOrDefault(emptySet())
            if (keys.isNotEmpty()) store.putLink(link.copy(libraryKeys = keys.sorted()))
        }
    }

    /** Merges [items] from [source] into the book list and queues what changed for the other trackers. */
    internal fun importLibrary(source: Tracker, items: List<LibraryBook>, bridgeOnly: Boolean = false) {
        val now = System.currentTimeMillis()
        var added = 0
        var changedCount = 0
        var history = 0
        store.update { s ->
            val books = HashMap(s.books)
            val pending = HashMap(s.pending)
            val links = HashMap(s.links)
            // Books also read on CrossPoint come from there, with their exact progress: known by
            // the ids the source gave the copies this app sent, else by title and author.
            val onCrossPoint = s.books.values.filter { (it.origin == null || it.origin == ManualBooks.ORIGIN) && it.title != null }
                .map { RemoteBook(it.document, it.title!!, it.author) }
            val sentKeys = s.links.values.filter { it.service == source.id }.flatMap { it.libraryKeys }.toSet()
            val removed = HashMap(s.removedBooks)
            for (item in items) {
                val doc = importedDocument(source.id, item.id)
                val old = books[doc]
                val remPct = removed[doc]
                val itemPct = item.fraction ?: 0.0
                if (old == null && remPct != null) {
                    if (itemPct <= remPct + 0.001) {
                        // Stays removed! No new progress since user removed it.
                        continue
                    } else {
                        // Additional progress made: re-add to library
                        removed.remove(doc)
                    }
                }
                val kindleMatch = books.values.firstOrNull { b ->
                    (b.origin == "kindle" || b.origin == "libby" || b.origin == "audible") && b.title != null && (
                        item.keys.contains("asin:${b.document.removePrefix("kindle:")}") ||
                            Matcher.sameBook(item.title, item.author, b.title, b.author)
                        )
                }
                if (kindleMatch != null) {
                    val kDoc = kindleMatch.document
                    val kPct = item.fraction
                    if (kPct != null && kPct > kindleMatch.percentage + 0.005) {
                        books[kDoc] = kindleMatch.copy(
                            percentage = kPct,
                            timestamp = now / 1000,
                        )
                        changedCount++
                        for (t in Trackers.all) {
                            if (t.id != source.id && s.service(t.id).let { it.linked && it.enabled }) {
                                val key = Link.key(t.id, kDoc)
                                pending[key] = pending[key]?.copy(tries = 0) ?: Pending(t.id, kDoc, now)
                            }
                        }
                    }
                    val linkKey = Link.key(source.id, kDoc)
                    if (links[linkKey]?.externalId == null) {
                        links[linkKey] = Link(
                            service = source.id,
                            document = kDoc,
                            externalId = item.id,
                            title = item.title,
                            author = item.author,
                            source = LinkSource.AUTO,
                            lastPercent = kPct?.let { Math.round(it * 100).toInt() } ?: kindleMatch.percent,
                            lastStatus = kindleMatch.status ?: ReadStatus.READING,
                            libraryKeys = item.keys.sorted(),
                            updatedAt = now,
                        )
                    }
                    continue
                }
                if (bridgeOnly) continue
                val alreadyHere = item.keys.any { it in sentKeys } || onCrossPoint.any {
                    Matcher.score(item.title, item.author, it) >= SAME_BOOK ||
                        Matcher.sameBook(item.title, item.author, it.title, it.author)
                }
                if (alreadyHere) {
                    if (old != null) {
                        books.remove(doc)
                        pending.keys.removeAll { it.endsWith("|$doc") }
                        links.keys.removeAll { it.endsWith("|$doc") }
                    }
                    continue
                }
                // New books: ones being read, and past reading (paused, finished, abandoned).
                // Want to Read stays out. Ones already here follow every change.
                if (old == null && item.status !in IMPORTED_STATUSES) continue
                // Past reading is held: shown here, sent nowhere until the user picks the book.
                val held = old?.held ?: (item.status != ReadStatus.READING)
                // Progress you set by hand stays until Seekquel reports reading after it.
                val manual = old?.manualAt != null && item.updatedAt <= old.manualAt!!
                val book = Book(
                    document = doc,
                    title = item.title,
                    author = item.author,
                    percentage = if (manual) old!!.percentage else item.fraction ?: old?.percentage ?: 0.0,
                    status = if (manual) old!!.status else item.status,
                    timestamp = if (manual) old!!.timestamp else if (item.updatedAt > 0) item.updatedAt else old?.timestamp ?: now / 1000,
                    coverUrl = item.coverUrl ?: old?.coverUrl,
                    // The tracker's own description, when it sends one (Seekquel).
                    description = old?.description ?: item.description?.takeIf { it.isNotBlank() },
                    descriptionSource = old?.descriptionSource ?: item.description?.takeIf { it.isNotBlank() }?.let { source.name },
                    detailsFetched = old?.detailsFetched ?: false,
                    origin = source.id,
                    finishedAt = item.finishedAt ?: old?.finishedAt,
                    startedAt = item.startedAt ?: old?.startedAt,
                    held = held,
                    rating = old?.rating,
                    review = old?.review,
                    reviewAsked = old?.reviewAsked ?: (item.status != ReadStatus.READING),
                    manualAt = old?.manualAt,
                    currentPage = if (manual) old!!.currentPage else null,
                    pageCount = item.pageCount ?: old?.pageCount,
                )
                books[doc] = book
                val changed = old == null || old.percent != book.percent || old.status != book.status
                if (old == null && held) history++
                // Moved back to Want to Read: nothing to tell the others.
                if (!changed || book.status == ReadStatus.TO_READ) continue
                if (held) continue
                if (old == null) {
                    added++
                    val autoEnable = s.settings.autoEnableNewBooks
                    if (!autoEnable) {
                        for (t in Trackers.all) {
                            if (t.id == source.id) continue
                            val key = Link.key(t.id, doc)
                            if (links[key] == null) {
                                links[key] = Link(t.id, doc, null, source = LinkSource.NONE, note = "Not synced")
                            }
                        }
                        continue // Do not add to pending!
                    } else {
                        for (t in Trackers.all) {
                            if (t.id == source.id) continue
                            val svc = s.service(t.id)
                            if (!svc.linked || !svc.enabled) continue
                            val key = Link.key(t.id, doc)
                            if (links[key]?.source == LinkSource.NONE) continue
                            pending[key] = pending[key]?.copy(tries = 0) ?: Pending(t.id, doc, now)
                        }
                    }
                } else {
                    changedCount++
                    for (t in Trackers.all) {
                        if (t.id == source.id) continue
                        val svc = s.service(t.id)
                        if (!svc.linked || !svc.enabled) continue
                        val key = Link.key(t.id, doc)
                        if (links[key]?.source == LinkSource.NONE) continue
                        pending[key] = pending[key]?.copy(tries = 0) ?: Pending(t.id, doc, now)
                    }
                }
            }
            s.copy(books = books, pending = pending, links = links, removedBooks = removed)
        }
        if (added > 0) store.log(source.id, "Brought in $added book${if (added == 1) "" else "s"} you're reading on ${source.name}")
        if (history > 0) store.log(source.id, "Brought in $history book${if (history == 1) "" else "s"} from your ${source.name} history; open one and tap Send to my trackers to sync it")
        if (changedCount > 0) store.log(source.id, "$changedCount book${if (changedCount == 1) "" else "s"} changed on ${source.name}")
    }

    /** [pending] with an update queued for [docs] on each linked tracker that takes reading dates. */
    private fun queueDates(s: Snapshot, books: Map<String, Book>, docs: List<String>, pending: Map<String, Pending>): Map<String, Pending> {
        if (docs.isEmpty()) return pending
        val out = HashMap(pending)
        val now = System.currentTimeMillis()
        for (doc in docs) {
            val b = books[doc] ?: continue
            if (b.held) continue
            for (t in Trackers.all) {
                if (!t.takesDates || t.id == b.origin) continue
                val svc = s.service(t.id)
                if (!svc.linked || !svc.enabled) continue
                val key = Link.key(t.id, doc)
                // Only books already on that tracker (a match waiting or turned off isn't sent).
                val link = s.links[key] ?: continue
                if (link.source == LinkSource.NONE || link.externalId == null) continue
                out[key] = out[key]?.copy(tries = 0) ?: Pending(t.id, doc, now)
            }
        }
        return out
    }

    /** Copies each book's pages read and finish date from the server's reading activity. */
    private fun fillActivity(client: CrossPointClient) {
        val tz = -java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val activity = runCatching { client.activity(tz) }.getOrNull() ?: return
        val byDoc = activity.associateBy { it.document }
        store.update { s ->
            val redated = ArrayList<String>()
            val books = s.books.mapValues { (doc, b) ->
                val a = byDoc[doc] ?: return@mapValues b
                // Seekquel's reading dates win over CrossPoint's.
                if (doc in s.seekquelDates) return@mapValues b.copy(pagesRead = a.pagesRead ?: b.pagesRead)
                val nb = b.copy(pagesRead = a.pagesRead ?: b.pagesRead, finishedAt = a.finishedAt, startedAt = a.startedAt ?: b.startedAt)
                if (nb.startedAt != b.startedAt || nb.finishedAt != b.finishedAt) redated += doc
                nb
            }
            // New start or finish days (they often come after the reading that sent the book out):
            // the trackers that take dates hear of them too.
            s.copy(books = books, pending = queueDates(s, books, redated, s.pending))
        }
    }

    /**
     * Checks the extra KOSync server (if one is added) for newer positions on books already
     * here: every book in progress each pass, plus a few others in turn. A newer position
     * updates the book and goes to the trackers like any CrossPoint change. Problems only
     * turn the server's light red; the rest of the sync carries on.
     */
    internal fun pullKosync() {
        val account = store.snapshot.kosync ?: return
        val client = KosyncClient(account)
        val own = store.snapshot.books.values.filter { it.origin == null }
        val inProgress = own.filter { it.status == null || it.status == ReadStatus.READING || it.status == ReadStatus.PAUSED }
            .sortedByDescending { it.timestamp }
            .take(KOSYNC_ACTIVE)
        val rest = own.filter { it !in inProgress }.sortedBy { it.document }
        val start = if (rest.isEmpty()) 0 else store.snapshot.kosyncRotation % rest.size
        val turn = if (rest.isEmpty()) emptyList() else (rest.drop(start) + rest.take(start)).take(KOSYNC_ROTATE)
        val found = ArrayList<io.github.prithirajn.trackersync.source.KosyncProgress>()
        try {
            for (b in inProgress + turn) {
                val p = client.progress(b.document) ?: continue
                if (p.timestamp > b.timestamp && kotlin.math.abs(p.percentage - b.percentage) > 0.0005) found.add(p)
            }
            store.update { it.copy(kosyncOkAt = System.currentTimeMillis(), kosyncError = null, kosyncRotation = start + turn.size) }
        } catch (e: Exception) {
            store.update { it.copy(kosyncError = Health.describeKosync(e)) }
            store.log(null, "KOSync: ${e.message}", error = true)
        }
        if (found.isEmpty()) return
        val now = System.currentTimeMillis()
        store.update { s ->
            val books = HashMap(s.books)
            val pending = HashMap(s.pending)
            val syntheticChanges = ArrayList<io.github.prithirajn.trackersync.source.Change>()
            for (p in found) {
                val b = books[p.document] ?: continue
                books[p.document] = b.copy(percentage = p.percentage, timestamp = p.timestamp, currentPage = null)
                syntheticChanges.add(io.github.prithirajn.trackersync.source.Change(document = p.document, aliases = emptyList(), percentage = p.percentage, timestamp = p.timestamp, device = null, spine = null))
                for (t in Trackers.all) {
                    val svc = s.service(t.id)
                    if (!svc.linked || !svc.enabled) continue
                    val key = Link.key(t.id, p.document)
                    if (s.links[key]?.source == LinkSource.NONE) continue
                    pending[key] = pending[key]?.copy(tries = 0) ?: Pending(t.id, p.document, now)
                }
            }
            HighlightPosition.record(s.copy(books = books, pending = pending), syntheticChanges)
        }
        val devices = found.mapNotNull { it.device }.distinct().joinToString(", ")
        store.log(null, "${found.size} book${if (found.size == 1) "" else "s"} moved ahead on KOSync" + if (devices.isNotEmpty()) " ($devices)" else "")
    }

    /**
     * Moon+ Reader's positions from the WebDAV folder its sync writes to. Problems only turn its
     * light red; the rest of the sync carries on.
     */
    internal fun pullMoon() {
        val account = store.snapshot.moon ?: return
        val client = io.github.prithirajn.trackersync.source.MoonReaderClient(account)
        val positions = try {
            val folder = client.findFolder(store.snapshot.moonFolder)
            if (folder == null) {
                store.update { it.copy(moonOkAt = System.currentTimeMillis(), moonError = Health.MOON_NO_FILES) }
                return
            }
            val stamps = HashMap<String, String>()
            client.positions(folder, store.snapshot.moonMods, stamps).also {
                store.update { s -> s.copy(moonFolder = folder, moonOkAt = System.currentTimeMillis(), moonError = null, moonMods = stamps) }
            }
        } catch (e: Exception) {
            store.update { it.copy(moonError = Health.describeMoon(e)) }
            store.log(null, "Moon+ Reader: ${e.message}", error = true)
            return
        }
        var r: MoonSync.Result? = null
        store.update { s -> MoonSync.apply(s, positions).also { r = it }.snapshot }
        val res = r ?: return
        if (res.moved > 0) store.log(null, "${res.moved} book${if (res.moved == 1) "" else "s"} moved ahead in Moon+ Reader" + if (res.finished > 0) " (${res.finished} finished)" else "")
        if (res.added > 0) store.log(null, "Brought in ${res.added} book${if (res.added == 1) "" else "s"} you're reading in Moon+ Reader")
        if (res.highlights > 0) store.log(null, "${res.highlights} new highlight${if (res.highlights == 1) "" else "s"} from Moon+ Reader")
    }

    /**
     * Reads Kindle books, Whispersync positions, and notebook highlights from read.amazon.in/.com.
     * Direct Kindle progress takes precedence over Goodreads.
     */
    internal fun pullKindle(force: Boolean = false) {
        val account = store.snapshot.kindle ?: return
        if (!account.enabled) return
        val startedAt = System.currentTimeMillis()
        // Amazon is strict about automated requests: asked at most every few hours (worked or not),
        // unless you tap its health light.
        if (!force && startedAt - store.snapshot.kindleTriedAt < KINDLE_EVERY_MS) return
        store.update { it.copy(kindleTriedAt = startedAt) }
        val wasNeverChecked = store.snapshot.kindleOkAt == 0L || store.snapshot.kindleError != null
        val client = io.github.prithirajn.trackersync.source.KindleClient(account)
        try {
            val items: List<io.github.prithirajn.trackersync.trackers.LibraryBook> = client.library()
            val currentBooks = store.snapshot.books
            // Highlights fetched this time (document -> all of that book's), and what they were fetched for.
            val fetchedClips = HashMap<String, List<Clipping>>()
            val seenNow = HashMap<String, String>()
            val lengthsNow = HashMap<String, Int>()
            if (account.syncHighlights) {
                val seen = store.snapshot.kindleSeen
                val sec0 = startedAt / 1000
                for (item in items) {
                    // Prefer an active CrossPoint reader book if the user moved to CrossPoint
                    val cpMatch = currentBooks.values.firstOrNull { b ->
                        b.origin == null && b.title != null && Matcher.sameBook(item.title, item.author, b.title, b.author)
                    }
                    val matched = cpMatch
                        ?: currentBooks["kindle:${item.id}"]
                        ?: currentBooks.values.firstOrNull { b ->
                            b.title != null && Matcher.sameBook(item.title, item.author, b.title, b.author)
                        }
                    // Only books you've started are brought in, so only theirs are worth fetching.
                    if (matched == null && (item.fraction ?: 0.0) <= 0.0) continue
                    val matchDoc = matched?.document ?: "kindle:${item.id}"
                    // Fetched again only when the notebook shows a newer "last annotated", or (when it
                    // shows none) once a day, or when you've read on.
                    val stamp = client.notebookStamps[item.id]
                    val last = seen[item.id]
                    val readOn = matched != null && (item.fraction ?: 0.0) > matched.percentage + 0.005
                    val due = when {
                        last == null -> true
                        stamp != null -> stamp != last
                        else -> readOn || (last.removePrefix("t:").toLongOrNull()?.let { sec0 - it >= DAY } ?: true)
                    }
                    if (!due) continue
                    // The book's length places each highlight; asked once per book.
                    val length = store.snapshot.kindleLengths[item.id]
                        ?: run { runCatching { client.progress(item.id) }; client.lengths[item.id] }?.also { lengthsNow[item.id] = it }
                    val clips = client.clippings(item.id, matchDoc, length, matched?.pageCount) ?: continue
                    fetchedClips[matchDoc] = clips
                    seenNow[item.id] = stamp ?: "t:$sec0"
                }
            }

            val now = System.currentTimeMillis()
            val sec = now / 1000
            var added = 0
            var moved = 0
            var newHighlights = 0
            val docsToQueue = ArrayList<String>()

            store.update { s ->
                val books = HashMap(s.books)
                val links = HashMap(s.links)
                val pending = HashMap(s.pending)
                var toCp = s.toCrossPoint
                var toKo = s.toKosync
                val allClips = ArrayList(s.kindleClips)
                val oldClipIds = s.kindleClips.map { it.id }.toSet()
                // Highlights whose id changed (old position-based ids): old id -> new id.
                val renamed = HashMap<String, String>()
                val removed = HashMap(s.removedBooks)

                for (item in items) {
                    val kindleDoc = "kindle:${item.id}"
                    val oldKindle = books[kindleDoc]
                    val cpBook = books.values.firstOrNull { b ->
                        b.origin == null && b.title != null && Matcher.sameBook(item.title, item.author, b.title, b.author)
                    }
                    val existing = cpBook
                        ?: oldKindle
                        ?: books.values.firstOrNull { b ->
                            b.title != null && Matcher.sameBook(item.title, item.author, b.title, b.author)
                        }
                    val doc = existing?.document ?: kindleDoc

                    val remPct = removed[doc] ?: removed[kindleDoc]
                    val itemPct = item.fraction ?: 0.0
                    // Only books you've started on Kindle come in (a notebook entry with no progress doesn't).
                    if (existing == null && itemPct <= 0.0) continue
                    if (existing == null && remPct != null) {
                        if (itemPct <= remPct + 0.001) {
                            // Stays removed! No further progress made since removal.
                            continue
                        } else {
                            // Additional progress made: re-add to library
                            removed.remove(doc)
                            removed.remove(kindleDoc)
                        }
                    }

                    // If the user started on Kindle and later opened the book on CrossPoint, unify into CrossPoint
                    if (cpBook != null && oldKindle != null && cpBook.document != kindleDoc) {
                        val cpDoc = cpBook.document
                        books.remove(kindleDoc)
                        pending.keys.removeAll { it.endsWith("|$kindleDoc") }
                        for (l in links.values.filter { it.document == kindleDoc }) {
                            links.remove(l.key)
                            if (l.externalId != null && l.source != LinkSource.NONE) {
                                val targetKey = Link.key(l.service, cpDoc)
                                if (links[targetKey]?.externalId == null) {
                                    links[targetKey] = l.copy(document = cpDoc)
                                }
                            }
                        }
                        for (i in allClips.indices) {
                            if (allClips[i].document == kindleDoc) {
                                allClips[i] = allClips[i].copy(document = cpDoc)
                            }
                        }
                    }

                    // Read again: Kindle's old finished position doesn't undo it; only new reading counts.
                    val rereadAt = s.rereads[doc]
                    val rereading = rereadAt != null && existing != null && existing.status != ReadStatus.FINISHED
                    val bestPrevPct = if (rereading) existing!!.percentage else maxOf(existing?.percentage ?: 0.0, oldKindle?.percentage ?: 0.0)
                    val nextPct = if (rereading && itemPct >= 0.98) bestPrevPct else maxOf(bestPrevPct, itemPct)
                    val prevFinished = !rereading && (existing?.status == ReadStatus.FINISHED || oldKindle?.status == ReadStatus.FINISHED)
                    val nextStatus = if (prevFinished || nextPct >= 0.98) {
                        ReadStatus.FINISHED
                    } else {
                        existing?.status ?: item.status
                    }

                    if (existing == null) {
                        added++
                        val autoEnable = s.settings.autoEnableNewBooks
                        if (!autoEnable) {
                            for (t in Trackers.all) {
                                val key = Link.key(t.id, doc)
                                if (links[key] == null) {
                                    links[key] = Link(t.id, doc, null, source = LinkSource.NONE, note = "Not synced")
                                }
                            }
                            // Do NOT add to docsToQueue!
                        } else {
                            docsToQueue.add(doc)
                        }
                    } else if (nextPct > existing.percentage + 0.005 || nextStatus != existing.status) {
                        moved++
                        docsToQueue.add(doc)
                        if (existing.origin == null) {
                            toCp = toCp + doc
                            if (s.kosync != null) toKo = toKo + doc
                        }
                    }

                    val stamp = if (existing == null || nextPct > existing.percentage + 0.005) maxOf(sec, (existing?.timestamp ?: 0L) + 1) else existing.timestamp
                    val b = (existing ?: Book(
                        document = doc,
                        title = item.title,
                        author = item.author,
                        percentage = nextPct,
                        status = nextStatus,
                        timestamp = stamp,
                        coverUrl = item.coverUrl,
                        origin = "kindle",
                    )).copy(
                        percentage = nextPct,
                        status = nextStatus,
                        coverUrl = existing?.coverUrl ?: oldKindle?.coverUrl ?: item.coverUrl,
                        rating = existing?.rating ?: oldKindle?.rating,
                        review = existing?.review ?: oldKindle?.review,
                        startedAt = listOfNotNull(existing?.startedAt, oldKindle?.startedAt).minOrNull(),
                        finishedAt = existing?.finishedAt ?: oldKindle?.finishedAt,
                        timestamp = stamp,
                        // Kindle read further: that stands, like progress you set yourself, until the book's
                        // own source (CrossPoint, or the tracker it came from, e.g. Seekquel) reports reading after it.
                        // Without this, a book brought in from Seekquel would be put back by Seekquel on the same sync.
                        manualAt = if (existing != null && existing.origin != "kindle" && nextPct > existing.percentage + 0.005) stamp else existing?.manualAt,
                    )
                    books[doc] = b

                    fetchedClips[doc]?.let { fetched ->
                        val before = allClips.filter { it.document == doc }
                        val clips = fetched.map { c ->
                            // The same highlight as before (by id, or by its text under an older id):
                            // it keeps when it was first seen, and what was sent stays sent.
                            val prev = before.firstOrNull { it.id == c.id } ?: before.firstOrNull { it.text == c.text }
                            if (prev != null && prev.id != c.id) renamed[prev.id] = c.id
                            if (prev != null) c.copy(createdAt = prev.createdAt) else c
                        }
                        newHighlights += clips.count { c -> c.id !in oldClipIds && before.none { it.text == c.text } }
                        allClips.removeAll { it.document == doc }
                        allClips.addAll(clips)
                    }
                }
                s.copy(
                    books = books,
                    links = links,
                    pending = pending,
                    toCrossPoint = toCp,
                    toKosync = toKo,
                    kindleClips = allClips,
                    // What was sent, edited, hidden or queued under an old id carries over to the new one.
                    sentClippings = if (renamed.isEmpty()) s.sentClippings else s.sentClippings + s.sentClippings.mapNotNull { k ->
                        val i = k.indexOf('|'); renamed[k.substring(i + 1)]?.let { k.substring(0, i + 1) + it }
                    },
                    clipRemote = if (renamed.isEmpty()) s.clipRemote else s.clipRemote + s.clipRemote.mapNotNull { (k, v) ->
                        val i = k.indexOf('|'); renamed[k.substring(i + 1)]?.let { (k.substring(0, i + 1) + it) to v }
                    },
                    clipEdits = if (renamed.isEmpty()) s.clipEdits else s.clipEdits + s.clipEdits.mapNotNull { (k, v) -> renamed[k]?.let { it to v } },
                    clipHidden = if (renamed.isEmpty()) s.clipHidden else s.clipHidden + s.clipHidden.mapNotNull { renamed[it] },
                    kindleOkAt = now,
                    kindleError = null,
                    kindleSeen = s.kindleSeen + seenNow,
                    kindleLengths = s.kindleLengths + lengthsNow,
                    removedBooks = removed,
                )
            }

            if (docsToQueue.isNotEmpty()) {
                val snap = store.snapshot
                for (t in Trackers.all) {
                    if (snap.service(t.id).let { it.linked && it.enabled }) {
                        queue(store, t.id, docsToQueue)
                    }
                }
            }

            val logParts = ArrayList<String>()
            if (added > 0) logParts.add("Brought in $added book${if (added == 1) "" else "s"} from Kindle")
            if (moved > 0) logParts.add("$moved book${if (moved == 1) "" else "s"} moved ahead on Kindle")
            if (newHighlights > 0) logParts.add("$newHighlights new highlight${if (newHighlights == 1) "" else "s"}")

            if (logParts.isNotEmpty()) {
                store.log("kindle", logParts.joinToString(" · "))
            } else if (items.isEmpty()) {
                store.log("kindle", "Connected to ${account.domain} (0 active or annotated books found)")
            } else if (wasNeverChecked) {
                store.log("kindle", "Connected to ${account.domain}: ${items.size} book${if (items.size == 1) "" else "s"} checked")
            }
        } catch (e: io.github.prithirajn.trackersync.net.AuthException) {
            store.update { it.copy(kindleError = Health.describeKindle(e)) }
            store.log("kindle", "Amazon Kindle: sign in again", error = true)
        } catch (e: Exception) {
            store.update { it.copy(kindleError = Health.describeKindle(e)) }
            store.log("kindle", "Kindle: ${e.message}", error = true)
        }
    }

    /** Kept for callers of the old name: manga and comics from every source. */
    internal fun pullMihon() = pullManga()

    /** Mihon's newest backup in the chosen folder, or null when there's none set (errors noted). */
    private fun readMihon(): List<io.github.prithirajn.trackersync.source.MihonBackup.Series>? {
        val src = store.snapshot.mihon ?: return null
        if (!src.enabled) return null
        val reader = io.github.prithirajn.trackersync.source.MihonBackup.reader ?: return null
        return try {
            val (name, at, bytes) = reader(src.folder)
                ?: throw ServiceException("No Mihon backup in that folder yet. In Mihon: More \u2192 Settings \u2192 Data and storage \u2192 Automatic backups.")
            val all = io.github.prithirajn.trackersync.source.MihonBackup.parse(bytes)
            store.update { it.copy(mihon = src.copy(backupName = name, backupAt = at), mihonOkAt = System.currentTimeMillis(), mihonError = null) }
            all
        } catch (e: Exception) {
            val msg = when (e) {
                is IllegalArgumentException -> "That folder's newest file isn't a Mihon backup."
                else -> e.message ?: "Couldn't read Mihon's backup"
            }
            store.update { it.copy(mihonError = msg) }
            store.log("mihon", "Mihon: $msg", error = true)
            null
        }
    }

    /** Your AniList manga list (what Mihon on any device has sent there), or null when not linked or reading is off. */
    private fun readAniList(): List<io.github.prithirajn.trackersync.source.AniList.Entry>? {
        val al = store.snapshot.anilist ?: return null
        if (!al.read) return null
        return try {
            val list = io.github.prithirajn.trackersync.source.AniList.list(al.token, al.userId)
            store.update { it.copy(anilistOkAt = System.currentTimeMillis(), anilistError = null) }
            list
        } catch (e: AuthException) {
            store.update { it.copy(anilistError = "AniList signed you out; sign in again.") }
            store.log("anilist", "AniList: signed out; sign in again", error = true)
            null
        } catch (e: Exception) {
            store.update { it.copy(anilistError = e.message ?: "Couldn't reach AniList") }
            store.log("anilist", "AniList: ${e.message ?: "couldn't reach it"}", error = true)
            null
        }
    }

    /**
     * Manga and comics, from Mihon's backup on this phone and your AniList list (which Mihon on any
     * device keeps up to date), merged per series. Manga becomes volumes: MangaDex says which
     * chapters each volume holds, and each volume you're in, finished lately, or already have here
     * becomes a book at its share read ("Chainsaw Man, Vol. 5" at 49%). Western comics become one
     * book per issue ("Saga #54"). Your trackers get them; MangaUpdates and AniList get the chapter.
     */
    internal fun pullManga() {
        mangaDeadline = System.currentTimeMillis() + MANGA_BUDGET_MS
        mangaLookups = 0
        val mihon = readMihon()
        val anilist = readAniList()
        val logged = store.snapshot.logged
        if (mihon == null && anilist == null && logged.isEmpty()) return
        val all = MangaSources.withLogged(MangaSources.merge(mihon.orEmpty(), anilist.orEmpty()), logged)
        val now = System.currentTimeMillis() / 1000
        val docs = store.snapshot.books.keys
        val here = docs.filter { it.startsWith("manga:") }.map { it.substringAfter("manga:").substringBefore(":v") }.toSet()
        val comicsHere = docs.filter { it.startsWith("comic:") }.map { it.substringAfter("comic:").substringBefore(":") }.toSet()
        val active = all.filter { s ->
            s.chapters.any { it.started } &&
                ((s.lastReadAt ?: 0L) >= now - MANGA_ACTIVE_DAYS * DAY || store.snapshot.mangaMaps[s.key]?.mangadexId in here || comicId(s) in comicsHere)
        }
        val docsToQueue = LinkedHashSet<String>()
        var moved = 0
        // What's been read of each series, for its volumes' issue and chapter lists.
        store.update { st -> st.copy(seriesRead = st.seriesRead + active.associate { s -> s.key to seriesReadOf(s) }) }
        for (s in active) {
            if (ComicIssues.isComic(s)) {
                val id = comicId(s)
                val cm = comicMap(s, now, mayLookUp()) { mangaLookups++ }
                if (cm != null) {
                    val pages = store.snapshot.pagesPer[s.key] ?: ComicIssues.TYPICAL_ISSUE_PAGES
                    moved += applyMangaBooks(s, ComicIssues.volumes(cm, s.chapters, pages), docsToQueue, comic = false,
                        docOf = { "comic:$id:v${it.volume}" }, titleOf = { MangaVolumes.title(s.title, it.volume) },
                        numbersOf = { p -> cm.volumes.firstOrNull { it.number == p.volume }?.issues.orEmpty() })
                    // Issues not collected yet stay single issues until their volume is out.
                    if (cm.exact) moved += applyMangaBooks(s, ComicIssues.uncollected(cm, s.chapters, pages), docsToQueue, comic = true, docOf = { "comic:$id:${it.volume}" }, titleOf = { ComicIssues.title(s.title, it.volume) })
                    // A single issue that's now counted in its volume isn't a book of its own any more.
                    val collected = cm.volumes.flatMap { it.issues }.toSet()
                    store.update { st ->
                        val gone = st.books.keys.filter { k ->
                            k.startsWith("comic:$id:") && !k.startsWith("comic:$id:v") && k.substringAfterLast(':').toDoubleOrNull() in collected && st.books[k]?.origin in MANGA_ORIGINS
                        }.toSet()
                        if (gone.isEmpty()) st else st.copy(books = st.books - gone, links = st.links.filterValues { it.document !in gone }, pending = st.pending.filterValues { it.document !in gone })
                    }
                }
            } else {
                val furthest = s.chapters.filter { it.started }.mapNotNull { it.number }.maxOrNull() ?: 0.0
                val pages = store.snapshot.pagesPer[s.key] ?: MangaVolumes.TYPICAL_CHAPTER_PAGES
                val estId = "x" + java.lang.Long.toString(s.key.hashCode().toLong() and 0xffffffffL, 36)
                var map = store.snapshot.mangaMaps[s.key]
                val custom = store.snapshot.comicCustom[s.key]
                if (custom != null) {
                    // Your own chapters-per-volume beat MangaDex and the estimate.
                    map = MangaVolumes.fromCustom(map?.takeIf { !it.estimated && it.source != "you" }?.mangadexId ?: estId, s.title, custom, furthest)
                    val m = map
                    store.update { st -> st.copy(mangaMaps = st.mangaMaps + (s.key to m)) }
                } else {
                    // Looked up when there's none yet, it's an estimate, or you've read past its last volume a while (a new one may be out).
                    val stale = map == null || map.estimated || map.source == "you" ||
                        (furthest > (map.lastChapter ?: 0.0) + 1e-6 && now - map.at >= COMIC_RECHECK_DAYS * DAY)
                    val missed = store.snapshot.mangaMissing[s.key]?.let { now - it < MANGA_RETRY_DAYS * DAY } == true
                    if (stale && !missed && mayLookUp()) {
                        mangaLookups++
                        val found = io.github.prithirajn.trackersync.source.MangaDex.find(s.title, s.anilistId, s.malId, s.mangaUpdatesId)
                            ?: s.otherTitles.asSequence().take(3).firstNotNullOfOrNull { io.github.prithirajn.trackersync.source.MangaDex.find(it, s.anilistId, s.malId, s.mangaUpdatesId) }
                        val fresh = found?.let { (id, t) -> io.github.prithirajn.trackersync.source.MangaDex.volumes(id, t) }
                        if (fresh != null) {
                            map = fresh
                            store.update { st -> st.copy(mangaMaps = st.mangaMaps + (s.key to fresh), mangaMissing = st.mangaMissing - s.key) }
                        } else {
                            store.update { st -> st.copy(mangaMissing = st.mangaMissing + (s.key to now)) }
                            if (map == null || map.estimated) store.log(s.origin, "\u201C${s.title}\u201D: MangaDex doesn't list its volumes; counted as ${MangaVolumes.TYPICAL_PER_VOLUME} chapters a volume for now (change it on a volume's page)")
                        }
                    }
                    if (map == null || map.estimated) {
                        // MangaDex hasn't got it: so many chapters a volume, until it does or you set them.
                        map = MangaVolumes.estimate(estId, s.title, furthest, MangaVolumes.TYPICAL_PER_VOLUME, now)
                        val m = map
                        store.update { st -> st.copy(mangaMaps = st.mangaMaps + (s.key to m)) }
                    }
                }
                val m = map!!
                moved += applyMangaBooks(s, MangaVolumes.of(m, s.chapters, pages), docsToQueue, comic = false, docOf = { "manga:${m.mangadexId}:v${it.volume}" }, titleOf = { MangaVolumes.title(s.title, it.volume) },
                    numbersOf = { p -> m.volumes.firstOrNull { it.number == p.volume }?.chapters?.map { it.number }.orEmpty() })
                // Chapters out but not yet in a volume: shown toward the volume to come, sent once it's out.
                if (!m.estimated && m.source != "you") {
                    moved += applyMangaBooks(s, MangaVolumes.upcoming(m, s.chapters, pages), docsToQueue, comic = false, held = true,
                        docOf = { "manga:${m.mangadexId}:v${it.volume}" }, titleOf = { MangaVolumes.title(s.title, it.volume) })
                }
            }
            sendChapterTrackers(s)
        }
        if (docsToQueue.isNotEmpty()) {
            val snap = store.snapshot
            for (tr in Trackers.all) if (snap.service(tr.id).let { it.linked && it.enabled }) queue(store, tr.id, docsToQueue)
        }
        runCatching { adoptTrackerNames(store) }
        if (moved > 0) store.log(if (mihon != null) "mihon" else if (anilist != null) "anilist" else "logged", "$moved manga volume${if (moved == 1) "" else "s"} or comic issue${if (moved == 1) "" else "s"} moved ahead")
    }

    /**
     * A comic's collected volumes: your own issues-a-volume when you set one; else what Hardcover's
     * volumes say they collect (looked up again once you've read past the last one, in case a new
     * volume is out); else six issues a volume.
     */
    private fun comicMap(
        s: io.github.prithirajn.trackersync.source.MihonBackup.Series,
        now: Long,
        mayLookUp: Boolean,
        lookedUp: () -> Unit,
    ): io.github.prithirajn.trackersync.source.ComicCollections.Map? {
        val furthest = ComicIssues.furthest(s.chapters) ?: return null
        val snap = store.snapshot
        // The volumes you set: carried on past your last one, the same size, as you read further.
        snap.comicCustom[s.key]?.let { mine ->
            val m = io.github.prithirajn.trackersync.source.ComicCollections.extend(mine, furthest).copy(title = s.title)
            store.update { it.copy(comicMaps = it.comicMaps + (s.key to m)) }
            return m
        }
        snap.comicPerVolume[s.key]?.let { per ->
            val m = io.github.prithirajn.trackersync.source.ComicCollections.estimate(s.title, furthest, per, now)
            store.update { it.copy(comicMaps = it.comicMaps + (s.key to m)) }
            return m
        }
        // A run of single issues taken for volumes (each "volume" one issue) isn't trusted.
        val have = snap.comicMaps[s.key]?.takeIf { it.exact && it.volumes.any { v -> v.issues.size > 1 } }
        val stale = have == null || (furthest > (have.lastIssue ?: 0.0) + 1e-6 && now - have.at >= COMIC_RECHECK_DAYS * DAY)
        val missed = snap.comicMissing[s.key]?.let { now - it < MANGA_RETRY_DAYS * DAY } == true
        val hc = snap.service("hardcover")
        val sq = snap.service("seekquel")
        val canHc = hc.linked && !hc.needsReauth
        val canSq = sq.linked && !sq.needsReauth
        if (stale && !missed && mayLookUp && (canHc || canSq)) {
            lookedUp()
            // Hardcover's series first; Seekquel's catalogue fills volumes Hardcover doesn't say (or has none).
            val fromHc = if (canHc) runCatching { io.github.prithirajn.trackersync.trackers.HardcoverTracker.comicVolumes(Session(hc.credential), s.title, now) }.getOrNull() else null
            val needSq = fromHc == null || (fromHc.lastIssue ?: 0.0) < furthest - 1e-6
            val fromSq = if (canSq && needSq) runCatching { io.github.prithirajn.trackersync.trackers.SeekquelTracker.comicVolumes(Session(sq.credential), s.title, now) }.getOrNull() else null
            val found = io.github.prithirajn.trackersync.source.ComicCollections.combine(fromHc, fromSq)?.copy(title = s.title)
            if (found != null) {
                store.update { it.copy(comicMaps = it.comicMaps + (s.key to found), comicMissing = it.comicMissing - s.key) }
                if (have == null) store.log(s.origin, "\u201C${s.title}\u201D: ${found.volumes.size} collected volume${if (found.volumes.size == 1) "" else "s"} found on ${io.github.prithirajn.trackersync.source.ComicCollections.sourceName(found)}")
                return found
            }
            if (have != null) store.update { it.copy(comicMaps = it.comicMaps + (s.key to have.copy(at = now))) }
            else store.update { it.copy(comicMissing = it.comicMissing + (s.key to now)) }
        }
        if (have != null) return have
        val guess = io.github.prithirajn.trackersync.source.ComicCollections.estimate(s.title, furthest, io.github.prithirajn.trackersync.source.ComicCollections.TYPICAL_PER_VOLUME, now)
        store.update { it.copy(comicMaps = it.comicMaps + (s.key to guess)) }
        return guess
    }

    /** Manga lookups this sync (MangaDex, Hardcover, finding a series on MangaUpdates or AniList) and when they stop. */
    private var mangaLookups = 0
    private var mangaDeadline = Long.MAX_VALUE

    /** Another lookup fits in this sync: few enough so far, and the manga step hasn't run long. The rest wait for the next sync. */
    private fun mayLookUp() = mangaLookups < MANGA_LOOKUPS_PER_RUN && System.currentTimeMillis() < mangaDeadline

    /** A comic series' id in its books' names: AniList's id when known, else one from its Mihon key. */
    private fun comicId(s: io.github.prithirajn.trackersync.source.MihonBackup.Series): String =
        s.anilistId?.let { "al$it" } ?: "m" + java.lang.Long.toString(s.key.hashCode().toLong() and 0xffffffffL, 36)

    private fun sendChapterTrackers(s: io.github.prithirajn.trackersync.source.MihonBackup.Series) {
        runCatching { sendMangaUpdates(s) }
        runCatching { sendAniList(s) }
    }

    /** Each volume (or issue) as a book here: new ones added, ones further along moved ahead. Returns how many moved. */
    private fun applyMangaBooks(
        s: io.github.prithirajn.trackersync.source.MihonBackup.Series,
        progress: List<MangaVolumes.Progress>,
        docsToQueue: MutableSet<String>,
        comic: Boolean,
        docOf: (MangaVolumes.Progress) -> String,
        titleOf: (MangaVolumes.Progress) -> String,
        /** A volume not out yet: kept here, not sent to trackers until it is. */
        held: Boolean = false,
        /** The chapters or issues in a volume, for its start and finish days from your log. */
        numbersOf: (MangaVolumes.Progress) -> List<Double> = { listOfNotNull(it.lastChapter) },
    ): Int {
        val now = System.currentTimeMillis() / 1000
        var moved = 0
        store.update { st ->
            val books = st.books.toMutableMap()
            val links = st.links.toMutableMap()
            val removed = HashMap(st.removedBooks)
            for (v in progress) {
                val doc = docOf(v)
                val title = titleOf(v)
                // Its days: started when its first issue was, finished when its last one was.
                val nums = numbersOf(v)
                val days = nums.mapNotNull { s.issueDates[it] }
                val startDay = days.mapNotNull { it.first ?: it.second }.minOrNull()
                val finishDay = if (v.finished) (nums.lastOrNull()?.let { s.issueDates[it]?.second } ?: days.mapNotNull { it.second }.maxOrNull()) else null
                val place = placeIn(s, v, comic || ComicIssues.isComic(s))
                val existing = books[doc] ?: books.values.firstOrNull { doc in it.aliases }
                    ?: books.values.firstOrNull { b ->
                        !b.held && b.title != null && (
                            if (comic) b.title.trim().equals(title, true)
                            else Matcher.sameBook(title, s.author, b.title, b.author) && Matcher.volume(b.title) == v.volume
                            )
                    }
                val recent = v.lastReadAt != null && v.lastReadAt >= now - MANGA_ACTIVE_DAYS * DAY
                if (existing == null && v.finished && !recent) continue // long-finished volumes aren't brought in
                // Removed from the library: stays removed unless you've read further in it since.
                val remPct = if (existing == null) removed[doc] else null
                if (remPct != null) {
                    if (v.percent <= remPct + 0.001) continue
                    removed.remove(doc)
                }
                val status = if (v.finished) ReadStatus.FINISHED else ReadStatus.READING
                if (existing == null) {
                    books[doc] = Book(
                        document = doc, title = title, author = s.author, percentage = v.percent, status = status,
                        timestamp = maxOf(now, v.lastReadAt ?: now), coverUrl = s.coverUrl, origin = s.origin,
                        startedAt = startDay,
                        finishedAt = if (v.finished) (finishDay ?: v.lastReadAt ?: now) else null,
                        place = if (held) place?.let { "$it \u00B7 volume not out yet" } else place, held = held,
                    )
                    if (!held) {
                        // New books start paused unless "Auto-sync new books" is on: you turn each on yourself.
                        if (st.settings.autoEnableNewBooks) docsToQueue += doc
                        else for (tr in Trackers.all) {
                            val key = Link.key(tr.id, doc)
                            if (links[key] == null) links[key] = Link(tr.id, doc, null, source = LinkSource.NONE, note = "Not synced")
                        }
                    }
                    moved++
                    continue
                }
                if (existing.held && existing.document == doc && existing.origin in MANGA_ORIGINS) {
                    if (held) {
                        // Still to come: follows the estimate as it is (it may shift when the volume's out).
                        books[doc] = existing.copy(percentage = v.percent, place = place?.let { "$it \u00B7 volume not out yet" }, timestamp = maxOf(existing.timestamp, v.lastReadAt ?: existing.timestamp))
                        continue
                    }
                    // The volume is out now: sent to your trackers from here on.
                    books[doc] = existing.copy(held = false, percentage = v.percent, status = if (v.finished) ReadStatus.FINISHED else ReadStatus.READING,
                        place = place, timestamp = maxOf(now, existing.timestamp + 1), startedAt = existing.startedAt ?: startDay,
                        finishedAt = if (v.finished) (finishDay ?: v.lastReadAt ?: now) else null)
                    docsToQueue += doc
                    moved++
                    continue
                }
                val ahead = v.percent > existing.percentage + 0.005 || (v.finished && existing.status != ReadStatus.FINISHED)
                var b = existing
                if (existing.document != doc && doc !in b.aliases) b = b.copy(aliases = b.aliases + doc)
                if (ahead || (b.place == null && place != null)) b = b.copy(place = place, coverUrl = b.coverUrl ?: s.coverUrl)
                if (ahead) {
                    val pct = maxOf(existing.percentage, v.percent)
                    val pages = b.pageCount
                    b = b.copy(
                        percentage = pct,
                        status = if (existing.status == ReadStatus.FINISHED || v.finished) ReadStatus.FINISHED else ReadStatus.READING,
                        timestamp = maxOf(now, existing.timestamp + 1),
                        pagesRead = if (pages != null && pages > 0) Math.round(pct * pages).toInt().coerceIn(0, pages) else b.pagesRead,
                        finishedAt = b.finishedAt ?: if (v.finished) (v.lastReadAt ?: now) else null,
                    )
                    docsToQueue += b.document
                    moved++
                }
                // The days you gave: the volume's start (its first issue's) and finish (its last issue's).
                val newStart = startDay?.takeIf { b.startedAt == null || it < b.startedAt!! }
                val newFinish = finishDay?.takeIf { b.status == ReadStatus.FINISHED && it != b.finishedAt }
                if (newStart != null || newFinish != null) {
                    b = b.copy(startedAt = newStart ?: b.startedAt, finishedAt = newFinish ?: b.finishedAt)
                    docsToQueue += b.document
                }
                books[b.document] = b
            }
            st.copy(books = books, links = links, removedBooks = removed)
        }
        return moved
    }

    /**
     * A series' volumes (number -> its chapters or issues), looked up the way a sync would, for
     * filling in the issues before the one you log. [key] is the series' key here.
     */
    fun volumesFor(key: String, title: String, comic: Boolean, upTo: Double): List<Pair<String, List<Double>>> {
        mangaDeadline = System.currentTimeMillis() + MANGA_BUDGET_MS
        mangaLookups = 0
        val now = System.currentTimeMillis() / 1000
        val src = key.substringBefore(':').toLongOrNull() ?: -3L
        val probe = io.github.prithirajn.trackersync.source.MihonBackup.Series(
            src, key.substringAfter(':'), title, null, null, true,
            listOf(io.github.prithirajn.trackersync.source.MihonBackup.Chapter("probe", if (comic) "#${ComicIssues.label(upTo)}" else "Chapter ${ComicIssues.label(upTo)}", upTo, false, 0, null, opened = true)),
            emptyList(), origin = "logged", comic = comic,
        )
        if (comic) return comicMap(probe, now, true) { mangaLookups++ }?.volumes?.map { it.number to it.issues }.orEmpty()
        val snap = store.snapshot
        val estId = "x" + java.lang.Long.toString(key.hashCode().toLong() and 0xffffffffL, 36)
        val m = snap.comicCustom[key]?.let { MangaVolumes.fromCustom(estId, title, it, upTo) }
            ?: snap.mangaMaps[key]?.takeIf { !it.estimated && (it.lastChapter ?: 0.0) >= upTo }
            ?: io.github.prithirajn.trackersync.source.MangaDex.find(title)?.let { (id, t) -> io.github.prithirajn.trackersync.source.MangaDex.volumes(id, t) }
                ?.also { found -> store.update { st -> st.copy(mangaMaps = st.mangaMaps + (key to found)) } }
            ?: MangaVolumes.estimate(estId, title, upTo, MangaVolumes.TYPICAL_PER_VOLUME, now)
        return m.volumes.map { v -> v.number to v.chapters.map { it.number } }
    }

    /** A series' chapters or issues read, and the one you're in. */
    private fun seriesReadOf(s: io.github.prithirajn.trackersync.source.MihonBackup.Series): io.github.prithirajn.trackersync.data.SeriesRead {
        val comic = ComicIssues.isComic(s)
        fun num(c: io.github.prithirajn.trackersync.source.MihonBackup.Chapter) = (if (comic) ComicIssues.issueNumber(c.name) else null) ?: c.number
        val read = s.chapters.filter { it.read }.mapNotNull { num(it) }.toSet()
        val cur = s.chapters.filter { !it.read && it.started }.mapNotNull { c -> num(c)?.takeIf { it !in read }?.let { it to c.lastPage + 1 } }.maxByOrNull { it.first }
        return io.github.prithirajn.trackersync.data.SeriesRead(io.github.prithirajn.trackersync.data.SeriesRead.ranges(read), cur?.first, cur?.second ?: 0)
    }

    /** Where you are in a volume: "Issue #50, page 12", "Chapter 44 read", or "Finished". */
    private fun placeIn(s: io.github.prithirajn.trackersync.source.MihonBackup.Series, v: MangaVolumes.Progress, comic: Boolean): String? {
        if (v.finished) return "Finished"
        val n = v.lastChapter ?: return null
        val word = if (comic) "Issue #" else "Chapter "
        val chs = s.chapters.filter { c -> ((if (comic) ComicIssues.issueNumber(c.name) else null) ?: c.number)?.let { kotlin.math.abs(it - n) < 1e-6 } == true }
        val label = word + ComicIssues.label(n)
        return when {
            chs.any { it.read } -> "$label read"
            chs.any { it.started } -> "$label, page ${chs.maxOf { it.lastPage } + 1}"
            else -> label
        }
    }

    /** The highest chapter you've read, to MangaUpdates (Reading list; never back). */
    private fun sendMangaUpdates(s: io.github.prithirajn.trackersync.source.MihonBackup.Series) {
        val mu = store.snapshot.mangaUpdates ?: return
        if (!mu.enabled) return
        val chapter = s.chapters.filter { it.read }.mapNotNull { it.number }.maxOrNull()?.toInt() ?: return
        if ((store.snapshot.muSent[s.key] ?: 0) >= chapter) return
        try {
            val id = store.snapshot.muSeries[s.key] ?: s.mangaUpdatesId ?: run {
                val missKey = "mu|" + s.key
                val missed = store.snapshot.mangaMissing[missKey]?.let { System.currentTimeMillis() / 1000 - it < MANGA_RETRY_DAYS * DAY } == true
                if (missed || !mayLookUp()) return
                mangaLookups++
                io.github.prithirajn.trackersync.source.MangaUpdates.find(s.title).also { found ->
                    if (found == null) store.update { it.copy(mangaMissing = it.mangaMissing + (missKey to System.currentTimeMillis() / 1000)) }
                }
            }
            if (id == null) { store.log("mangaupdates", "\u201C${s.title}\u201D isn't on MangaUpdates under that title"); return }
            io.github.prithirajn.trackersync.source.MangaUpdates.setChapter(mu.token, id, chapter)
            store.update { it.copy(muSeries = it.muSeries + (s.key to id), muSent = it.muSent + (s.key to chapter), mangaUpdatesOkAt = System.currentTimeMillis(), mangaUpdatesError = null) }
            store.log("mangaupdates", "\u201C${s.title}\u201D: chapter $chapter")
        } catch (e: AuthException) {
            store.update { it.copy(mangaUpdatesError = "MangaUpdates signed you out; sign in again.") }
            throw e
        }
    }

    /**
     * Chapters read in Mihon on this phone, to AniList (never back). A series that came from your
     * AniList list is already there, and one AniList is already past isn't sent.
     */
    private fun sendAniList(s: io.github.prithirajn.trackersync.source.MihonBackup.Series) {
        val al = store.snapshot.anilist ?: return
        if (!al.send || s.origin == "anilist") return
        val chapter = s.chapters.filter { it.read && !it.url.startsWith("al:") }.mapNotNull { it.number }.maxOrNull()?.toInt() ?: return
        if (s.comic == true) return // AniList doesn't list Western comics
        val known = s.tracking.firstOrNull { it.trackerId == io.github.prithirajn.trackersync.source.MihonBackup.ANILIST }?.lastChapter?.toInt() ?: 0
        if (maxOf(store.snapshot.alSent[s.key] ?: 0, known) >= chapter) return
        try {
            val id = store.snapshot.alSeries[s.key] ?: s.anilistId ?: run {
                val missKey = "al|" + s.key
                val missed = store.snapshot.mangaMissing[missKey]?.let { System.currentTimeMillis() / 1000 - it < MANGA_RETRY_DAYS * DAY } == true
                if (missed || !mayLookUp()) return
                mangaLookups++
                io.github.prithirajn.trackersync.source.AniList.find(s.title, s.malId).also { found ->
                    if (found == null) store.update { it.copy(mangaMissing = it.mangaMissing + (missKey to System.currentTimeMillis() / 1000)) }
                }
            }
            if (id == null) { store.log("anilist", "\u201C${s.title}\u201D isn't on AniList under that title"); return }
            io.github.prithirajn.trackersync.source.AniList.setProgress(al.token, id, chapter)
            store.update { it.copy(alSeries = it.alSeries + (s.key to id), alSent = it.alSent + (s.key to chapter), anilistOkAt = System.currentTimeMillis(), anilistError = null) }
            store.log("anilist", "\u201C${s.title}\u201D: chapter $chapter")
        } catch (e: AuthException) {
            store.update { it.copy(anilistError = "AniList signed you out; sign in again.") }
            throw e
        }
    }

    /**
     * What you're listening to on Audible: each title you've started and not finished (and any
     * already brought in, so finishing it is seen). A title that's already in the library (the
     * ebook on CrossPoint or Kindle, the same ISBN, or the same title and author) is that book, not
     * a second one: its position moves to Audible's when that's further along, and your trackers
     * are told. The reader itself isn't moved. Anything else comes in as an audiobook.
     */
    internal fun pullAudible() {
        val account = store.snapshot.audible ?: return
        if (!account.enabled) return
        val known = store.snapshot.books.values.flatMap { b -> (listOf(b.document) + b.aliases).filter { it.startsWith("audible:") } }
            .map { it.removePrefix("audible:") }.toSet()
        try {
            val lib = io.github.prithirajn.trackersync.source.AudibleClient(account).library(known)
            val sec = System.currentTimeMillis() / 1000
            val docsToQueue = LinkedHashSet<String>()
            var added = 0
            var moved = 0
            store.update { s ->
                val books = s.books.toMutableMap()
                val links = s.links.toMutableMap()
                val removed = HashMap(s.removedBooks)
                for (t in lib.titles) {
                    val aDoc = "audible:${t.asin}"
                    val existing = books[aDoc]
                        ?: books.values.firstOrNull { aDoc in it.aliases }
                        ?: books.values.firstOrNull { b -> !b.held && t.isbn != null && b.isbn == t.isbn }
                        ?: books.values.firstOrNull { b -> !b.held && b.title != null && Matcher.sameBook(t.title, t.author, b.title, b.author) }
                    if (existing == null) {
                        if (aDoc.removePrefix("audible:") in known) continue
                        // Removed from the library: stays removed unless you've listened further since.
                        val remPct = removed[aDoc]
                        if (remPct != null) {
                            if (t.percent <= remPct + 0.001 && t.status != ReadStatus.FINISHED) continue
                            removed.remove(aDoc)
                        }
                        added++
                        books[aDoc] = Book(
                            document = aDoc, title = t.title, author = t.author, percentage = t.percent, status = t.status,
                            timestamp = sec, coverUrl = t.coverUrl, origin = "audible", isbn = t.isbn,
                            finishedAt = if (t.status == ReadStatus.FINISHED) (t.finishedAt ?: sec) else null,
                            description = t.description, descriptionSource = t.description?.let { "Audible" },
                        )
                        // New books start paused unless "Auto-sync new books" is on, like the reader's.
                        if (s.settings.autoEnableNewBooks) docsToQueue += aDoc
                        else for (tr in Trackers.all) {
                            val key = Link.key(tr.id, aDoc)
                            if (links[key] == null) links[key] = Link(tr.id, aDoc, null, source = LinkSource.NONE, note = "Not synced")
                        }
                        continue
                    }
                    val doc = existing.document
                    val finished = existing.status == ReadStatus.FINISHED || t.status == ReadStatus.FINISHED
                    val ahead = t.percent > existing.percentage + 0.005
                    val becameFinished = t.status == ReadStatus.FINISHED && existing.status != ReadStatus.FINISHED
                    var b = existing
                    if (doc != aDoc && aDoc !in b.aliases) b = b.copy(aliases = b.aliases + aDoc)
                    if (ahead || becameFinished) {
                        moved++
                        val pct = maxOf(existing.percentage, t.percent)
                        val pages = b.pageCount
                        b = b.copy(
                            percentage = pct,
                            status = if (finished) ReadStatus.FINISHED else ReadStatus.READING,
                            timestamp = maxOf(sec, existing.timestamp + 1),
                            pagesRead = if (pages != null && pages > 0) Math.round(pct * pages).toInt().coerceIn(0, pages) else b.pagesRead,
                            finishedAt = b.finishedAt ?: if (finished) (t.finishedAt ?: sec) else null,
                            coverUrl = b.coverUrl ?: t.coverUrl,
                        )
                        docsToQueue += doc
                    }
                    books[doc] = b
                }
                s.copy(books = books, links = links, removedBooks = removed, audible = lib.account.copy(enabled = account.enabled), audibleOkAt = System.currentTimeMillis(), audibleError = null)
            }
            if (docsToQueue.isNotEmpty()) {
                val snap = store.snapshot
                for (tr in Trackers.all) if (snap.service(tr.id).let { it.linked && it.enabled }) queue(store, tr.id, docsToQueue)
            }
            val parts = listOfNotNull(
                added.takeIf { it > 0 }?.let { "Brought in $it audiobook${if (it == 1) "" else "s"}" },
                moved.takeIf { it > 0 }?.let { "$it book${if (it == 1) "" else "s"} moved ahead on Audible" },
            )
            if (parts.isNotEmpty()) store.log("audible", parts.joinToString(" \u00B7 "))
        } catch (e: AuthException) {
            store.update { it.copy(audibleError = Health.describeAudible(e)) }
            store.log("audible", "Audible: sign in again", error = true)
        } catch (e: Exception) {
            store.update { it.copy(audibleError = Health.describeAudible(e)) }
            store.log("audible", "Audible: ${e.message}", error = true)
        }
    }

    /**
     * Reads Libby loans, and the reading progress and highlights/notes Libby hands over, from
     * sentry.libbyapp.com; fills in page counts, ISBNs and covers it didn't give.
     */
    internal fun pullLibby() {
        val account = store.snapshot.libby ?: return
        if (!account.enabled) return
        val wasNeverChecked = store.snapshot.libbyOkAt == 0L || store.snapshot.libbyError != null
        val knownIds = store.snapshot.books.values.flatMap { b ->
            listOfNotNull(
                b.document.takeIf { it.startsWith("libby:") }?.removePrefix("libby:"),
            ) + b.aliases.filter { it.startsWith("libby:") }.map { it.removePrefix("libby:") }
        }.toSet()
        val client = LibbyClient(account)
        try {
            val res = client.fetchLibrary(knownIds)
            val items = res.books
            // Page counts, ISBNs and covers Libby didn't give: looked up for a few books per sync,
            // and a book none could be found for isn't looked up again.
            val enrichedPages = HashMap<String, Int>()
            val enrichedIsbns = HashMap<String, String>()
            val enrichedCovers = HashMap<String, String>()
            val noPages = HashSet<String>()
            var lookups = 0
            for (item in items) {
                if (item.titleId in store.snapshot.libbyNoPages) continue
                val existingBook = store.snapshot.books["libby:${item.titleId}"]
                    ?: store.snapshot.books.values.firstOrNull { b ->
                        b.title != null && (
                            (item.isbn != null && b.isbn == item.isbn) ||
                                Matcher.sameBook(item.title, item.author, b.title, b.author)
                            )
                    }
                val currentPages = existingBook?.pageCount ?: item.totalPages
                if (currentPages != null || lookups >= LIBBY_LOOKUPS_PER_RUN) continue
                lookups++
                val currentIsbn = existingBook?.isbn ?: item.isbn
                val d = runCatching {
                    io.github.prithirajn.trackersync.source.BookInfo.lookup(
                        Book(
                            document = "libby:${item.titleId}",
                            title = item.title,
                            author = item.author,
                            percentage = item.percentage ?: 0.0,
                            status = item.status,
                            timestamp = System.currentTimeMillis() / 1000L,
                            isbn = currentIsbn,
                        ),
                        isbn = currentIsbn,
                    )
                }.getOrNull()
                val pages = d?.pages?.takeIf { it > 0 }
                if (pages != null) enrichedPages[item.titleId] = pages else noPages += item.titleId
                (currentIsbn ?: d?.isbn13 ?: d?.isbn10)?.let { enrichedIsbns[item.titleId] = it }
                ((existingBook?.coverUrl ?: item.coverUrl) ?: d?.coverUrl)?.let { enrichedCovers[item.titleId] = it }
            }

            val now = System.currentTimeMillis()
            val sec = now / 1000
            var added = 0
            var moved = 0
            var newHighlights = 0
            val docsToQueue = ArrayList<String>()

            store.update { s ->
                val books = HashMap(s.books)
                val links = HashMap(s.links)
                val pending = HashMap(s.pending)
                var toCp = s.toCrossPoint
                var toKo = s.toKosync
                val allClips = ArrayList(s.libbyClips)
                val oldClipIds = s.libbyClips.map { it.id }.toSet()
                val removed = HashMap(s.removedBooks)

                for (item in items) {
                    val libbyDoc = "libby:${item.titleId}"
                    val oldLibby = books[libbyDoc]
                    val cpBook = books.values.firstOrNull { b ->
                        b.origin == null && b.title != null && (
                            (item.isbn != null && b.isbn == item.isbn) ||
                                Matcher.sameBook(item.title, item.author, b.title, b.author)
                            )
                    }
                    val existing = cpBook
                        ?: oldLibby
                        ?: books.values.firstOrNull { b ->
                            b.title != null && (
                                (item.isbn != null && b.isbn == item.isbn) ||
                                    Matcher.sameBook(item.title, item.author, b.title, b.author)
                                )
                        }
                    val doc = existing?.document ?: libbyDoc

                    val remPct = removed[doc] ?: removed[libbyDoc]
                    val itemPct = item.percentage ?: 0.0
                    if (existing == null && remPct != null) {
                        if (itemPct <= remPct + 0.001) {
                            continue
                        } else {
                            removed.remove(doc)
                            removed.remove(libbyDoc)
                        }
                    }

                    // If the user started on Libby and later opened the book on CrossPoint, unify into CrossPoint
                    if (cpBook != null && oldLibby != null && cpBook.document != libbyDoc) {
                        val cpDoc = cpBook.document
                        books.remove(libbyDoc)
                        pending.keys.removeAll { it.endsWith("|$libbyDoc") }
                        for (l in links.values.filter { it.document == libbyDoc }) {
                            links.remove(l.key)
                            if (l.externalId != null && l.source != LinkSource.NONE) {
                                val targetKey = Link.key(l.service, cpDoc)
                                if (links[targetKey]?.externalId == null) {
                                    links[targetKey] = l.copy(document = cpDoc)
                                }
                            }
                        }
                        for (i in allClips.indices) {
                            if (allClips[i].document == libbyDoc) {
                                allClips[i] = allClips[i].copy(document = cpDoc)
                            }
                        }
                    }

                    val bestPrevPct = maxOf(existing?.percentage ?: 0.0, oldLibby?.percentage ?: 0.0)
                    val nextPct = maxOf(bestPrevPct, itemPct)
                    val prevFinished = existing?.status == ReadStatus.FINISHED || oldLibby?.status == ReadStatus.FINISHED
                    val nextStatus = if (prevFinished || nextPct >= 0.98) {
                        ReadStatus.FINISHED
                    } else {
                        existing?.status ?: item.status
                    }

                    if (existing == null) {
                        added++
                        val autoEnable = s.settings.autoEnableNewBooks
                        if (!autoEnable) {
                            for (t in Trackers.all) {
                                val key = Link.key(t.id, doc)
                                if (links[key] == null) {
                                    links[key] = Link(t.id, doc, null, source = LinkSource.NONE, note = "Not synced")
                                }
                            }
                        } else {
                            docsToQueue.add(doc)
                        }
                    } else if (nextPct > existing.percentage + 0.0005 || nextStatus != existing.status) {
                        moved++
                        docsToQueue.add(doc)
                        if (existing.origin == null) {
                            toCp = toCp + doc
                            if (s.kosync != null) toKo = toKo + doc
                        }
                    }

                    val pageCount = existing?.pageCount ?: oldLibby?.pageCount ?: item.totalPages ?: enrichedPages[item.titleId]
                    val pagesRead = if (pageCount != null && pageCount > 0 && nextPct > 0.0) {
                        Math.round(nextPct * pageCount).toInt().coerceIn(0, pageCount)
                    } else {
                        existing?.pagesRead ?: oldLibby?.pagesRead
                    }
                    val isbn = existing?.isbn ?: oldLibby?.isbn ?: item.isbn ?: enrichedIsbns[item.titleId]
                    val coverUrl = existing?.coverUrl ?: oldLibby?.coverUrl ?: item.coverUrl ?: enrichedCovers[item.titleId]
                    val startedAt = listOfNotNull(existing?.startedAt, oldLibby?.startedAt, item.startedAt).minOrNull()
                    val finishedAt = existing?.finishedAt ?: oldLibby?.finishedAt ?: item.finishedAt
                    val stamp = if (existing == null || nextPct > existing.percentage + 0.0005) {
                        maxOf(sec, item.updatedAt ?: 0L, (existing?.timestamp ?: 0L) + 1)
                    } else {
                        existing.timestamp
                    }

                    val b = (existing ?: Book(
                        document = doc,
                        title = item.title,
                        author = item.author,
                        percentage = nextPct,
                        status = nextStatus,
                        timestamp = stamp,
                        coverUrl = coverUrl,
                        origin = "libby",
                        pageCount = pageCount,
                        pagesRead = pagesRead,
                        startedAt = startedAt,
                        finishedAt = finishedAt,
                        isbn = isbn,
                    )).copy(
                        percentage = nextPct,
                        status = nextStatus,
                        coverUrl = coverUrl,
                        pageCount = pageCount,
                        pagesRead = pagesRead,
                        rating = existing?.rating ?: oldLibby?.rating,
                        review = existing?.review ?: oldLibby?.review,
                        startedAt = startedAt,
                        finishedAt = finishedAt,
                        isbn = isbn,
                        timestamp = stamp,
                        manualAt = if (existing != null && existing.origin == null && nextPct > existing.percentage + 0.005) stamp else existing?.manualAt,
                    )
                    books[doc] = b

                    if (account.syncHighlights && item.highlights.isNotEmpty()) {
                        val mappedClips = item.highlights.map { it.copy(document = doc) }
                        newHighlights += mappedClips.count { it.id !in oldClipIds }
                        allClips.removeAll { it.document == doc }
                        allClips.addAll(mappedClips)
                    }
                }

                s.copy(
                    books = books,
                    links = links,
                    pending = pending,
                    toCrossPoint = toCp,
                    toKosync = toKo,
                    libby = res.account.copy(
                        enabled = account.enabled,
                        syncHighlights = account.syncHighlights,
                        syncBorrowedTag = account.syncBorrowedTag,
                    ),
                    libbyClips = allClips,
                    libbyNoPages = s.libbyNoPages + noPages,
                    libbyOkAt = now,
                    libbyError = null,
                    removedBooks = removed,
                )
            }

            if (docsToQueue.isNotEmpty()) {
                val snap = store.snapshot
                for (t in Trackers.all) {
                    if (snap.service(t.id).let { it.linked && it.enabled }) {
                        queue(store, t.id, docsToQueue)
                    }
                }
            }

            val logParts = ArrayList<String>()
            if (added > 0) logParts.add("Brought in $added book${if (added == 1) "" else "s"} from Libby")
            if (moved > 0) logParts.add("$moved book${if (moved == 1) "" else "s"} moved ahead on Libby")
            if (newHighlights > 0) logParts.add("$newHighlights new highlight${if (newHighlights == 1) "" else "s"}")

            val label = res.account.name ?: "Libby"
            if (logParts.isNotEmpty()) {
                store.log("libby", logParts.joinToString(" · "))
            } else if (items.isEmpty()) {
                store.log("libby", "Connected to $label (0 active loans found)")
            } else if (wasNeverChecked) {
                store.log("libby", "Connected to $label: ${items.size} loan${if (items.size == 1) "" else "s"} checked")
            }
        } catch (e: AuthException) {
            store.update { it.copy(libbyError = Health.describeLibby(e)) }
            store.log("libby", "Libby: link your account again", error = true)
        } catch (e: Exception) {
            if (isOffline(e) && reallyOffline()) { offlineThisRun = true; return }
            val msg = if (isOffline(e)) "Libby: " + blockedHost(e, e.message ?: "couldn't reach Libby") else "Libby: ${e.message}"
            store.update { it.copy(libbyError = if (isOffline(e)) msg.removePrefix("Libby: ") else Health.describeLibby(e)) }
            store.log("libby", msg, error = true)
        }
    }

    /**
     * Looks for a few books on the web novel sites each sync (the ones being read first): books
     * with no ISBN that aren't linked to a story yet and weren't searched lately. A clear match
     * becomes the book's story, marked as found so the book page offers to change it.
     */
    private fun findWebNovels() {
        io.github.prithirajn.trackersync.source.WebNovels.newRun()
        val deadline = System.currentTimeMillis() + WEB_SEARCH_BUDGET_MS
        val s0 = store.snapshot
        val todo = s0.books.values
            .filter { b -> b.title != null && b.isbn == null && !b.held && !b.isMangaOrComic && b.document !in s0.webNovels && webSearchDue(s0, b.document) }
            .sortedWith(compareBy<Book> { if (it.status == ReadStatus.READING) 0 else 1 }.thenByDescending { it.timestamp })
            .take(WEB_SEARCHES_PER_RUN)
        for (b in todo) {
            if (System.currentTimeMillis() > deadline) break // the rest wait for the next sync
            val now = System.currentTimeMillis() / 1000
            val d = runCatching { io.github.prithirajn.trackersync.source.WebNovels.search(b.title!!, b.author) }.getOrNull()
            store.update { s ->
                val web = d?.web?.copy(found = true)
                s.copy(
                    webSearched = if (web != null || webSearchComplete()) s.webSearched + (b.document to now) else s.webSearched,
                    webNovels = if (web != null && b.document !in s.webNovels) s.webNovels + (b.document to web) else s.webNovels,
                )
            }
            if (d?.web != null) store.log(null, "Found \u201C${b.displayTitle}\u201D on ${d.web.site}")
        }
    }

    /** Asks the server to look up covers for a few books that have none, newest first. */
    private fun fillCovers(client: CrossPointClient) {
        val missing = store.snapshot.books.values
            .filter { (it.origin == null || it.origin in setOf("kindle", "libby", "audible", "seekquel", "mihon", "anilist", "logged")) && it.coverUrl == null && it.title != null && !it.detailsFetched && it.document !in coverTried }
            .sortedByDescending { it.timestamp }
            .take(COVER_LOOKUPS_PER_RUN)
        val sqSvc = store.snapshot.service("seekquel")
        val sqSession = if (sqSvc.linked && !sqSvc.needsReauth) Session(sqSvc.credential) else null
        for (b in missing) {
            coverTried.add(b.document)
            val title = b.title ?: continue
            val url = (if (b.origin == null) runCatching { client.cover(b.document) }.getOrNull() else null)
                ?: sqSession?.let { runCatching { SeekquelTracker.details(it, store.snapshot.link("seekquel", b.document), title, b.author)?.coverUrl }.getOrNull() }
                ?: runCatching { OpenLibrary.cover(title, b.author) }.getOrNull()
                ?: continue
            store.update { s -> s.books[b.document]?.let { s.copy(books = s.books + (b.document to it.copy(coverUrl = url))) } ?: s }
        }
    }

    /** Sends every queued update, one tracker at a time (or for onlyDocument if specified). */
    private fun pushPending(onlyDocument: String? = null): Result {
        val attention = ArrayList<String>()
        var sent = 0
        for (tracker in Trackers.all) {
            val svc = store.snapshot.service(tracker.id)
            if (!svc.linked || !svc.enabled || svc.needsReauth) continue
            val queue = store.snapshot.pending.values
                .filter { it.service == tracker.id && (onlyDocument == null || it.document == onlyDocument) }
                .sortedBy { it.queuedAt }
            if (queue.isEmpty()) {
                // Nothing waiting means nothing is failing: clear an old error.
                if (svc.lastError != null) store.updateService(tracker.id) { it.copy(lastError = null) }
                continue
            }
            var failed = false
            val session = Session(svc.credential).also { it.visibility = svc.visibility }
            try {
                for (p in queue) {
                    if (System.currentTimeMillis() - p.queuedAt > MAX_AGE_DAYS * DAY * 1000) {
                        dropPending(p, "Gave up after $MAX_AGE_DAYS days")
                        continue
                    }
                    try {
                        val outcome = process(tracker, session, p)
                        if (outcome != null) sent++
                        store.update { s -> s.copy(pending = s.pending - p.key) }
                    } catch (e: AwaitingConfirm) {
                        // Matched, and waiting for you to confirm it's the right book: kept queued.
                        continue
                    } catch (e: AuthException) {
                        throw e
                    } catch (e: NetworkException) {
                        throw e
                    } catch (e: Exception) {
                        val tries = p.tries + 1
                        if (tries >= MAX_TRIES) {
                            dropPending(p, e.message ?: "Failed")
                        } else {
                            store.update { s -> s.copy(pending = s.pending + (p.key to p.copy(tries = tries, lastError = e.message))) }
                        }
                        failed = true
                        val msg = "${title(p.document)}: ${e.message}"
                        store.log(tracker.id, msg, error = true)
                        store.updateService(tracker.id) { it.copy(lastError = msg) }
                    }
                    Thread.sleep(PAUSE_MS)
                }
                // A pass where every book went through clears the error from an earlier one.
                store.updateService(tracker.id) {
                    it.copy(lastSync = System.currentTimeMillis(), lastError = if (failed) it.lastError else null)
                }
            } catch (e: AuthException) {
                attention.add(tracker.name)
                store.updateService(tracker.id) { it.copy(needsReauth = true, lastError = e.message) }
                store.log(tracker.id, e.message ?: "Sign in again", error = true)
            } catch (e: NetworkException) {
                // This tracker is unreachable right now; its books stay queued and the others go on.
                netFail(tracker.id, "Couldn't reach ${tracker.name}; will retry (${e.message})", e)
            } finally {
                if (session.changed) {
                    val credential = session.encode()
                    store.updateService(tracker.id) { it.copy(credential = credential) }
                }
            }
        }
        val msg = when {
            attention.isNotEmpty() -> "Sign in again: ${attention.joinToString(", ")}"
            sent == 0 -> "Up to date"
            else -> "Sent $sent update${if (sent == 1) "" else "s"}"
        }
        return Result(msg, attention)
    }

    /**
     * Sends new CrossPoint highlights to each tracker that takes them, one batch per book.
     * Public-only trackers (Goodreads, Pagebound) need "Everyone". Books not linked yet wait.
     */
    /**
     * Seekquel's positions you chose (from its prompt) go back to crosspoint-sync with their
     * status, and to the KOSync server, so the reader and the companion app have them too.
     */
    internal fun pushToCrossPoint() {
        pushToKosync()
        val todo = store.snapshot.toCrossPoint
        if (todo.isEmpty()) return
        val a = store.snapshot.account ?: return
        val client = CrossPointClient(a)
        for (doc in todo) {
            val b = store.snapshot.books[doc]
            if (b == null || b.origin != null) { store.update { s -> s.copy(toCrossPoint = s.toCrossPoint - doc) }; continue }
            try {
                client.putProgress(doc, b.percentage, b.timestamp, DEVICE_ID)
                val status = when (b.status) {
                    ReadStatus.FINISHED -> "finished"
                    ReadStatus.DNF -> "dnf"
                    ReadStatus.PAUSED -> "paused"
                    else -> "reading"
                }
                runCatching { client.putStatus(doc, status) }
                store.update { s -> s.copy(toCrossPoint = s.toCrossPoint - doc) }
                store.log(null, "${b.displayTitle}: ${b.percent}% sent to CrossPoint")
            } catch (e: NetworkException) {
                return // tried again next sync
            } catch (e: AuthException) {
                throw e
            } catch (e: Exception) {
                store.update { s -> s.copy(toCrossPoint = s.toCrossPoint - doc) }
                store.log(null, "${b.displayTitle}: couldn't send the position to CrossPoint (${e.message})", error = true)
            }
        }
    }

    private fun pushToKosync() {
        val todo = store.snapshot.toKosync
        if (todo.isEmpty()) return
        val account = store.snapshot.kosync ?: run { store.update { it.copy(toKosync = emptySet()) }; return }
        val client = KosyncClient(account)
        for (doc in todo) {
            val b = store.snapshot.books[doc]
            if (b == null) { store.update { s -> s.copy(toKosync = s.toKosync - doc) }; continue }
            try {
                client.putProgress(doc, b.percentage, DEVICE_ID)
                store.update { s -> s.copy(toKosync = s.toKosync - doc) }
                store.log(null, "${b.displayTitle}: ${b.percent}% sent to KOSync")
            } catch (e: NetworkException) {
                return
            } catch (e: Exception) {
                store.update { s -> s.copy(toKosync = s.toKosync - doc, kosyncError = Health.describeKosync(e)) }
                store.log(null, "${b.displayTitle}: couldn't send the position to KOSync (${e.message})", error = true)
            }
        }
    }

    /** CrossPoint's highlights are kept here too, to show (and edit) under each book, and to send. */
    private fun refreshClips() {
        store.snapshot.account?.let { a ->
            runCatching { CrossPointClient(a).clippings() }.getOrNull()
                ?.let { cp -> store.update { s -> s.copy(cpClips = HighlightPosition.place(s, cp.filter { it.id !in s.clipHidden })) } }
        }
    }

    /** Deletes the tracker entries of highlights you deleted, where the tracker allows it. */
    private fun deleteHighlights() {
        val todo = store.snapshot.clipDeletes
        if (todo.isEmpty()) return
        for ((svcId, keys) in todo.groupBy { it.substringBefore('|') }) {
            val t = Trackers.byId(svcId)
            val svc = store.snapshot.service(svcId)
            if (t == null || !svc.linked) { store.update { s -> s.copy(clipDeletes = s.clipDeletes - keys.toSet()) }; continue }
            if (!svc.enabled || svc.needsReauth) continue
            val session = Session(svc.credential)
            for (key in keys) {
                try {
                    t.deleteHighlight(session, key.substringAfter('|'))
                    store.log(t.id, "Highlight deleted")
                } catch (e: NetworkException) {
                    continue // tried again next sync
                } catch (e: AuthException) {
                    store.updateService(t.id) { it.copy(needsReauth = true, lastError = e.message) }
                    break
                } catch (e: Exception) {
                    store.log(t.id, "Highlight not deleted (${e.message}); delete it there", error = true)
                }
                store.update { s -> s.copy(clipDeletes = s.clipDeletes - key) }
            }
            if (session.changed) store.updateService(t.id) { it.copy(credential = session.encode()) }
        }
    }

    private fun highlightsOn(t: Tracker, s: Snapshot): Boolean {
        val svc = s.service(t.id)
        return svc.linked && svc.enabled && !svc.needsReauth && svc.highlights &&
            t.highlights != HighlightSupport.NONE &&
            (t.highlights != HighlightSupport.PUBLIC_POST || svc.visibility == Visibility.PUBLIC || t.highlightsIgnoreVisibility)
    }

    /** Linked and able to take highlights, whether or not "Send highlights" is on (for one sent by hand). */
    private fun canTakeHighlights(t: Tracker, s: Snapshot): Boolean {
        val svc = s.service(t.id)
        return svc.linked && svc.enabled && !svc.needsReauth && t.highlights != HighlightSupport.NONE
    }

    /** A book's highlights not yet sent to [t] (not counting ones to send again), as you edited them. */
    private fun newHighlights(t: Tracker, document: String): List<io.github.prithirajn.trackersync.source.Clipping> {
        val s = store.snapshot
        if (!highlightsOn(t, s)) return emptyList()
        val since = s.service(t.id).highlightsSince
        return (s.cpClips + s.paperClips + s.kindleClips + s.libbyClips + s.moonClips).filter { it.document == document && it.id !in s.clipHidden }
            .map { HighlightPosition.withPage(edited(it, s.clipEdits), s.books[it.document]) }
            .filter { val key = "${t.id}|${it.id}"; it.createdAt >= since && key !in s.sentClippings && key !in s.resend }
    }

    private fun pushHighlights(onlyDocument: String? = null): Int {
        val s0 = store.snapshot
        // Trackers with "Send highlights" on, and ones a highlight was sent to by hand ("Post to Fable").
        val active = Trackers.all.filter { t ->
            highlightsOn(t, s0) || (canTakeHighlights(t, s0) && s0.resend.any { it.startsWith("${t.id}|") })
        }
        if (active.isEmpty()) return 0
        // CrossPoint's highlights and ones photographed from paper, as you edited them.
        val clips = (s0.cpClips + s0.paperClips + s0.kindleClips + s0.libbyClips + s0.moonClips)
            .filter { it.id !in s0.clipHidden && (onlyDocument == null || it.document == onlyDocument) }
            .map { HighlightPosition.withPage(edited(it, s0.clipEdits), s0.books[it.document]) }
        if (clips.isEmpty()) return 0
        var sent = 0
        for (t in active) {
            val svc = store.snapshot.service(t.id)
            val on = highlightsOn(t, store.snapshot)
            val fresh = clips.filter {
                val key = "${t.id}|${it.id}"
                key in store.snapshot.resend || (on && it.createdAt >= svc.highlightsSince && key !in store.snapshot.sentClippings)
            }
            if (fresh.isEmpty()) continue
            val session = Session(svc.credential).also { it.visibility = svc.visibility }
            try {
                for ((doc, group) in fresh.groupBy { it.document }) {
                    val linkNow = store.snapshot.link(t.id, doc) ?: continue
                    if (linkNow.externalId == null || linkNow.source == LinkSource.NONE || !linkNow.confirmed) continue
                    val book = store.snapshot.books[doc]
                    val name = book?.displayTitle ?: doc
                    val pct = book?.percent ?: 0
                    val link = linkNow
                    // Each highlight at its own place in the book (26%, 28%…), never past where you are.
                    // Trackers other than Hardcover and Seekquel get them a few at a time, a little apart.
                    val byHand = t.id !in API_TRACKERS
                    fun at(c: io.github.prithirajn.trackersync.source.Clipping): Int =
                        c.percent?.let { Math.round(it * 100).toInt().coerceIn(0, 100) }?.coerceAtMost(pct) ?: pct
                    val (again, new) = group.partition { "${t.id}|${it.id}" in store.snapshot.resend }
                    val sortedNew = new.sortedWith(compareBy({ it.percent ?: 0.0 }, { it.createdAt }))
                        // A few per book per sync (counting every pass of this sync); the rest go on the next one.
                        .let { if (byHand) it.take((BY_HAND_PER_RUN - (byHandSent["${t.id}|$doc"] ?: 0)).coerceAtLeast(0)) else it }
                    var posted = 0
                    fun pace() { if (byHand && posted++ > 0) Thread.sleep(BY_HAND_GAP_MS + (Math.random() * BY_HAND_JITTER_MS).toLong()) }
                    // The position the last entry went out at: where the tracker's progress is left.
                    var lastAt: Int? = null
                    val positions = ArrayList<Int>()
                    try {
                        // Sent again: replace the earlier entry where the tracker allows, else post anew.
                        for (c in again) {
                            pace()
                            val key = "${t.id}|${c.id}"
                            val previous = store.snapshot.clipRemote[key]
                            val clipPct = at(c)
                            val id = t.postHighlight(session, link, clipPct, c, previous)
                            // A new entry (not an edit of the old one in place) moves the progress there.
                            if (id == null || id != previous) lastAt = clipPct
                            record(key, id)
                            val first = key !in store.snapshot.sentClippings && previous == null
                            val what = when {
                                previous != null && id == previous -> "highlight updated"
                                first -> "highlight sent"
                                else -> "highlight sent again"
                            }
                            store.log(t.id, "$name: $what")
                            recordSent(t.id, doc, SendEvent(at = System.currentTimeMillis(), other = what))
                            sent++
                        }
                        if (sortedNew.isNotEmpty()) {
                            if (t.oneEntryPerHighlight) {
                                // One entry each, in book order, with its id kept, so an edit can replace it later.
                                for (c in sortedNew) {
                                    pace()
                                    if (byHand) byHandSent["${t.id}|$doc"] = (byHandSent["${t.id}|$doc"] ?: 0) + 1
                                    val clipPct = at(c)
                                    record("${t.id}|${c.id}", t.postHighlight(session, link, clipPct, c, null))
                                    lastAt = clipPct
                                    positions += clipPct
                                }
                            } else {
                                t.postHighlights(session, link, pct, sortedNew)
                                store.update { s -> s.copy(sentClippings = s.sentClippings + sortedNew.map { "${t.id}|${it.id}" }) }
                            }
                        }
                        // Entries that also set your progress (Goodreads, StoryGraph) went out at the
                        // highlights' own places: your real position goes last, so the tracker ends where you are.
                        val endedAt = lastAt
                        var restoredProgress: Int? = null
                        if (t.highlightEntriesSetProgress && endedAt != null) {
                            if (endedAt != pct) {
                                t.pushProgress(session, linkNow, pct, svc.visibility)
                                restoredProgress = pct
                                store.log(t.id, "$name: back to $pct% after the highlights")
                            }
                            store.snapshot.link(t.id, doc)?.let { curr -> store.putLink(curr.copy(lastPercent = pct)) }
                        }
                        if (sortedNew.isNotEmpty()) {
                            store.log(t.id, "$name: sent ${sortedNew.size} highlight${if (sortedNew.size == 1) "" else "s"}")
                            // "at 26%, 28%" where each went in as its own entry at its own place,
                            // followed by ", back to X%" if your real reading progress was restored.
                            val where = if (t.highlightEntriesSetProgress && positions.isNotEmpty()) {
                                val atStr = "at " + positions.distinct().joinToString(", ") { "$it%" }
                                if (restoredProgress != null) "$atStr, back to $restoredProgress%" else atStr
                            } else null
                            recordSent(t.id, doc, SendEvent(at = System.currentTimeMillis(), highlights = sortedNew.size, other = where))
                            sent += sortedNew.size
                        }
                    } catch (e: AuthException) {
                        throw e
                    } catch (e: NetworkException) {
                        throw e
                    } catch (e: Exception) {
                        store.log(t.id, "$name: highlights not sent (${e.message})", error = true)
                    }
                    Thread.sleep(PAUSE_MS)
                }
            } catch (e: AuthException) {
                store.updateService(t.id) { it.copy(needsReauth = true, lastError = e.message) }
                store.log(t.id, e.message ?: "Sign in again", error = true)
            } catch (e: NetworkException) {
                netFail(t.id, "Couldn't reach ${t.name} for highlights; will retry", e)
            } finally {
                if (session.changed) {
                    val credential = session.encode()
                    store.updateService(t.id) { it.copy(credential = credential) }
                }
            }
        }
        return sent
    }

    /** Marks one highlight sent to one tracker, keeping the entry's id there when it gave one. */
    private fun record(key: String, remoteId: String?) = store.update { s ->
        s.copy(
            sentClippings = s.sentClippings + key,
            resend = s.resend - key,
            clipRemote = if (remoteId != null) s.clipRemote + (key to remoteId) else s.clipRemote,
        )
    }

    private fun title(document: String) = store.snapshot.books[document]?.displayTitle ?: document

    private fun dropPending(p: Pending, why: String) {
        store.update { s -> s.copy(pending = s.pending - p.key) }
        store.log(p.service, "${title(p.document)}: dropped ($why)", error = true)
    }

    /** Brings one book up to date on one tracker. Returns a description, or null if nothing was sent. */
    private fun process(tracker: Tracker, session: Session, p: Pending): String? {
        val book = store.snapshot.books[p.document] ?: return null
        // A book brought in from this tracker is already there; past reading waits for the user.
        if (book.origin == tracker.id || book.held) return null
        var link = store.snapshot.link(tracker.id, p.document)
        if (link == null) {
            link = autoLink(tracker, session, book) ?: return null
        }
        if (link.source == LinkSource.NONE || link.externalId == null) return null
        // A first match waits for you to confirm it; the pending update is kept for then.
        if (!link.confirmed) throw AwaitingConfirm()

        val remote = tracker.state(session, link)
        val percent = book.percent
        val cp = book.status
        // An ongoing web novel read to its latest chapter is caught up, not finished.
        val ongoing = store.snapshot.webNovels[book.document]?.ongoing == true
        // "Read again" since this tracker was last updated: its Finished is moved back to Reading.
        val rereading = (store.snapshot.rereads[book.document] ?: 0L) * 1000 > link.updatedAt
        val target: ReadStatus = when {
            percent >= 98 && ongoing && cp != ReadStatus.FINISHED -> ReadStatus.READING
            percent >= 98 -> ReadStatus.FINISHED
            cp != null && cp in setOf(ReadStatus.FINISHED, ReadStatus.DNF, ReadStatus.PAUSED) -> cp
            else -> ReadStatus.READING
        }
        val visibility = store.snapshot.service(tracker.id).visibility
        var did: String? = null
        var progressSent = false
        var alreadyThere = false
        // For the book's "what was sent" history.
        var carriedCount = 0
        val extras = ArrayList<String>()
        var note: String? = null
        var skipped = false
        // Finished there and not read again here: its read (and its days) are left alone.
        var leftAsIs = false

        try {
        when (target) {
            ReadStatus.READING -> {
                when (remote.status) {
                    ReadStatus.FINISHED, ReadStatus.DNF -> {
                        if (rereading) {
                            tracker.setStatus(session, link, ReadStatus.READING)
                            did = "Reading again"
                            extras += "Reading again"
                        } else {
                            note = "Marked ${remote.status?.label} on ${tracker.name}; left as is"
                            leftAsIs = true
                        }
                    }
                    ReadStatus.READING -> Unit
                    else -> {
                        tracker.setStatus(session, link, ReadStatus.READING)
                        did = "Reading"
                        extras += "Reading"
                    }
                }
                if (note == null) {
                    val skipProgress = tracker.privacy == PrivacySupport.SKIP_PROGRESS && visibility == Visibility.PRIVATE
                    // After the book got longer, a tracker further along is on the old length: bring it to the new one.
                    val grewSince = (store.snapshot.grewAt[book.document] ?: 0L) * 1000 > link.updatedAt
                    val behind = tracker.readsProgress && remote.percent != null && percent < remote.percent && !grewSince && !rereading
                    when {
                        skipProgress -> Unit
                        behind -> note = "${tracker.name} is further along (${remote.percent}%)"
                        // Already there: read back where possible, else what this app last sent.
                        // Already there (read back): recorded as what it has, so it shows like the others.
                        tracker.readsProgress && remote.percent == percent -> alreadyThere = true
                        !tracker.readsProgress && link.lastPercent == percent && remote.status == ReadStatus.READING -> Unit
                        else -> {
                            // New highlights ride along in the same update where the tracker allows
                            // (StoryGraph: one journal entry, not a second "0 pages read" one).
                            val carry = if (tracker.progressCarriesHighlights) newHighlights(tracker, book.document) else emptyList()
                            val carried = carry.isNotEmpty() && tracker.pushProgressWithHighlights(session, link, percent, visibility, carry)
                            if (!carried) tracker.pushProgress(session, link, percent, visibility)
                            progressSent = true
                            did = (did?.let { "$it, " } ?: "") + "$percent%"
                            if (carried) {
                                store.update { s -> s.copy(sentClippings = s.sentClippings + carry.map { "${tracker.id}|${it.id}" }) }
                                did += " with ${carry.size} highlight${if (carry.size == 1) "" else "s"}"
                                carriedCount = carry.size
                            }
                        }
                    }
                }
            }
            ReadStatus.FINISHED, ReadStatus.DNF, ReadStatus.PAUSED -> {
                if (target !in tracker.statuses) {
                    note = "${tracker.name} has no ${target.label} status"
                } else if (remote.status != target) {
                    tracker.setStatus(session, link, target)
                    did = target.label
                    extras += target.label
                }
            }
            else -> Unit
        }
        } catch (e: SkipException) {
            // Something only the user can fix on the tracker: a note on the book, not an error.
            note = e.message
            skipped = true
        }

        // Read today, but not a whole percent: no progress went out, yet a tracker with a daily
        // streak (Fable) still hears that you read, so the streak isn't broken.
        if (target == ReadStatus.READING && note == null && !skipped && !progressSent && day(book.timestamp) == java.time.LocalDate.now()) {
            val streak = runCatching { tracker.readToday(session, link) }.getOrDefault(false)
            if (streak && did == null) did = "read today (streak)"
        }

        // Real start and finish days, where the tracker takes them, once per change.
        var datesSent = link.datesSent
        // Sent even when the progress was held back (the tracker further along, a status it lacks):
        // the days you read are true either way.
        if (tracker.takesDates && !skipped && !leftAsIs) {
            val started = day(book.startedAt)
            val finished = if (target == ReadStatus.FINISHED) day(book.finishedAt) else null
            val key = "${started ?: ""}|${finished ?: ""}"
            if ((started != null || finished != null) && key != link.datesSent) {
                try {
                    tracker.setDates(session, link, started, finished)
                    datesSent = key
                    did = (did?.let { "$it, " } ?: "") + listOfNotNull(started?.let { "started $it" }, finished?.let { "finished $it" }).joinToString(", ")
                    extras += listOfNotNull(started?.let { "started $it" }, finished?.let { "finished $it" }).joinToString(", ")
                } catch (e: AuthException) {
                    throw e
                } catch (e: NetworkException) {
                    throw e
                } catch (e: Exception) {
                    // The dates are a nicety: note it and keep the update; tried again next time the book goes out.
                    note = "Dates not set on ${tracker.name}: ${e.message}"
                }
            }
        }

        val updated = link.copy(
            // Only progress that actually went out counts as sent; a status change alone doesn't.
            lastPercent = if (progressSent || alreadyThere) percent else link.lastPercent,
            lastStatus = if (skipped) link.lastStatus else target,
            note = note,
            updatedAt = System.currentTimeMillis(),
            datesSent = datesSent,
        )
        store.putLink(updated)
        if (did != null) {
            store.log(tracker.id, "${book.displayTitle}: $did")
            recordSent(tracker.id, book.document, SendEvent(
                at = System.currentTimeMillis(),
                from = if (progressSent) link.lastPercent else null,
                to = if (progressSent) percent else null,
                pageCount = book.pageCount,
                highlights = carriedCount,
                other = extras.distinct().joinToString(", ").ifEmpty { null },
            ))
        }
        return did
    }

    /** A unix time as the local calendar day. */
    private fun day(unix: Long?): java.time.LocalDate? =
        unix?.takeIf { it > 0 }?.let { java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }

    /**
     * Sends ratings and reviews from the finish flow to each tracker that takes them, once
     * per change. A tracker that refuses is noted and not retried until the review changes.
     */
    private fun pushReviews(onlyDocument: String? = null): Int {
        val s0 = store.snapshot
        val books = s0.books.values.filter { (it.rating != null || !it.review.isNullOrBlank()) && !it.held && (onlyDocument == null || it.document == onlyDocument) }
        if (books.isEmpty()) return 0
        var sent = 0
        for (t in Trackers.all) {
            if (t.reviews == ReviewSupport.NONE) continue
            val svc = store.snapshot.service(t.id)
            if (!svc.linked || !svc.enabled || svc.needsReauth) continue
            val session = Session(svc.credential).also { it.visibility = svc.visibility }
            try {
                for (b in books) {
                    if (b.origin == t.id) continue
                    val link = store.snapshot.link(t.id, b.document) ?: continue
                    if (link.externalId == null || link.source == LinkSource.NONE || !link.confirmed) continue
                    val fp = reviewPrint(b)
                    // A refused one is tried again once a day (and right away when the review changes).
                    val failedToday = "x:$fp:${Shelves.day()}"
                    if (link.reviewSent == fp || link.reviewSent == failedToday) continue
                    // The rating came from Seekquel: it already has it.
                    if (t.id == SeekquelTracker.id && b.rating != null && store.snapshot.seekquelRatings[b.document] == b.rating) {
                        store.putLink(link.copy(reviewSent = fp)); continue
                    }
                    val text = if (t.reviews == ReviewSupport.RATING_AND_TEXT) b.review?.takeIf { it.isNotBlank() } else null
                    if (b.rating == null && text == null) continue
                    try {
                        t.setReview(session, link, b.rating, text)
                        store.putLink(link.copy(reviewSent = fp))
                        if (t.id == SeekquelTracker.id) b.rating?.let { r -> store.update { s -> s.copy(seekquelRatings = s.seekquelRatings + (b.document to t.stepRating(r))) } }
                        store.log(t.id, "${b.displayTitle}: " + listOfNotNull(b.rating?.let { "rated ${stars(it)}" }, text?.let { "review sent" }).joinToString(", "))
                        recordSent(t.id, b.document, SendEvent(at = System.currentTimeMillis(),
                            other = listOfNotNull(b.rating?.let { "rated ${stars(t.stepRating(it))}" }, text?.let { "review" }).joinToString(", ")))
                        sent++
                    } catch (e: AuthException) {
                        throw e
                    } catch (e: NetworkException) {
                        throw e
                    } catch (e: SkipException) {
                        store.putLink(link.copy(note = e.message))
                    } catch (e: Exception) {
                        store.putLink(link.copy(reviewSent = failedToday))
                        store.log(t.id, "${b.displayTitle}: review not sent (${e.message})", error = true)
                    }
                    Thread.sleep(PAUSE_MS)
                }
            } catch (e: AuthException) {
                store.updateService(t.id) { it.copy(needsReauth = true, lastError = e.message) }
            } catch (e: NetworkException) {
                // Offline: tried again next pass.
            } finally {
                if (session.changed) {
                    val credential = session.encode()
                    store.updateService(t.id) { it.copy(credential = credential) }
                }
            }
        }
        return sent
    }

    /** A first match not confirmed yet: its update waits. */
    private class AwaitingConfirm : Exception("Waiting for you to confirm the match")

    /** Searches the tracker and links the book if there's one clear match. */
    private fun autoLink(tracker: Tracker, session: Session, book: Book): Link? {
        if (tracker.matchesByDocument) {
            // Seekquel knows the book by CrossPoint's own id; no search, nothing to pick.
            val link = Link(tracker.id, book.document, book.document, title = book.title, author = book.author,
                source = LinkSource.AUTO, updatedAt = System.currentTimeMillis())
            store.putLink(link)
            return link
        }
        val title = book.title
        if (title == null) {
            store.putLink(
                Link(tracker.id, book.document, null, note = "No title from CrossPoint; turn on Send Metadata",
                    updatedAt = System.currentTimeMillis())
            )
            return null
        }
        // The edition already on the user's shelves wins, before any search.
        val shelf = runCatching { tracker.shelfCandidates(session) }.getOrDefault(emptyList())
        val onShelf = Matcher.decide(title, book.author, shelf)
        // The same book however it's titled there ("Title [LitRPG, …]" vs "Title: tagline").
        val shelved = onShelf.best?.takeIf { onShelf.accepted }
            ?: shelf.filter { Matcher.sameBook(title, book.author, it.title, it.author) }.singleOrNull()
        if (shelved != null) {
            val link = linkFor(tracker.id, book.document, shelved, LinkSource.AUTO)
            store.putLink(link)
            store.log(tracker.id, "${book.displayTitle}: matched to \"${shelved.title}\" on your shelf")
            return link
        }

        // Only if NOT already shelved: try ISBN-first matching if known from book, Kindle ASIN, or cross-tracker links
        val isbn = getKnownIsbn(book)
        if (isbn != null) {
            val isbnCandidates = runCatching { tracker.findByIsbn(session, isbn) }.getOrDefault(emptyList())
            if (isbnCandidates.isNotEmpty()) {
                // An ISBN hit still has to be the book: some searches answer an ISBN with anything.
                val bestIsbn = isbnHit(title, book.author, isbnCandidates)
                if (bestIsbn != null) {
                    val link = linkFor(tracker.id, book.document, bestIsbn, LinkSource.AUTO)
                    store.putLink(link)
                    store.log(tracker.id, "${book.displayTitle}: matched to \"${bestIsbn.title}\" by ISBN ($isbn)")
                    return link
                }
            }
        }

        // Cross-tracker title and author hint if another tracker already matched
        val crossHint = getCrossTrackerHint(book.document, tracker.id)
        // Web-novel tags ("[LitRPG, …]") get in the way of a search: try without them too.
        val author = Matcher.naturalAuthor(book.author)
        var results = tracker.search(session, title, author)
        if (results.isEmpty() && crossHint != null && crossHint.first != title) {
            results = tracker.search(session, crossHint.first, Matcher.naturalAuthor(crossHint.second) ?: author)
        }
        if (results.isEmpty() && Matcher.plainTitle(title) != title) results = tracker.search(session, Matcher.plainTitle(title), author)
        if (results.isEmpty()) results = tracker.search(session, Matcher.plainTitle(title).substringBefore(":").trim(), null)
        val decision = Matcher.decide(title, book.author, results)
        val best = decision.best
        if (!decision.accepted || best == null) {
            store.putLink(
                Link(tracker.id, book.document, null, title = best?.title, author = best?.author,
                    note = "Needs a match", updatedAt = System.currentTimeMillis())
            )
            store.log(tracker.id, "${book.displayTitle}: no clear match, pick one in the app")
            suggestMatch(tracker, book, title, results)
            return null
        }
        val link = linkFor(tracker.id, book.document, best, LinkSource.AUTO)
        store.putLink(link)
        store.log(tracker.id, "${book.displayTitle}: matched to \"${best.title}\"")
        return link
    }

    /** Model answers asked for this run (each takes seconds on a phone). */
    private var aiAsked = 0

    /**
     * The rules couldn't pick: the on-device model, when set up, is asked which listing is the
     * book, and its pick is shown on the book to accept or dismiss. Never matched on its own.
     */
    private fun suggestMatch(tracker: Tracker, book: Book, title: String, results: List<RemoteBook>) {
        if (results.isEmpty() || !store.snapshot.settings.aiMatching || aiAsked >= AI_MATCHES_PER_RUN) return
        val key = Link.key(tracker.id, book.document)
        if (key in store.snapshot.aiSuggestions) return // already suggested, or dismissed
        val model = io.github.prithirajn.trackersync.ai.Ai.model() ?: return
        aiAsked++
        val pick = io.github.prithirajn.trackersync.ai.Assistant.pickMatch(model, title, book.author, results) ?: return
        store.update { s -> s.copy(aiSuggestions = s.aiSuggestions + (key to io.github.prithirajn.trackersync.data.AiSuggestion(pick.id, pick.title, pick.author, pick.uuid, pick.coverUrl))) }
        store.log(tracker.id, "${book.displayTitle}: the on-device AI suggests \"${pick.title}\"; accept it on the book")
    }

    private fun getKnownIsbn(book: Book): String? {
        book.isbn?.takeIf { it.isNotBlank() }?.let { return it }
        val snap = store.snapshot
        for (l in snap.links.values) {
            if (l.document == book.document && l.externalId != null && l.source != LinkSource.NONE) {
                val isbnKey = l.libraryKeys.firstOrNull { it.startsWith("isbn:") }?.removePrefix("isbn:")
                if (isbnKey != null) return isbnKey
            }
        }
        if (book.origin == "kindle" || book.document.startsWith("kindle:")) {
            val asin = book.document.removePrefix("kindle:")
            if (asin.isNotEmpty()) {
                val resolved = runCatching { io.github.prithirajn.trackersync.source.OpenLibrary.isbnForAsin(asin, book.title, book.author) }.getOrNull()
                if (resolved != null) {
                    store.update { s ->
                        s.books[book.document]?.let { s.copy(books = s.books + (book.document to it.copy(isbn = resolved))) } ?: s
                    }
                    return resolved
                }
            }
        }
        return null
    }

    private fun getCrossTrackerHint(document: String, currentTrackerId: String): Pair<String, String?>? {
        val snap = store.snapshot
        for (l in snap.links.values) {
            if (l.document == document && l.service != currentTrackerId && l.externalId != null && l.source != LinkSource.NONE) {
                if (l.title != null) return l.title to l.author
            }
        }
        return null
    }

    companion object {

        /** A comic read past its last known volume is looked up again after this many days (a new volume may be out). */
        const val COMIC_RECHECK_DAYS = 14L

        /** Where manga and comic volume books come from. */
        val MANGA_ORIGINS = setOf("mihon", "anilist", "logged")

        /** The manga step's lookups stop after this long in one sync; what's left is looked up next time. */
        const val MANGA_BUDGET_MS = 60_000L

        /** Manga series read within this many days are kept in step from Mihon. */
        const val MANGA_ACTIVE_DAYS = 45L

        /** Series looked up on MangaDex in one sync, at most; one with no volumes is tried again after this many days. */
        const val MANGA_LOOKUPS_PER_RUN = 4
        const val MANGA_RETRY_DAYS = 7L

        /** Books looked for on the web novel sites in one sync, at most. */
        const val WEB_SEARCHES_PER_RUN = 3

        /** Web novel searches stop after this long in one sync. */
        const val WEB_SEARCH_BUDGET_MS = 45_000L

        /** A book not found on the web novel sites is looked for again after this many days. */
        const val WEB_SEARCH_DAYS = 30L

        /** Searches before this (1.12.2) may have been stopped by a bot check unseen, so they're redone. */
        const val WEB_SEARCH_SINCE = 1793371810L

        fun webSearchDue(s: Snapshot, document: String, now: Long = System.currentTimeMillis() / 1000): Boolean =
            s.webSearched[document]?.let { it < WEB_SEARCH_SINCE || now - it >= WEB_SEARCH_DAYS * DAY } ?: true

        /** True when every web novel site could be searched (none stopped the app or failed to answer). */
        fun webSearchComplete(): Boolean =
            io.github.prithirajn.trackersync.source.WebNovels.problems.keys.none { it in setOf("Royal Road", "AO3", "Scribble Hub", "Wattpad") }

        /** Libby books looked up for a page count in one sync, at most. */
        const val LIBBY_LOOKUPS_PER_RUN = 4

        const val OFFLINE_NOTE = "You were offline; everything catches up once you're back online"

        /** No connection at all: the name couldn't be looked up, or there's no route to the network. */
        /** For tests: whether the internet is reachable (default: find two well-known sites). */
        @Volatile var probe: (() -> Boolean)? = null

        fun isOffline(e: Throwable): Boolean {
            var t: Throwable? = e
            var depth = 0
            while (t != null && depth < 6) {
                if (t is java.net.UnknownHostException || t is java.net.NoRouteToHostException) return true
                val m = t.message.orEmpty()
                if ("Unable to resolve host" in m || "No address associated with hostname" in m || "Network is unreachable" in m) return true
                t = t.cause; depth++
            }
            return false
        }

        /** The ISBN search result that is this book by title (and author when both have one), or null. */
        internal fun isbnHit(title: String, author: String?, hits: List<RemoteBook>): RemoteBook? {
            if (hits.isEmpty()) return null
            val decision = Matcher.decide(title, author, hits, threshold = 0.5)
            decision.best?.takeIf { decision.accepted }?.let { return it }
            return hits.firstOrNull { Matcher.sameBook(title, author, it.title, it.author) || Matcher.score(title, author, it) >= 0.6 }
        }

        /**
         * Propagates a newly confirmed match to other linked trackers that currently need a match.
         * The other tracker's shelves are ALWAYS checked first to respect existing shelved editions.
         */
        fun propagateMatch(store: Store, document: String, fromTrackerId: String, matchedBook: RemoteBook) {
            val snap = store.snapshot
            val book = snap.books[document] ?: return
            val title = matchedBook.title.ifBlank { book.title ?: return }
            val author = matchedBook.author ?: book.author
            val isbn = book.isbn

            for (t in Trackers.all) {
                if (t.id == fromTrackerId) continue
                val svc = snap.service(t.id)
                if (!svc.linked || !svc.enabled || svc.needsReauth) continue
                val linkKey = Link.key(t.id, document)
                val currLink = snap.links[linkKey]
                if (currLink != null && currLink.source == LinkSource.NONE) continue
                if (currLink?.externalId != null) continue

                val session = Session(svc.credential)

                // PRIORITY 1: User's Shelf on tracker t ALWAYS WINS!
                val shelf = runCatching { t.shelfCandidates(session) }.getOrDefault(emptyList())
                val onShelf = Matcher.decide(title, author, shelf)
                val shelved = onShelf.best?.takeIf { onShelf.accepted }
                    ?: shelf.filter { Matcher.sameBook(title, author, it.title, it.author) }.singleOrNull()
                if (shelved != null) {
                    store.putLink(linkFor(t.id, document, shelved, LinkSource.AUTO))
                    store.log(t.id, "${book.displayTitle}: matched to \"${shelved.title}\" on your shelf")
                    queue(store, t.id, listOf(document))
                    continue
                }

                // PRIORITY 2: ISBN match on tracker t
                if (isbn != null) {
                    val isbnHits = runCatching { t.findByIsbn(session, isbn) }.getOrDefault(emptyList())
                    val bestIsbn = isbnHit(title, author, isbnHits)
                    if (bestIsbn != null) {
                        store.putLink(linkFor(t.id, document, bestIsbn, LinkSource.AUTO))
                        store.log(t.id, "${book.displayTitle}: matched to \"${bestIsbn.title}\" via ISBN from ${Trackers.byId(fromTrackerId)?.name ?: fromTrackerId}")
                        queue(store, t.id, listOf(document))
                        continue
                    }
                }

                // PRIORITY 3: Search with verified title and author from matchedBook
                val results = runCatching { t.search(session, title, Matcher.naturalAuthor(author)) }.getOrDefault(emptyList())
                val decision = Matcher.decide(title, author, results)
                if (decision.accepted && decision.best != null) {
                    store.putLink(linkFor(t.id, document, decision.best, LinkSource.AUTO))
                    store.log(t.id, "${book.displayTitle}: auto-matched to \"${decision.best.title}\" from ${Trackers.byId(fromTrackerId)?.name ?: fromTrackerId}")
                    queue(store, t.id, listOf(document))
                }
            }
        }
        /**
         * Whether a lower percentage in [c] means the book got longer rather than that you went
         * back: you're in the same chapter or a later one than at the last position before it
         * (chapters keep their numbers when new ones are added at the end).
         */
        internal fun grewLonger(points: List<io.github.prithirajn.trackersync.data.PositionPoint>, was: Double, c: Change): Boolean {
            if (was - c.percentage < 0.01) return false
            val spine = c.spine ?: return false
            val prev = points.filter { it.timestamp < c.timestamp && it.spine != null }.maxByOrNull { it.timestamp } ?: return false
            val drop = prev.percentage - c.percentage
            // A later chapter, or the same one with a fall bigger than paging back inside it.
            return (spine > prev.spine!! && drop >= 0.01) || (spine == prev.spine && drop >= 0.02)
        }

        const val DEVICE_ID = "tracker-sync-android"
        private const val SENT_KEEP = 15
        const val DAY = 86_400L
        /** Unsure matches the on-device model is asked about per sync. */
        const val AI_MATCHES_PER_RUN = 5
        /** A description search that found nothing is tried again after this many days. */
        const val DETAILS_RETRY_DAYS = 3L
        /** When the description sources last grew (unix seconds): books looked up before then are looked up again. */
        const val DETAILS_SOURCES_SINCE = 1_793_290_000L
        /** Kindle (Amazon) is checked at most this often by the regular sync. */
        const val KINDLE_EVERY_MS = 3 * 3_600_000L
        const val RECENT_DAYS = 7L
        const val MAX_AGE_DAYS = 28L
        const val MAX_TRIES = 5
        const val PAUSE_MS = 700L
        /** Trackers with an API for this (the rest are posted to the way you would by hand). */
        val API_TRACKERS = setOf("hardcover", "seekquel")
        /** Highlights posted by hand at most this many per book per sync; the rest follow next time. */
        const val BY_HAND_PER_RUN = 4
        /** The pause between them: a few seconds, never the same twice (tests set it to nothing). */
        @Volatile internal var BY_HAND_GAP_MS = 4_000L
        @Volatile internal var BY_HAND_JITTER_MS = 5_000L

        private const val KEY_LOOKUPS_PER_RUN = 25

        /** A highlight with your edit applied, if you made one. */
        fun edited(c: io.github.prithirajn.trackersync.source.Clipping, edits: Map<String, io.github.prithirajn.trackersync.data.ClipEdit>) =
            edits[c.id]?.let { c.copy(text = it.text, note = it.note) } ?: c

        /** What was sent for a book's rating and review, to send each change once. */
        fun reviewPrint(b: Book): String = "${b.rating ?: ""}|${(b.review ?: "").trim().hashCode()}"

        fun stars(r: Double): String = (if (r % 1.0 == 0.0) r.toInt().toString() else r.toString()) + "\u2605"

        /** KOSync checks per pass: every book in progress (up to this many), plus a few others in turn. */
        private const val KOSYNC_ACTIVE = 40
        private const val KOSYNC_ROTATE = 10

        /** What comes in from a source's library. Want to Read doesn't. */
        private val IMPORTED_STATUSES = setOf(ReadStatus.READING, ReadStatus.PAUSED, ReadStatus.FINISHED, ReadStatus.DNF)

        /**
         * The user picked a held book: send it to every linked tracker from now on.
         * "Don't sync" choices made for single trackers stay.
         */
        fun release(store: Store, document: String) {
            store.update { s ->
                val b = s.books[document] ?: return@update s
                s.copy(books = s.books + (document to b.copy(held = false)))
            }
            val origin = store.snapshot.books[document]?.origin
            for (svc in store.snapshot.services.values.filter { it.linked && it.enabled && it.id != origin }) {
                queue(store, svc.id, listOf(document))
            }
        }

        /** Score at which a source's book is taken to be one already on CrossPoint. */
        private const val SAME_BOOK = 0.8

        fun importedDocument(source: String, id: String) = "$source:$id"

        /** Removes every book brought in from [source], with its links and queued updates. */
        fun forgetImported(store: Store, source: String) = store.update { s ->
            val docs = s.books.values.filter { it.origin == source }.map { it.document }.toSet()
            if (docs.isEmpty()) return@update s
            s.copy(
                books = s.books - docs,
                links = s.links.filterValues { it.document !in docs },
                pending = s.pending.filterValues { it.document !in docs },
            )
        }
        private val lock = ReentrantLock()

        private const val COVER_LOOKUPS_PER_RUN = 10

        /** Books whose cover was already looked up while the app has been running. */
        private val coverTried: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

        /**
         * Fills a book's description: crosspoint-sync's (from Hardcover, or set by hand on the
         * server) first, then Open Library's. Runs once per book; blocking.
         */
        /** Whether [book]'s description is worth looking for now: never looked, or looked and found none a while ago. */
        /** Facts are looked up again after this long (and a lookup that found nothing, after a day). */
        const val FACTS_DAYS = 30L

        /** The sites facts come from, in the order they're preferred. */
        private val FACT_SOURCES = listOf("hardcover", "storygraph", "seekquel")

        fun needsFacts(s: Snapshot, document: String, now: Long = System.currentTimeMillis() / 1000): Boolean {
            val linked = FACT_SOURCES.any { id -> s.service(id).let { it.linked && !it.needsReauth } }
            if (!linked || s.books[document]?.title == null) return false
            val f = s.facts[document] ?: return true
            if (f.version < io.github.prithirajn.trackersync.data.BookFacts.VERSION) return true
            // A linked source that gave nothing last time is asked again after a couple of days.
            val missing = FACT_SOURCES.any { id -> s.service(id).let { it.linked && !it.needsReauth } && f.sources.none { src -> src.equals(Trackers.byId(id)?.name ?: id, true) } }
            if (missing && now - f.at >= 2 * DAY) return true
            return now - f.at >= (if (f.isEmpty) 1L else FACTS_DAYS) * DAY
        }

        /**
         * Asks each linked site (Hardcover, StoryGraph, Seekquel) what readers say about a book,
         * and keeps them combined: series, ratings, pace, moods, genres, tropes, content warnings.
         */
        fun loadFacts(store: Store, document: String) {
            if (!needsFacts(store.snapshot, document)) return
            val book = store.snapshot.books[document] ?: return
            val title = book.title ?: return
            val now = System.currentTimeMillis() / 1000
            val parts = FACT_SOURCES.mapNotNull { id ->
                val svc = store.snapshot.service(id)
                if (!svc.linked || svc.needsReauth) return@mapNotNull null
                val link = store.snapshot.link(id, document)?.takeIf { it.source != LinkSource.NONE }
                val session = Session(svc.credential)
                runCatching {
                    when (id) {
                        "hardcover" -> io.github.prithirajn.trackersync.trackers.HardcoverTracker.facts(session, link, title, book.author, now)
                        "storygraph" -> io.github.prithirajn.trackersync.trackers.StoryGraphTracker.facts(session, link, title, book.author, now)
                        else -> io.github.prithirajn.trackersync.trackers.SeekquelTracker.facts(session, link, title, book.author, now).also { f ->
                            // Seekquel's tags have been missing: say what it answered, so it can be put right.
                            if (f == null) io.github.prithirajn.trackersync.trackers.SeekquelTracker.factsWhy?.let { why ->
                                store.log("seekquel", "Seekquel tags for \u201C${book.displayTitle}\u201D: $why".take(600))
                            }
                        }
                    }
                }.getOrNull()
            }
            val f = Facts.merge(parts, now)
            store.update { s -> s.copy(facts = s.facts + (document to f)) }
            runCatching { adoptTrackerNames(store) }
        }

        /**
         * Manga and comic volumes take the trackers' own names: the title a tracker matched it to
         * ("Something is Killing the Children, Vol. 1"), else the series name the trackers give
         * ("Something is Killing the Children") with its volume number. The series you log is
         * renamed the same way, so its later volumes come in under that name. A title you edited
         * yourself (anything not in the app's own "Series, Vol. N" or "Series #N" form) is left alone.
         */
        fun adoptTrackerNames(store: Store) = store.update { s ->
            val books = s.books.toMutableMap()
            val logged = s.logged.toMutableMap()
            val comicMaps = s.comicMaps.toMutableMap()
            val mangaMaps = s.mangaMaps.toMutableMap()
            var changed = false
            for (b in s.books.values) {
                if (!b.isMangaOrComic) continue
                val title = b.title ?: continue
                val ours = Regex("""(?i)^(.*?)(?:, Vol\. |\s#)(\d+(?:\.\d+)?)$""").find(title) ?: continue
                val series = ours.groupValues[1]
                val number = ours.groupValues[2]
                val issue = !title.contains(", Vol. ")
                val base = Matcher.baseTitle(series)
                // A tracker's title for this very book: same series, same number, a volume (not a single issue) for a volume.
                val linked = Trackers.all.asSequence().mapNotNull { t -> s.link(t.id, b.document) }
                    .filter { it.externalId != null && !it.title.isNullOrBlank() }
                    .mapNotNull { it.title }
                    .firstOrNull { t ->
                        Matcher.baseTitle(MangaSources.seriesTitleOf(t)) == base && Matcher.volume(t) == number &&
                            (issue || !Matcher.isSingleIssue(t))
                    }
                val factsSeries = s.facts[b.document]?.series?.trim()?.takeIf { Matcher.baseTitle(MangaSources.seriesTitleOf(it)) == base }
                    ?.let { MangaSources.seriesTitleOf(it) }
                val seriesName = linked?.let { MangaSources.seriesTitleOf(it) } ?: factsSeries ?: continue
                val newTitle = linked ?: if (issue) "$seriesName #$number" else MangaVolumes.title(seriesName, number)
                if (newTitle != title) { books[b.document] = b.copy(title = newTitle); changed = true }
                // The series' own name, for its later volumes.
                if (seriesName != series) {
                    for ((k, l) in s.logged) if (Matcher.baseTitle(l.title) == base && l.title != seriesName) { logged[k] = l.copy(title = seriesName); changed = true }
                    for ((k, m) in s.comicMaps) if (Matcher.baseTitle(m.title) == base && m.title != seriesName) { comicMaps[k] = m.copy(title = seriesName); changed = true }
                    for ((k, m) in s.mangaMaps) if (Matcher.baseTitle(m.title) == base && m.title != seriesName) { mangaMaps[k] = m.copy(title = seriesName); changed = true }
                }
            }
            if (changed) s.copy(books = books, logged = logged, comicMaps = comicMaps, mangaMaps = mangaMaps) else s
        }

        fun needsDetails(s: Snapshot, book: Book): Boolean {
            if (!book.detailsFetched) return true
            // Looked before this app had all of today's sources (Hardcover, Goodreads, Seekquel's
            // catalogue, Royal Road search): looked again now rather than after the usual wait.
            val tried0 = s.detailsTried[book.document]
            if (book.description == null && (tried0 == null || tried0 < DETAILS_SOURCES_SINCE)) return true
            // A Kindle book also needs its print page count (from Goodreads), for pages and highlight pages.
            val kindleNoPages = book.pageCount == null && (book.origin == "kindle" || book.document.startsWith("kindle:"))
            if (book.description != null && !kindleNoPages) return false
            val tried = s.detailsTried[book.document] ?: return true
            return System.currentTimeMillis() / 1000 - tried >= DETAILS_RETRY_DAYS * DAY
        }

        /**
         * Finds a book's description and cover, from wherever it is likely to be: crosspoint-sync
         * (CrossPoint's own books), the web novel's own page, Goodreads (first, for Kindle books),
         * Seekquel (your shelf, else its catalogue), Hardcover, Goodreads, Google Books, Open Library,
         * then KalpaDB (Indian books). Books from Kindle, Seekquel, Goodreads and physical books get
         * the same search as CrossPoint's. A search that finds nothing is tried again after a few days.
         */
        fun loadDetails(store: Store, document: String, force: Boolean = false) {
            val book = store.snapshot.books[document] ?: return
            if (!force && !needsDetails(store.snapshot, book)) return
            // Every source asked, for "No description found (looked in …)".
            val asked = ArrayList<String>()
            val account = store.snapshot.account
            val client = account?.let { CrossPointClient(it) }
            val onServer = book.origin == null && client != null
            var description: String? = null
            var source: String? = null
            if (onServer) asked += "crosspoint-sync"
            if (onServer) runCatching { client!!.about(document) }.getOrNull()?.let { (text, manual) ->
                description = text
                source = if (manual) "you" else "Hardcover"
            }
            val title = book.title
            var cover = book.coverUrl
            if (cover == null && onServer) {
                cover = runCatching { client!!.cover(document) }.getOrNull()
            }

            // A web novel: its own page.
            if (description == null) store.snapshot.webNovels[document]?.url?.let { url ->
                asked += store.snapshot.webNovels[document]?.site ?: "the story's page"
                runCatching { io.github.prithirajn.trackersync.source.WebNovels.fetch(url) }.getOrNull()?.let { w ->
                    w.description?.let { description = it; source = w.web?.site ?: "the story's page" }
                    if (cover == null) cover = w.coverUrl
                }
            }

            // Goodreads: Amazon's own catalogue for Kindle books (found by the ASIN), and any book
            // matched there. Also the book's print page count and ISBN when this app has none.
            var pages: Int? = null
            var isbn: String? = null
            val asin = book.document.takeIf { it.startsWith("kindle:") }?.removePrefix("kindle:")
                ?: book.aliases.firstOrNull { it.startsWith("kindle:") }?.removePrefix("kindle:")
            val isKindle = asin != null || book.origin == "kindle"
            fun fromGoodreads() {
                if (description != null && cover != null && book.pageCount != null) return
                val grSvc = store.snapshot.service("goodreads")
                val session = Session(if (grSvc.linked && !grSvc.needsReauth) grSvc.credential else null)
                val gr = io.github.prithirajn.trackersync.trackers.GoodreadsTracker
                asked += "Goodreads"
                runCatching {
                    val id = store.snapshot.link("goodreads", document)?.externalId
                        ?: asin?.let { gr.bookIdForAsin(session, it) }
                        ?: return@runCatching
                    val g = gr.bookDetails(session, id) ?: return@runCatching
                    // The page must be this book.
                    if (title != null && !Matcher.sameBook(title, book.author, g.title, book.author) && Matcher.score(title, null, RemoteBook("", g.title, null)) < 0.6) return@runCatching
                    if (description == null && g.description != null) { description = g.description; source = "Goodreads" }
                    if (cover == null) cover = g.coverUrl
                    pages = g.pages
                    isbn = g.isbn13 ?: g.isbn10
                }
                if (session.changed && grSvc.linked) store.updateService("goodreads") { it.copy(credential = session.encode()) }
            }
            if (isKindle) fromGoodreads()

            // Seekquel (your main tracker).
            val sqSvc = store.snapshot.service("seekquel")
            if (sqSvc.linked && !sqSvc.needsReauth && title != null && (description == null || cover == null)) {
                asked += "Seekquel"
                runCatching {
                    val session = Session(sqSvc.credential)
                    val link = store.snapshot.link("seekquel", document)
                    val sq = SeekquelTracker.details(session, link, title, book.author)
                    if (sq != null) {
                        if (description == null && sq.description != null) {
                            description = sq.description
                            source = "Seekquel"
                        }
                        if (cover == null && sq.coverUrl != null) {
                            cover = sq.coverUrl
                        }
                    }
                }
            }

            // Hardcover: the book it's matched to there, else a clear match from its search.
            val hcSvc = store.snapshot.service("hardcover")
            if (hcSvc.linked && !hcSvc.needsReauth && title != null && (description == null || cover == null)) {
                asked += "Hardcover"
                runCatching {
                    val session = Session(hcSvc.credential)
                    val link = store.snapshot.link("hardcover", document)?.takeIf { it.source != LinkSource.NONE }
                    io.github.prithirajn.trackersync.trackers.HardcoverTracker.details(session, link, title, book.author)?.let { hc ->
                        if (description == null && hc.description != null) { description = hc.description; source = "Hardcover" }
                        if (cover == null) cover = hc.coverUrl
                        if (pages == null) pages = hc.pages
                    }
                }
            }

            if (!isKindle && store.snapshot.link("goodreads", document)?.externalId != null) fromGoodreads()

            // Google Books (by ISBN when known, else by title and author: often the fullest description).
            if (description == null && title != null) {
                asked += "Google Books"
                runCatching { io.github.prithirajn.trackersync.source.BookInfo.description(title, book.author, book.isbn ?: isbn) }.getOrNull()
                    ?.let { description = it; source = "Google Books" }
            }
            // Open Library.
            if (description == null && title != null) {
                asked += "Open Library"
                OpenLibrary.description(title, book.author)?.let { description = it; source = "Open Library" }
            }
            // A web novel not linked to its page yet (Royal Road, AO3): found by title and author.
            var webFound: io.github.prithirajn.trackersync.data.WebNovel? = null
            if (title != null && store.snapshot.webNovels[document] == null && book.isbn == null && webSearchDue(store.snapshot, document)) {
                if (description == null) { asked += "Royal Road"; asked += "AO3"; asked += "Scribble Hub"; asked += "Wattpad" }
                runCatching { io.github.prithirajn.trackersync.source.WebNovels.search(title, book.author) }.getOrNull()?.let { w ->
                    if (description == null) w.description?.let { description = it; source = w.web?.site ?: "the story's page" }
                    if (cover == null) cover = w.coverUrl
                    webFound = w.web?.copy(found = true)
                }
                // A search a site blocked doesn't count: it's tried again next time.
                if (webFound != null || webSearchComplete()) store.update { s -> s.copy(webSearched = s.webSearched + (document to System.currentTimeMillis() / 1000)) }
            }
            // KalpaDB, for a book in an Indian language.
            if (description == null && title != null && io.github.prithirajn.trackersync.source.KalpaDB.isIndic(title)) {
                asked += "KalpaDB"
                runCatching { io.github.prithirajn.trackersync.source.KalpaDB.search(title, book.author, book.isbn) }.getOrNull()?.let { k ->
                    k.description?.let { description = it; source = "KalpaDB" }
                    if (cover == null) cover = k.coverUrl
                }
            }
            if (cover == null && title != null) {
                cover = OpenLibrary.cover(title, book.author)
            }
            val d = description
            val src = source
            val c = cover
            val pg = pages
            val ib = isbn
            val wf = webFound
            val looked = asked.distinct().joinToString(", ")
            store.update { s ->
                val b = s.books[document] ?: return@update s
                s.copy(
                    webNovels = if (wf != null && document !in s.webNovels) s.webNovels + (document to wf) else s.webNovels,
                    detailsLooked = if (d == null) s.detailsLooked + (document to looked) else s.detailsLooked - document,
                    books = s.books + (document to b.copy(
                        description = d ?: b.description, descriptionSource = if (d != null) src else b.descriptionSource, coverUrl = c, detailsFetched = true,
                        // Filled only where this app has none (a CrossPoint book keeps crosspoint-sync's).
                        pageCount = b.pageCount ?: pg, isbn = b.isbn ?: ib,
                    )),
                    detailsTried = if ((d == null && b.description == null) || ((b.pageCount ?: pg) == null && (b.origin == "kindle" || document.startsWith("kindle:"))))
                        s.detailsTried + (document to System.currentTimeMillis() / 1000) else s.detailsTried - document,
                )
            }
        }

        fun linkFor(service: String, document: String, book: RemoteBook, source: LinkSource) = Link(
            service = service,
            document = document,
            externalId = book.id,
            externalUuid = book.uuid,
            title = book.title,
            author = book.author,
            source = source,
            // A match the app found waits for you to confirm it; one you picked is confirmed.
            confirmed = source != LinkSource.AUTO,
            updatedAt = System.currentTimeMillis(),
        )

        /** Queues books for a tracker: the given ones, or every book read in the last week. */
        fun queue(store: Store, service: String, documents: Collection<String>? = null): Snapshot = store.update { s ->
            val now = System.currentTimeMillis()
            val docs = documents ?: s.books.values
                .filter { now / 1000 - it.timestamp <= RECENT_DAYS * DAY }
                .map { it.document }
            val pending = HashMap(s.pending)
            for (d in docs) {
                if (s.link(service, d)?.source == LinkSource.NONE) continue
                if (s.books[d]?.origin == service) continue
                if (s.books[d]?.held == true) continue
                pending[Link.key(service, d)] = Pending(service, d, now)
            }
            s.copy(pending = pending)
        }
    }
}
