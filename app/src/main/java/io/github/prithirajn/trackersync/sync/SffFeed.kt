package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.sync.SffArtifactAi.SffStoryCluster
import io.github.prithirajn.trackersync.sync.SffRadar.Sector
import kotlin.math.ln
import kotlin.math.pow

/**
 * The SFF feed, ranked the way Artifact ranked news. First how much a story matters on its own:
 * the publisher's standing, how many outlets carry it, whether it's about your books, and how new
 * it is (news fades over about a day, book finds over days). Then your taste on top: the sectors
 * you're strong in, what you follow, and what the story-level model has learned from what you
 * read, liked and skipped. One story from each outlet at a time (two at most in any ten), and
 * every eighth story is from a sector you rarely read, so the feed doesn't close in on you.
 */
object SffFeed {
    const val EXPLORE_EVERY = 8

    data class Story(
        val cluster: SffStoryCluster,
        val sectors: List<Sector>,
        val quality: Double,
        val score: Double,
        val read: Boolean,
        /** Came in since your last visit. */
        val fresh: Boolean,
        /** The exploring slot: a sector you rarely read. */
        val exploring: Boolean,
        val spoiler: Boolean,
        val saved: Boolean,
        val reaction: String?,
    ) {
        val lead get() = cluster.lead
        val id get() = cluster.id
    }

    data class Topic(val id: String, val label: String, val badges: Set<String>, val tags: Set<String> = emptySet(), val categories: Set<String> = emptySet())

    /** Topics to browse and follow, besides the five sectors. */
    val TOPICS = listOf(
        Topic("awards", "Awards", setOf("Award Watch"), setOf("awards", "hugo", "nebula")),
        Topic("adaptations", "Adaptations", setOf("SFF Adaptation"), setOf("adaptation")),
        Topic("releases", "Releases & deals", setOf("Release & Deal Alert", "Sequel Countdown", "Series Horizon"), setOf("publisher news")),
        Topic("reveals", "Cover reveals", setOf("Cover & Excerpt Reveal")),
        Topic("authors", "Author news", setOf("Author & Social Dispatch"), setOf("author news"), setOf("SOCIALS")),
        Topic("reviews", "Reviews & essays", setOf("SFF Editorial & Review"), setOf("reviews")),
        Topic("community", "Community", setOf("Community Discussion"), categories = setOf("COMMUNITY")),
        Topic("serials", "Serials & manga", setOf("Serial & Manga Pulse"), setOf("manga", "web serial"), setOf("SERIALS_MANGA")),
    )

    private val SCREEN_TITLE = Regex(
        "(?i)\\b(trailer|teaser|season \\d+|episode|showrunner|casting|cast as|box office|netflix|hbo|max original|prime video|" +
            "apple tv|disney\\+|peacock|paramount\\+|starz|film|movie|tv series|tv show|miniseries|limited series|premiere|renewed|cancell?ed|" +
            "director|anime adaptation|live-action|on screen|streaming)\\b"
    )

    /** Whether a story is about SFF on screen: films, series and adaptations. */
    fun isScreen(c: SffStoryCluster): Boolean =
        c.perspectives.any { p -> p.tags.any { it.equals("Film & TV", true) } } ||
            ((c.topicBadge == "SFF Adaptation" || c.perspectives.any { p -> p.tags.any { it.equals("Adaptation", true) } }) &&
                c.perspectives.any { p -> SCREEN_TITLE.containsMatchIn(p.title) }) ||
            (c.lead.category == "NEWS_AWARDS" || c.lead.category == "COMMUNITY") && c.perspectives.any { p ->
                SCREEN_TITLE.containsMatchIn(p.aiHeadline ?: p.title) && SCREEN_TITLE.findAll(p.title + " " + (p.summary ?: "").take(400)).count() >= 2
            }

    fun inTopic(c: SffStoryCluster, t: Topic): Boolean =
        c.topicBadge in t.badges ||
            c.perspectives.any { p -> p.category in t.categories || p.tags.any { it.lowercase() in t.tags } } ||
            c.canonicalTags.any { it.lowercase() in t.tags }

    private fun halfLifeHours(category: String): Double = when (category) {
        "COMMUNITY", "SOCIALS" -> 10.0
        "NEWS_AWARDS" -> 20.0
        "SERIALS_MANGA" -> 24.0
        "SERIES_HORIZON" -> 72.0
        else -> 120.0
    }

