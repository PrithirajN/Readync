package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.data.LinkSource
import io.github.prithirajn.readync.data.Store
import io.github.prithirajn.readync.net.ServiceException
import io.github.prithirajn.readync.source.BookDetails
import io.github.prithirajn.readync.trackers.Session
import io.github.prithirajn.readync.trackers.Tracker
import io.github.prithirajn.readync.trackers.Trackers

/**
 * Adds a book to a tracker that doesn't have it, from its public details, and matches it there.
 * It looks once more first (by ISBN, then title and author), so a book that is there after all
 * is matched instead of added twice.
 */
object AddToTracker {

    /** What happened on one tracker: added, found after all, or why not. */
    data class Outcome(
        val tracker: Tracker, val added: Boolean, val found: Boolean, val error: String? = null, val note: String? = null,
        /** The details as sent (with the Goodreads page found for Pagebound), for filling the site's own page. */
        val details: BookDetails? = null,
    ) {
        /** Refused, but the tracker's own add-a-book page can be opened in the app, filled in, to finish there. */
        val canFinishOnSite: Boolean get() = error != null && tracker.addBookPage != null
        val text: String get() = when {
            error != null -> "${tracker.name}: $error"
            found -> "${tracker.name}: it was there after all, so it's matched"
            else -> "${tracker.name}: added and matched" + (note?.let { " ($it)" } ?: "")
        }
    }

    /** Trackers this book isn't matched on that can take a new book. */
    fun candidates(store: Store, document: String): List<Tracker> {
        val s = store.snapshot
        val book = s.books[document] ?: return emptyList()
        return Trackers.all.filter { t ->
            val svc = s.service(t.id)
            val link = s.link(t.id, document)
            t.addsBooks && svc.linked && !svc.needsReauth && t.id != book.origin &&
                (link == null || (link.externalId == null && link.source != LinkSource.NONE))
        }
    }

    /**
     * Why fan fiction can't go to [tracker], or null when it can: Goodreads doesn't take it in its
     * catalogue. StoryGraph does, marked as fan fiction ("not a book") on its own page.
     */
    fun refusal(tracker: Tracker, details: BookDetails): String? = when {
        details.web?.fanfic != true -> null
        tracker.id == "goodreads" -> "Goodreads doesn't allow fan fiction in its catalogue"
        else -> null
    }

    /** Fan fiction goes to StoryGraph through its own page, so it can be marked as such there. */
    fun onSiteOnly(tracker: Tracker, details: BookDetails): Boolean = details.web?.fanfic == true && tracker.id == "storygraph"

    fun add(store: Store, document: String, tracker: Tracker, details0: BookDetails): Outcome {
        val svc = store.snapshot.service(tracker.id)
        val session = Session(svc.credential).also { it.visibility = svc.visibility }
        refusal(tracker, details0)?.let { return Outcome(tracker, added = false, found = false, error = it) }
        val details = withGoodreadsUrl(store, tracker, details0)
        return try {
            val existing = findExisting(tracker, session, details)
            if (existing == null && onSiteOnly(tracker, details)) {
                throw ServiceException("Fan fiction is added on StoryGraph's own page, where you can mark it as fan fiction")
            }
            val remote = existing ?: tracker.addBook(session, document, details)
            val link = if (tracker.matchesByDocument) {
                SyncEngine.linkFor(tracker.id, document, remote, LinkSource.MANUAL).copy(externalId = document, externalUuid = remote.id.ifEmpty { null })
            } else {
                SyncEngine.linkFor(tracker.id, document, remote, LinkSource.MANUAL)
            }
            if (existing != null && tracker.matchesByDocument) {
                val b = store.snapshot.books[document]
                tracker.linkToBook(session, document, b?.title, b?.author, existing, b?.percent ?: 0)
            }
            store.putLink(link.copy(note = null))
            remember(store, document, details)
            SyncEngine.queue(store, tracker.id, listOf(document))
            // Read the new book back (Seekquel, StoryGraph), and name any detail it didn't keep.
            val missing = if (existing == null) runCatching { tracker.missingAfterAdd(session, document, remote.id, details) }.getOrNull().orEmpty()
            else emptyList()
            val note = missing.takeIf { it.isNotEmpty() }?.let { "${tracker.name} didn't keep: ${it.joinToString(", ")}" }
            store.log(tracker.id, "${details.title}: " + (if (existing != null) "found on ${tracker.name} and matched" else "added to ${tracker.name}") + (note?.let { "; $it" } ?: ""))
            Outcome(tracker, added = existing == null, found = existing != null, note = note, details = details)
        } catch (e: Exception) {
            store.log(tracker.id, "${details.title}: not added (${e.message})", error = true)
            Outcome(tracker, added = false, found = false, error = e.message ?: "Couldn't add it", details = details)
        } finally {
            if (session.changed) store.updateService(tracker.id) { it.copy(credential = session.encode()) }
        }
    }

