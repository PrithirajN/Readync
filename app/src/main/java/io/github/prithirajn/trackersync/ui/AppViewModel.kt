package io.github.prithirajn.trackersync.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.prithirajn.trackersync.TrackerSyncApp
import io.github.prithirajn.trackersync.data.Account
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.Link
import io.github.prithirajn.trackersync.data.LinkSource
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.data.Visibility
import io.github.prithirajn.trackersync.source.LibbyClient
import io.github.prithirajn.trackersync.source.CrossPointClient
import io.github.prithirajn.trackersync.source.KosyncClient
import io.github.prithirajn.trackersync.net.AuthException
import io.github.prithirajn.trackersync.data.SyncSettings
import io.github.prithirajn.trackersync.source.Clipping
import io.github.prithirajn.trackersync.source.OpenLibrary
import io.github.prithirajn.trackersync.sync.Backup
import io.github.prithirajn.trackersync.sync.FinishFlow
import io.github.prithirajn.trackersync.sync.Isbn
import io.github.prithirajn.trackersync.trackers.RemoteState
import io.github.prithirajn.trackersync.trackers.Trackers
import io.github.prithirajn.trackersync.sync.BookEditor
import io.github.prithirajn.trackersync.sync.ManualBooks
import io.github.prithirajn.trackersync.sync.PageReader
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.trackers.HardcoverTracker
import io.github.prithirajn.trackersync.trackers.SeekquelTracker
import io.github.prithirajn.trackersync.sync.Health
import io.github.prithirajn.trackersync.sync.Highlights
import io.github.prithirajn.trackersync.sync.Matcher
import io.github.prithirajn.trackersync.source.BookDates
import io.github.prithirajn.trackersync.source.CoverCandidate
import io.github.prithirajn.trackersync.sync.SyncEngine
import io.github.prithirajn.trackersync.sync.Shelves
import io.github.prithirajn.trackersync.sync.SyncWorker
import io.github.prithirajn.trackersync.trackers.DeviceLink
import io.github.prithirajn.trackersync.trackers.DevicePoll
import io.github.prithirajn.trackersync.trackers.HighlightSupport
import io.github.prithirajn.trackersync.trackers.RemoteBook
import io.github.prithirajn.trackersync.trackers.Session
import io.github.prithirajn.trackersync.trackers.SignInResult
import io.github.prithirajn.trackersync.trackers.Tracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = (app as TrackerSyncApp).store

    private val _state = MutableStateFlow(store.snapshot)
    val state: StateFlow<Snapshot> = _state

    /** A short message shown as a toast. */
    val toast = MutableStateFlow<Toast?>(null)

    /** A message at the bottom; [onUndo] adds an Undo button that reverses what was just done. */
    data class Toast(
        val title: String,
        val detail: String? = null,
        val error: Boolean = false,
        val id: Long = System.nanoTime(),
        val onUndo: (() -> Unit)? = null,
    )

    private val listener: (Snapshot) -> Unit = { _state.value = it }

    init {
        store.addListener(listener)
        store.update { Shelves.recordPages(it) }
        // Manga and comic volumes take the trackers' names (also for ones added before this was done).
        viewModelScope.launch { io { runCatching { SyncEngine.adoptTrackerNames(store) } } }
    }

    override fun onCleared() {
        store.removeListener(listener)
    }

    fun notify(title: String, detail: String? = null, error: Boolean = false) {
        toast.value = Toast(title, detail, error)
    }

    /**
     * Shows [title] with Undo: puts back the book [document], its matches and queued updates
     * as they were in [before].
     */
    private fun notifyUndo(title: String, before: Snapshot, document: String, extra: (() -> Unit)? = null) {
        toast.value = Toast(title, onUndo = {
            viewModelScope.launch {
                io {
                    extra?.invoke()
                    store.update { s ->
                        s.copy(
                            books = s.books + before.books.filterKeys { it == document },
                            links = s.links.filterValues { it.document != document } + before.links.filterValues { it.document == document },
                            pending = s.pending + before.pending.filterValues { it.document == document },
                            paperClips = (s.paperClips + before.paperClips.filter { it.document == document }).distinctBy { it.id },
                        )
                    }
                }
                notify("Undone")
            }
        })
    }

    // ---- finish flow ----

    fun saveReview(document: String, rating: Double?, review: String?, done: (String?) -> Unit) =
        edit(if (rating != null) "Saved; goes to your trackers on the next sync" else "Review saved", done, syncAfter = true) {
            FinishFlow.save(store, document, rating, review)
        }

    fun dismissFinish(document: String) {
        viewModelScope.launch { io { FinishFlow.dismiss(store, document) } }
    }

    // ---- what each tracker says ----

    /** One tracker's view of a book: what it shows now (when it can be read) and what was sent. */
    data class TrackerView(val tracker: Tracker, val link: Link?, val remote: RemoteState?, val error: String?)

    fun compareTrackers(document: String, done: (List<TrackerView>) -> Unit) {
        viewModelScope.launch {
            val out = io {
                val s = store.snapshot
                val book = s.books[document]
                Trackers.all.filter { s.service(it.id).linked && it.id != book?.origin }.map { t ->
                    val link = s.link(t.id, document)
                    if (link?.externalId == null || link.source == LinkSource.NONE) {
                        TrackerView(t, link, null, null)
                    } else {
                        val svc = s.service(t.id)
                        val session = Session(svc.credential)
                        val r = runCatching {
                            t.state(session, link).let { st ->
                                if (st.percent == null && !t.readsProgress) st.copy(percent = runCatching { t.peekPercent(session, link) }.getOrNull()) else st
                            }
                        }
                        if (session.changed) store.updateService(t.id) { it.copy(credential = session.encode()) }
                        TrackerView(t, link, r.getOrNull(), r.exceptionOrNull()?.message)
                    }
                }
            }
            done(out)
        }
    }

    /** Sends this book to one tracker again now, whatever was sent before. */
    fun sendTo(tracker: Tracker, document: String) {
        viewModelScope.launch {
            io {
                store.snapshot.link(tracker.id, document)?.let { store.putLink(it.copy(lastPercent = null, lastStatus = null)) }
                SyncEngine.queue(store, tracker.id, listOf(document))
            }
            notify("Sending to ${tracker.name}")
            syncNow(quiet = true)
        }
    }

    // ---- highlights from paper ----

    /** The photo's lines of text, top to bottom, to pick the highlighted passage from. */
    fun readPassage(uri: android.net.Uri, photo: java.io.File, done: (List<String>?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val lines = withContext(Dispatchers.IO) { PageScanner.read(getApplication(), uri) }
                done(lines.sortedWith(compareBy({ it.top }, { it.left })).map { it.text.trim() }.filter { it.isNotEmpty() }, null)
            } catch (e: Exception) {
                done(null, e.message ?: "Couldn't read the photo")
            } finally {
                runCatching { photo.delete() }
            }
        }
    }

    fun addPaperHighlight(document: String, text: String, note: String?, done: (String?) -> Unit) = addPaperHighlight(document, text, note, null, done)

    /** Keeps the text of a highlight's page (read off a photo), for book photos of it. */
    fun setClipPage(id: String, page: String?) {
        viewModelScope.launch {
            io { store.update { s -> s.copy(clipPages = if (page.isNullOrBlank()) s.clipPages - id else s.clipPages + (id to page.take(20_000))) } }
        }
    }

    fun addPaperHighlight(document: String, text: String, note: String?, pageText: String?, done: (String?) -> Unit) {
        val t = text.trim()
        if (t.isEmpty()) { done("Pick or type the passage"); return }
        val takers = Trackers.all.filter { val s = store.snapshot.service(it.id); s.linked && s.highlights }
        edit(
            if (takers.isEmpty()) "Highlight saved" else "Highlight saved; it goes to ${takers.joinToString(", ") { it.name }}",
            done, syncAfter = true,
        ) {
            val clip = Clipping(
                "paper-" + java.util.UUID.randomUUID().toString().replace("-", "").take(16),
                document, t, note?.trim()?.ifEmpty { null }, null, System.currentTimeMillis() / 1000,
                // The page you're on now is where the passage is.
                percent = store.snapshot.books[document]?.percentage,
            )
            store.update { s -> s.copy(paperClips = s.paperClips + clip, clipPages = if (pageText.isNullOrBlank()) s.clipPages else s.clipPages + (clip.id to pageText.take(20_000))) }
        }
    }

    fun removePaperHighlight(id: String) {
        viewModelScope.launch {
            val before = store.snapshot
            val doc = before.paperClips.firstOrNull { it.id == id }?.document ?: return@launch
            io { store.update { s -> s.copy(paperClips = s.paperClips.filter { it.id != id }) } }
            notifyUndo("Highlight removed", before, doc)
        }
    }

    // ---- highlights and notes under each book (see sync/Highlights) ----

    /** CrossPoint's highlights, fetched when a book opens if a sync hasn't brought them yet. */
    fun loadHighlights() {
        val a = store.snapshot.account ?: return
        if (store.snapshot.cpClips.isNotEmpty()) return
        viewModelScope.launch {
            runCatching { io { CrossPointClient(a).clippings() } }.getOrNull()
                ?.let { cp -> io { store.update { it.copy(cpClips = cp) } } }
        }
    }

    /** Trackers this highlight went to that still take highlights: where a resend goes. */
    fun resendTargets(clipId: String): List<Tracker> = Trackers.all.filter { t ->
        val svc = store.snapshot.service(t.id)
        // A highlight posted by hand (Fable) is still updated there when you edit it.
        svc.linked && svc.enabled && (svc.highlights || store.snapshot.clipRemote["${t.id}|$clipId"] != null) &&
            "${t.id}|$clipId" in store.snapshot.sentClippings
    }

    /** Saves your text and note; with [resend], sends it again, replacing the earlier entry where it can. */
    fun editHighlight(clipId: String, text: String, note: String?, resend: Boolean, done: (String?) -> Unit) {
        val targets = if (resend) resendTargets(clipId).map { it.id } else emptyList()
        edit(
            if (targets.isEmpty()) "Highlight saved" else "Saved; sending to ${targets.size} tracker${if (targets.size == 1) "" else "s"}",
            done, syncAfter = targets.isNotEmpty(),
        ) {
            noteToCrossPoint(clipId, note)
            Highlights.edit(store, clipId, text, note)
            if (targets.isNotEmpty()) Highlights.resend(store, clipId, targets)
        }
    }

    /** A changed note on a CrossPoint highlight is changed (or deleted) on crosspoint-sync too. */
    private fun noteToCrossPoint(clipId: String, note: String?) {
        val clip = store.snapshot.cpClips.firstOrNull { it.id == clipId } ?: return
        val n = note?.trim()?.ifEmpty { null }
        if (n == clip.note) return
        val a = store.snapshot.account ?: throw IllegalStateException("Sign in to crosspoint-sync first")
        CrossPointClient(a).setClippingNote(clip.document, clip.id, n)
        Highlights.originalNote(store, clipId, n)
    }

    /**
     * Deletes a highlight's note: on crosspoint-sync too for a CrossPoint highlight, and it's sent
     * again (without the note) to trackers that replace the earlier entry.
     */
    fun deleteNote(clipId: String, done: (String?) -> Unit) {
        val inPlace = Highlights.deletable(store.snapshot, clipId).toSet() +
            Trackers.all.filter { it.updatesHighlights && store.snapshot.clipRemote["${it.id}|$clipId"] != null }.map { it.id }
        edit("Note deleted", done, syncAfter = inPlace.isNotEmpty()) {
            val s = store.snapshot
            val shown = Highlights.forBook(s, (s.cpClips + s.paperClips + s.kindleClips + s.libbyClips + s.moonClips + s.historyClips + s.koClips).firstOrNull { it.id == clipId }?.document ?: "")
                .firstOrNull { it.clip.id == clipId }?.clip ?: throw IllegalStateException("That highlight is gone")
            noteToCrossPoint(clipId, null)
            Highlights.edit(store, clipId, shown.text, null)
            val targets = resendTargets(clipId).map { it.id }.filter { it in inPlace }
            if (targets.isNotEmpty()) Highlights.resend(store, clipId, targets)
        }
    }

    /**
     * Deletes a highlight here; [onCrossPoint] also deletes it on crosspoint-sync (and so on the
     * reader), and [fromTrackers] its entries on trackers that allow it, on the next sync.
     */
    fun deleteHighlight(clipId: String, onCrossPoint: Boolean, fromTrackers: Boolean, done: (String?) -> Unit) {
        val s = store.snapshot
        val clip = s.cpClips.firstOrNull { it.id == clipId }
        val trackers = if (fromTrackers) Highlights.deletable(s, clipId).size else 0
        edit(
            "Highlight deleted" + listOfNotNull(
                if (onCrossPoint && clip != null) "on CrossPoint" else null,
                if (trackers > 0) "on $trackers tracker${if (trackers == 1) "" else "s"} next sync" else null,
            ).joinToString(" and ").let { if (it.isEmpty()) "" else " (also $it)" },
            done, syncAfter = trackers > 0,
        ) {
            if (onCrossPoint && clip != null) {
                val a = store.snapshot.account ?: throw IllegalStateException("Sign in to crosspoint-sync first")
                CrossPointClient(a).deleteClipping(clip.document, clip.id)
            }
            Highlights.delete(store, clipId, fromTrackers)
        }
    }

    /**
     * Trackers this highlight can be posted to by hand: linked, taking highlights as posts others
     * see (Fable), with "Send highlights" off and this highlight not sent there yet.
     */
    fun postTargets(clipId: String): List<Tracker> = Trackers.all.filter { t ->
        val svc = store.snapshot.service(t.id)
        t.highlightsIgnoreVisibility && svc.linked && svc.enabled && !svc.needsReauth && !svc.highlights &&
            "${t.id}|$clipId" !in store.snapshot.sentClippings && "${t.id}|$clipId" !in store.snapshot.resend
    }

    /** Posts one highlight to [tracker] now (it goes out with this sync). */
    fun postHighlight(tracker: Tracker, clipId: String, done: (String?) -> Unit) =
        edit("Posting to ${tracker.name}", done, syncAfter = true) { Highlights.resend(store, clipId, listOf(tracker.id)) }

    /**
     * Trackers a book's highlights could still go to, with how many each hasn't had: linked and
     * able to take highlights, the book matched there, and (for public posts) your updates public.
     * Notes kept on bookmarks don't count.
     */
    fun unsentHighlights(document: String): List<Pair<Tracker, Int>> {
        val s = store.snapshot
        val clips = Highlights.forBook(s, document).map { it.clip }.filter { !io.github.prithirajn.trackersync.sync.KoHighlightSync.isNote(it.id) }
        if (clips.isEmpty()) return emptyList()
        return Trackers.all.mapNotNull { t ->
            val svc = s.service(t.id)
            if (!svc.linked || !svc.enabled || svc.needsReauth || t.highlights == io.github.prithirajn.trackersync.trackers.HighlightSupport.NONE) return@mapNotNull null
            if (t.highlights == io.github.prithirajn.trackersync.trackers.HighlightSupport.PUBLIC_POST && svc.visibility != Visibility.PUBLIC && !t.highlightsIgnoreVisibility) return@mapNotNull null
            val link = s.link(t.id, document) ?: return@mapNotNull null
            if (link.externalId == null || link.source == io.github.prithirajn.trackersync.data.LinkSource.NONE || !link.confirmed) return@mapNotNull null
            val n = clips.count { "${t.id}|${it.id}" !in s.sentClippings && "${t.id}|${it.id}" !in s.resend }
            if (n == 0) null else t to n
        }
    }

    /** Sends a book's highlights [trackers] haven't had yet (older ones included), each at its own place in the book. */
    fun sendBookHighlights(document: String, trackers: Collection<String>) {
        val s = store.snapshot
        val clips = Highlights.forBook(s, document).map { it.clip.id }.filter { !io.github.prithirajn.trackersync.sync.KoHighlightSync.isNote(it) }
        val keys = trackers.flatMap { t -> clips.map { "$t|$it" } }.filter { it !in s.sentClippings && it !in s.resend }
        if (keys.isEmpty()) return
        edit("Sending ${keys.size} highlight${if (keys.size == 1) "" else "s"}", {}, syncAfter = true) { store.update { it.copy(resend = it.resend + keys) } }
    }

    fun resendHighlight(clipId: String) {
        val targets = resendTargets(clipId).map { it.id }
        if (targets.isEmpty()) return
        edit("Sending again", {}, syncAfter = true) { Highlights.resend(store, clipId, targets) }
    }

    fun revertHighlight(clipId: String, done: (String?) -> Unit) =
        edit("Back to CrossPoint's text", done) { Highlights.revert(store, clipId) }

    // ---- barcodes ----

    /** Reads an ISBN off a photo of the barcode. */
    fun scanIsbn(uri: android.net.Uri, photo: java.io.File, done: (String?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val codes = withContext(Dispatchers.IO) { PageScanner.barcodes(getApplication(), uri) }
                val isbn = codes.firstNotNullOfOrNull { Isbn.fromBarcode(it) }
                done(isbn, if (isbn == null) (if (codes.isEmpty()) "No barcode found. Get closer, with the barcode flat and lit." else "That barcode isn't a book's ISBN.") else null)
            } catch (e: Exception) {
                done(null, e.message ?: "Couldn't read the photo")
            } finally {
                runCatching { photo.delete() }
            }
        }
    }

    /** The edition with this ISBN on Hardcover and Seekquel, else Open Library. */
    fun isbnCandidates(isbn: String, done: (List<AddCandidate>?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val out = io {
                    val tries = listOfNotNull(isbn, if (isbn.length == 10) Isbn.to13(isbn) else null)
                    val list = ArrayList<AddCandidate>()
                    for (t in addSources()) {
                        val svc = store.snapshot.service(t.id)
                        val session = Session(svc.credential)
                        for (code in tries) {
                            val hits = runCatching { t.findByIsbn(session, code) }.getOrDefault(emptyList())
                            if (hits.isNotEmpty()) { list.addAll(hits.take(3).map { AddCandidate(t, it, null) }); break }
                        }
                        if (session.changed) store.updateService(t.id) { it.copy(credential = session.encode()) }
                    }
                    if (list.isEmpty()) {
                        tries.firstNotNullOfOrNull { runCatching { OpenLibrary.byIsbn(it) }.getOrNull() }
                            ?.let { list.add(AddCandidate(null, it, null)) }
                    }
                    list
                }
                done(out, if (out.isEmpty()) "No book found for ISBN $isbn. Search by title instead." else null)
            } catch (e: Exception) {
                done(null, e.message ?: "Couldn't look the book up")
            }
        }
    }

    // ---- sync settings ----

    fun updateSettings(transform: (SyncSettings) -> SyncSettings) {
        viewModelScope.launch {
            val next = io { store.update { s -> s.copy(settings = transform(s.settings)) }.settings }
            SyncWorker.schedule(getApplication(), next, changed = true)
        }
    }

    // ---- backup ----

    fun exportBackup(uri: android.net.Uri, password: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            val error = try {
                io {
                    val bytes = Backup.export(store.snapshot, password)
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: throw IllegalStateException("Couldn't write the file")
                }
                null
            } catch (e: Exception) {
                e.message ?: "Couldn't save the backup"
            }
            if (error == null) notify("Backup saved", "Keep the password: it's the only way to open the file.") else notify("Backup failed", error, error = true)
            done(error)
        }
    }

    fun restoreBackup(uri: android.net.Uri, password: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            val error = try {
                io {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Couldn't read the file")
                    store.replace(Backup.restore(bytes, password))
                }
                SyncWorker.schedule(getApplication(), store.snapshot.settings, changed = true)
                null
            } catch (e: Exception) {
                e.message ?: "Couldn't restore"
            }
            if (error == null) {
                notify("Restored", "Everything from the backup is back.")
                syncNow(quiet = true)
            }
            done(error)
        }
    }

    // ---- reading data, without sign-ins ----

    fun exportAccountData(uri: android.net.Uri, done: (String?) -> Unit) {
        viewModelScope.launch {
            val error = try {
                io {
                    val bytes = io.github.prithirajn.trackersync.sync.AccountData.export(
                        store.snapshot, io.github.prithirajn.trackersync.BuildConfig.VERSION_NAME, io.github.prithirajn.trackersync.BuildConfig.VERSION_CODE,
                    )
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: throw IllegalStateException("Couldn't write the file")
                }
                null
            } catch (e: Exception) {
                e.message ?: "Couldn't save your reading data"
            }
            if (error == null) notify("Reading data saved", "No sign-ins in it, but it has your reading and highlights: keep it private.")
            else notify("Couldn't save", error, error = true)
            done(error)
        }
    }

    /** What an imported file turned out to be: reading data (merged in) or a full backup (needs its password). */
    sealed class Incoming {
        data class Data(val read: io.github.prithirajn.trackersync.sync.AccountData.Read) : Incoming()
        object FullBackup : Incoming()
    }

    fun readIncoming(uri: android.net.Uri, done: (Incoming?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val r = io {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Couldn't read the file")
                    val format = runCatching { org.json.JSONObject(String(bytes)).optString("format") }.getOrDefault("")
                    if (format == "tracker-sync-backup") Incoming.FullBackup
                    else Incoming.Data(io.github.prithirajn.trackersync.sync.AccountData.read(bytes))
                }
                done(r, null)
            } catch (e: Exception) {
                done(null, e.message ?: "Couldn't read that file")
            }
        }
    }

    fun mergeAccountData(read: io.github.prithirajn.trackersync.sync.AccountData.Read, done: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                var m: io.github.prithirajn.trackersync.sync.AccountData.Merged? = null
                io { store.update { s -> io.github.prithirajn.trackersync.sync.AccountData.merge(s, read.incoming).also { m = it }.snapshot } }
                val r = m
                notify(
                    "Reading data brought in",
                    if (r == null) null else listOf(
                        "${r.added} book${if (r.added == 1) "" else "s"} added",
                        "${r.updated} updated",
                        "${r.highlights} highlight${if (r.highlights == 1) "" else "s"}",
                    ).joinToString(", ") + ". Nothing was sent to your trackers.",
                )
                done(null)
            } catch (e: Exception) {
                done(e.message ?: "Couldn't bring it in")
            }
        }
    }

    // ---- reading history from elsewhere ----

    /**
     * A history file read and matched against the library, not brought in yet: a Goodreads,
     * StoryGraph or Hardcover export (or any CSV with title and author columns), KOReader's
     * statistics.sqlite3, or a ZIP holding one.
     */
    fun readHistoryFile(uri: android.net.Uri, done: (io.github.prithirajn.trackersync.sync.HistoryImport.Plan?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val plan = io {
                    val app = getApplication<Application>()
                    val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
                        val buf = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(chunk); if (n < 0) break
                            buf.write(chunk, 0, n)
                            if (buf.size() > 50L * 1024 * 1024) throw IllegalStateException("That file is over 50 MB")
                        }
                        buf.toByteArray()
                    } ?: throw IllegalStateException("Couldn't read the file")
                    val (source, entries, byAi) = historyEntries(bytes)
                    if (entries.isEmpty()) throw IllegalStateException(
                        if (aiReady()) "No books found in that file: not even the on-device AI could tell which column is the title"
                        else "No books found in that file. If it's a CSV with unusual column names, set up the on-device AI (Services › On-device AI) and it can work them out",
                    )
                    io.github.prithirajn.trackersync.sync.HistoryImport.plan(store.snapshot, source, entries).copy(columnsByAi = byAi)
                }
                done(plan, null)
            } catch (e: Exception) {
                done(null, e.message ?: "Couldn't read that file")
            }
        }
    }

    private fun historyEntries(bytes: ByteArray): Triple<io.github.prithirajn.trackersync.sync.HistoryImport.Source, List<io.github.prithirajn.trackersync.sync.HistoryImport.Entry>, List<Pair<String, String>>> {
        val HI = io.github.prithirajn.trackersync.sync.HistoryImport
        fun ko(b: ByteArray) = Triple(HI.KOREADER, HI.fromKoreader(koreaderBooks(b)), emptyList<Pair<String, String>>())
        val sqlite = "SQLite format 3".toByteArray()
        fun isSqlite(b: ByteArray) = b.size > 16 && b.copyOfRange(0, sqlite.size).contentEquals(sqlite)
        if (isSqlite(bytes)) return ko(bytes)
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            // A ZIP: KOReader's statistics database inside it, else a CSV export.
            var csv: ByteArray? = null
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name.lowercase()
                    if (name.endsWith(".sqlite3") || name.endsWith(".sqlite") || name.endsWith(".db")) {
                        val inner = zip.readBytes()
                        if (isSqlite(inner)) return ko(inner)
                    } else if (name.endsWith(".csv") && csv == null) csv = zip.readBytes()
                }
            }
            csv?.let { return csvHistory(String(it, Charsets.UTF_8)) }
            throw IllegalStateException("No KOReader statistics or CSV export in that ZIP")
        }
        return csvHistory(String(bytes, Charsets.UTF_8))
    }

    /**
     * A CSV export read by its column names; when those don't say enough (another app's export,
     * a spreadsheet of your own), the on-device model, if set up, works out which column is which
     * from the header and a few rows, and only what the file's values bear out is used. Returns
     * the columns the model added, to show before anything's brought in.
     */
    private fun csvHistory(text: String): Triple<io.github.prithirajn.trackersync.sync.HistoryImport.Source, List<io.github.prithirajn.trackersync.sync.HistoryImport.Entry>, List<Pair<String, String>>> {
        val HI = io.github.prithirajn.trackersync.sync.HistoryImport
        val (header, sample) = HI.preview(text, 6)
        val known = HI.columns(header)
        if (!HI.needsHelp(known)) return HI.fromCsv(text, cols = known).let { Triple(it.first, it.second, emptyList()) }
        val model = runCatching { io.github.prithirajn.trackersync.ai.Ai.model() }.getOrNull()
        val suggested = model?.let { m -> runCatching { io.github.prithirajn.trackersync.ai.Assistant.csvColumns(m, header, sample) }.getOrNull() }
        val cols = if (suggested == null) known else HI.checkColumns(known, suggested, header, sample)
        val added = cols.filterKeys { it !in known }.map { (f, c) -> f.label to (header.getOrNull(c) ?: "column ${c + 1}") }
        val (source, entries) = HI.fromCsv(text, cols = cols)
        return Triple(source, entries, added)
    }

    /** The books in KOReader's statistics database (settings/statistics.sqlite3), with pages read and first and last read. */
    private fun koreaderBooks(bytes: ByteArray): List<io.github.prithirajn.trackersync.sync.HistoryImport.KoBook> {
        val file = java.io.File(getApplication<Application>().cacheDir, "koreader-stats-${System.nanoTime()}.sqlite3")
        file.writeBytes(bytes)
        try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            try {
                val withPages = """SELECT b.title, b.authors, COALESCE(NULLIF(b.pages, 0), MAX(p.total_pages)), b.total_read_pages,
                                          MIN(p.start_time), COALESCE(MAX(p.start_time), b.last_open), b.id
                                   FROM book b LEFT JOIN page_stat_data p ON p.id_book = b.id GROUP BY b.id"""
                val plain = "SELECT title, authors, pages, total_read_pages, NULL, last_open, id FROM book"
                // Day by day: pages turned and minutes spent on each book each day, in this phone's time zone.
                val perDay = HashMap<Long, MutableMap<String, io.github.prithirajn.trackersync.data.ReadDay>>()
                runCatching {
                    db.rawQuery(
                        """SELECT id_book, date(start_time, 'unixepoch', 'localtime') AS d, COUNT(DISTINCT page), SUM(duration)
                           FROM page_stat_data WHERE start_time > 0 GROUP BY id_book, d""", null,
                    ).use { c ->
                        while (c.moveToNext()) {
                            val day = if (c.isNull(1)) continue else c.getString(1)
                            val minutes = Math.round(c.getLong(3) / 60.0).toInt()
                            perDay.getOrPut(c.getLong(0)) { HashMap() }[day] = io.github.prithirajn.trackersync.data.ReadDay(
                                pages = c.getInt(2).takeIf { it > 0 }, minutes = minutes.takeIf { it > 0 }, source = "KOReader",
                            )
                        }
                    }
                }
                val cursor = runCatching { db.rawQuery(withPages, null) }.getOrNull()
                    ?: runCatching { db.rawQuery(plain, null) }.getOrNull()
                    ?: throw IllegalStateException("That database doesn't have KOReader's reading statistics")
                val out = ArrayList<io.github.prithirajn.trackersync.sync.HistoryImport.KoBook>()
                cursor.use { c ->
                    while (c.moveToNext()) {
                        fun intAt(i: Int) = if (c.isNull(i)) null else c.getLong(i)
                        val title = if (c.isNull(0)) null else c.getString(0)
                        if (title.isNullOrBlank()) continue
                        out += io.github.prithirajn.trackersync.sync.HistoryImport.KoBook(
                            title = title,
                            authors = if (c.isNull(1)) null else c.getString(1),
                            pages = intAt(2)?.toInt(),
                            readPages = intAt(3)?.toInt(),
                            firstRead = intAt(4)?.takeIf { it > 0 },
                            lastRead = intAt(5)?.takeIf { it > 0 },
                            days = intAt(6)?.let { perDay[it] } ?: emptyMap(),
                        )
                    }
                }
                return out
            } finally {
                db.close()
            }
        } finally {
            file.delete()
        }
    }

    /** The linked trackers whose whole history can be read straight from them. */
    fun historyTrackers(): List<io.github.prithirajn.trackersync.trackers.Tracker> =
        listOf(io.github.prithirajn.trackersync.trackers.HardcoverTracker, io.github.prithirajn.trackersync.trackers.SeekquelTracker)
            .filter { store.snapshot.service(it.id).linked }

    fun readHistoryFrom(tracker: io.github.prithirajn.trackersync.trackers.Tracker, done: (io.github.prithirajn.trackersync.sync.HistoryImport.Plan?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val plan = io {
                    val svc = store.snapshot.service(tracker.id)
                    val session = io.github.prithirajn.trackersync.trackers.Session(svc.credential)
                    val items = when (tracker) {
                        io.github.prithirajn.trackersync.trackers.HardcoverTracker -> io.github.prithirajn.trackersync.trackers.HardcoverTracker.history(session)
                        else -> tracker.library(session)
                    }
                    if (items.isEmpty()) throw IllegalStateException("No books on your ${tracker.name} shelves")
                    // Books this app brought in from that tracker already are part of it: not matched again.
                    io.github.prithirajn.trackersync.sync.HistoryImport.plan(
                        store.snapshot,
                        io.github.prithirajn.trackersync.sync.HistoryImport.Source(tracker.id, tracker.name),
                        io.github.prithirajn.trackersync.sync.HistoryImport.fromLibrary(items),
                    )
                }
                done(plan, null)
            } catch (e: Exception) {
                done(null, e.message ?: "Couldn't read your ${tracker.name} shelves")
            }
        }
    }

    fun applyHistory(plan: io.github.prithirajn.trackersync.sync.HistoryImport.Plan, withWantToRead: Boolean, done: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                var r: io.github.prithirajn.trackersync.sync.HistoryImport.Result? = null
                io { store.update { s -> io.github.prithirajn.trackersync.sync.HistoryImport.apply(s, plan, withWantToRead).also { r = it }.snapshot } }
                val res = r
                if (res != null) {
                    store.log(null, "Brought in ${plan.source.name} history: ${res.added} new, ${res.filled} filled in")
                    notify(
                        "${plan.source.name} history brought in",
                        listOfNotNull(
                            "${res.added} book${if (res.added == 1) "" else "s"} added",
                            "${res.filled} filled in",
                            res.highlights.takeIf { it > 0 }?.let { "$it highlight${if (it == 1) "" else "s"}" },
                            res.days.takeIf { it > 0 }?.let { "$it reading day${if (it == 1) "" else "s"}" },
                        ).joinToString(", ") + ". Nothing was sent to your trackers.",
                    )
                }
                done(null)
            } catch (e: Exception) {
                done(e.message ?: "Couldn't bring it in")
            }
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    // ---- crosspoint-sync account ----

    fun signIn(server: String, username: String, password: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            val error = try {
                io {
                    val base = CrossPointClient.resolveServer(server)
                    val account = Account(base, username.trim(), CrossPointClient.md5(password))
                    if (!CrossPointClient(account).verify()) throw IllegalStateException("Wrong username or password")
                    store.update { it.copy(account = account, cursor = 0L) }
                }
                SyncWorker.schedule(getApplication(), store.snapshot.settings)
                syncNow(quiet = true)
                null
            } catch (e: Exception) {
                e.message ?: "Couldn't sign in"
            }
            done(error)
        }
    }

    fun signOut() {
        SyncWorker.cancel(getApplication<android.app.Application>())
        viewModelScope.launch {
            io { store.update { Snapshot() } }
        }
    }

    /** The crosspoint-sync health light: checks the sign-in live, then explains the light. */
    fun checkCrossPoint() {
        viewModelScope.launch {
            io {
                val account = store.snapshot.account ?: return@io
                try {
                    if (CrossPointClient(account).verify()) {
                        store.update { it.copy(sourceOkAt = System.currentTimeMillis(), sourceError = null) }
                    } else {
                        store.update { it.copy(sourceError = Health.describeSource(AuthException("rejected"))) }
                    }
                } catch (e: Exception) {
                    store.update { it.copy(sourceError = Health.describeSource(e)) }
                }
            }
            Health.source(store.snapshot)?.let { notify(it.title, it.detail, error = it.level == Health.Level.BAD) }
        }
    }

    // ---- physical books, tracked by hand (sync/ManualBooks) ----

    /**
     * A book to add, from the user's Hardcover or Seekquel: [shelf] names the shelf it's on
     * there ("Reading", "Want to Read"), or null for a catalog search hit.
     */
    data class AddCandidate(
        /** Null for a book found only on Open Library (by ISBN): added without a match. */
        val tracker: Tracker?,
        val book: RemoteBook,
        val shelf: String?,
        val fraction: Double? = null,
        val status: ReadStatus? = null,
    )

    /** The trackers books can be added from: linked Hardcover and Seekquel. */
    fun addSources(): List<Tracker> = listOf(HardcoverTracker, SeekquelTracker).filter {
        val svc = store.snapshot.service(it.id)
        svc.linked && !svc.needsReauth
    }

    /**
     * With an empty [query]: what's on your Hardcover and Seekquel shelves. Otherwise those
     * shelves filtered by the query first, then both catalogs.
     */
    /** Shelves fetched for Add a book, kept a few minutes so each search doesn't download them again. */
    private val shelfCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<AddCandidate>>>()

    private fun cachedShelf(t: Tracker, session: Session): List<AddCandidate> {
        val now = System.currentTimeMillis()
        shelfCache[t.id]?.takeIf { now - it.first < 5 * 60_000 }?.let { return it.second }
        return shelfFor(t, session).also { shelfCache[t.id] = now to it }
    }

    /** Your shelves' matches first, then each tracker's search; Hardcover and Seekquel are asked at once. */
    fun addCandidates(query: String, done: (List<AddCandidate>?, String?) -> Unit) {
        val q = query.trim()
        if (q.isEmpty()) { done(emptyList(), null); return }
        viewModelScope.launch {
            try {
                val perSource = addSources().map { t ->
                    async(Dispatchers.IO) {
                        val session = Session(store.snapshot.service(t.id).credential)
                        try {
                            val shelfHits = runCatching { cachedShelf(t, session) }.getOrDefault(emptyList()).filter { c ->
                                Matcher.score(q, null, c.book) >= 0.3 || c.book.title.contains(q, ignoreCase = true)
                            }
                            val seen = shelfHits.map { it.book.id }.toSet()
                            Result.success(shelfHits + t.search(session, q, null).filter { it.id !in seen }.map { AddCandidate(t, it, null) })
                        } catch (e: Exception) {
                            Result.failure<List<AddCandidate>>(IllegalStateException("${t.name}: ${e.message}"))
                        } finally {
                            if (session.changed) store.updateService(t.id) { it.copy(credential = session.encode()) }
                        }
                    }
                }.awaitAll()
                val list = perSource.flatMap { it.getOrDefault(emptyList()) }
                val errors = perSource.mapNotNull { it.exceptionOrNull()?.message }
                if (list.isEmpty() && errors.isNotEmpty()) throw IllegalStateException(errors.joinToString("\n"))
                // Shelf books first (Reading before Want to Read), then search hits in the trackers' order.
                done(list.sortedBy { when (it.shelf) { "Reading" -> 0; null -> 2; else -> 1 } }, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                done(null, e.message ?: "Couldn't search")
            }
        }
    }

    private fun shelfFor(t: Tracker, session: Session): List<AddCandidate> = when (t) {
        HardcoverTracker -> t.shelfCandidates(session).map { AddCandidate(t, it, "On your shelves") }
        SeekquelTracker -> t.library(session)
            .filter { it.status == ReadStatus.READING || it.status == ReadStatus.TO_READ || it.status == ReadStatus.PAUSED }
            .map { lb ->
                // Seekquel links by catalogue work: the library's work id, when it has one.
                val work = lb.keys.firstOrNull { it.startsWith("work:") }?.removePrefix("work:")
                AddCandidate(
                    t,
                    RemoteBook(work ?: "", lb.title, lb.author, coverUrl = lb.coverUrl),
                    if (lb.status == ReadStatus.TO_READ) "Want to Read" else "Reading",
                    lb.fraction,
                    lb.status,
                )
            }
        else -> emptyList()
    }

    /**
     * Adds the book and matches it on the tracker it came from, so its cover and edition are
     * the ones you picked; the other trackers match it as usual.
     */
    fun addPhysicalBook(c: AddCandidate, pages: Int?, isbn: String? = null, done: (String?, String?) -> Unit) {
        viewModelScope.launch {
            var doc: String? = null
            val error = try {
                io {
                    val d = ManualBooks.add(
                        store,
                        ManualBooks.Pick(c.book.title, c.book.author, c.book.coverUrl, pages ?: c.book.pages, c.fraction, c.status,
                            isbn = isbn ?: c.book.id.takeIf { c.tracker == null && Isbn.fromBarcode(it) != null }),
                    )
                    doc = d
                    val tracker = c.tracker
                    val svc = tracker?.let { store.snapshot.service(it.id) }
                    if (tracker == null || svc == null) {
                        // From Open Library: the trackers match it by title, as usual.
                    } else if (tracker.matchesByDocument) {
                        if (c.book.id.isNotEmpty()) {
                            val session = Session(svc.credential).also { it.visibility = svc.visibility }
                            val b = store.snapshot.books[d]
                            tracker.linkToBook(session, d, b?.title, b?.author, c.book, b?.percent ?: 0)
                            if (session.changed) store.updateService(tracker.id) { it.copy(credential = session.encode()) }
                            store.putLink(SyncEngine.linkFor(tracker.id, d, c.book, LinkSource.MANUAL).copy(externalId = d, externalUuid = c.book.id))
                        }
                    } else {
                        store.putLink(SyncEngine.linkFor(tracker.id, d, c.book, LinkSource.MANUAL))
                    }
                }
                null
            } catch (e: Exception) {
                e.message ?: "Couldn't add the book"
            }
            if (error == null) {
                notify("Added", c.book.title)
                syncNow(quiet = true)
            } else {
                notify("Couldn't add the book", error, error = true)
            }
            done(doc, error)
        }
    }
/** Enables all connected trackers for this book in a single action. */
    fun enableAllTrackers(document: String) {
        viewModelScope.launch {
            val book = store.snapshot.books[document] ?: return@launch
            io {
                for (t in io.github.prithirajn.trackersync.trackers.Trackers.all) {
                    val svc = store.snapshot.service(t.id)
                    if (!svc.linked || !svc.enabled) continue
                    if (book.origin == t.id) continue
                    val key = io.github.prithirajn.trackersync.data.Link.key(t.id, document)
                    val old = store.snapshot.links[key]
                    if (old == null || old.source == io.github.prithirajn.trackersync.data.LinkSource.NONE) {
                        if (old?.externalId != null) {
                            store.putLink(old.copy(source = io.github.prithirajn.trackersync.data.LinkSource.MANUAL, note = null))
                        } else {
                            store.update { s -> s.copy(links = s.links - key) }
                        }
                        SyncEngine.queue(store, t.id, listOf(document))
                    }
                }
            }
            notify("Enabled all trackers for this book")
            syncNow(quiet = true)
        }
    }


    /** A book that isn't on Hardcover or Seekquel: typed in; the trackers match it by title. */
    fun addTypedBook(title: String, author: String, pages: Int?, done: (String?, String?) -> Unit) {
        viewModelScope.launch {
            var doc: String? = null
            val error = try {
                io { doc = ManualBooks.add(store, ManualBooks.Pick(title, author, pages = pages)) }
                null
            } catch (e: Exception) {
                e.message ?: "Couldn't add the book"
            }
            if (error == null) {
                notify("Added", title.trim())
                syncNow(quiet = true)
            }
            done(doc, error)
        }
    }

    fun setPage(document: String, page: Int, done: (String?) -> Unit) =
        edit("On page $page", done, syncAfter = true) { ManualBooks.setPage(store, document, page) }

    fun setPercent(document: String, percent: Int, done: (String?) -> Unit) =
        edit("At $percent%", done, syncAfter = true) { ManualBooks.setPercent(store, document, percent) }

    /** A tracked book's progress set by hand; it wins until a source reports newer reading. */
    fun overrideProgress(document: String, percent: Int?, page: Int?, status: ReadStatus?, done: (String?) -> Unit) =
        edit("Progress saved; it goes to your trackers now", done, syncAfter = true) {
            io.github.prithirajn.trackersync.sync.ProgressOverride.set(store, document, percent, page, status)
        }

    /** Starts a new reading session at 0% for a previously finished book. */
    fun reReadBook(document: String, done: (String?) -> Unit) =
        edit("Started re-reading; it goes to your trackers now", done, syncAfter = true) {
            // Marked first, so trackers that have it as Finished are moved back to Reading.
            store.update { s -> s.copy(rereads = s.rereads + (document to System.currentTimeMillis() / 1000)) }
            val b = store.snapshot.books[document]
            if (b != null && ManualBooks.isManual(b)) {
                ManualBooks.setPercent(store, document, 0)
                ManualBooks.setStatus(store, document, ReadStatus.READING)
            } else {
                io.github.prithirajn.trackersync.sync.ProgressOverride.set(store, document, percent = 0, status = ReadStatus.READING)
            }
        }

    /** A physical book you've started on your reader: move everything to the reader's copy, or keep both. */
    fun continueOnReader(physical: String) =
        edit("Moved to the reader's copy; your trackers carry on from there", {}, syncAfter = true) {
            io.github.prithirajn.trackersync.sync.Handoff.accept(store, physical)
        }

    fun keepBothCopies(physical: String) =
        edit(null, {}) { io.github.prithirajn.trackersync.sync.Handoff.keepBoth(store, physical) }

    // ---- adding a book to trackers that don't have it (sync/AddToTracker) ----

    private val detailsCache = HashMap<String, io.github.prithirajn.trackersync.source.BookDetails>()

    /** The book's public details (Open Library, Google Books, and what's here), looked up once. */
    /**
     * The book's details for adding it somewhere. [link] is a KalpaDB book or a web novel's page
     * (Royal Road, AO3, Scribble Hub, Wattpad…) you pasted; a story link found before is reused.
     */
    fun bookDetails(document: String, link: String? = null, done: (io.github.prithirajn.trackersync.source.BookDetails?, String?) -> Unit) {
        val given = link?.trim()?.takeIf { it.isNotEmpty() }
        val kalpaId = given?.let { io.github.prithirajn.trackersync.source.KalpaDB.idFrom(it) }
        val storyLink = given?.takeIf { kalpaId == null }?.let { io.github.prithirajn.trackersync.source.WebNovels.canonical(it) }
        if (given != null && kalpaId == null && storyLink == null) {
            done(null, "Paste a KalpaDB book link, or a story's page on Royal Road, AO3, Scribble Hub or Wattpad"); return
        }
        if (given == null) detailsCache[document]?.let { done(it, null); return }
        val webUrl = storyLink ?: store.snapshot.webNovels[document]?.url
        viewModelScope.launch {
            try {
                val b = store.snapshot.books[document] ?: throw IllegalStateException("That book is gone")
                val d = io {
                    // Seekquel (the main tracker) is asked first while it's linked, even with its sync off.
                    val sqSvc = store.snapshot.service(SeekquelTracker.id)
                    val asked = sqSvc.linked && !sqSvc.needsReauth
                    val sq = if (!asked) null else runCatching {
                        val session = Session(sqSvc.credential)
                        SeekquelTracker.details(session, store.snapshot.link(SeekquelTracker.id, document), b.title ?: "", b.author)
                            .also { if (session.changed) store.updateService(SeekquelTracker.id) { s -> s.copy(credential = session.encode()) } }
                    }.getOrNull()
                    // crosspoint-sync knows books on the reader well (the book file's language and description, and more).
                    val acct = store.snapshot.account
                    val askedCp = b.origin == null && acct != null
                    val cp = if (askedCp) runCatching { CrossPointClient(acct!!).details(document) }.getOrNull() else null
                    io.github.prithirajn.trackersync.source.BookInfo.lookup(
                        b, kalpaId = kalpaId, seekquel = sq, askedSeekquel = asked, crosspoint = cp, askedCrossPoint = askedCp, webUrl = webUrl,
                    )
                }
                if (kalpaId != null && "KalpaDB" !in d.found) throw IllegalStateException("KalpaDB didn't send that book; try again in a minute (its server wakes slowly)")
                if (storyLink != null && d.web == null) throw IllegalStateException("Couldn't read that story's page; check the link, or try again later")
                d.web?.let { w -> store.update { s -> s.copy(webNovels = s.webNovels + (document to w)) } }
                val grId = store.snapshot.link("goodreads", document)?.externalId
                val grUrl = grId?.let { "https://www.goodreads.com/book/show/" + it }
                val asinVal = if (document.startsWith("kindle:")) document.removePrefix("kindle:") else null
                val enriched = d.copy(goodreadsUrl = grUrl, asin = asinVal)
                detailsCache[document] = enriched
                done(enriched, null)
            } catch (e: Exception) {
                done(null, e.message ?: "Couldn't look the book up")
            }
        }
    }

    /** Your sign-in cookies for a tracker's site, to open its pages as you in the app's browser. */
    fun webCookies(t: Tracker): List<Pair<String, String>> = t.webCookies(Session(store.snapshot.service(t.id).credential))

    /** A cover image as base64 with its type, to put in a site's upload field; null when it can't be had. */
    fun coverForUpload(url: String?, done: (Pair<String, String>?) -> Unit) {
        if (url == null) { done(null); return }
        viewModelScope.launch {
            val bytes = io { io.github.prithirajn.trackersync.net.Http.bytes(url) }
            done(bytes?.let { b ->
                val mime = when {
                    b.size > 3 && b[0] == 0x89.toByte() -> "image/png"
                    b.size > 12 && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
                    else -> "image/jpeg"
                }
                android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP) to mime
            })
        }
    }

    /** A book you created on the tracker's own page: match it there and send it. */
    fun linkCreated(document: String, t: Tracker, remoteId: String, details: io.github.prithirajn.trackersync.source.BookDetails) {
        viewModelScope.launch {
            val error = io { runCatching { io.github.prithirajn.trackersync.sync.AddToTracker.linkCreated(store, document, t, remoteId, details) }.exceptionOrNull() }
            if (error != null) { notify("Added on ${t.name}, but not matched", error.message, error = true); return@launch }
            notify("Added to ${t.name} and matched")
            syncNow(quiet = true)
        }
    }

    /** Why a book can't be added to [trackerId] from here right now, or null when it can. */
    fun addBlocked(trackerId: String): String? {
        val t = Trackers.byId(trackerId) ?: return "Unknown tracker"
        val svc = store.snapshot.service(t.id)
        return when {
            !t.addsBooks -> "${t.name} doesn't let apps add books"
            !svc.linked -> "Link ${t.name} under Services first"
            svc.needsReauth -> "Sign in to ${t.name} again under Services first"
            else -> null
        }
    }

    /** Sets (or, with a blank [link], forgets) the page of the web novel this book is, after reading it once. */
    fun setStoryLink(document: String, link: String, done: (String?) -> Unit) {
        val l = link.trim()
        if (l.isEmpty()) {
            store.update { s -> s.copy(webNovels = s.webNovels - document) }
            detailsCache.remove(document)
            done(null); return
        }
        val url = io.github.prithirajn.trackersync.source.WebNovels.canonical(l)
            ?: run { done("That isn't a story page on Royal Road, AO3, Scribble Hub, Wattpad or a similar site"); return }
        viewModelScope.launch {
            val d = io { io.github.prithirajn.trackersync.source.WebNovels.fetch(url) }
            val w = d?.web
            if (w == null) { done("Couldn't read that page; check the link, or try again later"); return@launch }
            store.update { s -> s.copy(webNovels = s.webNovels + (document to w)) }
            detailsCache.remove(document)
            // A complete story you've read to the end is now Finished; send it again.
            io { for (t in Trackers.all) if (store.snapshot.service(t.id).let { it.linked && it.enabled }) io.github.prithirajn.trackersync.sync.SyncEngine.queue(store, t.id, listOf(document)) }
            done(null)
        }
    }

    /** Stories on the web novel sites that might be this book, best match first. */
    fun webCandidates(document: String, done: (List<io.github.prithirajn.trackersync.source.WebNovels.Found>?) -> Unit) {
        val b = store.snapshot.books[document] ?: run { done(emptyList()); return }
        val title = b.title ?: run { done(emptyList()); return }
        viewModelScope.launch {
            val found = io { runCatching { io.github.prithirajn.trackersync.source.WebNovels.candidates(title, b.author) }.getOrNull() }
            if (io.github.prithirajn.trackersync.sync.SyncEngine.webSearchComplete()) {
                io { store.update { s -> s.copy(webSearched = s.webSearched + (document to System.currentTimeMillis() / 1000)) } }
            }
            done(found)
        }
    }

    fun webNovel(document: String): io.github.prithirajn.trackersync.data.WebNovel? = store.snapshot.webNovels[document]

    /** Why [details] can't go to [trackerId] (fan fiction on Goodreads), or null. */
    fun addRefusal(trackerId: String, details: io.github.prithirajn.trackersync.source.BookDetails): String? =
        Trackers.byId(trackerId)?.let { io.github.prithirajn.trackersync.sync.AddToTracker.refusal(it, details) }

    fun addTargets(document: String): List<Tracker> = io.github.prithirajn.trackersync.sync.AddToTracker.candidates(store, document)

    /** Adds the book to [trackers] (each checked once more first) and syncs it there. */
    fun addToTrackers(
        document: String, trackers: List<Tracker>, details: io.github.prithirajn.trackersync.source.BookDetails,
        done: (List<io.github.prithirajn.trackersync.sync.AddToTracker.Outcome>) -> Unit,
    ) {
        viewModelScope.launch {
            val out = io { trackers.map { io.github.prithirajn.trackersync.sync.AddToTracker.add(store, document, it, details) } }
            val ok = out.count { it.error == null }
            notify(
                if (ok == out.size) "Added to ${out.joinToString(", ") { it.tracker.name }}" else "$ok of ${out.size} done",
                out.filter { it.error != null }.joinToString("\n") { it.text }.ifEmpty { null },
                error = ok < out.size,
            )
            done(out)
            if (ok > 0) syncNow(quiet = true)
        }
    }

    /** Seekquel's different position for a book: use it, or keep the one here. */
    fun acceptSeekquelProgress(document: String) =
        edit("Using Seekquel's position; it goes to your trackers now", {}, syncAfter = true) {
            io.github.prithirajn.trackersync.sync.ProgressOverride.acceptSeekquel(store, document)
        }

    fun denySeekquelProgress(document: String) =
        edit(null, {}) { io.github.prithirajn.trackersync.sync.ProgressOverride.denySeekquel(store, document) }

    fun setManualStatus(document: String, status: ReadStatus) =
        edit(status.label, {}, syncAfter = true) { ManualBooks.setStatus(store, document, status) }

    fun editManual(document: String, title: String, author: String, pages: Int?, done: (String?) -> Unit) =
        edit("Saved", done, syncAfter = true) { ManualBooks.edit(store, document, title, author, pages) }

    fun removeManual(document: String, done: (String?) -> Unit) {
        val before = store.snapshot
        edit(null, { err -> if (err == null) notifyUndo("Removed", before, document); done(err) }) { ManualBooks.remove(store, document) }
    }

    /** Reads the page number off the photo at [uri]; the photo is deleted afterwards. */
    fun readPage(uri: android.net.Uri, photo: java.io.File, document: String, done: (PageReader.Guess?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                // Decoding a full-size photo is heavy: off the main thread.
                val lines = withContext(Dispatchers.IO) { PageScanner.read(getApplication(), uri) }
                val b = store.snapshot.books[document]
                done(PageReader.guess(lines, b?.pageCount, b?.currentPage ?: b?.pageCount?.let { pc -> Math.round(b.percentage * pc).toInt() }), null)
            } catch (e: Exception) {
                done(null, e.message ?: "Couldn't read the photo")
            } finally {
                runCatching { photo.delete() }
            }
        }
    }

    // ---- an extra KOSync server ----

    fun addKosync(server: String, username: String, password: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            val error = try {
                io {
                    val account = KosyncClient.signIn(server, username, password)
                    store.update { it.copy(kosync = account, kosyncOkAt = System.currentTimeMillis(), kosyncError = null, kosyncRotation = 0) }
                }
                null
            } catch (e: Exception) {
                e.message ?: "Couldn't sign in"
            }
            done(error)
            if (error == null) {
                notify("KOSync server added", "Checking your books on it now.")
                syncNow(quiet = true)
            }
        }
    }

    // ---- Moon+ Reader, through WebDAV (Koofr…) ----

    fun addMoon(server: String, email: String, appPassword: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            var found = false
            val error = try {
                io {
                    val address = server.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }.trimEnd('/')
                    val account = io.github.prithirajn.trackersync.data.Account(address, email.trim(), appPassword.trim())
                    val client = io.github.prithirajn.trackersync.source.MoonReaderClient(account)
                    if (!client.verify()) throw IllegalStateException("The server rejected that email and app password. Check them (an app password, not your main one) and try again.")
                    val folder = runCatching { client.findFolder() }.getOrNull()
                    found = folder != null
                    store.update {
                        it.copy(
                            moon = account, moonFolder = folder, moonOkAt = System.currentTimeMillis(),
                            moonError = if (folder == null) Health.MOON_NO_FILES else null,
                            moonSeen = emptyMap(), moonMods = emptyMap(),
                        )
                    }
                }
                null
            } catch (e: Exception) {
                when (e) {
                    is io.github.prithirajn.trackersync.net.NetworkException -> "Couldn't reach that address. Check it, and that you're online."
                    else -> e.message ?: "Couldn't connect"
                }
            }
            done(error)
            if (error == null) {
                if (found) notify("Moon+ Reader connected", "Reading its progress now.")
                else notify("Connected", "No Moon+ Reader sync files there yet. Turn on WebDAV sync in Moon+ Reader with the same account, then read a page.")
                syncNow(quiet = true)
            }
        }
    }

    // ---- KOReader highlights, from HighlightSync / AnnotationSync files on WebDAV (Koofr…) ----

    fun addKoHighlights(server: String, email: String, appPassword: String, folder: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            var found = 0
            val error = try {
                io {
                    val address = server.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }.trimEnd('/')
                    val account = io.github.prithirajn.trackersync.data.Account(address, email.trim(), appPassword.trim())
                    val client = io.github.prithirajn.trackersync.source.KoHighlightsClient(account)
                    if (!client.verify()) throw IllegalStateException("The server rejected that email and app password. Check them (an app password, not your main one) and try again.")
                    val hint = folder.trim().takeIf { it.isNotEmpty() }?.let { if (it.startsWith("/")) it else "/$it" }
                    val folders = runCatching { client.findFolders(listOfNotNull(hint)) }.getOrDefault(emptyList())
                    found = folders.size
                    store.update {
                        it.copy(
                            koHl = account, koHlFolders = folders.ifEmpty { listOfNotNull(hint) }, koHlOkAt = System.currentTimeMillis(),
                            koHlError = if (folders.isEmpty()) Health.KOHL_NO_FILES else null, koHlMods = emptyMap(), koHlUnmatched = emptyList(),
                        )
                    }
                }
                null
            } catch (e: Exception) {
                when (e) {
                    is io.github.prithirajn.trackersync.net.NetworkException -> "Couldn't reach that address. Check it, and that you're online."
                    else -> e.message ?: "Couldn't connect"
                }
            }
            done(error)
            if (error == null) {
                if (found > 0) notify("KOReader highlights connected", "Reading them now.")
                else notify("Connected", "No KOReader highlight files there yet. Check the plugin syncs to this storage, or type its folder.")
                syncNow(quiet = true)
            }
        }
    }

    /** Forgets the connection; the highlights already brought in stay unless [dropHighlights]. */
    fun removeKoHighlights(dropHighlights: Boolean = false) {
        viewModelScope.launch {
            io {
                store.update {
                    it.copy(koHl = null, koHlFolders = emptyList(), koHlOkAt = 0L, koHlError = null, koHlMods = emptyMap(), koHlUnmatched = emptyList(),
                        koClips = if (dropHighlights) emptyList() else it.koClips)
                }
            }
        }
    }

    /** KOReader's sidecars from a folder on this device: the books folder, or koreader/docsettings. */
    fun setKoLocal(uri: android.net.Uri) {
        viewModelScope.launch {
            runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            io { store.update { it.copy(koLocal = uri.toString(), koLocalOkAt = 0L, koLocalError = null) } }
            io { runCatching { SyncEngine(store).pullKoHighlights() } }
            Health.koLocal(store.snapshot)?.let { notify(it.title, it.detail, error = it.level == Health.Level.BAD) }
            syncNow(quiet = true)
        }
    }

    fun removeKoLocal() {
        viewModelScope.launch {
            val folder = store.snapshot.koLocal
            io { store.update { s -> s.copy(koLocal = null, koLocalOkAt = 0L, koLocalError = null, koHlMods = s.koHlMods.filterKeys { !it.startsWith("local:") }) } }
            folder?.let { f -> runCatching { getApplication<Application>().contentResolver.releasePersistableUriPermission(android.net.Uri.parse(f), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        }
    }

    fun setKoToCrossPoint(on: Boolean) {
        viewModelScope.launch {
            io { store.update { it.copy(koToCrossPoint = on) } }
            if (on) syncNow(quiet = true)
        }
    }

    /**
     * Names for books known only by KOReader's ID, from files you pick (a list of IDs and names,
     * or IDs with Calibre numbers plus a Calibre catalog). [done] gets what happened.
     */
    fun importBookNames(uris: List<android.net.Uri>, done: (String) -> Unit) {
        viewModelScope.launch {
            val msg = try {
                io {
                    val app = getApplication<Application>()
                    val texts = uris.take(10).mapNotNull { u ->
                        app.contentResolver.openInputStream(u)?.use { input ->
                            val b = input.readBytes()
                            if (b.size > 20 * 1024 * 1024) throw IllegalStateException("That file is over 20 MB")
                            String(b, Charsets.UTF_8)
                        }
                    }
                    val r = io.github.prithirajn.trackersync.sync.NameImport.read(texts)
                    if (r.names.isEmpty()) {
                        if (r.unresolved > 0) "Those IDs point to ${r.unresolved} other numbers (Calibre's, say) but nothing gave those numbers names. Pick that file too: Calibre's CSV catalog, or the file listing the books."
                        else "No KOReader document IDs with names in there."
                    } else {
                        var here = 0
                        store.update { s -> here = io.github.prithirajn.trackersync.sync.NameImport.relevant(s, r); io.github.prithirajn.trackersync.sync.NameImport.apply(s, r) }
                        "Named ${r.names.size} book${if (r.names.size == 1) "" else "s"}" + (if (here > 0) ", $here of them waiting here" else "") +
                            (if (r.unresolved > 0) "; ${r.unresolved} couldn't be named" else "") + ". Matching them now."
                    }
                }
            } catch (e: Exception) {
                e.message ?: "Couldn't read that file"
            }
            done(msg)
            syncNow(quiet = true)
        }
    }

    /** The folder of your EPUBs on the KOReader highlights' WebDAV storage (null stops reading it). */
    fun setKoDavBooks(folder: String?) {
        viewModelScope.launch {
            val f = folder?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("/")) it else "/$it" }?.trimEnd('/')?.ifEmpty { "/" }
            io { store.update { s -> s.copy(koDavBooks = f, koBooks = if (f == null) s.koBooks.filterKeys { !it.startsWith("dav:") } else s.koBooks) } }
            if (f != null) syncNow(quiet = true)
        }
    }

    /** Looks for your EPUBs on that storage and reads that folder. */
    fun findKoDavBooks(done: () -> Unit) {
        viewModelScope.launch {
            val found = runCatching {
                io { store.snapshot.koHl?.let { io.github.prithirajn.trackersync.source.KoHighlightsClient(it).findBooksFolder() } }
            }.getOrNull()
            done()
            if (found == null) notify("No EPUBs found", "Type the folder they're in instead.")
            else { notify("Books found", "Reading EPUBs in $found."); setKoDavBooks(found) }
        }
    }

    /** Opens KOReader highlights' setup (under Services). */
    fun openKoHighlights() {
        AppActions.pending.value = AppAction.ServicePage("kohl")
    }

    /** "Not now" on the card offering KOReader's highlights. */
    fun dismissKoNudge() {
        viewModelScope.launch { io { store.update { it.copy(koNudgeOff = true) } } }
    }

    fun setCpToKo(on: Boolean) {
        viewModelScope.launch {
            io { store.update { it.copy(cpToKo = on, cpToKoNote = null) } }
            if (on) syncNow(quiet = true)
        }
    }

    fun checkKoHighlights() {
        viewModelScope.launch {
            io { runCatching { SyncEngine(store).pullKoHighlights() } }
            Health.koHl(store.snapshot)?.let { notify(it.title, it.detail, error = it.level == Health.Level.BAD) }
        }
    }

    fun removeMoon() {
        viewModelScope.launch { io { store.update { it.copy(moon = null, moonFolder = null, moonOkAt = 0L, moonError = null, moonSeen = emptyMap(), moonMods = emptyMap()) } } }
    }

    fun checkMoon() {
        viewModelScope.launch {
            io { runCatching { SyncEngine(store).pullMoon() } }
            Health.moon(store.snapshot)?.let { notify(it.title, it.detail, error = it.level == Health.Level.BAD) }
        }
    }

    // ---- KOSync documents linked to a book by hand ----

    /** What KOSync holds for a document ID (position, device, when), to check it's the right book before linking. */
    fun kosyncPeek(id: String, done: (io.github.prithirajn.trackersync.source.KosyncProgress?, String?) -> Unit) {
        val account = store.snapshot.kosync ?: return done(null, "Add your KOSync server under Services first")
        viewModelScope.launch {
            try {
                val p = io { KosyncClient(account).progress(id.trim().lowercase()) }
                done(p, if (p == null) "KOSync has nothing under that ID. Check it's copied exactly from your KOSync dashboard." else null)
            } catch (e: Exception) {
                done(null, e.message ?: "Couldn't reach KOSync")
            }
        }
    }

    /** Every document on the KOSync server, newest first, when the server can list them (null when it can't). */
    fun kosyncDocuments(done: (List<io.github.prithirajn.trackersync.source.KosyncProgress>?) -> Unit) {
        val account = store.snapshot.kosync ?: return done(null)
        viewModelScope.launch {
            val list = runCatching { io { KosyncClient(account).documents() } }.getOrNull()
            done(list?.sortedByDescending { it.timestamp })
        }
    }

    fun linkKosync(document: String, id: String) {
        val clean = id.trim().lowercase()
        if (clean.isEmpty() || clean == document) return
        viewModelScope.launch {
            io { store.update { s -> s.copy(kosyncIds = s.kosyncIds + (document to ((s.kosyncIds[document] ?: emptyList()) + clean).distinct())) } }
            notify("KOSync document linked", "Its position is checked with this book's on every sync.")
            syncNow(quiet = true)
        }
    }

    fun unlinkKosync(document: String, id: String) {
        viewModelScope.launch {
            io {
                store.update { s ->
                    val left = (s.kosyncIds[document] ?: emptyList()) - id
                    s.copy(kosyncIds = if (left.isEmpty()) s.kosyncIds - document else s.kosyncIds + (document to left))
                }
            }
        }
    }

    /** The KOSync ID KOReader gives a file when it matches by file name: the MD5 of the name. */
    fun kosyncIdForFileName(name: String): String = io.github.prithirajn.trackersync.source.CrossPointClient.md5(name.trim())

    fun removeKosync() {
        viewModelScope.launch { io { store.update { it.copy(kosync = null, kosyncOkAt = 0L, kosyncError = null) } } }
    }

    // ---- Amazon Kindle source ----

    fun saveKindleAccount(domain: String, cookies: String, userAgent: String?, done: () -> Unit) {
        viewModelScope.launch {
            io {
                val account = io.github.prithirajn.trackersync.data.KindleAccount(
                    domain = domain,
                    cookies = cookies,
                    userAgent = userAgent,
                    name = "Kindle ($domain)",
                )
                store.update { it.copy(kindle = account, kindleOkAt = 0L, kindleError = null, kindleTriedAt = 0L) }
            }
            done()
            notify("Kindle linked", "Checking your reading progress and highlights.")
            syncNow(quiet = false)
        }
    }

    fun removeKindle() {
        viewModelScope.launch {
            io {
                store.update { it.copy(kindle = null, kindleOkAt = 0L, kindleError = null, kindleClips = emptyList()) }
            }
            notify("Kindle disconnected", "Your books remain in your library.")
        }
    }

    fun setKindleHighlights(sync: Boolean) {
        viewModelScope.launch {
            io {
                store.update { s -> s.copy(kindle = s.kindle?.copy(syncHighlights = sync)) }
            }
        }
    }

    /** The Kindle health light: checks the Amazon session live and pulls any new books/progress. */
    fun checkKindle() {
        viewModelScope.launch {
            io {
                SyncEngine(store).pullKindle(force = true)
            }
            Health.kindle(store.snapshot)?.let { notify(it.title, it.detail, error = it.level == Health.Level.BAD) }
        }
    }

    // ---- Mihon and MangaUpdates ----

    /** Keeps access to Mihon's backup folder and reads it now. */
    fun setMihonFolder(uri: android.net.Uri) {
        viewModelScope.launch {
            runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            io { store.update { it.copy(mihon = io.github.prithirajn.trackersync.data.MihonSource(folder = uri.toString()), mihonOkAt = 0L, mihonError = null) } }
            notify("Mihon linked", "Reading its newest backup.")
            io { SyncEngine(store).pullManga() }
            syncNow(quiet = true)
        }
    }

    fun removeMihon() {
        viewModelScope.launch {
            val folder = store.snapshot.mihon?.folder
            io {
                folder?.let { f -> runCatching { getApplication<Application>().contentResolver.releasePersistableUriPermission(android.net.Uri.parse(f), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
                store.update { it.copy(mihon = null, mihonOkAt = 0L, mihonError = null) }
            }
            notify("Mihon disconnected", "Your volumes remain in your library.")
        }
    }

    fun checkMihon() {
        viewModelScope.launch {
            io { SyncEngine(store).pullManga() }
            Health.mihon(store.snapshot)?.let { notify(it.title, it.detail, error = it.level == Health.Level.BAD) }
        }
    }

    fun linkMangaUpdates(username: String, password: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val token = io { io.github.prithirajn.trackersync.source.MangaUpdates.login(username.trim(), password) }
                io { store.update { it.copy(mangaUpdates = io.github.prithirajn.trackersync.data.MangaUpdatesAccount(username.trim(), token), mangaUpdatesOkAt = System.currentTimeMillis(), mangaUpdatesError = null) } }
                done(null)
                notify("MangaUpdates linked", "Chapters you read in Mihon go to your Reading list.")
                syncNow(quiet = true)
            } catch (e: Exception) {
                done(e.message ?: "Couldn't sign in to MangaUpdates")
            }
        }
    }

    fun removeMangaUpdates() {
        viewModelScope.launch {
            io { store.update { it.copy(mangaUpdates = null, mangaUpdatesOkAt = 0L, mangaUpdatesError = null, muSent = emptyMap()) } }
            notify("MangaUpdates disconnected", "Nothing was changed on MangaUpdates.")
        }
    }

    // ---- Manga and comics you log by hand ----

    /**
     * Notes where you are in a manga or comic read somewhere the app can't see. [page] is the page
     * you're on (0 when you finished [at]). Volumes and trackers follow on the spot.
     */
    fun logReading(
        title: String, comic: Boolean, at: Double, page: Int, fromStart: Boolean, done: (String?) -> Unit = {},
        /** Start and finish days you gave (chapter or issue -> unix seconds); unset ones are noted as you log. */
        dates: Map<Double, Pair<Long?, Long?>> = emptyMap(),
    ) {
        val t = title.trim()
        if (t.isEmpty() || at <= 0) { done("Give the title and the chapter or issue"); return }
        // The sheet closes at once; the volumes and trackers follow in the background.
        done(null)
        viewModelScope.launch {
            io {
                val key = io.github.prithirajn.trackersync.sync.MangaSources.loggedKey(t)
                val nowS = System.currentTimeMillis() / 1000
                store.update { st ->
                    val prev = st.logged[key]
                    // Chapters read since the last log count as read now; on a first log, only this one.
                    val before = prev?.let { it.at - (if (it.finished) 0.0 else 1.0) } ?: (kotlin.math.floor(at) - 1)
                    // Days: the ones you gave win; else this one started now (if not noted before), and finished now when it's done.
                    val d = (prev?.dates ?: emptyMap()).toMutableMap()
                    for ((n, v) in dates) d[n] = v
                    val cur = d[at]
                    if (at !in dates) d[at] = (cur?.first ?: nowS) to (if (page <= 0) (cur?.second ?: nowS) else cur?.second)
                    st.copy(logged = st.logged + (key to io.github.prithirajn.trackersync.data.LoggedSeries(
                        title = prev?.title ?: t, comic = comic, at = at, page = page.coerceAtLeast(0), fromStart = fromStart,
                        before = minOf(before, at - 1), loggedAt = nowS, author = prev?.author, dates = d,
                    )))
                }
            }
            notify("Logged", if (page > 0) "${if (comic) "Issue #" else "Chapter "}${io.github.prithirajn.trackersync.sync.ComicIssues.label(at)}, page $page" else "Finished ${if (comic) "issue #" else "chapter "}${io.github.prithirajn.trackersync.sync.ComicIssues.label(at)}")
            io { SyncEngine(store).pullManga() }
            syncNow(quiet = true)
        }
    }

    /** A series as a catalogue knows it: its name, a cover, where it was found, and whether it's a comic. */
    data class SeriesHit(val name: String, val coverUrl: String?, val source: String, val comic: Boolean?)

    /**
     * Series names matching what you type, found the forgiving way the trackers search (typos and
     * capitals don't matter): your own series first, then Hardcover, Seekquel and (for manga)
     * MangaDex, each as its series name ("Something is Killing the Children", not "… #5").
     */
    fun seriesSuggestions(query: String, comic: Boolean, done: (List<SeriesHit>) -> Unit) {
        val q = query.trim()
        if (q.length < 2) { done(emptyList()); return }
        viewModelScope.launch {
            val out = io {
                val s = store.snapshot
                val hits = LinkedHashMap<String, SeriesHit>()
                fun add(name: String, cover: String?, source: String, isComic: Boolean?) {
                    val clean = io.github.prithirajn.trackersync.sync.MangaSources.seriesTitleOf(name)
                    val k = io.github.prithirajn.trackersync.sync.Matcher.baseTitle(clean)
                    if (k.isBlank()) return
                    val had = hits[k]
                    if (had == null) hits[k] = SeriesHit(clean, cover, source, isComic)
                    else if (had.coverUrl == null && cover != null) hits[k] = had.copy(coverUrl = cover)
                }
                val want = io.github.prithirajn.trackersync.sync.Matcher.baseTitle(q)
                (s.logged.values.map { it.title to it.comic } + s.comicMaps.values.map { it.title to true } + s.mangaMaps.values.map { it.title to false })
                    .filter { (t, _) -> io.github.prithirajn.trackersync.sync.Matcher.baseTitle(t).contains(want) }
                    .forEach { (t, c) -> add(t, null, "yours", c) }
                val hc = s.service("hardcover").takeIf { it.linked && !it.needsReauth }
                // A series' volumes give its name and cover; its single issues are passed over.
                hc?.let { svc -> runCatching { io.github.prithirajn.trackersync.trackers.HardcoverTracker.search(io.github.prithirajn.trackersync.trackers.Session(svc.credential), q, null) }.getOrNull() }
                    ?.take(15)?.sortedBy { if (io.github.prithirajn.trackersync.sync.Matcher.isSingleIssue(it.title, it.pages)) 1 else 0 }?.forEach { add(it.title, it.coverUrl, "Hardcover", null) }
                val sq = s.service("seekquel").takeIf { it.linked && !it.needsReauth }
                sq?.let { svc -> runCatching { io.github.prithirajn.trackersync.trackers.SeekquelTracker.search(io.github.prithirajn.trackersync.trackers.Session(svc.credential), q, null) }.getOrNull() }
                    ?.take(15)?.sortedBy { if (io.github.prithirajn.trackersync.sync.Matcher.isSingleIssue(it.title, it.pages)) 1 else 0 }?.forEach { add(it.title, it.coverUrl, "Seekquel", null) }
                if (!comic) runCatching { io.github.prithirajn.trackersync.source.MangaDex.searchTitles(q) }.getOrNull()?.forEach { add(it, null, "MangaDex", false) }
                hits.values.take(6)
            }
            done(out)
        }
    }

    /**
     * The volume a chapter or issue is in, for filling in the ones before it: (volume number, its
     * chapters or issues). Looks the series up (Hardcover, Seekquel, MangaDex) when it's new here.
     */
    fun volumeFor(title: String, comic: Boolean, number: Double, done: (Pair<String, List<Double>>?) -> Unit) {
        viewModelScope.launch {
            val r = io {
                val base = io.github.prithirajn.trackersync.sync.Matcher.baseTitle(io.github.prithirajn.trackersync.sync.MangaSources.seriesTitleOf(title))
                val s = store.snapshot
                val key = (if (comic) s.comicMaps.entries.firstOrNull { io.github.prithirajn.trackersync.sync.Matcher.baseTitle(it.value.title) == base }?.key
                    else s.mangaMaps.entries.firstOrNull { io.github.prithirajn.trackersync.sync.Matcher.baseTitle(it.value.title) == base }?.key)
                    ?: ("-3:" + io.github.prithirajn.trackersync.sync.MangaSources.loggedKey(title).removePrefix("logged:"))
                runCatching { SyncEngine(store).volumesFor(key, title, comic, number) }.getOrNull()
            }
            done(r?.firstOrNull { (_, ns) -> ns.any { kotlin.math.abs(it - number) < 1e-6 } })
        }
    }

    /**
     * Sets the days you read one chapter or issue you've already read, without moving your place:
     * editing #1's days while you're on #5 leaves #2–#4 read and you on #5. The volume's own days
     * follow (its first issue's start, its last issue's finish).
     */
    fun setIssueDays(title: String, comic: Boolean, number: Double, started: Long?, finished: Long?) {
        viewModelScope.launch {
            io {
                val key = io.github.prithirajn.trackersync.sync.MangaSources.loggedKey(title)
                store.update { st ->
                    val l = st.logged[key]
                        // Not logged yet (it came from Mihon or AniList): a log that only holds days.
                        // Only this one, as read (it is), so nothing else changes.
                        ?: io.github.prithirajn.trackersync.data.LoggedSeries(title, comic, number, 0, false, number, System.currentTimeMillis() / 1000)
                    st.copy(logged = st.logged + (key to l.copy(dates = l.dates + (number to (started to finished)))))
                }
                SyncEngine(store).pullManga()
            }
            notify("Days saved", "${if (comic) "Issue #" else "Chapter "}${io.github.prithirajn.trackersync.sync.ComicIssues.label(number)}")
            syncNow(quiet = true)
        }
    }

    /**
     * Sets which chapters or issues one volume holds, keeping the volumes before it as they are and
     * the ones after it where they still fit (later ones follow at this one's size).
     */
    fun setVolumeRange(key: String, volume: String, from: Double, to: Double, done: (String?) -> Unit) {
        val s = store.snapshot
        val cur = s.comicCustom[key] ?: s.comicMaps[key] ?: s.mangaMaps[key]?.let { m ->
            io.github.prithirajn.trackersync.source.ComicCollections.Map(m.title, m.volumes.map { v -> io.github.prithirajn.trackersync.source.ComicCollections.Volume(v.number, v.chapters.map { it.number }) }, m.source, m.at)
        }
        val vols = cur?.volumes.orEmpty().sortedBy { it.number.toDoubleOrNull() ?: Double.MAX_VALUE }
        val vn = volume.toDoubleOrNull() ?: return done("That volume has no number")
        if (from <= 0 || to < from) return done("The last one can't be before the first")
        val before = vols.filter { (it.number.toDoubleOrNull() ?: 0.0) < vn }
        val prevEnd = before.lastOrNull()?.issues?.maxOrNull()
        if (prevEnd != null && from <= prevEnd) return done("Vol. ${before.last().number} ends at ${io.github.prithirajn.trackersync.sync.ComicIssues.label(prevEnd)}; this one starts after it")
        val after = vols.filter { (it.number.toDoubleOrNull() ?: 0.0) > vn }.takeWhile { v -> (v.issues.minOrNull() ?: 0.0) > to }
        val ranges = (before.map { (it.issues.min()) to it.issues.max() } + listOf(from to to) + after.map { it.issues.min() to it.issues.max() })
        // Volumes numbered from 1 in order: the ones before are kept whole, so this one keeps its number.
        if (before.size + 1 != vn.toInt()) {
            // Gaps in what's known before it: fill them at the usual size so the numbering holds.
            return done("Set the volumes before this one first (under Services \u2192 Manga & comics)")
        }
        setComicVolumes(key, ranges.map { (a, b) -> a to b }, done)
    }

    /** Finished the next chapter or issue of a logged series. */
    fun logNext(key: String) {
        val l = store.snapshot.logged[key] ?: return
        val next = if (l.finished) kotlin.math.floor(l.at) + 1 else l.at
        logReading(l.title, l.comic, next, 0, l.fromStart)
    }

    fun removeLog(key: String) {
        viewModelScope.launch {
            io { store.update { it.copy(logged = it.logged - key) } }
            notify("Log removed", "Volumes already in your library stay.")
        }
    }

    // ---- SFF Radar & Subgenre Star-Chart (sync/SffRadar) ----

    /** Adds a custom social handle or hashtag ("x:torbooks", "insta:orbitbooks", "bsky:scalzi.com", "mastodon:sff") to the SFF Radar. */
    fun addSffSocial(rawHandle: String) {
        val clean = rawHandle.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            io {
                store.update { s ->
                    val next = (s.settings.sffSocials + clean).distinctBy { it.lowercase() }
                    s.copy(settings = s.settings.copy(sffSocials = next))
                }
            }
            notify("Followed $clean on SFF Radar", "Refreshing social feeds…")
            refreshSff(force = true)
        }
    }

    /** Removes a custom social handle or hashtag from the SFF Radar. */
    fun removeSffSocial(handle: String) {
        viewModelScope.launch {
            io {
                store.update { s ->
                    val next = s.settings.sffSocials.filterNot { it.equals(handle, ignoreCase = true) }
                    s.copy(settings = s.settings.copy(sffSocials = next))
                }
            }
            notify("Unfollowed $handle")
        }
    }

    /** Loads the full in-app readable article, Reddit thread + comments, or social thread for [item]. */
    fun loadSffArticle(item: io.github.prithirajn.trackersync.data.SffFeedItem, done: (io.github.prithirajn.trackersync.source.SffArticleReader.ReadableArticle) -> Unit) {
        viewModelScope.launch {
            val article = io { io.github.prithirajn.trackersync.source.SffArticleReader.load(item) }
            if (article.bodyMarkdown.length > (item.fullContent?.length ?: 0)) {
                io {
                    store.update { s ->
                        s.copy(sffItems = s.sffItems.map { if (it.id == item.id) it.copy(fullContent = article.bodyMarkdown) else it })
                    }
                }
            }
            done(article)
        }
    }

    /** Generates a 3-bullet Artifact-style summary for [cluster] using the on-device AI model (or extractive fallback). */
    fun summarizeSffCluster(
        cluster: io.github.prithirajn.trackersync.sync.SffArtifactAi.SffStoryCluster,
        bodyMarkdown: String,
        done: (List<String>, Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val (bullets, usedLlm) = io { io.github.prithirajn.trackersync.sync.SffArtifactAi.summarizeArticle(cluster, bodyMarkdown) }
            io {
                store.update { s ->
                    s.copy(sffItems = s.sffItems.map { if (it.id == cluster.lead.id) it.copy(aiBullets = bullets) else it })
                }
            }
            done(bullets, usedLlm)
        }
    }

    /** Rewrites a clickbait or vague headline into a factual headline using the on-device AI model (Artifact style). */
    fun rewriteSffClickbait(
        item: io.github.prithirajn.trackersync.data.SffFeedItem,
        bodyMarkdown: String,
        done: (String, Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val body = if (bodyMarkdown.length >= 80) bodyMarkdown else io { io.github.prithirajn.trackersync.source.SffArticleReader.load(item).bodyMarkdown }
            val (rewritten, usedLlm) = io { io.github.prithirajn.trackersync.sync.SffArtifactAi.rewriteClickbait(item, body) }
            io {
                store.update { s ->
                    s.copy(sffItems = s.sffItems.map { if (it.id == item.id) it.copy(aiHeadline = rewritten) else it })
                }
            }
            notify(if (usedLlm) "Headline rewritten by On-Device AI" else "Headline clarified from article lead", rewritten)
            done(rewritten, usedLlm)
        }
    }

    /** Records in-app reader dwell time (seconds) to train the Artifact SFF feed algorithm. */
    fun recordSffReadDwell(cluster: io.github.prithirajn.trackersync.sync.SffArtifactAi.SffStoryCluster, dwellSeconds: Int) {
        viewModelScope.launch {
            val news = io {
                io.github.prithirajn.trackersync.sync.SffArtifactAi.recordReadDwell(store, cluster, dwellSeconds)
                val Sky = io.github.prithirajn.trackersync.sync.SffChart
                val before = store.snapshot
                val t = sffNow()
                store.update { it.copy(sffSky = Sky.recordRead(it, cluster.lead, dwellSeconds, t)) }
                val after = store.snapshot
                val lvl0 = Sky.levelOf(Sky.sectorXp(before).values.sum())
                val lvl1 = Sky.levelOf(Sky.sectorXp(after).values.sum())
                val revealed = after.sffSky.revealed.values.flatten() - before.sffSky.revealed.values.flatten().toSet()
                val constellation = after.sffSky.constellations.drop(before.sffSky.constellations.size).firstOrNull()
                when {
                    constellation != null -> "Expedition complete: you named ${constellation.name}" to "+${Sky.EXPEDITION_XP} XP"
                    lvl1 > lvl0 -> "Level $lvl1 reached" to "Your sky is growing"
                    revealed.isNotEmpty() -> "Revealed on your chart: ${revealed.joinToString(", ")}" to null
                    else -> null
                }
            }
            news?.let { (title, body) -> notify(title, body) }
        }
    }

    /** Records explicit thumbs-up ("More like this") or thumbs-down ("Less like this") feedback on a story cluster. */
    fun sffFeedback(cluster: io.github.prithirajn.trackersync.sync.SffArtifactAi.SffStoryCluster, positive: Boolean) {
        viewModelScope.launch {
            io { io.github.prithirajn.trackersync.sync.SffArtifactAi.recordFeedback(store, cluster, positive) }
            if (positive) {
                notify("Boosted in your SFF feed", "Showing more ${cluster.canonicalTags.take(2).joinToString(" & ")}")
            } else {
                notify("Hidden from feed", "Showing less like this")
            }
        }
    }

    /** Runs an explicit On-Device AI re-ranking pass over the current SFF feed clusters. */
    fun curateSffWithOnDeviceAi() {
        viewModelScope.launch {
            val hasModel = io { io.github.prithirajn.trackersync.ai.Ai.model() != null }
            val picked = io {
                val snap = store.snapshot
                val clusters = io.github.prithirajn.trackersync.sync.SffArtifactAi.clusterAndRank(snap.sffItems, snap)
                val topIds = io.github.prithirajn.trackersync.sync.SffArtifactAi.aiCurateTopPicks(snap, clusters)
                if (!topIds.isNullOrEmpty()) {
                    store.update { s ->
                        s.copy(
                            sffItems = s.sffItems.map { item ->
                                if (item.id in topIds && item.matchedLibraryBook?.startsWith("✨ On-Device AI Pick") != true) item.copy(
                                    matchedLibraryBook = "✨ On-Device AI Pick · " + (item.matchedLibraryBook ?: "Top taste match"),
                                    artifactScore = item.artifactScore + 10.0,
                                ) else item
                            }.sortedByDescending { it.artifactScore }
                        )
                    }
                }
                topIds?.size ?: 0
            }
            if (picked > 0) {
                notify("On-Device AI curated your feed", "Highlighted $picked top stories for your library taste")
            } else if (!hasModel) {
                notify("Artifact Telemetry Ranking active", "Install an on-device .litertlm model in Services → On-device AI for local LLM re-ranking & summaries")
            } else {
                notify("Feed ranked for your library", "Sorted by your SFF taste profile & reading dwell time")
            }
        }
    }

    /** Refreshes the multi-source SFF Radar when stale or when [force] is true. */
    fun refreshSff(force: Boolean = false) {
        if (store.snapshot.sffLoading) return
        viewModelScope.launch {
            val items = io { io.github.prithirajn.trackersync.sync.SffRadar.refresh(store, force = force) }
            if (force) {
                if (items.isNotEmpty()) notify("SFF Radar updated", "${items.size} updates across all sources")
                else store.snapshot.sffError?.let { notify("Couldn't refresh SFF Radar", it, error = true) }
            }
        }
    }

    // ---- SFF tab: visits, reading, reactions, follows, the probe ----

    private fun sffNow() = System.currentTimeMillis() / 1000

    /** The SFF tab was opened: what came in since your last visit counts as new, and the sky catches up. */
    fun sffVisit() {
        viewModelScope.launch {
            io {
                val t = sffNow()
                store.update { it.copy(sffSky = io.github.prithirajn.trackersync.sync.SffChart.tick(it.copy(sffSky = io.github.prithirajn.trackersync.sync.SffChart.visit(it.sffSky, t)), t)) }
            }
        }
    }

    private fun sffUpdate(change: (io.github.prithirajn.trackersync.data.SffSky) -> io.github.prithirajn.trackersync.data.SffSky) {
        viewModelScope.launch { io { store.update { it.copy(sffSky = change(it.sffSky)) } } }
    }

    private fun oneStory(item: io.github.prithirajn.trackersync.data.SffFeedItem) =
        io.github.prithirajn.trackersync.sync.SffArtifactAi.SffStoryCluster("cluster-${item.id}", item, listOf(item), item.tags, "", "", false, 0.0)

    /** Your reaction to a story (tapping the same one takes it back); the warm ones count as "more like this". */
    fun sffReact(item: io.github.prithirajn.trackersync.data.SffFeedItem, emoji: String) {
        val had = store.snapshot.sffSky.reactions[item.id]
        sffUpdate { sky -> sky.copy(reactions = if (had == emoji) sky.reactions - item.id else sky.reactions + (item.id to emoji)) }
        if (had == null && emoji in setOf("❤️", "🔥", "🤯")) {
            viewModelScope.launch { io { io.github.prithirajn.trackersync.sync.SffArtifactAi.recordFeedback(store, oneStory(item), true) } }
        }
    }

    /** Saves a story to read later (or takes it off the list). */
    fun sffSave(item: io.github.prithirajn.trackersync.data.SffFeedItem) {
        val saved = store.snapshot.sffSky.saved.any { it.id == item.id }
        sffUpdate { sky -> sky.copy(saved = if (saved) sky.saved.filter { it.id != item.id } else listOf(item) + sky.saved) }
        notify(if (saved) "Removed from Saved" else "Saved for later")
    }

    /** Follows a sector or topic (or stops following it). */
    fun sffFollow(id: String, label: String) {
        val on = id in store.snapshot.sffSky.followed
        sffUpdate { sky -> sky.copy(followed = if (on) sky.followed - id else sky.followed + id, mutedSectors = sky.mutedSectors - id) }
        notify(if (on) "Stopped following $label" else "Following $label", if (on) null else "More of it in For You")
    }

    /** Mutes a sector (or brings it back). */
    fun sffMute(id: String, label: String) {
        val on = id in store.snapshot.sffSky.mutedSectors
        sffUpdate { sky -> sky.copy(mutedSectors = if (on) sky.mutedSectors - id else sky.mutedSectors + id, followed = sky.followed - id) }
        notify(if (on) "$label is back in your feed" else "Muted $label")
    }

    /** Stops showing a publisher (or shows it again). */
    fun sffBlockSource(source: String) {
        val name = source.substringBefore(" ·")
        val on = name in store.snapshot.sffSky.blockedSources
        sffUpdate { sky -> sky.copy(blockedSources = if (on) sky.blockedSources - name else sky.blockedSources + name) }
        notify(if (on) "Showing $name again" else "Hiding $name", if (on) null else "Undo under Profile → Sources")
    }

    fun sffAlerts(bigNews: Boolean? = null, probeAlerts: Boolean? = null) =
        sffUpdate { sky -> sky.copy(bigNews = bigNews ?: sky.bigNews, probeAlerts = probeAlerts ?: sky.probeAlerts) }

    /** Clears the probe's briefing once you've looked. */
    fun sffDismissBriefing() = sffUpdate { it.copy(briefing = emptyList()) }

    /** Sends the charged probe into [sector] for an hour; it's brought in when it's back (with an alert, if you want one). */
    fun launchSffProbe(sector: io.github.prithirajn.trackersync.sync.SffRadar.Sector) {
        viewModelScope.launch {
            val ok = io {
                val t = sffNow()
                var done = false
                store.update { st ->
                    val next = io.github.prithirajn.trackersync.sync.SffChart.launch(st.sffSky, sector, t)
                    done = next !== st.sffSky
                    st.copy(sffSky = next)
                }
                done
            }
            if (ok) {
                io.github.prithirajn.trackersync.sync.SffProbeWorker.schedule(getApplication(), io.github.prithirajn.trackersync.sync.SffChart.PROBE_TRAVEL_SEC)
                notify("Probe launched into ${sector.title}", "Back in an hour with a briefing")
            } else {
                notify("The probe isn't charged yet", "Read a few pages or stories to charge it")
            }
        }
    }

    /** Brings the probe in, once it's back. */
    fun claimSffProbe() {
        viewModelScope.launch {
            io { io.github.prithirajn.trackersync.sync.SffRadar.claimProbe(store) }
            val sky = store.snapshot.sffSky
            io { store.update { it.copy(sffSky = it.sffSky.copy(notified = (it.sffSky.notified + "probe:${sky.briefingAt}").takeLast(200))) } }
            val where = sky.briefingSector?.let { io.github.prithirajn.trackersync.sync.SffRadar.Sector.byId(it).title } ?: "deep space"
            notify("Probe back from $where", "+${io.github.prithirajn.trackersync.sync.SffChart.PROBE_XP} XP · ${sky.briefing.size} finds in your briefing")
        }
    }

    /**
     * 1-tap shelves a book from the SFF Radar or Star-Chart Probe as "Want to Read" in your
     * Library and queues it for Seekquel, Hardcover, and all enabled trackers.
     */
    fun shelfSffItem(item: io.github.prithirajn.trackersync.data.SffFeedItem) {
        viewModelScope.launch {
            val existing = store.snapshot.books.values.firstOrNull {
                Matcher.baseTitle(it.displayTitle) == Matcher.baseTitle(item.title)
            }
            if (existing != null) {
                AppActions.pending.value = AppAction.OpenBook(existing.document)
                return@launch
            }
            val error = try {
                io {
                    val d = ManualBooks.add(
                        store,
                        ManualBooks.Pick(
                            title = item.title,
                            author = item.author,
                            coverUrl = item.coverUrl,
                            pages = item.pages,
                            fraction = 0.0,
                            status = ReadStatus.TO_READ,
                        ),
                    )
                    // Pre-populate BookFacts if the SFF card already has series/tags/rating
                    if (item.series != null || item.tags.isNotEmpty() || item.rating != null) {
                        val f = io.github.prithirajn.trackersync.data.BookFacts(
                            series = item.series,
                            seriesNumber = item.seriesNumber,
                            rating = item.rating,
                            ratingsCount = item.ratingsCount,
                            year = item.year,
                            genres = item.tags,
                            sources = listOf(item.source),
                            at = System.currentTimeMillis() / 1000,
                        )
                        store.update { s -> s.copy(facts = s.facts + (d to f)) }
                    }
                    // Link directly to Hardcover or Seekquel if the item came from there
                    val extId = item.externalId
                    if (!extId.isNullOrBlank()) {
                        val rb = RemoteBook(extId, item.title, item.author, coverUrl = item.coverUrl, pages = item.pages, year = item.year)
                        if (item.source.contains("Hardcover", ignoreCase = true) && store.snapshot.service(HardcoverTracker.id).linked) {
                            store.putLink(SyncEngine.linkFor(HardcoverTracker.id, d, rb, LinkSource.MANUAL))
                        } else if (item.source.contains("Seekquel", ignoreCase = true) && store.snapshot.service(SeekquelTracker.id).linked) {
                            val svc = store.snapshot.service(SeekquelTracker.id)
                            val session = Session(svc.credential).also { it.visibility = svc.visibility }
                            runCatching { SeekquelTracker.linkToBook(session, d, item.title, item.author, rb, 0) }
                            if (session.changed) store.updateService(SeekquelTracker.id) { it.copy(credential = session.encode()) }
                            store.putLink(SyncEngine.linkFor(SeekquelTracker.id, d, rb, LinkSource.MANUAL).copy(externalId = d, externalUuid = extId))
                        }
                    }
                    for (t in Trackers.all) {
                        val svc = store.snapshot.service(t.id)
                        if (svc.linked && svc.enabled) SyncEngine.queue(store, t.id, listOf(d))
                    }
                }
                null
            } catch (e: Exception) {
                e.message ?: "Couldn't shelf the book"
            }
            if (error == null) {
                notify("Added to Want to Read", "${item.title} · syncing to your trackers")
                syncNow(quiet = true)
            } else {
                notify("Couldn't add book", error, error = true)
            }
        }
    }

    // ---- New & upcoming ----

    fun setReleaseSettings(change: (io.github.prithirajn.trackersync.data.ReleaseSettings) -> io.github.prithirajn.trackersync.data.ReleaseSettings) {
        viewModelScope.launch { io { store.update { it.copy(releaseSettings = change(it.releaseSettings)) }; io.github.prithirajn.trackersync.sync.Releases.tidy(store) } }
    }

    /** Stops showing a series' news (it can be shown again from the settings). */
    fun hideReleaseSeries(series: String) {
        viewModelScope.launch {
            io {
                val base = io.github.prithirajn.trackersync.sync.Matcher.baseTitle(series)
                store.update { it.copy(releasesHidden = it.releasesHidden + base) }
                io.github.prithirajn.trackersync.sync.Releases.tidy(store)
            }
            notify("Hidden", "$series won't show here. Show it again from New & upcoming's settings.")
        }
    }

    fun unhideReleaseSeries(base: String) {
        viewModelScope.launch { io { store.update { it.copy(releasesHidden = it.releasesHidden - base, releasesChecked = it.releasesChecked - ("series:$base")) } } }
    }

    /** Looks at every series again now, instead of waiting for its daily turn. */
    fun checkReleasesNow(done: (Int) -> Unit) {
        viewModelScope.launch {
            val n = io {
                store.update { it.copy(releasesChecked = emptyMap()) }
                var total = 0
                // A few rounds, each within its time limit, until every series has had its look.
                repeat(4) { total += runCatching { io.github.prithirajn.trackersync.sync.Releases.check(store) }.getOrDefault(0) }
                total
            }
            done(n)
        }
    }

    // ---- Comics ----

    /**
     * Sets which issues each volume of a comic holds, as you know them ([first issue, last issue]
     * per volume, in order); null goes back to the trackers' or the estimate. Volumes after your
     * last one are taken to be the same size.
     */
    fun setComicVolumes(key: String, ranges: List<Pair<Double, Double>>?, done: (String?) -> Unit = {}) {
        val clean = ranges?.filter { (a, b) -> a > 0 && b >= a && b - a <= 100 }
        if (ranges != null && (clean.isNullOrEmpty() || clean.size != ranges.size)) { done("Each volume needs a first and last issue, the last not before the first"); return }
        if (clean != null && clean.zipWithNext().any { (x, y) -> y.first <= x.second }) { done("Volumes can't share issues; each starts after the one before ends"); return }
        viewModelScope.launch {
            io {
                store.update { st ->
                    val title = st.comicMaps[key]?.title ?: st.mangaMaps[key]?.title ?: ""
                    if (clean == null) st.copy(comicCustom = st.comicCustom - key, comicMaps = st.comicMaps - key, comicMissing = st.comicMissing - key, mangaMissing = st.mangaMissing - key)
                    else st.copy(
                        comicCustom = st.comicCustom + (key to io.github.prithirajn.trackersync.source.ComicCollections.Map(
                            title,
                            clean.mapIndexed { i, (a, b) ->
                                io.github.prithirajn.trackersync.source.ComicCollections.Volume((i + 1).toString(), generateSequence(a) { it + 1 }.takeWhile { it <= b + 1e-9 }.toList())
                            },
                            io.github.prithirajn.trackersync.source.ComicCollections.YOURS, System.currentTimeMillis() / 1000,
                        )),
                        comicPerVolume = st.comicPerVolume - key,
                    )
                }
                SyncEngine(store).pullManga()
            }
            done(null)
            notify(if (clean == null) "Volumes reset" else "Volumes saved", if (clean == null) "Looked up again on the next sync." else "Your library follows them now.")
            syncNow(quiet = true)
        }
    }

    /** Pages in each chapter or issue of a series (null: back to the usual), for the one you're part-way through. */
    fun setPagesPer(key: String, pages: Int?) {
        viewModelScope.launch {
            io {
                store.update { st -> st.copy(pagesPer = if (pages == null || pages <= 0) st.pagesPer - key else st.pagesPer + (key to pages.coerceIn(1, 500))) }
                SyncEngine(store).pullManga()
            }
        }
    }

    private val coversLooking = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Looks up the covers of a comic volume's single issues (once each; ones not found aren't asked again). */
    fun loadIssueCovers(series: String, issues: List<Double>) {
        val base = io.github.prithirajn.trackersync.sync.Matcher.baseTitle(series)
        val todo = issues.map { n -> "$base|${io.github.prithirajn.trackersync.sync.ComicIssues.label(n)}" to n }
            .filter { (k, _) -> k !in store.snapshot.issueCovers && coversLooking.add(k) }
        if (todo.isEmpty()) return
        viewModelScope.launch {
            io {
                val hc = store.snapshot.service("hardcover").takeIf { it.linked && !it.needsReauth }
                val search: ((String) -> List<io.github.prithirajn.trackersync.trackers.RemoteBook>)? = hc?.let { svc ->
                    { q: String -> io.github.prithirajn.trackersync.trackers.HardcoverTracker.search(io.github.prithirajn.trackersync.trackers.Session(svc.credential), q, null) }
                }
                for ((k, n) in todo) {
                    val url = runCatching { io.github.prithirajn.trackersync.source.IssueCovers.find(series, n, search) }.getOrNull()
                    store.update { it.copy(issueCovers = it.issueCovers + (k to (url ?: ""))) }
                    coversLooking.remove(k)
                }
            }
        }
    }

    /**
     * The series a manga or comic book in the library belongs to: (the key its volumes are kept
     * under, its title, whether it's a comic). Null for other books.
     */
    fun seriesOf(book: io.github.prithirajn.trackersync.data.Book): Triple<String, String, Boolean>? {
        val docs = listOf(book.document) + book.aliases
        val comic = docs.any { it.startsWith("comic:") }
        if (!comic && docs.none { it.startsWith("manga:") }) return null
        val title = io.github.prithirajn.trackersync.sync.MangaSources.seriesTitleOf(book.title ?: return null)
        val s = store.snapshot
        val base = io.github.prithirajn.trackersync.sync.Matcher.baseTitle(title)
        val key = s.comicMaps.entries.firstOrNull { io.github.prithirajn.trackersync.sync.Matcher.baseTitle(it.value.title) == base }?.key
            ?: s.mangaMaps.entries.firstOrNull { io.github.prithirajn.trackersync.sync.Matcher.baseTitle(it.value.title) == base }?.key
            ?: ("-3:" + io.github.prithirajn.trackersync.sync.MangaSources.loggedKey(title).removePrefix("logged:"))
        return Triple(key, title, comic)
    }

    /** Sets how many issues each collected volume of a comic holds (null: back to Hardcover's or the usual six), then re-reads. */
    fun setComicPerVolume(key: String, per: Int?) {
        viewModelScope.launch {
            io {
                store.update { st ->
                    st.copy(
                        comicPerVolume = if (per == null) st.comicPerVolume - key else st.comicPerVolume + (key to per.coerceIn(1, 50)),
                        comicMaps = if (per == null) st.comicMaps - key else st.comicMaps,
                        comicMissing = st.comicMissing - key,
                    )
                }
                SyncEngine(store).pullManga()
            }
        }
    }

    // ---- AniList ----

    /** Keeps the API client id you registered on AniList, ready for sign-in. */
    fun setAniListClient(id: String) {
        viewModelScope.launch { io { store.update { it.copy(anilistClientId = id.trim().takeIf { s -> s.isNotEmpty() }) } } }
    }

    /** Checks a token AniList gave and links the account with it. */
    fun linkAniList(token: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val t = token.trim()
                if (!io.github.prithirajn.trackersync.source.AniList.looksLikeToken(t)) throw IllegalArgumentException("That isn't an AniList token; copy the whole long text AniList shows.")
                val me = io { io.github.prithirajn.trackersync.source.AniList.viewer(t) }
                io {
                    store.update {
                        it.copy(
                            anilist = io.github.prithirajn.trackersync.data.AniListAccount(it.anilistClientId.orEmpty(), t, me.id, me.name, read = it.anilist?.read ?: true, send = it.anilist?.send ?: true),
                            anilistOkAt = 0L, anilistError = null,
                        )
                    }
                }
                done(null)
                notify("AniList linked", "Signed in as ${me.name}. Reading your manga list.")
                io { SyncEngine(store).pullManga() }
                syncNow(quiet = true)
            } catch (e: Exception) {
                done(e.message ?: "Couldn't sign in to AniList")
            }
        }
    }

    fun setAniListOptions(read: Boolean? = null, send: Boolean? = null) {
        viewModelScope.launch {
            io { store.update { st -> st.anilist?.let { a -> st.copy(anilist = a.copy(read = read ?: a.read, send = send ?: a.send)) } ?: st } }
        }
    }

    fun checkAniList() {
        viewModelScope.launch {
            io { SyncEngine(store).pullManga() }
            Health.anilist(store.snapshot)?.let { notify(it.title, it.detail, error = it.level == Health.Level.BAD) }
        }
    }

    fun removeAniList() {
        viewModelScope.launch {
            io { store.update { it.copy(anilist = null, anilistOkAt = 0L, anilistError = null, alSent = emptyMap()) } }
            notify("AniList disconnected", "Nothing was changed on AniList; your volumes remain in your library.")
        }
    }

    // ---- Audible ----

    /** Amazon's sign-in page for [store], set up to register this phone as an Audible device. */
    fun startAudibleSignIn(store: io.github.prithirajn.trackersync.source.AudibleClient.Companion.Store) =
        io.github.prithirajn.trackersync.source.AudibleClient.startSignIn(store)

    fun finishAudibleSignIn(signIn: io.github.prithirajn.trackersync.source.AudibleClient.Companion.SignIn, code: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val acct = io { io.github.prithirajn.trackersync.source.AudibleClient.finishSignIn(signIn, code) }
                io { store.update { it.copy(audible = acct, audibleOkAt = 0L, audibleError = null) } }
                done(null)
                notify("Audible linked", "Bringing in what you're listening to.")
                syncNow(quiet = false)
            } catch (e: Exception) {
                done(e.message ?: "Couldn't link Audible")
            }
        }
    }

    /** Unlinks Audible, and removes this phone from your Audible devices. */
    fun removeAudible() {
        viewModelScope.launch {
            val acct = store.snapshot.audible
            io {
                acct?.let { runCatching { io.github.prithirajn.trackersync.source.AudibleClient(it).deregister() } }
                store.update { it.copy(audible = null, audibleOkAt = 0L, audibleError = null) }
            }
            notify("Audible disconnected", "Your books remain in your library.")
        }
    }

    /** The Audible health light: checks your library live. */
    fun checkAudible() {
        viewModelScope.launch {
            io { SyncEngine(store).pullAudible() }
            Health.audible(store.snapshot)?.let { notify(it.title, it.detail, error = it.level == Health.Level.BAD) }
        }
    }

    // ---- Libby (OverDrive) ----

    fun startLibbySetupCode(done: (LibbyClient.SetupCodeSession?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val session = io { LibbyClient.startSetupCode() }
                done(session, null)
            } catch (e: Exception) {
                done(null, e.message ?: "Couldn't get a Libby setup code")
            }
        }
    }

    fun pollLibbySetupCode(session: LibbyClient.SetupCodeSession, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val account = io {
                runCatching { LibbyClient.pollSetupCode(session) }.getOrNull()
                    // Once Libby has copied itself over, this setup has library cards of its own:
                    // a fresh sign-in for it and a look at the shelf shows that, whatever the
                    // code check says.
                    ?: runCatching {
                        val (chip, identity) = LibbyClient.acquireChip(session.identity, session.chip)
                        LibbyClient(io.github.prithirajn.trackersync.data.LibbyAccount(identity = identity, chip = chip))
                            .fetchLibrary().account.takeIf { !it.name.isNullOrBlank() }
                    }.getOrNull()
            }
            if (account != null) {
                io { store.update { it.copy(libby = account, libbyOkAt = 0L, libbyError = null) } }
                done(true)
                notify("Libby linked", "Bringing in your Libby loans.")
                syncNow(quiet = false)
            } else {
                done(false)
            }
        }
    }

    fun connectLibbyWithInput(input: String, chipHint: String? = null, done: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val account = io { LibbyClient.connectWithToken(input, chipHint) }
                io { store.update { it.copy(libby = account, libbyOkAt = 0L, libbyError = null) } }
                done(null)
                notify("Libby linked", "Bringing in your Libby loans.")
                syncNow(quiet = false)
            } catch (e: Exception) {
                done(e.message ?: "Couldn't link your Libby account")
            }
        }
    }

    fun removeLibby() {
        viewModelScope.launch {
            io { store.update { it.copy(libby = null, libbyOkAt = 0L, libbyError = null, libbyClips = emptyList(), libbyNoPages = emptySet()) } }
            notify("Libby disconnected", "Your books remain in your library.")
        }
    }

    fun setLibbyHighlights(sync: Boolean) {
        viewModelScope.launch {
            io { store.update { s -> s.copy(libby = s.libby?.copy(syncHighlights = sync)) } }
        }
    }

    fun setLibbyBorrowedTag(sync: Boolean) {
        viewModelScope.launch {
            io { store.update { s -> s.copy(libby = s.libby?.copy(syncBorrowedTag = sync)) } }
            if (sync) syncNow(quiet = false)
        }
    }

    /** The Libby health light: checks the Libby shelf live and pulls in any new loans. */
    fun checkLibby() {
        viewModelScope.launch {
            io { SyncEngine(store).pullLibby() }
            Health.libby(store.snapshot)?.let { notify(it.title, it.detail, error = it.level == Health.Level.BAD) }
        }
    }

    /** Opens Libby Reading Journey exports (.json): position, highlights and borrow dates. */
    fun importLibbyJourney(uris: List<android.net.Uri>, done: (String?) -> Unit) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                val r = io {
                    val texts = uris.map { u ->
                        getApplication<Application>().contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) }
                            ?: throw io.github.prithirajn.trackersync.net.ServiceException("Couldn't open that file")
                    }
                    io.github.prithirajn.trackersync.sync.LibbyJourney.import(store, texts)
                }
                done(null)
                notify(
                    "Libby journey read",
                    buildString {
                        append("${r.books} book${if (r.books == 1) "" else "s"}")
                        if (r.added > 0) append(", ${r.added} new")
                        if (r.highlights > 0) append(" · ${r.highlights} highlight${if (r.highlights == 1) "" else "s"}")
                    },
                )
                syncNow(quiet = true)
            } catch (e: Exception) {
                done(e.message ?: "Couldn't read that file")
            }
        }
    }

    /** The KOSync health light: checks the sign-in live, then explains the light. */
    fun checkKosync() {
        viewModelScope.launch {
            io {
                val account = store.snapshot.kosync ?: return@io
                try {
                    if (KosyncClient(account).verify()) {
                        store.update { it.copy(kosyncOkAt = System.currentTimeMillis(), kosyncError = null) }
                    } else {
                        store.update { it.copy(kosyncError = Health.describeKosync(AuthException("rejected"))) }
                    }
                } catch (e: Exception) {
                    store.update { it.copy(kosyncError = Health.describeKosync(e)) }
                }
            }
            Health.kosync(store.snapshot)?.let { notify(it.title, it.detail, error = it.level == Health.Level.BAD) }
        }
    }

    fun syncNow(quiet: Boolean = false) {
        viewModelScope.launch {
            // Told as soon as your trackers are up to date; the extras finish quietly after.
            var told = false
            val result = io {
                SyncEngine(store).runThen { r ->
                    told = true
                    if (!quiet) viewModelScope.launch { notify(r.message, error = r.needsAttention.isNotEmpty()) }
                }
            }
            if (!quiet && !told) notify(result.message, error = result.needsAttention.isNotEmpty())
        }
    }

    // ---- trackers ----

    fun linkTracker(tracker: Tracker, signIn: () -> SignInResult, done: (String?) -> Unit) {
        viewModelScope.launch {
            val error = try {
                saveLink(tracker, io(signIn))
                null
            } catch (e: Exception) {
                e.message ?: "Sign-in failed"
            }
            done(error)
        }
    }

    private suspend fun saveLink(tracker: Tracker, result: SignInResult) {
        io {
            store.updateService(tracker.id) {
                // Private journals take highlights by default; public posts stay opt-in.
                val journal = tracker.highlights == HighlightSupport.PRIVATE_JOURNAL
                it.copy(linked = true, enabled = true, credential = result.credential, account = result.account,
                    needsReauth = false, lastError = null,
                    highlights = if (it.linked) it.highlights else journal,
                    highlightsSince = if (it.highlightsSince > 0) it.highlightsSince else lastWeek())
            }
            // Books read in the last week go to the new tracker right away.
            SyncEngine.queue(store, tracker.id)
        }
        notify("${tracker.name} linked", "Signed in as ${result.account}")
        syncNow(quiet = true)
    }

    private var pairing: Job? = null

    /**
     * Seekquel-style pairing: gets a code, shows it ([onCode]), then polls until it's approved
     * in the tracker's app, declined, or expired. [done] gets null on success, else a message.
     */
    fun startDeviceLink(tracker: Tracker, onCode: (DeviceLink) -> Unit, done: (String?) -> Unit) {
        pairing?.cancel()
        pairing = viewModelScope.launch {
            val error = try {
                val link = io { tracker.beginDeviceLink() }
                onCode(link)
                val deadline = System.currentTimeMillis() + link.expiresInSeconds * 1000L
                var outcome: String? = "The code expired. Start again."
                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(link.intervalSeconds.coerceAtLeast(2) * 1000L)
                    val poll = try {
                        io { tracker.pollDeviceLink(link.deviceCode) }
                    } catch (e: Exception) {
                        DevicePoll.Pending // a dropped poll isn't a failure; try again
                    }
                    when (poll) {
                        is DevicePoll.Linked -> { saveLink(tracker, poll.result); outcome = null; break }
                        is DevicePoll.Failed -> { outcome = poll.message; break }
                        DevicePoll.Pending -> Unit
                    }
                }
                outcome
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Couldn't start linking"
            }
            done(error)
        }
    }

    fun cancelDeviceLink() {
        pairing?.cancel()
        pairing = null
    }

    fun unlinkTracker(tracker: Tracker) {
        viewModelScope.launch {
            io {
                SyncEngine.forgetImported(store, tracker.id)
                store.update { s ->
                    s.copy(
                        services = s.services - tracker.id,
                        links = s.links.filterValues { it.service != tracker.id },
                        pending = s.pending.filterValues { it.service != tracker.id },
                    )
                }
            }
            notify("${tracker.name} unlinked")
        }
    }

    fun setEnabled(tracker: Tracker, enabled: Boolean) {
        viewModelScope.launch { io { store.updateService(tracker.id) { it.copy(enabled = enabled) } } }
    }

    fun setVisibility(tracker: Tracker, visibility: Visibility) {
        viewModelScope.launch { io { store.updateService(tracker.id) { it.copy(visibility = visibility) } } }
    }

    /** Turning highlights on sends the last week's, then everything new; never older ones. */
    fun setHighlights(tracker: Tracker, on: Boolean) {
        viewModelScope.launch {
            io {
                store.updateService(tracker.id) {
                    // Posts others see (Fable) start from now, so turning it on doesn't post a week of old highlights.
                    val since = if (tracker.highlightsIgnoreVisibility) System.currentTimeMillis() / 1000 else lastWeek()
                    it.copy(highlights = on, highlightsSince = if (on && !it.highlights) since else it.highlightsSince)
                }
            }
            if (on) syncNow(quiet = true)
        }
    }

    /** Brings in (or, switched off, removes) the books the user is reading on [tracker]. */
    fun setImportReading(tracker: Tracker, on: Boolean) {
        viewModelScope.launch {
            io {
                store.updateService(tracker.id) { it.copy(importReading = on) }
                if (!on) SyncEngine.forgetImported(store, tracker.id)
            }
            if (on) syncNow(quiet = true)
        }
    }

    private fun lastWeek(): Long = System.currentTimeMillis() / 1000 - SyncEngine.RECENT_DAYS * SyncEngine.DAY

    fun syncTracker(tracker: Tracker) {
        viewModelScope.launch {
            val queued = io { SyncEngine.queue(store, tracker.id).pending.values.count { it.service == tracker.id } }
            notify("Syncing to ${tracker.name}", if (queued == 0) "No books read in the last week" else "$queued book${if (queued == 1) "" else "s"} queued")
            syncNow(quiet = true)
        }
    }

    // ---- per-book links ----

    /**
     * Searches [tracker] for picking a match by hand. Editions already on the user's shelves
     * there are marked and listed first, including a shelved edition of [title] that the
     * search itself didn't return.
     */
    fun search(tracker: Tracker, query: String, title: String?, author: String?, done: (List<RemoteBook>?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val results = io {
                    val svc = store.snapshot.service(tracker.id)
                    val session = Session(svc.credential)
                    // Nothing for what was typed: try it without [tags], then the title before a colon, then without the author.
                    val tries = listOf(
                        query,
                        Matcher.plainTitle(query),
                        Matcher.plainTitle(query).substringBefore(":").trim(),
                        title?.let { Matcher.plainTitle(it).substringBefore(":").trim() },
                    ).filterNotNull().filter { it.length >= 2 }.distinct()
                    var found: List<RemoteBook> = emptyList()
                    for (q in tries) {
                        found = tracker.search(session, q, null)
                        if (found.isNotEmpty()) break
                    }
                    val shelf = runCatching { tracker.shelfCandidates(session) }.getOrDefault(emptyList())
                    if (session.changed) store.updateService(tracker.id) { it.copy(credential = session.encode()) }
                    val shelfIds = shelf.map { it.id }.toSet()
                    val foundIds = found.map { it.id }.toSet()
                    val shelvedToo = if (title == null) emptyList() else shelf.filter {
                        it.id !in foundIds && (Matcher.score(title, author, it) >= 0.6 || Matcher.sameBook(title, author, it.title, it.author))
                    }
                    (shelvedToo + found)
                        .map { if (it.id in shelfIds) it.copy(onShelf = true) else it }
                        .sortedByDescending { it.onShelf }
                }
                done(results, null)
            } catch (e: Exception) {
                done(null, e.message ?: "Search failed")
            }
        }
    }

    // ---- On-device AI ----

    /** The model file in use (name, size in bytes), or null when none is set up. */
    fun aiModel(): Pair<String, Long>? = io.github.prithirajn.trackersync.ai.LocalModel.file(getApplication<android.app.Application>())?.let { it.name to it.length() }

    /** Whether the AI helpers can be offered (a model is set up). */
    fun aiReady(): Boolean = aiModel() != null

    /** Copies the chosen model file into the app, then loads it once to check it works on this phone. */
    fun installModel(uri: android.net.Uri, name: String, onProgress: (Float) -> Unit, done: (String?) -> Unit) {
        viewModelScope.launch {
            val error = io {
                // The model already in use stays if the new one can't be fetched or added.
                val before = io.github.prithirajn.trackersync.ai.LocalModel.file(getApplication<android.app.Application>())?.path
                runCatching {
                    io.github.prithirajn.trackersync.ai.LocalModel.install(getApplication<android.app.Application>(), uri, name) { p -> onProgress(p) }
                    // Loading it now shows straight away whether this phone can run it.
                    io.github.prithirajn.trackersync.ai.LocalModel.get(getApplication<android.app.Application>()) ?: throw IllegalStateException("The model didn't load")
                    Unit
                }.exceptionOrNull()?.let { e ->
                    // A new model that won't load on this phone isn't kept.
                    val now = io.github.prithirajn.trackersync.ai.LocalModel.file(getApplication<android.app.Application>())?.path
                    if (now != null && now != before) io.github.prithirajn.trackersync.ai.LocalModel.remove(getApplication<android.app.Application>())
                    e.message ?: "That model can't run on this phone"
                }
            }
            if (error == null) notify("On-device AI is ready", name) else notify("Couldn't use that model", error, error = true)
            done(error)
        }
    }

    /** Downloads a ready-made model into the app, then loads it once to check it works on this phone. */
    fun downloadModel(choice: io.github.prithirajn.trackersync.ai.LocalModel.Choice, onProgress: (Float) -> Unit, done: (String?) -> Unit) {
        viewModelScope.launch {
            val error = io {
                // The model already in use stays if the new one can't be fetched or added.
                val before = io.github.prithirajn.trackersync.ai.LocalModel.file(getApplication<android.app.Application>())?.path
                runCatching {
                    io.github.prithirajn.trackersync.ai.LocalModel.download(getApplication<android.app.Application>(), choice) { p -> onProgress(p) }
                    io.github.prithirajn.trackersync.ai.LocalModel.get(getApplication<android.app.Application>()) ?: throw IllegalStateException("The model didn't load")
                    Unit
                }.exceptionOrNull()?.let { e ->
                    // A model that won't run here isn't kept (the licence message keeps nothing either).
                    // A new model that won't load on this phone isn't kept.
                    val now = io.github.prithirajn.trackersync.ai.LocalModel.file(getApplication<android.app.Application>())?.path
                    if (now != null && now != before) io.github.prithirajn.trackersync.ai.LocalModel.remove(getApplication<android.app.Application>())
                    e.message ?: "Couldn't download that model"
                }
            }
            if (error == null) notify("On-device AI is ready", choice.name) else notify("Couldn't use ${choice.name}", error, error = true)
            done(error)
        }
    }

    fun removeModel(done: () -> Unit = {}) {
        viewModelScope.launch {
            io { io.github.prithirajn.trackersync.ai.LocalModel.remove(getApplication<android.app.Application>()) }
            notify("On-device AI removed")
            done()
        }
    }

    /** Runs [block] with the model off the main thread; [done] gets its answer, or why there's none. */
    private fun <T> withModel(none: String, block: (io.github.prithirajn.trackersync.ai.TextModel) -> T?, done: (T?, String?) -> Unit) {
        viewModelScope.launch {
            val result: Pair<T?, String?> = io {
                val m = runCatching { io.github.prithirajn.trackersync.ai.Ai.model() }.getOrNull()
                if (m == null) {
                    Pair<T?, String?>(null, "Set up a model under Services → On-device AI first")
                } else {
                    val r = runCatching { block(m) }
                    val v = r.getOrNull()
                    if (v != null) Pair<T?, String?>(v, null)
                    else Pair<T?, String?>(null, r.exceptionOrNull()?.message?.let { "The model couldn't answer ($it)" } ?: none)
                }
            }
            done(result.first, result.second)
        }
    }

    /** The AI's pick for a book the rules couldn't match on [trackerId], when there is one (not dismissed). */
    fun aiSuggestion(trackerId: String, document: String): io.github.prithirajn.trackersync.data.AiSuggestion? =
        store.snapshot.aiSuggestions[io.github.prithirajn.trackersync.data.Link.key(trackerId, document)]?.takeIf { it.id.isNotEmpty() }

    fun acceptSuggestion(tracker: Tracker, document: String) {
        val key = io.github.prithirajn.trackersync.data.Link.key(tracker.id, document)
        val sug = store.snapshot.aiSuggestions[key]?.takeIf { it.id.isNotEmpty() } ?: return
        linkBook(tracker, document, RemoteBook(sug.id, sug.title, sug.author, uuid = sug.uuid, coverUrl = sug.coverUrl))
    }

    /** Not that one: hidden, and not suggested again for this book on this tracker. */
    fun dismissSuggestion(trackerId: String, document: String) {
        val key = io.github.prithirajn.trackersync.data.Link.key(trackerId, document)
        viewModelScope.launch {
            io { store.update { s -> s.copy(aiSuggestions = s.aiSuggestions + (key to io.github.prithirajn.trackersync.data.AiSuggestion("", "", null))) } }
        }
    }

    /** A tidied description and a two-sentence one, to choose from. */
    fun aiDescriptions(document: String, done: (Pair<String?, String?>?, String?) -> Unit) {
        val b = store.snapshot.books[document]
        val text = b?.description ?: run { done(null, "This book has no description"); return }
        withModel("The model couldn't tidy this description", { m ->
            val tidy = io.github.prithirajn.trackersync.ai.Assistant.tidyDescription(m, text)
            val short = io.github.prithirajn.trackersync.ai.Assistant.shortDescription(m, b.displayTitle, text)
            if (tidy == null && short == null) null else tidy to short
        }, done)
    }

    /** Uses [text] as the book's description (shown here, and sent when adding the book to a tracker). */
    fun useDescription(document: String, text: String, how: String) {
        viewModelScope.launch {
            io { store.update { s -> s.books[document]?.let { b -> s.copy(books = s.books + (document to b.copy(description = text, descriptionSource = how))) } ?: s } }
        }
    }

    fun aiSummarizeHighlights(document: String, done: (io.github.prithirajn.trackersync.ai.Assistant.HighlightSummary?, String?) -> Unit) {
        val b = store.snapshot.books[document] ?: return
        val clips = io.github.prithirajn.trackersync.sync.Highlights.forBook(store.snapshot, document).map { it.clip }.sortedBy { it.percent ?: 0.0 }
        withModel("The model couldn't summarise these highlights", { m -> io.github.prithirajn.trackersync.ai.Assistant.summarizeHighlights(m, b, clips) }, done)
    }

    fun aiExpandNote(document: String, quote: String, note: String, done: (String?, String?) -> Unit) {
        val title = store.snapshot.books[document]?.displayTitle ?: "this book"
        withModel("The model couldn't write that out", { m -> io.github.prithirajn.trackersync.ai.Assistant.expandNote(m, title, quote, note) }, done)
    }

    fun aiDraftReview(document: String, rating: Double?, done: (String?, String?) -> Unit) {
        val b = store.snapshot.books[document] ?: return
        val clips = io.github.prithirajn.trackersync.sync.Highlights.forBook(store.snapshot, document).map { it.clip }.sortedBy { it.percent ?: 0.0 }
        withModel("The model couldn't draft a review", { m -> io.github.prithirajn.trackersync.ai.Assistant.draftReview(m, b, rating, clips) }, done)
    }

    fun aiFindPassage(pageText: String, hint: String, done: (String?, String?) -> Unit) =
        withModel("Couldn't find that passage on the page", { m -> io.github.prithirajn.trackersync.ai.Assistant.findPassage(m, pageText, hint) }, done)

    fun aiCleanScan(text: String, done: (String?, String?) -> Unit) =
        withModel("The model couldn't clean this up without changing the words", { m -> io.github.prithirajn.trackersync.ai.Assistant.cleanScan(m, text) }, done)

    /** Which sentences of a review give the story away (indexes into its sentences); empty when none. */
    fun aiSpoilers(document: String, review: String, done: (List<Int>?, String?) -> Unit) {
        val title = store.snapshot.books[document]?.displayTitle ?: "this book"
        withModel("The model couldn't check this review", { m -> io.github.prithirajn.trackersync.ai.Assistant.spoilerSentences(m, title, review) }, done)
    }

    /** A messy file title and author, as a clean title, author and series. */
    fun aiTidyTitle(title: String, author: String, done: (io.github.prithirajn.trackersync.ai.Assistant.CleanTitle?, String?) -> Unit) =
        withModel("Nothing to tidy, or the model's answer added words that weren't there", { m ->
            io.github.prithirajn.trackersync.ai.Assistant.tidyTitle(m, title, author.ifBlank { null })
        }, done)

    /** Where you were in a book you're coming back to, from your highlights so far. */
    fun aiCatchUp(document: String, done: (String?, String?) -> Unit) {
        val b = store.snapshot.books[document] ?: return
        val clips = io.github.prithirajn.trackersync.sync.Highlights.forBook(store.snapshot, document).map { it.clip }
        val days = b.timestamp.takeIf { it > 0 }?.let { ((System.currentTimeMillis() / 1000 - it) / 86_400).toInt() }
        withModel("The model couldn't put that together", { m -> io.github.prithirajn.trackersync.ai.Assistant.catchUp(m, b, clips, days) }, done)
    }

    /** This month in reading, as a few sentences to share. */
    fun aiMonthRecap(done: (String?, String?) -> Unit) {
        val s = store.snapshot
        val m0 = io.github.prithirajn.trackersync.sync.Shelves.month()
        fun inMonth(t: Long?) = t != null && t > 0 && io.github.prithirajn.trackersync.sync.Shelves.month(t * 1000) == m0
        val books = s.books.values.filter { !it.held }
        val finished = books.filter { it.status == ReadStatus.FINISHED && inMonth(it.finishedAt ?: it.timestamp) }
        val reading = books.filter { it.status == ReadStatus.READING && inMonth(it.timestamp) }.sortedByDescending { it.timestamp }
        val pages = io.github.prithirajn.trackersync.sync.Shelves.pagesThisMonth(s, null)
        val highlights = (s.cpClips + s.paperClips + s.kindleClips + s.libbyClips + s.moonClips + s.historyClips + s.koClips).filter { it.id !in s.clipHidden && inMonth(it.createdAt) }.distinctBy { it.id }.size
        withModel("The model couldn't write a recap that kept to your numbers", { m ->
            io.github.prithirajn.trackersync.ai.Assistant.monthRecap(m, io.github.prithirajn.trackersync.sync.Shelves.monthName(), finished, reading, pages, highlights)
        }, done)
    }

    /** Up to three books from your to-read and paused shelves, fitting what you've loved lately. */
    fun aiNextReads(done: (List<io.github.prithirajn.trackersync.ai.Assistant.NextRead>?, String?) -> Unit) {
        val books = store.snapshot.books.values
        val candidates = books.filter { it.status == ReadStatus.TO_READ || it.status == ReadStatus.PAUSED }
            .sortedByDescending { it.timestamp }
        if (candidates.isEmpty()) { done(null, "Nothing on your to-read or paused shelves to choose from"); return }
        val loved = books.filter { it.status == ReadStatus.FINISHED && (it.rating ?: 0.0) >= 4.0 }
            .sortedByDescending { it.finishedAt ?: it.timestamp }
            .ifEmpty { books.filter { it.status == ReadStatus.FINISHED }.sortedByDescending { it.finishedAt ?: it.timestamp }.take(5) }
        withModel("The model couldn't pick from your shelves", { m -> io.github.prithirajn.trackersync.ai.Assistant.nextReads(m, candidates, loved) }, done)
    }

    /** An answer about your reading, from your library and highlights, worked out on the phone. */
    fun aiAsk(question: String, done: (String?, String?) -> Unit) {
        val s = store.snapshot
        val clips = (s.cpClips + s.paperClips + s.kindleClips + s.libbyClips + s.moonClips + s.historyClips + s.koClips).filter { it.id !in s.clipHidden }.distinctBy { it.id }
        withModel("The model couldn't answer that", { m -> io.github.prithirajn.trackersync.ai.Assistant.ask(m, question, s.books.values, clips) }, done)
    }

    fun linkBook(tracker: Tracker, document: String, book: RemoteBook) {
        viewModelScope.launch {
            val error = try {
                io {
                    // A match chosen now replaces any AI suggestion for it.
                    store.update { s -> s.copy(aiSuggestions = s.aiSuggestions - io.github.prithirajn.trackersync.data.Link.key(tracker.id, document)) }
                    if (tracker.matchesByDocument) {
                        // Seekquel keeps the book under CrossPoint's id: tell it which book that is.
                        val svc = store.snapshot.service(tracker.id)
                        val session = Session(svc.credential).also { it.visibility = svc.visibility }
                        val b = store.snapshot.books[document]
                        tracker.linkToBook(session, document, b?.title, b?.author, book, b?.percent ?: 0)
                        if (session.changed) store.updateService(tracker.id) { it.copy(credential = session.encode()) }
                        store.putLink(
                            SyncEngine.linkFor(tracker.id, document, book, LinkSource.MANUAL).copy(externalId = document, externalUuid = book.id)
                        )
                    } else {
                        store.putLink(SyncEngine.linkFor(tracker.id, document, book, LinkSource.MANUAL))
                    }
                    SyncEngine.queue(store, tracker.id, listOf(document))
                    runCatching { SyncEngine.propagateMatch(store, document, tracker.id, book) }
                }
                null
            } catch (e: Exception) {
                e.message ?: "Couldn't link the book"
            }
            if (error == null) {
                notify("Linked on ${tracker.name}", book.title)
                syncNow(quiet = true)
            } else {
                notify("${tracker.name}: couldn't link", error, error = true)
            }
        }
    }

    fun neverSync(tracker: Tracker, document: String) {
        viewModelScope.launch {
            val before = store.snapshot
            notifyUndo("Won't sync to ${tracker.name}", before, document)
            io {
                store.update { s ->
                    // The match is kept (only paused), so Sync again picks the same book back up.
                    val old = s.link(tracker.id, document)
                    val link = (old ?: Link(tracker.id, document, null)).copy(source = LinkSource.NONE, note = "Not synced",
                        updatedAt = System.currentTimeMillis())
                    s.copy(links = s.links + (link.key to link), pending = s.pending - link.key)
                }
            }
        }
    }

    fun resetLink(tracker: Tracker, document: String) {
        viewModelScope.launch {
            io {
                store.update { s ->
                    val old = s.link(tracker.id, document)
                    // A book it was matched to before stays matched; with none, it's matched afresh.
                    if (old?.externalId != null) s.copy(links = s.links + (old.key to old.copy(source = LinkSource.MANUAL, note = null)))
                    else s.copy(links = s.links - Link.key(tracker.id, document))
                }
                SyncEngine.queue(store, tracker.id, listOf(document))
            }
            syncNow(quiet = true)
        }
    }

    /** Looks up a book's description (and a missing cover) the first time its sheet opens. */
    // ---- the book menu: fixes saved on crosspoint-sync (see sync/BookEditor) ----

    private val editor get() = BookEditor(store)

    /** Runs a book edit off the main thread; [done] gets the error, or null when it worked. */
    private fun edit(success: String?, done: (String?) -> Unit, syncAfter: Boolean = false, action: () -> Unit) {
        viewModelScope.launch {
            val error = try {
                io { action() }
                null
            } catch (e: Exception) {
                e.message ?: "Something went wrong"
            }
            if (error != null) notify("Couldn't save", error, error = true) else success?.let { notify(it) }
            done(error)
            if (error == null && syncAfter) syncNow(quiet = true)
        }
    }

    /** Signs in to the Seekquel account (for covers and details on books added there); keeps only the token. */
    fun seekquelAccountSignIn(email: String, password: String, done: (String?) -> Unit) =
        edit("Signed in to your Seekquel account", done) {
            val svc = store.snapshot.service(io.github.prithirajn.trackersync.trackers.SeekquelTracker.id)
            val cred = io.github.prithirajn.trackersync.trackers.SeekquelTracker.accountSignIn(svc.credential, email, password)
            store.updateService(io.github.prithirajn.trackersync.trackers.SeekquelTracker.id) { it.copy(credential = cred) }
        }

    /**
     * Signed in (or out) on the Seekquel website, for its tags: noted on the Seekquel link, and
     * books' tags looked up again so Seekquel's join them.
     */
    fun seekquelWebSignedIn(on: Boolean) {
        viewModelScope.launch {
            io {
                val id = io.github.prithirajn.trackersync.trackers.SeekquelTracker.id
                store.updateService(id) { svc ->
                    val session = io.github.prithirajn.trackersync.trackers.Session(svc.credential)
                    session.data.put("webSignedIn", on)
                    svc.copy(credential = session.encode())
                }
                if (on) store.update { s -> s.copy(facts = s.facts.mapValues { (_, f) -> if (f.sources.none { it.equals("Seekquel", true) }) f.copy(at = 0L) else f }) }
            }
            notify(if (on) "Signed in on the Seekquel website" else "Signed out of the Seekquel website",
                if (on) "Seekquel's moods, tropes and warnings join the others as each book's page opens." else null)
        }
    }

    /** How highlights go to Fable: "quote" cards (your note as the caption) or "text" posts; and the card style. */
    fun setFableHighlights(style: String? = null, theme: String? = null) {
        viewModelScope.launch {
            io {
                store.updateService("fable") { svc ->
                    val session = io.github.prithirajn.trackersync.trackers.Session(svc.credential)
                    style?.let { session.data.put("highlightStyle", it) }
                    theme?.let { session.data.put("quoteTheme", it) }
                    svc.copy(credential = session.encode())
                }
            }
        }
    }

    fun seekquelAccountSignOut() = edit("Signed out of your Seekquel account", {}) {
        val id = io.github.prithirajn.trackersync.trackers.SeekquelTracker.id
        val cred = io.github.prithirajn.trackersync.trackers.SeekquelTracker.accountSignOut(store.snapshot.service(id).credential)
        store.updateService(id) { it.copy(credential = cred) }
    }

    fun editDetails(document: String, title: String, author: String, done: (String?) -> Unit) =
        edit("Saved", done, syncAfter = true) { editor.editDetails(document, title, author) }

    fun coverCandidates(document: String, query: String?, done: (List<CoverCandidate>?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                done(io { editor.coverCandidates(document, query) }, null)
            } catch (e: Exception) {
                done(null, e.message ?: "Couldn't load covers")
            }
        }
    }

    fun setCover(document: String, url: String?, done: (String?) -> Unit) =
        edit(if (url == null) "Using the automatic cover" else "Cover changed", done) { editor.setCover(document, url) }

    fun setPages(document: String, pages: Int?, done: (String?) -> Unit) =
        edit("Saved", done) { editor.setPages(document, pages) }

    /** The phone's offset as JavaScript's getTimezoneOffset gives it (minutes, east negative). */
    private fun tzOffset(): Int = -java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

    fun loadDates(document: String, done: (BookDates?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                done(io { editor.dates(document, tzOffset()) }, null)
            } catch (e: Exception) {
                done(null, e.message ?: "Couldn't load the dates")
            }
        }
    }

    fun setDates(document: String, changes: Map<String, String?>, done: (String?) -> Unit) =
        edit("Dates saved", done, syncAfter = true) { editor.setDates(document, changes) }

    fun likelyDuplicates(document: String): List<Book> = editor.likelyDuplicates(document)

    fun merge(duplicate: String, into: String, stats: Boolean, done: (String?) -> Unit) {
        val before = store.snapshot
        edit(null, { err ->
            if (err == null) {
                // Undo separates them on the server again and puts the copy back here.
                notifyUndo("Merged", before, duplicate) { runCatching { CrossPointClient(store.snapshot.account!!).unmerge(duplicate) } }
            }
            done(err)
        }, syncAfter = true) { editor.merge(duplicate, into, stats) }
    }

    fun separate(document: String, alias: String, done: (String?) -> Unit) =
        edit("Separated", done) { editor.separate(document, alias) }

    fun removeBook(document: String, done: (String?) -> Unit) =
        edit("Removed from your library", done) { editor.remove(document) }

    /** Looks for the book's description again now, in every source. */
    fun lookAgain(document: String) {
        viewModelScope.launch {
            store.update { s -> s.books[document]?.let { b -> s.copy(books = s.books + (document to b.copy(detailsFetched = false))) } ?: s }
            io { runCatching { SyncEngine.loadDetails(store, document, force = true) } }
            val b = store.snapshot.books[document]
            if (b?.description == null) notify("Still no description", store.snapshot.detailsLooked[document]?.let { "Looked in $it." })
        }
    }

    fun loadDetails(document: String) {
        val b = store.snapshot.books[document] ?: return
        // A manga or comic volume takes the trackers' name as soon as its page is open (tags already in hand).
        if (b.isMangaOrComic) viewModelScope.launch { io { runCatching { SyncEngine.adoptTrackerNames(store) } } }
        if (SyncEngine.needsFacts(store.snapshot, document)) {
            viewModelScope.launch { io { runCatching { SyncEngine.loadFacts(store, document) } } }
        }
        if (!SyncEngine.needsDetails(store.snapshot, b)) return
        viewModelScope.launch { io { runCatching { SyncEngine.loadDetails(store, document) } } }
    }

    /** A held book from a tracker's history: send it to every linked tracker from now on. */
    fun sendHeld(document: String) {
        viewModelScope.launch {
            io { SyncEngine.release(store, document) }
            notify("Sending to your trackers")
            syncNow(quiet = true)
        }
    }

    /** You've checked the match the app found on [tracker]: the book's first sync there goes ahead. */
    fun confirmMatch(tracker: io.github.prithirajn.trackersync.trackers.Tracker, document: String) {
        viewModelScope.launch {
            io {
                store.snapshot.link(tracker.id, document)?.let { store.putLink(it.copy(confirmed = true)) }
                SyncEngine.queue(store, tracker.id, listOf(document))
            }
            syncNow(quiet = true)
        }
    }

    /** Every match the app found for this book is right: all of them go ahead. */
    fun confirmAllMatches(document: String) {
        viewModelScope.launch {
            io {
                for (l in store.snapshot.links.values.filter { it.document == document && it.externalId != null && !it.confirmed && it.source != io.github.prithirajn.trackersync.data.LinkSource.NONE }) {
                    store.putLink(l.copy(confirmed = true))
                    SyncEngine.queue(store, l.service, listOf(document))
                }
            }
            notify("Matches confirmed", "Sending to your trackers.")
            syncNow(quiet = true)
        }
    }

    /** "Sync this book now": its own source checked, then only this book sent to its trackers. */
    fun syncBook(document: String) {
        viewModelScope.launch {
            val result = io {
                for (id in store.snapshot.services.values.filter { it.linked && it.enabled }.map { it.id }) {
                    SyncEngine.queue(store, id, listOf(document))
                }
                SyncEngine(store).runForBook(document)
            }
            notify(result.message, error = result.failed || result.needsAttention.isNotEmpty())
        }
    }

    /** Explains the SFF series/world context for [cluster] using the on-device AI model (or lore fallback). */
    fun explainSffContext(
        cluster: io.github.prithirajn.trackersync.sync.SffArtifactAi.SffStoryCluster,
        bodyMarkdown: String,
        done: (String, Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val (explanation, usedLlm) = io {
                io.github.prithirajn.trackersync.sync.SffArtifactAi.explainContext(cluster, bodyMarkdown, store.snapshot)
            }
            done(explanation, usedLlm)
        }
    }

    /** Synthesizes how multiple clubbed sources cover [cluster] using the on-device AI model. */
    fun compareSffPerspectives(
        cluster: io.github.prithirajn.trackersync.sync.SffArtifactAi.SffStoryCluster,
        bodyMarkdown: String,
        done: (String, Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val (synthesis, usedLlm) = io {
                io.github.prithirajn.trackersync.sync.SffArtifactAi.comparePerspectives(cluster, bodyMarkdown)
            }
            done(synthesis, usedLlm)
        }
    }

    /** Runs an on-device AI spoiler check on [cluster] against the reader's active/unfinished books. */
    fun checkSffSpoilers(
        cluster: io.github.prithirajn.trackersync.sync.SffArtifactAi.SffStoryCluster,
        bodyMarkdown: String,
        done: (String, Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val (verdict, usedLlm) = io {
                io.github.prithirajn.trackersync.sync.SffArtifactAi.checkArticleSpoilers(cluster, bodyMarkdown, store.snapshot)
            }
            done(verdict, usedLlm)
        }
    }
}
