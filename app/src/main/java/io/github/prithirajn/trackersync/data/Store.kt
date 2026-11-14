package io.github.prithirajn.trackersync.data

import io.github.prithirajn.trackersync.util.int
import io.github.prithirajn.trackersync.util.long
import io.github.prithirajn.trackersync.util.obj
import io.github.prithirajn.trackersync.util.objects
import io.github.prithirajn.trackersync.util.str
import io.github.prithirajn.trackersync.util.strings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Encrypts secrets at rest. The Android implementation uses a Keystore key. */
interface Secrets {
    fun seal(plain: String): String
    fun open(sealed: String): String?
}

/**
 * The app's single source of truth, saved as one JSON file. Every change goes
 * through [update], which saves the file and notifies listeners (the UI).
 * Secrets (the crosspoint-sync key and each tracker's login) are encrypted on disk.
 */
class Store(private val file: File, private val secrets: Secrets) {
    @Volatile
    var snapshot: Snapshot = load()
        private set

    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()

    fun addListener(l: (Snapshot) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Snapshot) -> Unit) { listeners.remove(l) }

    @Synchronized
    fun update(transform: (Snapshot) -> Snapshot): Snapshot {
        val before = snapshot
        // Changes made while a source runs pass its main-source check (sync.Primary) on the way in.
        val next = transform(before).let { n -> gate?.invoke(before, n) ?: n }.let { keepOwnCovers(before, it) }
        snapshot = next
        save(next)
        for (l in listeners) l(next)
        return next
    }

    /**
     * A cover you chose from your photos stays when a source reports the book again with its own
     * cover; only choosing another cover (or the automatic one) under Change cover replaces it.
     */
    private fun keepOwnCovers(before: Snapshot, next: Snapshot): Snapshot {
        if (next.books === before.books || coverEdit.get() == true) return next
        var books: HashMap<String, Book>? = null
        for ((doc, old) in before.books) {
            val own = old.coverUrl?.takeIf { it.startsWith("file:") } ?: continue
            val nb = next.books[doc] ?: continue
            if (nb.coverUrl != own) (books ?: HashMap(next.books).also { books = it })[doc] = nb.copy(coverUrl = own)
        }
        return books?.let { next.copy(books = it) } ?: next
    }

    /** Runs [block] as a cover change you made (Change cover), which may replace a photo cover. */
    fun <T> coverChange(block: () -> T): T {
        coverEdit.set(true)
        try { return block() } finally { coverEdit.set(false) }
    }

    private val coverEdit = ThreadLocal<Boolean>()

    fun log(service: String?, message: String, error: Boolean = false) = update { s ->
        val entry = LogEntry(System.currentTimeMillis(), service, message, error)
        s.copy(log = (listOf(entry) + s.log).take(MAX_LOG))
    }

    fun updateService(id: String, transform: (ServiceState) -> ServiceState) = update { s ->
        s.copy(services = s.services + (id to transform(s.service(id))))
    }

    fun putLink(link: Link) = update { s -> s.copy(links = s.links + (link.key to link)) }

    private fun load(): Snapshot {
        if (!file.exists()) return Snapshot()
        return try {
            // A sync can't be running in a process that's only starting: a sync the system stopped
            // part-way (or the app being closed) would otherwise leave "Syncing…" showing for good.
            fromJson(JSONObject(file.readText())).copy(running = false, syncStep = null, sffLoading = false)
        } catch (e: Exception) {
            // A damaged file shouldn't stop the app: start fresh, keep the bad copy for inspection.
            file.copyTo(File(file.parentFile, file.name + ".broken"), overwrite = true)
            Snapshot()
        }
    }

    /**
     * Saving happens on its own thread, so a tap that changes something never waits for the whole
     * library to be written out (the screen stutters otherwise). Changes made in quick succession are
     * written once, as the newest; the file is always written whole and swapped in, so it's never
     * left half-written.
     */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "store-save").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true }
    }
    private val unsaved = java.util.concurrent.atomic.AtomicReference<Snapshot?>(null)

    private fun save(s: Snapshot) {
        if (unsaved.getAndSet(s) == null) writer.execute { unsaved.getAndSet(null)?.let { write(it) } }
    }

    /** Waits until every change so far is on disk (before something reads the file itself). */
    fun flush() {
        runCatching { writer.submit { unsaved.getAndSet(null)?.let { write(it) } }.get(10, java.util.concurrent.TimeUnit.SECONDS) }
    }

    private fun write(s: Snapshot) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(toJson(s).toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun toJson(s: Snapshot): JSONObject = encode(s, secrets)

    private fun fromJson(o: JSONObject): Snapshot = decode(o, secrets)

    /** Replaces everything (a restored backup). Running state isn't carried over. */
    fun replace(next: Snapshot) = update { next.copy(running = false) }

    private object Codec {
    fun encode(s: Snapshot, secrets: Secrets): JSONObject {
        val o = JSONObject()
        s.account?.let {
            o.put("account", JSONObject()
                .put("server", it.server)
                .put("username", it.username)
                .put("key", secrets.seal(it.key)))
        }
        s.kosync?.let {
            o.put("kosync", JSONObject()
                .put("server", it.server)
                .put("username", it.username)
                .put("key", secrets.seal(it.key)))
        }
        o.put("kosyncOkAt", s.kosyncOkAt)
        o.put("kosyncError", s.kosyncError ?: JSONObject.NULL)
        o.put("kosyncRotation", s.kosyncRotation)
        o.put("kosyncIds", JSONObject().also { j -> s.kosyncIds.forEach { (k, v) -> j.put(k, org.json.JSONArray(v)) } })
        s.moon?.let {
            o.put("moon", JSONObject()
                .put("server", it.server)
                .put("username", it.username)
                .put("key", secrets.seal(it.key)))
        }
        o.put("moonFolder", s.moonFolder ?: JSONObject.NULL)
        s.koHl?.let { o.put("koHl", JSONObject().put("server", it.server).put("username", it.username).put("key", secrets.seal(it.key))) }
        o.put("koHlFolders", org.json.JSONArray(s.koHlFolders))
        o.put("koHlOkAt", s.koHlOkAt)
        o.put("koHlError", s.koHlError ?: JSONObject.NULL)
        o.put("koHlMods", JSONObject().also { j -> s.koHlMods.forEach { (k, v) -> j.put(k, v) } })
        o.put("koHlDocs", JSONObject().also { j -> s.koHlDocs.forEach { (k, v) -> j.put(k, v) } })
        o.put("koHlUnmatched", org.json.JSONArray(s.koHlUnmatched))
        o.put("koToCrossPoint", s.koToCrossPoint)
        o.put("koLocal", s.koLocal ?: JSONObject.NULL)
        o.put("koLocalOkAt", s.koLocalOkAt)
        o.put("koLocalError", s.koLocalError ?: JSONObject.NULL)
        o.put("koSentCp", JSONObject().also { j -> s.koSentCp.forEach { (k, v) -> j.put(k, v) } })
        o.put("koBooks", JSONObject().also { j -> s.koBooks.forEach { (k, v) -> j.put(k, v) } })
        o.put("koTitles", JSONObject().also { j -> s.koTitles.forEach { (k, v) -> j.put(k, v) } })
        o.put("koUnnamed", JSONObject().also { j -> s.koUnnamed.forEach { (k, v) -> j.put(k, v) } })
        o.put("koStoryLinks", JSONObject().also { j -> s.koStoryLinks.forEach { (k, v) -> j.put(k, v) } })
        o.put("koSameAs", JSONObject().also { j -> s.koSameAs.forEach { (k, v) -> j.put(k, v) } })
        o.put("threadsToken", s.threadsToken ?: JSONObject.NULL).put("threadsUser", s.threadsUser ?: JSONObject.NULL)
            .put("threadsUserId", s.threadsUserId ?: JSONObject.NULL).put("threadsTokenAt", s.threadsTokenAt)
            .put("threadsAuto", s.threadsAuto).put("threadsPerDay", s.threadsPerDay).put("threadsNotes", s.threadsNotes).put("threadsWithNote", s.threadsWithNote)
            .put("threadsSeen", JSONArray().also { a -> s.threadsSeen.forEach { a.put(it) } })
            .put("threadsPosted", JSONObject().also { j -> s.threadsPosted.forEach { (k, v) -> j.put(k, v) } })
            .put("threadsFails", JSONObject().also { j -> s.threadsFails.forEach { (k, v) -> j.put(k, v) } })
            .put("threadsError", s.threadsError ?: JSONObject.NULL).put("threadsLastLook", s.threadsLastLook ?: JSONObject.NULL).put("threadsAnyLook", s.threadsAnyLook)
            .put("threadsBooks", JSONArray().also { a -> s.threadsBooks.forEach { a.put(it) } })
            .put("threadsReviewed", JSONObject().also { j -> s.threadsReviewed.forEach { (k, v) -> j.put(k, v) } })
            .put("threadsPerSession", s.threadsPerSession).put("threadsTopic", s.threadsTopic)
            .put("threadsArrived", JSONObject().also { j -> s.threadsArrived.forEach { (k, v) -> j.put(k, v) } })
            .put("threadsPostLog", JSONArray().also { a -> s.threadsPostLog.forEach { a.put(it) } })
            .put("threadsAi", s.threadsAi).put("threadsAiCaptions", s.threadsAiCaptions)
            .put("threadsTopics", JSONObject().also { j -> s.threadsTopics.forEach { (k, v) -> j.put(k, v) } })
            .put("serverLinked", JSONArray().also { a -> s.serverLinked.forEach { a.put(it) } }).put("serverLinkedAt", s.serverLinkedAt)
            .put("readwiseToken", s.readwiseToken ?: JSONObject.NULL).put("readwiseDone", JSONArray().also { a -> s.readwiseDone.forEach { a.put(it) } })
            .put("notionToken", s.notionToken ?: JSONObject.NULL).put("notionParent", s.notionParent ?: JSONObject.NULL)
            .put("notionPages", JSONObject().also { j -> s.notionPages.forEach { (k, v) -> j.put(k, v) } })
            .put("notionDone", JSONArray().also { a -> s.notionDone.forEach { a.put(it) } })
            .put("obsidianFolder", s.obsidianFolder ?: JSONObject.NULL)
            .put("obsidianHashes", JSONObject().also { j -> s.obsidianHashes.forEach { (k, v) -> j.put(k, v) } })
            .put("exportErrors", JSONObject().also { j -> s.exportErrors.forEach { (k, v) -> j.put(k, v) } })
            .put("exportOkAt", JSONObject().also { j -> s.exportOkAt.forEach { (k, v) -> j.put(k, v) } })
        o.put("koPages", JSONObject().also { j -> s.koPages.forEach { (k, v) -> j.put(k, v) } })
        o.put("koDavBooks", s.koDavBooks ?: JSONObject.NULL)
        o.put("koPlaced", org.json.JSONArray(s.koPlaced.toList()))
        o.put("koUnplaced", org.json.JSONArray(s.koUnplaced.toList()))
        o.put("cpToKo", s.cpToKo)
        o.put("koreaderDocs", org.json.JSONArray(s.koreaderDocs.toList()))
        o.put("koSeenAt", s.koSeenAt)
        o.put("koNudgeOff", s.koNudgeOff)
        o.put("cpSentKo", org.json.JSONArray(s.cpSentKo.toList()))
        o.put("cpToKoOkAt", s.cpToKoOkAt)
        o.put("cpToKoNote", s.cpToKoNote ?: JSONObject.NULL)
        o.put("moonOkAt", s.moonOkAt)
        o.put("moonError", s.moonError ?: JSONObject.NULL)
        o.put("moonSeen", JSONObject().also { j -> s.moonSeen.forEach { (k, v) -> j.put(k, v) } })
        o.put("moonDocs", JSONObject().also { j -> s.moonDocs.forEach { (k, v) -> j.put(k, v) } })
        s.kindle?.let { o.put("kindle", it.toJson(secrets.seal(it.cookies))) }
        o.put("kindleOkAt", s.kindleOkAt)
        o.put("kindleError", s.kindleError ?: JSONObject.NULL)
        o.put("cursor", s.cursor)
        o.put("lastRun", s.lastRun)
        o.put("sourceOkAt", s.sourceOkAt)
        o.put("sourceError", s.sourceError ?: JSONObject.NULL)
        o.put("books", JSONArray().also { a -> s.books.values.forEach { a.put(it.toJson()) } })
        o.put("services", JSONArray().also { a ->
            s.services.values.forEach { a.put(it.toJson(it.credential?.let(secrets::seal))) }
        })
        o.put("links", JSONArray().also { a -> s.links.values.forEach { a.put(it.toJson()) } })
        o.put("pending", JSONArray().also { a -> s.pending.values.forEach { a.put(it.toJson()) } })
        o.put("log", JSONArray().also { a -> s.log.forEach { a.put(it.toJson()) } })
        o.put("sentClippings", JSONArray().also { a -> s.sentClippings.forEach { a.put(it) } })
        o.put("paperClips", JSONArray().also { a ->
            s.paperClips.forEach { c ->
                a.put(JSONObject().put("id", c.id).put("document", c.document).put("text", c.text)
                    .put("note", c.note ?: JSONObject.NULL).put("chapter", c.chapter ?: JSONObject.NULL).put("createdAt", c.createdAt)
                    .put("spine", c.spine ?: JSONObject.NULL).put("percent", c.percent ?: JSONObject.NULL))
            }
        })
        // The GitHub token is a credential: sealed like the others, never stored as plain text.
        o.put("settings", s.settings.toJson().also { j -> s.settings.githubToken?.let { j.put("githubToken", secrets.seal(it)) } })
        fun clips(list: List<io.github.prithirajn.trackersync.source.Clipping>) = JSONArray().also { a ->
            list.forEach { c ->
                a.put(JSONObject().put("id", c.id).put("document", c.document).put("text", c.text)
                    .put("note", c.note ?: JSONObject.NULL).put("chapter", c.chapter ?: JSONObject.NULL).put("createdAt", c.createdAt)
                    .put("spine", c.spine ?: JSONObject.NULL).put("percent", c.percent ?: JSONObject.NULL)
                    .put("page", c.page ?: JSONObject.NULL))
            }
        }
        o.put("cpClips", clips(s.cpClips))
        o.put("kindleClips", clips(s.kindleClips))
        o.put("libbyClips", clips(s.libbyClips))
        o.put("moonClips", clips(s.moonClips))
        o.put("koClips", clips(s.koClips))
        o.put("historyClips", clips(s.historyClips))
        o.put("readingDays", JSONObject().also { r ->
            s.readingDays.forEach { (doc, days) -> r.put(doc, JSONObject().also { d -> days.forEach { (day, v) -> d.put(day, v.toJson()) } }) }
        })
        o.put("moonMods", JSONObject().also { j -> s.moonMods.forEach { (k, v) -> j.put(k, v) } })
        s.libby?.let { o.put("libby", it.toJson(secrets.seal(it.identity))) }
        s.audible?.let { o.put("audible", it.toJson(secrets.seal(it.refreshToken))) }
        s.mihon?.let { o.put("mihon", it.toJson()) }
        o.put("mihonOkAt", s.mihonOkAt)
        o.put("mihonError", s.mihonError ?: JSONObject.NULL)
        o.put("mangaMaps", JSONObject().also { r -> s.mangaMaps.forEach { (k, v) -> r.put(k, v.toJson()) } })
        o.put("mangaMissing", JSONObject().also { r -> s.mangaMissing.forEach { (k, v) -> r.put(k, v) } })
        s.mangaUpdates?.let { o.put("mangaUpdates", it.toJson(secrets.seal(it.token))) }
        o.put("mangaUpdatesOkAt", s.mangaUpdatesOkAt)
        o.put("mangaUpdatesError", s.mangaUpdatesError ?: JSONObject.NULL)
        o.put("muSeries", JSONObject().also { r -> s.muSeries.forEach { (k, v) -> r.put(k, v) } })
        o.put("muSent", JSONObject().also { r -> s.muSent.forEach { (k, v) -> r.put(k, v) } })
        s.anilist?.let { o.put("anilist", it.toJson(secrets.seal(it.token))) }
        o.put("anilistClientId", s.anilistClientId ?: JSONObject.NULL)
        o.put("anilistOkAt", s.anilistOkAt)
        o.put("anilistError", s.anilistError ?: JSONObject.NULL)
        o.put("alSeries", JSONObject().also { r -> s.alSeries.forEach { (k, v) -> r.put(k, v) } })
        o.put("alSent", JSONObject().also { r -> s.alSent.forEach { (k, v) -> r.put(k, v) } })
        o.put("comicMaps", JSONObject().also { r -> s.comicMaps.forEach { (k, v) -> r.put(k, v.toJson()) } })
        o.put("comicMissing", JSONObject().also { r -> s.comicMissing.forEach { (k, v) -> r.put(k, v) } })
        o.put("comicPerVolume", JSONObject().also { r -> s.comicPerVolume.forEach { (k, v) -> r.put(k, v) } })
        o.put("seriesRead", JSONObject().also { r -> s.seriesRead.forEach { (k, v) -> r.put(k, v.toJson()) } })
        o.put("pagesPer", JSONObject().also { r -> s.pagesPer.forEach { (k, v) -> r.put(k, v) } })
        o.put("issueCovers", JSONObject().also { r -> s.issueCovers.forEach { (k, v) -> r.put(k, v) } })
        o.put("sffItems", JSONArray().also { a -> s.sffItems.take(260).forEach { a.put(it.toJson()) } })
        o.put("sffUpdatedAt", s.sffUpdatedAt)
        o.put("sffError", s.sffError ?: JSONObject.NULL)
        o.put("sffProbe", s.sffProbe.toJson())
        o.put("sffProfile", s.sffProfile.toJson())
        o.put("sffSky", s.sffSky.toJson())
        o.put("releases", JSONObject().also { r -> s.releases.forEach { (k, v) -> r.put(k, v.toJson()) } })
        o.put("releaseSettings", s.releaseSettings.toJson())
        o.put("releasesHidden", JSONArray(s.releasesHidden.toList()))
        o.put("releasesChecked", JSONObject().also { r -> s.releasesChecked.forEach { (k, v) -> r.put(k, v) } })
        o.put("comicCustom", JSONObject().also { r -> s.comicCustom.forEach { (k, v) -> r.put(k, v.toJson()) } })
        o.put("logged", JSONObject().also { r -> s.logged.forEach { (k, v) -> r.put(k, v.toJson()) } })
        o.put("audibleOkAt", s.audibleOkAt)
        o.put("audibleError", s.audibleError ?: JSONObject.NULL)
        o.put("libbyOkAt", s.libbyOkAt)
        o.put("libbyError", s.libbyError ?: JSONObject.NULL)
        o.put("libbyNoPages", org.json.JSONArray(s.libbyNoPages.toList()))
        o.put("kindleTriedAt", s.kindleTriedAt)
        o.put("kindleSeen", JSONObject().also { r -> s.kindleSeen.forEach { (k, v) -> r.put(k, v) } })
        o.put("kindleLengths", JSONObject().also { r -> s.kindleLengths.forEach { (k, v) -> r.put(k, v) } })
        o.put("clipEdits", JSONObject().also { e ->
            s.clipEdits.forEach { (id, ed) -> e.put(id, JSONObject().put("text", ed.text).put("note", ed.note ?: JSONObject.NULL)) }
        })
        o.put("clipPages", JSONObject().also { r -> s.clipPages.forEach { (k, v) -> r.put(k, v) } })
        o.put("clipRemote", JSONObject().also { r -> s.clipRemote.forEach { (k, v) -> r.put(k, v) } })
        o.put("sentHash", JSONObject().also { r -> s.sentHash.forEach { (k, v) -> r.put(k, v) } })
        o.put("sentWords", JSONObject().also { r -> s.sentWords.forEach { (k, v) -> r.put(k, v) } })
        o.put("clipFails", JSONObject().also { r -> s.clipFails.forEach { (k, v) -> r.put(k, v) } })
        o.put("resend", JSONArray().also { a -> s.resend.forEach { a.put(it) } })
        o.put("clipHidden", JSONArray().also { a -> s.clipHidden.forEach { a.put(it) } })
        o.put("checkHighlights", s.checkHighlights).put("spoilerPublic", s.spoilerPublic)
        o.put("priceWatch", JSONObject().also { r -> s.priceWatch.forEach { (k, v) -> r.put(k, v.toJson()) } })
        o.put("clipChecked", JSONObject().also { r -> s.clipChecked.forEach { (k, v) -> r.put(k, v) } })
        o.put("rubric", org.json.JSONArray(s.rubric))
        o.put("rubricScores", JSONObject().also { r -> s.rubricScores.forEach { (d, m) -> r.put(d, JSONObject().also { x -> m.forEach { (k, v) -> x.put(k, v) } }) } })
        o.put("sent", JSONObject().also { r -> s.sent.forEach { (k, evs) -> r.put(k, JSONArray().also { a -> evs.forEach { a.put(it.toJson()) } }) } })
        o.put("positions", JSONObject().also { r ->
            s.positions.forEach { (d, pts) ->
                r.put(d, JSONArray().also { a -> pts.forEach { p -> a.put(JSONArray().put(p.timestamp).put(p.percentage).put(p.spine ?: JSONObject.NULL)) } })
            }
        })
        o.put("toCrossPoint", JSONArray().also { a -> s.toCrossPoint.forEach { a.put(it) } })
        o.put("toKosync", JSONArray().also { a -> s.toKosync.forEach { a.put(it) } })
        o.put("clipDeletes", JSONArray().also { a -> s.clipDeletes.forEach { a.put(it) } })
        o.put("seekquelRatings", JSONObject().also { r -> s.seekquelRatings.forEach { (k, v) -> r.put(k, v) } })
        o.put("seekquelDates", JSONObject().also { r -> s.seekquelDates.forEach { (k, v) -> r.put(k, v) } })
        o.put("progressAsks", JSONObject().also { r ->
            s.progressAsks.forEach { (k, v) ->
                r.put(k, JSONObject().put("fraction", v.fraction).put("status", v.status?.name ?: JSONObject.NULL).put("updatedAt", v.updatedAt).put("source", v.source))
            }
        })
        o.put("progressDenied", JSONObject().also { r -> s.progressDenied.forEach { (k, v) -> r.put(k, v) } })
        o.put("rereadAsks", JSONObject().also { r ->
            s.rereadAsks.forEach { (k, v) ->
                r.put(k, JSONObject().put("fraction", v.fraction).put("status", v.status?.name ?: JSONObject.NULL).put("updatedAt", v.updatedAt).put("source", v.source))
            }
        })
        o.put("rereadDenied", JSONObject().also { r -> s.rereadDenied.forEach { (k, v) -> r.put(k, v) } })
        o.put("backAt", JSONObject().also { r -> s.backAt.forEach { (k, v) -> r.put(k, v) } })
        o.put("keptApart", JSONArray(s.keptApart.toList()))
        o.put("extras", JSONObject().also { r -> s.extras.forEach { (k, v) -> r.put(k, v) } })
        o.put("waitingSeen", s.waitingSeen)
        o.put("primaries", JSONObject().also { r -> s.primaries.forEach { (k, v) -> r.put(k, v) } })
        o.put("sourceRanks", JSONObject().also { r -> s.sourceRanks.forEach { (k, v) -> r.put(k, JSONArray(v)) } })
        o.put("sourceStreaks", JSONObject().also { r -> s.sourceStreaks.forEach { (k, v) -> r.put(k, v) } })
        o.put("moveBack", JSONArray(s.moveBack.toList()))
        o.put("pinnedPrimary", JSONArray(s.pinnedPrimary.toList()))
        o.put("trustedSources", JSONArray(s.trustedSources.toList()))
        o.put("trustedFor", JSONArray(s.trustedFor.toList()))
        o.put("asksNotified", JSONObject().also { r -> s.asksNotified.forEach { (k, v) -> r.put(k, v) } })
        o.put("aiSuggestions", JSONObject().also { r -> s.aiSuggestions.forEach { (k, v) -> r.put(k, v.toJson()) } })
        o.put("detailsLooked", JSONObject().also { r -> s.detailsLooked.forEach { (k, v) -> r.put(k, v) } })
        o.put("detailsTried", JSONObject().also { r -> s.detailsTried.forEach { (k, v) -> r.put(k, v) } })
        o.put("facts", JSONObject().also { r -> s.facts.forEach { (k, v) -> r.put(k, v.toJson()) } })
        o.put("rereads", JSONObject().also { r -> s.rereads.forEach { (k, v) -> r.put(k, v) } })
        o.put("removedBooks", JSONObject().also { r -> s.removedBooks.forEach { (k, v) -> r.put(k, v) } })
        o.put("webNovels", JSONObject().also { r -> s.webNovels.forEach { (k, v) -> r.put(k, v.toJson()) } })
        o.put("webSearched", JSONObject().also { r -> s.webSearched.forEach { (k, v) -> r.put(k, v) } })
        o.put("grewAt", JSONObject().also { r -> s.grewAt.forEach { (k, v) -> r.put(k, v) } })
        o.put("handoffs", JSONObject().also { r -> s.handoffs.forEach { (k, v) -> r.put(k, v) } })
        o.put("pagesTotal", s.pagesTotal ?: JSONObject.NULL)
        o.put("pagesDay", s.pagesDay ?: JSONObject.NULL)
        o.put("pagesDayStart", s.pagesDayStart)
        o.put("pagesStartF", JSONObject().also { r -> s.pagesStartF.forEach { (k, v) -> r.put(k, v) } })
        o.put("pagesLastF", JSONObject().also { r -> s.pagesLastF.forEach { (k, v) -> r.put(k, v) } })
        o.put("pagesCleared", JSONArray(s.pagesCleared.toList()))
        o.put("pagesByDay", JSONObject().also { r -> s.pagesByDay.forEach { (k, v) -> r.put(k, v) } })
        o.put("handoffDenied", JSONArray().also { a -> s.handoffDenied.forEach { a.put(it) } })
        return o
    }

    fun decode(o: JSONObject, secrets: Secrets): Snapshot {
        val account = o.obj("account")?.let { a ->
            val key = a.str("key")?.let(secrets::open)
            if (key == null) null else Account(a.optString("server"), a.optString("username"), key)
        }
        val kosync = o.obj("kosync")?.let { a ->
            val key = a.str("key")?.let(secrets::open)
            if (key == null) null else Account(a.optString("server"), a.optString("username"), key)
        }
        val libby = o.obj("libby")?.let { l ->
            val unsealed = l.str("identity")?.let(secrets::open)
            if (unsealed == null) null else LibbyAccount.fromJson(l, unsealed)
        }
        val kindle = o.obj("kindle")?.let { k ->
            val unsealed = k.str("cookies")?.let(secrets::open)
            if (unsealed == null) null else KindleAccount.fromJson(k, unsealed)
        }
        val books = (o.optJSONArray("books")?.objects() ?: emptyList()).map(Book::fromJson)
        val services = (o.optJSONArray("services")?.objects() ?: emptyList()).map { s ->
            ServiceState.fromJson(s, s.str("credential")?.let(secrets::open))
        }
        val links = (o.optJSONArray("links")?.objects() ?: emptyList()).map(Link::fromJson)
        val pending = (o.optJSONArray("pending")?.objects() ?: emptyList()).map(Pending::fromJson)
        val log = (o.optJSONArray("log")?.objects() ?: emptyList()).map(LogEntry::fromJson)
        return Snapshot(
            account = account,
            cursor = o.long("cursor") ?: 0L,
            books = books.associateBy { it.document },
            services = services.associateBy { it.id },
            links = links.associateBy { it.key },
            pending = pending.associateBy { it.key },
            log = log,
            lastRun = o.long("lastRun") ?: 0L,
            sourceOkAt = o.long("sourceOkAt") ?: 0L,
            sourceError = o.str("sourceError"),
            kosync = kosync,
            kosyncOkAt = o.long("kosyncOkAt") ?: 0L,
            kosyncError = o.str("kosyncError"),
            kosyncRotation = o.int("kosyncRotation") ?: 0,
            kosyncIds = o.obj("kosyncIds")?.let { r ->
                r.keys().asSequence().associateWith { k -> r.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList() }
                    .filterValues { it.isNotEmpty() }
            } ?: emptyMap(),
            moon = o.obj("moon")?.let { a ->
                a.str("key")?.let(secrets::open)?.let { key -> Account(a.optString("server"), a.optString("username"), key) }
            },
            moonFolder = o.str("moonFolder"),
            koHl = o.obj("koHl")?.let { a -> a.str("key")?.let(secrets::open)?.let { key -> Account(a.optString("server"), a.optString("username"), key) } },
            koHlFolders = o.optJSONArray("koHlFolders")?.strings() ?: emptyList(),
            koHlOkAt = o.long("koHlOkAt") ?: 0L,
            koHlError = o.str("koHlError"),
            koHlMods = o.obj("koHlMods")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            koHlDocs = o.obj("koHlDocs")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            koHlUnmatched = o.optJSONArray("koHlUnmatched")?.strings() ?: emptyList(),
            koToCrossPoint = o.optBoolean("koToCrossPoint", false),
            koLocal = o.str("koLocal"),
            koLocalOkAt = o.long("koLocalOkAt") ?: 0L,
            koLocalError = o.str("koLocalError"),
            koSentCp = o.obj("koSentCp")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            koBooks = o.obj("koBooks")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            koTitles = o.obj("koTitles")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            koUnnamed = o.obj("koUnnamed")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            koStoryLinks = o.obj("koStoryLinks")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            koSameAs = o.obj("koSameAs")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            threadsToken = o.str("threadsToken"), threadsUser = o.str("threadsUser"), threadsUserId = o.str("threadsUserId"),
            threadsTokenAt = o.long("threadsTokenAt") ?: 0L, threadsAuto = o.optBoolean("threadsAuto", false),
            threadsPerDay = o.int("threadsPerDay") ?: 5, threadsNotes = o.optBoolean("threadsNotes", true), threadsWithNote = o.optBoolean("threadsWithNote", true),
            threadsSeen = o.optJSONArray("threadsSeen")?.strings()?.toSet() ?: emptySet(),
            threadsPosted = o.obj("threadsPosted")?.let { r -> r.keys().asSequence().associateWith { r.optLong(it) } } ?: emptyMap(),
            threadsFails = o.obj("threadsFails")?.let { r -> r.keys().asSequence().associateWith { r.optInt(it) } } ?: emptyMap(),
            threadsError = o.str("threadsError"), threadsLastLook = o.str("threadsLastLook"), threadsAnyLook = o.optBoolean("threadsAnyLook", false),
            threadsBooks = o.optJSONArray("threadsBooks")?.strings()?.toSet() ?: emptySet(),
            threadsReviewed = o.obj("threadsReviewed")?.let { r -> r.keys().asSequence().associateWith { r.optLong(it) } } ?: emptyMap(),
            threadsPerSession = o.optBoolean("threadsPerSession", true), threadsTopic = if (o.has("threadsTopic")) o.optString("threadsTopic") else "genre",
            threadsArrived = o.obj("threadsArrived")?.let { r -> r.keys().asSequence().associateWith { r.optLong(it) } } ?: emptyMap(),
            threadsPostLog = o.optJSONArray("threadsPostLog")?.let { a -> (0 until a.length()).map { a.optLong(it) } } ?: emptyList(),
            threadsAi = o.optBoolean("threadsAi", true), threadsAiCaptions = o.optBoolean("threadsAiCaptions", false),
            threadsTopics = o.obj("threadsTopics")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            serverLinked = o.optJSONArray("serverLinked")?.strings()?.toSet() ?: emptySet(), serverLinkedAt = o.optLong("serverLinkedAt", 0L),
            readwiseToken = o.str("readwiseToken"), readwiseDone = o.optJSONArray("readwiseDone")?.strings()?.toSet() ?: emptySet(),
            notionToken = o.str("notionToken"), notionParent = o.str("notionParent"),
            notionPages = o.obj("notionPages")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            notionDone = o.optJSONArray("notionDone")?.strings()?.toSet() ?: emptySet(),
            obsidianFolder = o.str("obsidianFolder"),
            obsidianHashes = o.obj("obsidianHashes")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            exportErrors = o.obj("exportErrors")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            exportOkAt = o.obj("exportOkAt")?.let { r -> r.keys().asSequence().associateWith { r.optLong(it) } } ?: emptyMap(),
            koDavBooks = o.str("koDavBooks"),
            koPages = o.obj("koPages")?.let { r -> r.keys().asSequence().associateWith { r.optInt(it) }.filterValues { it > 0 } } ?: emptyMap(),
            koPlaced = o.optJSONArray("koPlaced")?.strings()?.toSet() ?: emptySet(),
            koUnplaced = o.optJSONArray("koUnplaced")?.strings()?.toSet() ?: emptySet(),
            cpToKo = o.optBoolean("cpToKo", false),
            koreaderDocs = o.optJSONArray("koreaderDocs")?.strings()?.toSet() ?: emptySet(),
            koSeenAt = o.long("koSeenAt") ?: 0L,
            koNudgeOff = o.optBoolean("koNudgeOff", false),
            cpSentKo = o.optJSONArray("cpSentKo")?.strings()?.toSet() ?: emptySet(),
            cpToKoOkAt = o.long("cpToKoOkAt") ?: 0L,
            cpToKoNote = o.str("cpToKoNote"),
            koClips = (o.optJSONArray("koClips")?.objects() ?: emptyList()).mapNotNull { c ->
                io.github.prithirajn.trackersync.source.Clipping(
                    c.str("id") ?: return@mapNotNull null,
                    c.str("document") ?: return@mapNotNull null,
                    c.str("text") ?: return@mapNotNull null,
                    c.str("note"),
                    c.str("chapter"),
                    c.long("createdAt") ?: 0L,
                    spine = c.int("spine"),
                    percent = c.optDouble("percent").takeIf { !it.isNaN() },
                    page = c.int("page"),
                )
            },
            moonOkAt = o.long("moonOkAt") ?: 0L,
            moonError = o.str("moonError"),
            moonSeen = o.obj("moonSeen")?.let { r -> r.keys().asSequence().associateWith { r.optLong(it) } } ?: emptyMap(),
            moonDocs = o.obj("moonDocs")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            kindle = kindle,
            kindleOkAt = o.long("kindleOkAt") ?: 0L,
            kindleTriedAt = o.long("kindleTriedAt") ?: 0L,
            kindleSeen = o.obj("kindleSeen")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            kindleLengths = o.obj("kindleLengths")?.let { r -> r.keys().asSequence().associateWith { r.optInt(it) } } ?: emptyMap(),
            kindleError = o.str("kindleError"),
            kindleClips = (o.optJSONArray("kindleClips")?.objects() ?: emptyList()).mapNotNull { c ->
                io.github.prithirajn.trackersync.source.Clipping(
                    c.str("id") ?: return@mapNotNull null,
                    c.str("document") ?: return@mapNotNull null,
                    c.str("text") ?: return@mapNotNull null,
                    c.str("note"),
                    c.str("chapter"),
                    c.long("createdAt") ?: 0L,
                    spine = c.int("spine"),
                    percent = c.optDouble("percent").takeIf { !it.isNaN() },
                    page = c.int("page"),
                )
            },
            libby = libby,
            audible = o.obj("audible")?.let { a -> secrets.open(a.optString("refreshToken"))?.let { AudibleAccount.fromJson(a, it) } },
            audibleOkAt = o.long("audibleOkAt") ?: 0L,
            mihon = o.obj("mihon")?.let { MihonSource.fromJson(it) },
            mihonOkAt = o.long("mihonOkAt") ?: 0L,
            mihonError = o.str("mihonError"),
            mangaMaps = o.obj("mangaMaps")?.let { r -> r.keys().asSequence().mapNotNull { k -> r.optJSONObject(k)?.let { k to io.github.prithirajn.trackersync.source.MangaDex.Map.fromJson(it) } }.toMap() } ?: emptyMap(),
            mangaMissing = o.obj("mangaMissing")?.let { r -> r.keys().asSequence().associateWith { r.optLong(it) } } ?: emptyMap(),
            mangaUpdates = o.obj("mangaUpdates")?.let { m -> secrets.open(m.optString("token"))?.let { MangaUpdatesAccount.fromJson(m, it) } },
            mangaUpdatesOkAt = o.long("mangaUpdatesOkAt") ?: 0L,
            mangaUpdatesError = o.str("mangaUpdatesError"),
            muSeries = o.obj("muSeries")?.let { r -> r.keys().asSequence().associateWith { r.optLong(it) } } ?: emptyMap(),
            muSent = o.obj("muSent")?.let { r -> r.keys().asSequence().associateWith { r.optInt(it) } } ?: emptyMap(),
            anilist = o.obj("anilist")?.let { m -> secrets.open(m.optString("token"))?.let { AniListAccount.fromJson(m, it) } },
            anilistClientId = o.str("anilistClientId"),
            anilistOkAt = o.long("anilistOkAt") ?: 0L,
            anilistError = o.str("anilistError"),
            alSeries = o.obj("alSeries")?.let { r -> r.keys().asSequence().associateWith { r.optLong(it) } } ?: emptyMap(),
            alSent = o.obj("alSent")?.let { r -> r.keys().asSequence().associateWith { r.optInt(it) } } ?: emptyMap(),
            comicMaps = o.obj("comicMaps")?.let { r -> r.keys().asSequence().mapNotNull { k -> r.optJSONObject(k)?.let { k to io.github.prithirajn.trackersync.source.ComicCollections.Map.fromJson(it) } }.toMap() } ?: emptyMap(),
            comicMissing = o.obj("comicMissing")?.let { r -> r.keys().asSequence().associateWith { r.optLong(it) } } ?: emptyMap(),
            comicPerVolume = o.obj("comicPerVolume")?.let { r -> r.keys().asSequence().associateWith { r.optInt(it) } } ?: emptyMap(),
            seriesRead = o.obj("seriesRead")?.let { r -> r.keys().asSequence().mapNotNull { k -> r.optJSONObject(k)?.let { k to SeriesRead.fromJson(it) } }.toMap() } ?: emptyMap(),
            pagesPer = o.obj("pagesPer")?.let { r -> r.keys().asSequence().associateWith { r.optInt(it) } } ?: emptyMap(),
            issueCovers = o.obj("issueCovers")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            sffItems = (o.optJSONArray("sffItems")?.objects() ?: emptyList()).map(SffFeedItem::fromJson),
            sffUpdatedAt = o.long("sffUpdatedAt") ?: 0L,
            sffLoading = false,
            sffError = o.str("sffError"),
            sffProbe = SffProbeState.fromJson(o.obj("sffProbe")),
            sffProfile = SffArtifactProfile.fromJson(o.obj("sffProfile")),
            sffSky = SffSky.fromJson(o.obj("sffSky")),
            releases = o.obj("releases")?.let { r -> r.keys().asSequence().mapNotNull { k -> r.optJSONObject(k)?.let { k to Release.fromJson(it) } }.toMap() } ?: emptyMap(),
            releaseSettings = o.obj("releaseSettings")?.let { ReleaseSettings.fromJson(it) } ?: ReleaseSettings(),
            releasesHidden = o.optJSONArray("releasesHidden")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet(),
            releasesChecked = o.obj("releasesChecked")?.let { r -> r.keys().asSequence().associateWith { r.optLong(it) } } ?: emptyMap(),
            comicCustom = o.obj("comicCustom")?.let { r -> r.keys().asSequence().mapNotNull { k -> r.optJSONObject(k)?.let { k to io.github.prithirajn.trackersync.source.ComicCollections.Map.fromJson(it) } }.toMap() } ?: emptyMap(),
            logged = o.obj("logged")?.let { r -> r.keys().asSequence().mapNotNull { k -> r.optJSONObject(k)?.let { k to LoggedSeries.fromJson(it) } }.toMap() } ?: emptyMap(),
            audibleError = o.str("audibleError"),
            libbyOkAt = o.long("libbyOkAt") ?: 0L,
            libbyError = o.str("libbyError"),
            libbyNoPages = o.optJSONArray("libbyNoPages")?.strings()?.toSet() ?: emptySet(),
            libbyClips = (o.optJSONArray("libbyClips")?.objects() ?: emptyList()).mapNotNull { c ->
                io.github.prithirajn.trackersync.source.Clipping(
                    c.str("id") ?: return@mapNotNull null,
                    c.str("document") ?: return@mapNotNull null,
                    c.str("text") ?: return@mapNotNull null,
                    c.str("note"),
                    c.str("chapter"),
                    c.long("createdAt") ?: 0L,
                    spine = c.int("spine"),
                    percent = c.optDouble("percent").takeIf { !it.isNaN() },
                    page = c.int("page"),
                )
            },
            moonClips = (o.optJSONArray("moonClips")?.objects() ?: emptyList()).mapNotNull { c ->
                io.github.prithirajn.trackersync.source.Clipping(
                    c.str("id") ?: return@mapNotNull null,
                    c.str("document") ?: return@mapNotNull null,
                    c.str("text") ?: return@mapNotNull null,
                    c.str("note"),
                    c.str("chapter"),
                    c.long("createdAt") ?: 0L,
                    spine = c.int("spine"),
                    percent = c.optDouble("percent").takeIf { !it.isNaN() },
                    page = c.int("page"),
                )
            },
            historyClips = (o.optJSONArray("historyClips")?.objects() ?: emptyList()).mapNotNull { c ->
                io.github.prithirajn.trackersync.source.Clipping(
                    c.str("id") ?: return@mapNotNull null,
                    c.str("document") ?: return@mapNotNull null,
                    c.str("text") ?: return@mapNotNull null,
                    c.str("note"),
                    c.str("chapter"),
                    c.long("createdAt") ?: 0L,
                    spine = c.int("spine"),
                    percent = c.optDouble("percent").takeIf { !it.isNaN() },
                    page = c.int("page"),
                )
            },
            readingDays = o.obj("readingDays")?.let { r ->
                r.keys().asSequence().mapNotNull { doc ->
                    r.optJSONObject(doc)?.let { d -> doc to d.keys().asSequence().mapNotNull { day -> d.optJSONObject(day)?.let { day to ReadDay.fromJson(it) } }.toMap() }
                }.toMap()
            } ?: emptyMap(),
            moonMods = o.obj("moonMods")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            running = false,
            sentClippings = o.optJSONArray("sentClippings")?.strings()?.toSet() ?: emptySet(),
            paperClips = (o.optJSONArray("paperClips")?.objects() ?: emptyList()).mapNotNull { c ->
                io.github.prithirajn.trackersync.source.Clipping(
                    c.str("id") ?: return@mapNotNull null,
                    c.str("document") ?: return@mapNotNull null,
                    c.str("text") ?: return@mapNotNull null,
                    c.str("note"),
                    c.str("chapter"),
                    c.long("createdAt") ?: 0L,
                    spine = c.int("spine"),
                    percent = c.optDouble("percent").takeIf { !it.isNaN() },
                )
            },
            settings = SyncSettings.fromJson(o.obj("settings")).let { st ->
                // Saved sealed; one saved as plain text by an earlier version is still read, and sealed on the next save.
                st.copy(githubToken = st.githubToken?.let { raw ->
                    secrets.open(raw) ?: raw.takeIf { Regex("^(ghp_|github_pat_|gho_|ghs_)").containsMatchIn(it) }
                })
            },
            cpClips = (o.optJSONArray("cpClips")?.objects() ?: emptyList()).mapNotNull { c ->
                io.github.prithirajn.trackersync.source.Clipping(
                    c.str("id") ?: return@mapNotNull null,
                    c.str("document") ?: return@mapNotNull null,
                    c.str("text") ?: return@mapNotNull null,
                    c.str("note"),
                    c.str("chapter"),
                    c.long("createdAt") ?: 0L,
                    spine = c.int("spine"),
                    percent = c.optDouble("percent").takeIf { !it.isNaN() },
                )
            },
            clipPages = o.obj("clipPages")?.let { e -> e.keys().asSequence().mapNotNull { k -> e.optString(k).takeIf { it.isNotEmpty() }?.let { k to it } }.toMap() } ?: emptyMap(),
            clipEdits = o.obj("clipEdits")?.let { e ->
                e.keys().asSequence().mapNotNull { k ->
                    val v = e.optJSONObject(k) ?: return@mapNotNull null
                    k to ClipEdit(v.str("text") ?: return@mapNotNull null, v.str("note"))
                }.toMap()
            } ?: emptyMap(),
            clipRemote = o.obj("clipRemote")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            sentHash = o.obj("sentHash")?.let { r -> r.keys().asSequence().associateWith { r.optInt(it) } } ?: emptyMap(),
            sentWords = o.obj("sentWords")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            clipFails = o.obj("clipFails")?.let { r -> r.keys().asSequence().associateWith { r.optInt(it) } } ?: emptyMap(),
            resend = o.optJSONArray("resend")?.strings()?.toSet() ?: emptySet(),
            clipHidden = o.optJSONArray("clipHidden")?.strings()?.toSet() ?: emptySet(),
            checkHighlights = o.optBoolean("checkHighlights", false),
            priceWatch = o.obj("priceWatch")?.let { r -> r.keys().asSequence().mapNotNull { k -> r.optJSONObject(k)?.let { k to io.github.prithirajn.trackersync.data.PriceWatch.from(it) } }.toMap() } ?: emptyMap(),
            spoilerPublic = o.optString("spoilerPublic", "hold").ifEmpty { "hold" },
            clipChecked = o.obj("clipChecked")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            rubric = o.optJSONArray("rubric")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList(),
            rubricScores = o.obj("rubricScores")?.let { r ->
                r.keys().asSequence().associateWith { d -> r.optJSONObject(d)?.let { x -> x.keys().asSequence().associateWith { x.optInt(it) } } ?: emptyMap() }
            } ?: emptyMap(),
            sent = o.obj("sent")?.let { r ->
                r.keys().asSequence().associateWith { k ->
                    val a = r.optJSONArray(k) ?: JSONArray()
                    (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { SendEvent.fromJson(it) } }
                }
            } ?: emptyMap(),
            positions = o.obj("positions")?.let { r ->
                r.keys().asSequence().associateWith { d ->
                    val a = r.optJSONArray(d) ?: JSONArray()
                    (0 until a.length()).mapNotNull { i ->
                        val p = a.optJSONArray(i) ?: return@mapNotNull null
                        PositionPoint(p.optLong(0), p.optDouble(1), if (p.isNull(2)) null else p.optInt(2))
                    }
                }
            } ?: emptyMap(),
            toCrossPoint = o.optJSONArray("toCrossPoint")?.strings()?.toSet() ?: emptySet(),
            toKosync = o.optJSONArray("toKosync")?.strings()?.toSet() ?: emptySet(),
            clipDeletes = o.optJSONArray("clipDeletes")?.strings()?.toSet() ?: emptySet(),
            seekquelRatings = o.obj("seekquelRatings")?.let { r ->
                r.keys().asSequence().mapNotNull { k -> r.optDouble(k).takeIf { !it.isNaN() }?.let { k to it } }.toMap()
            } ?: emptyMap(),
            seekquelDates = o.obj("seekquelDates")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            progressAsks = o.obj("progressAsks")?.let { r ->
                r.keys().asSequence().mapNotNull { k ->
                    val v = r.optJSONObject(k) ?: return@mapNotNull null
                    k to ProgressAsk(
                        v.optDouble("fraction").takeIf { !it.isNaN() } ?: return@mapNotNull null,
                        v.str("status")?.let { n -> runCatching { ReadStatus.valueOf(n) }.getOrNull() },
                        v.optLong("updatedAt"),
                        v.str("source") ?: "seekquel",
                    )
                }.toMap()
            } ?: emptyMap(),
            progressDenied = o.obj("progressDenied")?.let { r -> r.keys().asSequence().associateWith { r.optInt(it) } } ?: emptyMap(),
            rereadAsks = o.obj("rereadAsks")?.let { r ->
                r.keys().asSequence().mapNotNull { k ->
                    val v = r.optJSONObject(k) ?: return@mapNotNull null
                    k to ProgressAsk(
                        v.optDouble("fraction").takeIf { !it.isNaN() } ?: return@mapNotNull null,
                        v.str("status")?.let { n -> runCatching { ReadStatus.valueOf(n) }.getOrNull() },
                        v.optLong("updatedAt"),
                        v.str("source") ?: "crosspoint",
                    )
                }.toMap()
            } ?: emptyMap(),
            rereadDenied = o.obj("rereadDenied")?.let { r -> r.keys().asSequence().associateWith { r.optLong(it) } } ?: emptyMap(),
            backAt = o.obj("backAt")?.let { r -> r.keys().asSequence().associateWith { r.optLong(it) } } ?: emptyMap(),
            keptApart = o.optJSONArray("keptApart")?.strings()?.toSet() ?: emptySet(),
            extras = o.obj("extras")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            waitingSeen = o.optInt("waitingSeen", 0),
            primaries = o.obj("primaries")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            sourceRanks = o.obj("sourceRanks")?.let { r -> r.keys().asSequence().associateWith { k -> r.optJSONArray(k)?.strings() ?: emptyList() } } ?: emptyMap(),
            sourceStreaks = o.obj("sourceStreaks")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            moveBack = o.optJSONArray("moveBack")?.strings()?.toSet() ?: emptySet(),
            pinnedPrimary = o.optJSONArray("pinnedPrimary")?.strings()?.toSet() ?: emptySet(),
            trustedSources = o.optJSONArray("trustedSources")?.strings()?.toSet() ?: emptySet(),
            trustedFor = o.optJSONArray("trustedFor")?.strings()?.toSet() ?: emptySet(),
            asksNotified = o.obj("asksNotified")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            aiSuggestions = o.obj("aiSuggestions")?.let { r -> r.keys().asSequence().mapNotNull { k -> r.optJSONObject(k)?.let { k to AiSuggestion.fromJson(it) } }.toMap() } ?: emptyMap(),
            detailsLooked = o.obj("detailsLooked")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            detailsTried = o.obj("detailsTried")?.let { r -> r.keys().asSequence().associateWith { r.optLong(it) } } ?: emptyMap(),
            facts = o.obj("facts")?.let { r -> r.keys().asSequence().mapNotNull { k -> r.optJSONObject(k)?.let { k to BookFacts.fromJson(it) } }.toMap() } ?: emptyMap(),
            rereads = o.obj("rereads")?.let { r -> r.keys().asSequence().associateWith { r.optLong(it) } } ?: emptyMap(),
            removedBooks = o.obj("removedBooks")?.let { r -> r.keys().asSequence().associateWith { r.optDouble(it) } } ?: emptyMap(),
            webNovels = o.obj("webNovels")?.let { r -> r.keys().asSequence().mapNotNull { k -> r.optJSONObject(k)?.let { k to WebNovel.fromJson(it) } }.toMap() } ?: emptyMap(),
            webSearched = o.obj("webSearched")?.let { r -> r.keys().asSequence().associateWith { r.optLong(it) } } ?: emptyMap(),
            grewAt = o.obj("grewAt")?.let { r -> r.keys().asSequence().associateWith { r.optLong(it) } } ?: emptyMap(),
            handoffs = o.obj("handoffs")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
            pagesTotal = o.int("pagesTotal"),
            pagesDay = o.str("pagesDay"),
            pagesDayStart = o.optInt("pagesDayStart"),
            pagesStartF = o.obj("pagesStartF")?.let { r -> r.keys().asSequence().associateWith { r.optDouble(it) }.filterValues { !it.isNaN() } } ?: emptyMap(),
            pagesLastF = o.obj("pagesLastF")?.let { r -> r.keys().asSequence().associateWith { r.optDouble(it) }.filterValues { !it.isNaN() } } ?: emptyMap(),
            pagesCleared = o.optJSONArray("pagesCleared")?.strings()?.toSet() ?: emptySet(),
            pagesByDay = o.obj("pagesByDay")?.let { r -> r.keys().asSequence().associateWith { r.optInt(it) } } ?: emptyMap(),
            handoffDenied = o.optJSONArray("handoffDenied")?.strings()?.toSet() ?: emptySet(),
        )
    }
    }

    companion object {
        /** Looks over each change before it's kept (set by the sync engine; see sync.Primary). */
        @Volatile var gate: ((Snapshot, Snapshot) -> Snapshot)? = null

        const val MAX_LOG = 150

        /** The saved form of [s], with secrets sealed by [secrets] (the phone's key, or a backup's). */
        fun encode(s: Snapshot, secrets: Secrets): JSONObject = Codec.encode(s, secrets)
        fun decode(o: JSONObject, secrets: Secrets): Snapshot = Codec.decode(o, secrets)
    }
}