    /**
     * The book was created on the tracker's own page (opened in the app, filled in, saved by
     * you): match it to the new entry [remoteId] and send the book there.
     */
    fun linkCreated(store: Store, document: String, tracker: Tracker, found: String, details: BookDetails) {
        // The tracker's own id for the page the site went to (Hardcover: its name in the address -> id).
        val svc = store.snapshot.service(tracker.id)
        val session = Session(svc.credential)
        val remoteId = tracker.resolveAddedId(session, found)
        if (session.changed) store.updateService(tracker.id) { it.copy(credential = session.encode()) }
        val remote = io.github.prithirajn.readync.trackers.RemoteBook(remoteId, details.title, details.author, coverUrl = details.coverUrl, pages = details.pages, year = details.year)
        store.putLink(SyncEngine.linkFor(tracker.id, document, remote, LinkSource.MANUAL).copy(note = null))
        remember(store, document, details)
        SyncEngine.queue(store, tracker.id, listOf(document))
        store.log(tracker.id, "${details.title}: added on ${tracker.name}'s page and matched")
    }

    /**
     * Pagebound imports a book whole from its Goodreads page (cover, description, pages and all):
     * the page is found by ISBN or ASIN, else by title and author, when the book isn't matched
     * on Goodreads already. Fan fiction and web novels aren't on Goodreads, so they skip this.
     */
    fun withGoodreadsUrl(store: Store, tracker: Tracker, d: BookDetails): BookDetails {
        if (tracker.id != "pagebound" || d.goodreadsUrl != null || d.web != null) return d
        val svc = store.snapshot.service("goodreads")
        val session = Session(if (svc.linked && !svc.needsReauth) svc.credential else null)
        val url = runCatching {
            io.github.prithirajn.readync.trackers.GoodreadsTracker.bookUrlFor(session, d.title, d.author, d.isbn13 ?: d.isbn10, d.asin)
        }.getOrNull()
        return if (url != null) d.copy(goodreadsUrl = url) else d
    }

    /** A web novel's site and status, kept for the book (ongoing stories aren't marked Finished when you catch up). */
    fun remember(store: Store, document: String, details: BookDetails) {
        val web = details.web ?: return
        store.update { s -> s.copy(webNovels = s.webNovels + (document to web)) }
    }

    /** A clear match already on the tracker, by ISBN or by title and author. */
    private fun findExisting(t: Tracker, session: Session, d: BookDetails): io.github.prithirajn.readync.trackers.RemoteBook? {
        // 1. By ISBN-13 and ISBN-10
        for (isbn in listOfNotNull(d.isbn13, d.isbn10)) {
            runCatching { t.findByIsbn(session, isbn) }.getOrNull()
                ?.firstOrNull { Matcher.score(d.title, d.author, it) >= 0.5 || Matcher.sameBook(d.title, d.author, it.title, it.author) }
                ?.let { return it }
        }
        // 2. By title, cleaned plainTitle, and romanised altTitle
        val searchTitles = listOfNotNull(d.title, Matcher.plainTitle(d.title), d.altTitle).distinct()
        for (title in searchTitles) {
            val hits = runCatching { t.search(session, title, io.github.prithirajn.readync.sync.Matcher.naturalAuthor(d.author)) }.getOrNull() ?: continue
            hits.firstOrNull { Matcher.score(title, d.author, it) >= 0.85 || Matcher.sameBook(d.title, d.author, it.title, it.author) }?.let { return it }
        }
        // 3. Fallback: by base title without subtitle (before ":")
        val base = Matcher.coreTitle(d.title).substringBefore(":").trim()
        if (base.isNotEmpty() && searchTitles.none { it.equals(base, ignoreCase = true) }) {
            val hits = runCatching { t.search(session, base, io.github.prithirajn.readync.sync.Matcher.naturalAuthor(d.author)) }.getOrNull().orEmpty()
            hits.firstOrNull { Matcher.sameBook(d.title, d.author, it.title, it.author) || Matcher.score(d.title, d.author, it) >= 0.85 }?.let { return it }
        }
        return null
    }

    fun requireAny(store: Store, document: String) {
        if (candidates(store, document).isEmpty()) throw ServiceException("Every tracker that can take a new book already has this one")
    }
}
