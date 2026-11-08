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
        val next = transform(snapshot)
        snapshot = next
        save(next)
        for (l in listeners) l(next)
        return next
    }

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

    private fun save(s: Snapshot) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(toJson(s).toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
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
        s.moon?.let {
            o.put("moon", JSONObject()
                .put("server", it.server)
                .put("username", it.username)
                .put("key", secrets.seal(it.key)))
        }
        o.put("moonFolder", s.moonFolder ?: JSONObject.NULL)
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
        o.put("resend", JSONArray().also { a -> s.resend.forEach { a.put(it) } })
        o.put("clipHidden", JSONArray().also { a -> s.clipHidden.forEach { a.put(it) } })
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
                r.put(k, JSONObject().put("fraction", v.fraction).put("status", v.status?.name ?: JSONObject.NULL).put("updatedAt", v.updatedAt))
            }
        })
        o.put("progressDenied", JSONObject().also { r -> s.progressDenied.forEach { (k, v) -> r.put(k, v) } })
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
            moon = o.obj("moon")?.let { a ->
                a.str("key")?.let(secrets::open)?.let { key -> Account(a.optString("server"), a.optString("username"), key) }
            },
            moonFolder = o.str("moonFolder"),
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
            resend = o.optJSONArray("resend")?.strings()?.toSet() ?: emptySet(),
            clipHidden = o.optJSONArray("clipHidden")?.strings()?.toSet() ?: emptySet(),
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
                    )
                }.toMap()
            } ?: emptyMap(),
            progressDenied = o.obj("progressDenied")?.let { r -> r.keys().asSequence().associateWith { r.optInt(it) } } ?: emptyMap(),
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
            pagesByDay = o.obj("pagesByDay")?.let { r -> r.keys().asSequence().associateWith { r.optInt(it) } } ?: emptyMap(),
            handoffDenied = o.optJSONArray("handoffDenied")?.strings()?.toSet() ?: emptySet(),
        )
    }
    }

    companion object {
        const val MAX_LOG = 150

        /** The saved form of [s], with secrets sealed by [secrets] (the phone's key, or a backup's). */
        fun encode(s: Snapshot, secrets: Secrets): JSONObject = Codec.encode(s, secrets)
        fun decode(o: JSONObject, secrets: Secrets): Snapshot = Codec.decode(o, secrets)
    }
}