    /**
     * The feed, best first. [sector] or [topic] narrows it to one; [includeRead] false leaves out
     * what you've read. The probe's briefing is shown on its own, so it isn't repeated here.
     */
    fun build(s: Snapshot, now: Long, sector: Sector? = null, topic: Topic? = null): List<Story> {
        val sky = s.sffSky
        val briefing = sky.briefing.map { it.id }.toSet()
        val items = s.sffItems.filter {
            it.id !in briefing && sky.blockedSources.none { b -> it.source.equals(b, true) || it.source.startsWith("$b ·") } &&
                io.github.prithirajn.trackersync.source.SffSources.isNews(it) && io.github.prithirajn.trackersync.source.SffSources.isEnglish(it)
        }
        if (items.isEmpty()) return emptyList()
        val clusters = SffArtifactAi.clusterAndRank(items, s, now)
        val strengths = SffChart.strengths(s, now)
        val read = sky.history.filter { it.seconds >= 10 }.map { it.id }.toSet() + s.sffProfile.readIds
        val saved = sky.saved.map { it.id }.toSet()
        val maxModel = clusters.maxOfOrNull { it.score }?.takeIf { it > 0 } ?: 1.0

        val stories = clusters.mapNotNull { c ->
            val secs = c.perspectives.take(3).flatMap { SffChart.sectorsOf(it) }.distinct().take(2)
            val followed = secs.any { it.id in sky.followed } || TOPICS.any { it.id in sky.followed && inTopic(c, it) }
            if (!followed && secs.isNotEmpty() && secs.all { it.id in sky.mutedSectors }) return@mapNotNull null
            if (sector != null && sector !in secs) return@mapNotNull null
            if (topic != null && !inTopic(c, topic)) return@mapNotNull null
            val lead = c.lead
            val outlets = c.perspectives.map { it.source.substringBefore(" ·") }.distinct().size
            val mine = c.perspectives.any { it.matchedDocument != null || it.matchedLibraryBook != null }
            val quality = SffChart.sourceWeight(lead.source) + 1.2 * ln(outlets.toDouble()) + if (mine) 1.0 else 0.0
            val at = lead.publishedAt.takeIf { it > 0 } ?: lead.firstSeenAt.takeIf { it > 0 } ?: now
            val ageH = (now - at).coerceAtLeast(0) / 3600.0
            val recency = 0.5.pow(ageH / halfLifeHours(lead.category))
            val taste = 3.0 * (c.score / maxModel).coerceIn(-1.0, 1.0) +
                2.0 * secs.sumOf { strengths[it] ?: 0.0 } / secs.size.coerceAtLeast(1) +
                if (followed) 1.5 else 0.0
            val isRead = c.perspectives.any { it.id in read }
            // Your taste lifts a story among its contemporaries, but doesn't keep an old one on top:
            // both fade with age, and past three weeks a story sinks well below the day's news.
            var score = (quality * 2.2 + taste.coerceAtLeast(0.0)) * (0.25 + 0.75 * recency) + taste.coerceAtMost(0.0)
            if (ageH > 21 * 24) score *= 0.25
            if (isRead) score *= 0.3
            Story(
                cluster = c, sectors = secs, quality = quality, score = score, read = isRead,
                fresh = sky.prevSeenAt > 0 && c.perspectives.any { it.firstSeenAt > sky.prevSeenAt },
                exploring = false, spoiler = lead.spoilerWarning,
                saved = c.perspectives.any { it.id in saved }, reaction = c.perspectives.firstNotNullOfOrNull { sky.reactions[it.id] },
            )
        }.distinctBy { it.id }.sortedByDescending { it.score }

        // Order: no outlet more than twice in any ten, and (in the full feed) every eighth a sector you rarely read.
        val pool = stories.toMutableList()
        val out = ArrayList<Story>(pool.size)
        val exploreOn = sector == null && topic == null
        while (pool.isNotEmpty()) {
            val pos = out.size
            if (exploreOn && (pos + 1) % EXPLORE_EVERY == 0) {
                val ex = pool.filter { !it.read && it.sectors.isNotEmpty() && it.sectors.minOf { sec -> strengths[sec] ?: 0.0 } < 0.35 }
                    .maxByOrNull { it.quality + it.score * 0.2 }
                if (ex != null) { pool.remove(ex); out += ex.copy(exploring = true); continue }
            }
            val recent = out.takeLast(9).groupingBy { it.lead.source.substringBefore(" ·") }.eachCount()
            val next = pool.firstOrNull { (recent[it.lead.source.substringBefore(" ·")] ?: 0) < 2 } ?: pool.first()
            pool.remove(next)
            out += next
        }
        return out
    }

    /** The big card at the top: among the first three, the most important unread story with a picture. */
    fun hero(stories: List<Story>, now: Long = System.currentTimeMillis() / 1000): Story? {
        fun ok(it: Story) = !it.read && !it.spoiler && !it.lead.coverUrl.isNullOrBlank()
        // Recent news only (the last three days, else the last week).
        fun age(it: Story) = now - (it.lead.publishedAt.takeIf { p -> p > 0 } ?: it.lead.firstSeenAt.takeIf { f -> f > 0 } ?: now)
        return stories.take(6).filter { ok(it) && age(it) <= 3 * 86_400 }.maxByOrNull { it.quality }
            ?: stories.take(10).filter { ok(it) && age(it) <= 7 * 86_400 }.maxByOrNull { it.quality }
    }

    data class Today(val top: Story?, val echo: Pair<String, String>?, val fresh: Int)

    /** Today's sky: the day's biggest story, a highlight of yours to go with it, and how much is new. */
    fun today(s: Snapshot, stories: List<Story>, now: Long): Today {
        val dayAgo = now - SffChart.DAY
        val top = stories.filter { !it.read && (it.lead.publishedAt.takeIf { p -> p > 0 } ?: it.lead.firstSeenAt) >= dayAgo }
            .maxByOrNull { it.quality * 2 + it.score * 0.3 } ?: stories.firstOrNull { !it.read }
        val docs = top?.cluster?.perspectives?.mapNotNull { it.matchedDocument }.orEmpty()
        val echo = SffChart.echoFor(s, docs, top?.sectors?.firstOrNull(), now / SffChart.DAY)
        return Today(top, echo, stories.count { it.fresh })
    }
}
