package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.ai.Ai
import io.github.prithirajn.readync.ai.Assistant
import io.github.prithirajn.readync.ai.TextModel
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.data.SffArtifactProfile
import io.github.prithirajn.readync.data.SffFeedItem
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.data.Store
import kotlin.math.exp
import kotlin.math.ln

/**
 * Artifact-inspired SFF Feed Intelligence Engine:
 *
 * 1. **Multi-Source Story Clustering**: Clubs articles, Reddit discussions, X/Instagram/Bluesky/
 *    Mastodon posts, YouTube videos, and Hardcover/Seekquel book listings covering the same news,
 *    book, sequel, author, or award into a single [SffStoryCluster] with multi-source perspective tabs.
 * 2. **Canonical SFF Taxonomy Tagging**: Normalizes and tags every cluster across Event Type
 *    (Sequel Alert, Cover Reveal, Award Watch, Adaptation, Review/Essay, Author Dispatch),
 *    Subgenre (Hard SF, Space Opera, Grimdark, Progression/LitRPG, Weird Horror, etc.), Mood/Pace,
 *    and Matched Entities.
 * 3. **Personalized Ranking + On-Device AI**: Combines library affinities (books, ratings,
 *    Hardcover/Seekquel tropes, highlights) + in-app reader dwell time telemetry + explicit
 *    thumbs-up/down feedback + on-device LLM (`Ai.model()`) re-ranking, 3-bullet executive
 *    summaries, and anti-clickbait headline rewriting.
 */
object SffArtifactAi {

    data class SffStoryCluster(
        val id: String,
        val lead: SffFeedItem,
        /** All items covering this same story/entity across sources (lead is first). */
        val perspectives: List<SffFeedItem>,
        /** Canonical normalized tags (Subgenre + Topic + Entity + Mood). */
        val canonicalTags: List<String>,
        /** High-level event badge ("Sequel Alert", "Award Watch", "Cover Reveal", "Multi-Source Buzz"…). */
        val topicBadge: String,
        /** Transparent Artifact-style explanation of why this cluster is ranked here for you. */
        val whyInFeed: String,
        val isClickbait: Boolean,
        val score: Double,
    ) {
        val sourceNames: List<String> get() = perspectives.map { it.source.substringBefore(" ·") }.distinct()
    }

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "that", "this", "from", "what", "when", "where", "which",
        "who", "whom", "whose", "why", "how", "are", "was", "were", "been", "have", "has", "had",
        "will", "would", "could", "should", "about", "into", "over", "after", "before", "between",
        "under", "again", "further", "then", "once", "here", "there", "all", "any", "both", "each",
        "few", "more", "most", "other", "some", "such", "only", "own", "same", "than", "too", "very",
        "just", "book", "books", "read", "reading", "new", "post", "thread", "review", "update",
    )

    private val SFF_ANCHOR_ENTITIES = listOf(
        "cosmere", "stormlight", "wind and truth", "mistborn", "sanderson", "dragonsteel",
        "dungeon crawler carl", "matt dinniman", "cradle", "will wight", "wandering inn", "pirateaba",
        "murderbot", "martha wells", "sun eater", "christopher ruocchio", "red rising", "pierce brown",
        "first law", "joe abercrombie", "the devils", "malazan", "steven erikson", "wheel of time",
        "locked tomb", "tamsyn muir", "alecto", "expanse", "james s.a. corey", "mercy of gods",
        "adrian tchaikovsky", "children of time", "john scalzi", "three-body", "cixin liu",
        "hugo award", "hugo awards", "nebula award", "nebula awards", "locus award", "worldcon",
        "booker prize", "world fantasy award", "arthur c. clarke", "ursula k. le guin",
        "discworld", "terry pratchett", "neil gaiman", "stephen king", "robin hobb", "farseer",
        "silmarillion", "tolkien", "lord of the rings", "dune", "hyperion", "blindsight",
        "progression fantasy", "royal road", "litrpg", "grimdark", "weird fiction", "area x", "vandermeer",
    )

    private val CLICKBAIT_REGEX = Regex(
        "(?i)(you won't believe|shocking|broke the internet|here's why everyone|the real reason why|will blow your mind|everything you need to know about|wait until you see|unpopular opinion:|am i the only one who|hot take:)"
    )

    /** Detects whether a headline reads like clickbait or vague rage-bait. */
    fun isClickbaitHeadline(title: String): Boolean {
        if (CLICKBAIT_REGEX.containsMatchIn(title)) return true
        if (title.endsWith("?!") || title.endsWith("...")) return true
        val letters = title.filter { it.isLetter() }
        if (letters.length > 20 && letters.count { it.isUpperCase() }.toDouble() / letters.length > 0.55) return true
        return false
    }

    /** Classifies the story's primary Event / Topic type badge. */
    fun inferTopicBadge(item: SffFeedItem, sourceCount: Int): String {
        val text = "${item.title} ${item.subtitle.orEmpty()} ${item.summary.orEmpty()}".lowercase()
        return when {
            sourceCount >= 3 -> "Trending Across $sourceCount Sources"
            item.category == "SERIES_HORIZON" && text.contains("upcoming") -> "Sequel Countdown"
            item.category == "SERIES_HORIZON" -> "Series Horizon"
            Regex("(?i)\\b(cover reveal|first look|excerpt)\\b").containsMatchIn(text) -> "Cover & Excerpt Reveal"
            Regex("(?i)\\b(hugo|nebula|locus award|world fantasy|award|finalist|winner|shortlist)\\b").containsMatchIn(text) -> "Award Watch"
            Regex("(?i)\\b(sequel|book \\d+|release date|announces new|upcoming novel|book deal|acquires)\\b").containsMatchIn(text) -> "Release & Deal Alert"
            Regex("(?i)\\b(adaptation|tv series|movie|season \\d+|trailer|apple tv|netflix|hbo|prime video)\\b").containsMatchIn(text) -> "SFF Adaptation"
            item.source.contains("Royal Road") || item.source == "AniList" || item.source == "MangaDex" -> "Serial & Manga Pulse"
            item.category == "SOCIALS" -> "Author & Social Dispatch"
            item.category == "COMMUNITY" -> "Community Discussion"
            item.category == "DISCOVERY" -> "Trope Resonance"
            else -> "SFF Editorial & Review"
        }
    }

    /**
     * Generates clean, canonical multi-axis SFF tags for a cluster of items (Subgenre, Topic,
     * Mood/Pace, and Matched Entity).
     */
    fun buildCanonicalTags(items: List<SffFeedItem>, topicBadge: String): List<String> {
        val corpus = items.joinToString(" ") { "${it.title} ${it.subtitle.orEmpty()} ${it.summary.orEmpty()} ${it.tags.joinToString(" ")}" }.lowercase()
        val out = LinkedHashSet<String>()
        out += topicBadge

        // Subgenre taxonomy detection
        val subgenres = listOf(
            "Hard SF" to listOf("hard sf", "hard science fiction", "astrophysics", "first contact", "blindsight", "three-body"),
            "Space Opera" to listOf("space opera", "galactic", "starship", "interstellar", "sun eater", "expanse", "dune"),
            "Epic Fantasy" to listOf("epic fantasy", "high fantasy", "stormlight", "cosmere", "wheel of time", "malazan"),
            "Grimdark" to listOf("grimdark", "abercrombie", "first law", "dark fantasy", "warhammer"),
            "Progression & LitRPG" to listOf("progression", "litrpg", "cultivation", "xianxia", "dungeon crawler", "cradle", "royal road"),
            "Weird & Cosmic Horror" to listOf("weird fiction", "new weird", "cosmic horror", "eldritch", "horror", "vandermeer"),
            "Cyberpunk & AI" to listOf("cyberpunk", "artificial intelligence", "murderbot", "android", "biopunk"),
            "Time Loop & Chronos" to listOf("time loop", "time travel", "alternate history", "dystopian"),
            "Manga & Light Novel" to listOf("manga", "light novel", "anilist", "mangadex"),
            "Indian SFF" to listOf("kalpadb", "indian sff", "bengali", "hindi", "tamil"),
        )
        for ((label, kws) in subgenres) {
            if (kws.any { corpus.contains(it) }) out += label
        }

        // Preserve explicit item tags (moods, tropes, series)
        for (item in items) {
            item.series?.takeIf { it.isNotBlank() }?.let { out += it }
            for (t in item.tags) {
                val clean = t.trim()
                if (clean.length in 3..28 && !clean.equals(item.source, ignoreCase = true)) {
                    out += clean
                }
            }
        }
        return out.take(7).toList()
    }

    /** Extracts normalized content stems and anchor entities for clustering. */
    private fun extractSignature(item: SffFeedItem): Pair<Set<String>, Set<String>> {
        val text = "${item.title} ${item.series.orEmpty()} ${item.author.orEmpty()} ${item.subtitle.orEmpty()}".lowercase()
        val full = "$text ${item.summary.orEmpty().lowercase()}"

        val entities = HashSet<String>()
        item.series?.trim()?.lowercase()?.takeIf { it.length >= 4 }?.let { entities += "series:$it" }
        item.author?.substringBefore(",")?.trim()?.lowercase()?.takeIf { it.length >= 5 && !it.startsWith("@") }?.let { entities += "author:$it" }
        // Quoted book/series titles in headlines ("Wind and Truth", “Murderbot”)
        for (m in Regex("[\"\u201C\u2018']([A-Za-z0-9][A-Za-z0-9 :,'\\-]{4,42})[\"\u201D\u2019']").findAll(item.title)) {
            val q = m.groupValues[1].trim().lowercase()
            if (q !in STOP_WORDS && q.split(" ").size in 1..6) entities += "quote:$q"
        }
        for (anchor in SFF_ANCHOR_ENTITIES) {
            if (full.contains(anchor)) entities += "anchor:$anchor"
        }

        val tokens = Regex("[\\p{L}\\p{N}]{4,}")
            .findAll(text)
            .map { it.value }
            .filter { it !in STOP_WORDS }
            .toSet()

        return entities to tokens
    }

    private fun shouldClubTogether(
        a: SffFeedItem,
        sigA: Pair<Set<String>, Set<String>>,
        b: SffFeedItem,
        sigB: Pair<Set<String>, Set<String>>,
    ): Boolean {
        val (entA, tokA) = sigA
        val (entB, tokB) = sigB

        // 1. Exact same book title
        if (Matcher.baseTitle(a.title) == Matcher.baseTitle(b.title) && Matcher.baseTitle(a.title).length >= 5) return true

        // 2. Shared specific quoted title or series or SFF anchor entity + at least 1 shared content token
        val sharedSpecificEntities = entA.intersect(entB).filter {
            it.startsWith("series:") || it.startsWith("quote:") || it.startsWith("anchor:")
        }
        val sharedTokens = tokA.intersect(tokB)
        if (sharedSpecificEntities.isNotEmpty() && (sharedTokens.size >= 2 || a.source != b.source)) {
            return true
        }

        // 3. Shared author + shared content keywords across different sources
        val sharedAuthors = entA.intersect(entB).filter { it.startsWith("author:") }
        if (sharedAuthors.isNotEmpty() && sharedTokens.size >= 2) return true

        // 4. High headline token overlap (Jaccard / overlap coefficient)
        if (tokA.size >= 3 && tokB.size >= 3) {
            val minSize = minOf(tokA.size, tokB.size).toDouble()
            val overlap = sharedTokens.size / minSize
            if (overlap >= 0.55 && sharedTokens.size >= 3) return true
        }
        return false
    }

    /**
     * Clubs related stories from all sources into [SffStoryCluster] groups and ranks them using
     * the Artifact personalization formula + library taste vector + dwell-time telemetry.
     */
    fun clusterAndRank(
        items: List<SffFeedItem>,
        state: Snapshot,
        nowSec: Long = System.currentTimeMillis() / 1000,
    ): List<SffStoryCluster> {
        val profile = state.sffProfile
        // Filter out explicitly disliked items or muted tags
        val visible = items.filter { item ->
            item.id !in profile.dislikedIds &&
                item.tags.none { t -> profile.mutedTags.any { it.equals(t, ignoreCase = true) } }
        }
        if (visible.isEmpty()) return emptyList()

        val signatures = visible.associateWith { extractSignature(it) }
        val rawClusters = ArrayList<MutableList<SffFeedItem>>()

        for (item in visible) {
            val sig = signatures[item] ?: continue
            val matchCluster = rawClusters.firstOrNull { group ->
                // Check against the cluster's lead and first 2 members
                group.take(3).any { existing ->
                    shouldClubTogether(item, sig, existing, signatures[existing]!!)
                }
            }
            if (matchCluster != null && matchCluster.size < 8) {
                matchCluster.add(item)
            } else {
                rawClusters.add(mutableListOf(item))
            }
        }

        // Build library affinity maps
        val libAuthors = HashMap<String, Double>()
        val libSeries = HashMap<String, Double>()
        val libTropes = HashMap<String, Double>()

        for (b in state.books.values) {
            if (b.held) continue
            val weight = when (b.status) {
                ReadStatus.READING -> 4.0
                ReadStatus.FINISHED -> 3.0 + ((b.rating ?: 3.5) - 3.0) * 1.5
                ReadStatus.TO_READ -> 2.0
                ReadStatus.PAUSED -> 1.0
                ReadStatus.DNF -> -2.0
                null -> 1.5
            }
            b.author?.substringBefore(",")?.trim()?.lowercase()?.takeIf { it.length >= 4 }?.let { a ->
                libAuthors[a] = (libAuthors[a] ?: 0.0) + weight
            }
            val f = state.facts[b.document]
            f?.series?.trim()?.lowercase()?.takeIf { it.length >= 3 }?.let { s ->
                libSeries[s] = (libSeries[s] ?: 0.0) + weight + 1.5
            }
            for (tag in (f?.genres.orEmpty() + f?.moods.orEmpty() + f?.tropes.orEmpty() + b.titleTags)) {
                val k = tag.trim().lowercase()
                if (k.isNotEmpty()) libTropes[k] = (libTropes[k] ?: 0.0) + (weight * 0.45)
            }
        }

        return rawClusters.map { group ->
            // Order perspectives: items with covers/full articles/library matches first, then diverse sources
            val sortedPerspectives = group.sortedWith(
                compareByDescending<SffFeedItem> { it.matchedLibraryBook != null }
                    .thenByDescending { (it.fullContent?.length ?: 0) >= 600 }
                    .thenByDescending { !it.coverUrl.isNullOrBlank() }
                    .thenByDescending { it.publishedAt }
            )
            val lead = sortedPerspectives.first()
            val distinctSources = sortedPerspectives.map { it.source.substringBefore(" ·") }.distinct()
            val topicBadge = inferTopicBadge(lead, distinctSources.size)
            val canonicalTags = buildCanonicalTags(sortedPerspectives, topicBadge)
            val clickbait = isClickbaitHeadline(lead.title) && lead.aiHeadline == null

            // Compute Artifact Personalization Score
            var score = 10.0
            val reasons = ArrayList<String>()

            // 1. Direct Library Match (Active Series / Author / Book)
            val libMatch = sortedPerspectives.firstNotNullOfOrNull { it.matchedLibraryBook }
            if (libMatch != null) {
                score += if (lead.category == "SERIES_HORIZON") 14.0 else 9.5
                reasons += libMatch
            }

            // 2. Multi-Source Cluster Momentum (Breaking SFF Story across multiple sources!)
            if (distinctSources.size >= 2) {
                val multiBoost = ln(1.0 + distinctSources.size) * 4.5
                score += multiBoost
                reasons += "Clubbed from ${distinctSources.size} sources (${distinctSources.take(3).joinToString(", ")})"
            }

            // 3. Library Trope / Genre / Author Affinity
            var tasteHits = 0.0
            val matchedTasteTags = ArrayList<String>()
            for (tag in canonicalTags) {
                val tl = tag.lowercase()
                val libW = (libTropes[tl] ?: 0.0).coerceAtMost(4.5)
                val profW = (profile.tagWeights[tl] ?: 0.0).coerceIn(-4.0, 6.0)
                if (libW + profW > 0.6) {
                    tasteHits += (libW + profW) * 0.7
                    if (matchedTasteTags.size < 2) matchedTasteTags += tag
                } else if (profW < 0) {
                    tasteHits += profW
                }
            }
            score += tasteHits.coerceIn(-6.0, 10.0)
            if (matchedTasteTags.isNotEmpty() && reasons.size < 2) {
                reasons += "Matches your taste in ${matchedTasteTags.joinToString(" & ")}"
            }

            // 4. Source & Author Learned Dwell Affinity (from SffArtifactProfile)
            val srcKey = lead.source.substringBefore(" ·").lowercase()
            score += (profile.sourceWeights[srcKey] ?: 0.0).coerceIn(-3.0, 5.0)
            lead.author?.lowercase()?.let { a ->
                score += (profile.authorWeights[a] ?: 0.0).coerceIn(-3.0, 6.0)
                score += (libAuthors[a] ?: 0.0).coerceIn(-2.0, 5.0)
            }

            // 5. Explicit Like / Already-Read Decay / Clickbait Penalty
            if (sortedPerspectives.any { it.id in profile.likedIds }) {
                score += 6.0
                reasons += "Boosted by you"
            }
            if (lead.id in profile.readIds) {
                score -= 3.2 // Gently move already-read stories below fresh unread ones
            }
            if (clickbait) {
                score -= 2.5
            }

            // 6. Recency decay (fresh stories within the last 24h get a boost)
            val ageHours = ((nowSec - lead.publishedAt).coerceAtLeast(0L)) / 3600.0
            val recencyBoost = 4.0 * exp(-ageHours / 36.0)
            score += recencyBoost

            val matchPct = (65 + (score.coerceIn(0.0, 34.0))).toInt().coerceIn(60, 99)
            val whyLine = if (reasons.isNotEmpty()) {
                "${reasons.first()} · $matchPct% match"
            } else {
                "$topicBadge · $matchPct% taste match"
            }

            SffStoryCluster(
                id = "cluster-${lead.id}",
                lead = lead.copy(artifactScore = score),
                perspectives = sortedPerspectives,
                canonicalTags = canonicalTags,
                topicBadge = topicBadge,
                whyInFeed = whyLine,
                isClickbait = clickbait,
                score = score,
            )
        }.sortedByDescending { it.score }
    }

    // ---- Telemetry Recording (Dwell Time, Opens, Boost / Mute) ----

    /** Records when the user opens and reads a cluster in the in-app reader for [dwellSec] seconds. */
    fun recordReadDwell(store: Store, cluster: SffStoryCluster, dwellSec: Int) {
        store.update { s ->
            val p = s.sffProfile
            val item = cluster.lead
            val prevDwell = p.dwellSeconds[item.id] ?: 0
            // Dwell >= 15s is a positive signal; >= 45s is a strong deep-read signal
            val delta = when {
                dwellSec >= 45 -> 0.85
                dwellSec >= 15 -> 0.45
                dwellSec in 1..3 -> -0.15
                else -> 0.15
            }
            val nextTags = HashMap(p.tagWeights)
            for (t in cluster.canonicalTags) {
                val k = t.lowercase()
                nextTags[k] = ((nextTags[k] ?: 0.0) + delta).coerceIn(-5.0, 8.0)
            }
            val nextSources = HashMap(p.sourceWeights)
            val sk = item.source.substringBefore(" ·").lowercase()
            nextSources[sk] = ((nextSources[sk] ?: 0.0) + delta * 0.6).coerceIn(-4.0, 6.0)

            val nextAuthors = HashMap(p.authorWeights)
            item.author?.lowercase()?.takeIf { it.length >= 4 }?.let { ak ->
                nextAuthors[ak] = ((nextAuthors[ak] ?: 0.0) + delta * 0.8).coerceIn(-4.0, 7.0)
            }

            s.copy(
                sffProfile = p.copy(
                    readIds = p.readIds + item.id,
                    dwellSeconds = p.dwellSeconds + (item.id to (prevDwell + dwellSec)),
                    tagWeights = nextTags,
                    sourceWeights = nextSources,
                    authorWeights = nextAuthors,
                )
            )
        }
    }

    /** Records explicit thumbs-up ("More like this") or thumbs-down ("Less like this") feedback. */
    fun recordFeedback(store: Store, cluster: SffStoryCluster, positive: Boolean) {
        val delta = if (positive) 1.8 else -2.2
        store.update { s ->
            val p = s.sffProfile
            val nextTags = HashMap(p.tagWeights)
            for (t in cluster.canonicalTags) {
                val k = t.lowercase()
                nextTags[k] = ((nextTags[k] ?: 0.0) + delta).coerceIn(-5.0, 8.0)
            }
            val nextSources = HashMap(p.sourceWeights)
            val sk = cluster.lead.source.substringBefore(" ·").lowercase()
            nextSources[sk] = ((nextSources[sk] ?: 0.0) + delta * 0.7).coerceIn(-4.0, 6.0)

            val allClusterIds = cluster.perspectives.map { it.id }.toSet()
            s.copy(
                sffProfile = p.copy(
                    tagWeights = nextTags,
                    sourceWeights = nextSources,
                    likedIds = if (positive) p.likedIds + cluster.lead.id else p.likedIds - allClusterIds,
                    dislikedIds = if (!positive) p.dislikedIds + allClusterIds else p.dislikedIds - allClusterIds,
                )
            )
        }
    }

    // ---- On-Device AI Functions (LiteRT-LM via Ai.model() + Extractive Fallbacks) ----

    /**
     * Generates Artifact's signature **3-Bullet AI Summary** of the article (and multi-source
     * perspectives when present). Uses the on-device LLM (`Ai.model()`) when installed, or
     * deterministic salience sentence extraction when no model is set up.
     */
    fun summarizeArticle(
        cluster: SffStoryCluster,
        bodyMarkdown: String,
    ): Pair<List<String>, Boolean> {
        val model: TextModel? = Ai.model()
        val combinedSourceText = buildString {
            appendLine("Title: ${cluster.lead.title}")
            appendLine("Primary Source (${cluster.lead.source}):")
            appendLine(bodyMarkdown.take(2800))
            if (cluster.perspectives.size > 1) {
                appendLine()
                appendLine("Other Sources Covering This Story:")
                for (p in cluster.perspectives.drop(1).take(4)) {
                    appendLine("- [${p.source}] ${p.title}: ${p.summary.orEmpty().take(260)}")
                }
            }
        }

        if (model != null) {
            val prompt = """
                Summarize this speculative fiction article/story in exactly 3 concise bullet points.
                - Use only facts stated in the text below. Do not invent details or spoilers.
                - If multiple sources are listed, include how community or other sources view it in the 3rd bullet.
                - Start each bullet with "• ". Reply with the 3 bullets only.

                ${combinedSourceText.take(Assistant.MAX_PROMPT_CHARS - 600)}
            """.trimIndent()
            val reply = runCatching { Assistant.clean(model.generate(prompt)) }.getOrNull()
            if (reply != null) {
                val bullets = reply.lines()
                    .map { it.trim().removePrefix("•").removePrefix("-").removePrefix("*").replace(Regex("^\\d+[.)]\\s*"), "").trim() }
                    .filter { it.length in 20..280 }
                    .take(3)
                if (bullets.size >= 2) return bullets to true
            }
        }

        // Extractive Salience Fallback (works on all phones even without a .litertlm model downloaded)
        val cleanText = bodyMarkdown
            .replace(Regex("(?m)^#+\\s+.*$"), "")
            .replace(Regex("\\*\\*|\\*|\\|\\|"), "")
        val sentences = Regex("[^.!?\\n]{35,240}[.!?]")
            .findAll(cleanText)
            .map { it.value.trim() }
            .distinct()
            .toList()
        val titleWords = Regex("[\\p{L}\\p{N}]{4,}").findAll(cluster.lead.title.lowercase()).map { it.value }.filter { it !in STOP_WORDS }.toSet()
        val ranked = sentences.sortedByDescending { s ->
            val words = Regex("[\\p{L}\\p{N}]{4,}").findAll(s.lowercase()).map { it.value }.toSet()
            words.intersect(titleWords).size * 3 + (if (Regex("\\d{4}|Book \\d+").containsMatchIn(s)) 2 else 0)
        }.take(3)
        val out = buildList {
            addAll(ranked)
            if (cluster.perspectives.size > 1 && size < 3) {
                add("Also covered by ${cluster.perspectives.drop(1).joinToString(", ") { it.source }} with ${cluster.perspectives.size} total perspectives.")
            }
            if (isEmpty()) {
                add(cluster.lead.summary?.take(200) ?: cluster.lead.title)
            }
        }.take(3)
        return out to false
    }

    /**
     * Artifact's signature **Anti-Clickbait Headline Rewriter**: rewrites a vague or sensational
     * headline into a direct, factual one using the article body. Uses `Ai.model()` when available,
     * with an extractive lead-sentence fallback.
     */
    fun rewriteClickbait(item: SffFeedItem, bodyMarkdown: String): Pair<String, Boolean> {
        val model: TextModel? = Ai.model()
        val contextText = (bodyMarkdown.ifBlank { item.summary.orEmpty() }).take(2200)
        if (model != null && contextText.length >= 40) {
            val prompt = """
                The headline below is vague or clickbait. Rewrite it as one clear, factual headline of at most 14 words stating the actual news or topic from the article text.
                Use only facts from the text. Reply with the new headline only, without quotes.

                Original headline: ${item.title}
                Article text:
                $contextText
            """.trimIndent()
            val reply = runCatching { Assistant.clean(model.generate(prompt)) }.getOrNull()
                ?.lineSequence()?.firstOrNull()?.trim()?.trim('"', '“', '”', '*')
            if (reply != null && reply.length in 15..120 && !reply.equals(item.title, ignoreCase = true)) {
                return reply to true
            }
        }
        // Deterministic factual headline extraction from the first substantive sentence
        val firstFact = Regex("[^.!?\\n]{25,115}[.!?]").find(contextText.replace(Regex("(?m)^#+\\s+"), ""))?.value?.trim()
            ?: "${item.source}: ${item.title.replace(CLICKBAIT_REGEX, "").trim()}"
        return firstFact.removeSuffix(".") to false
    }

    /**
     * Optional on-device AI feed re-ranker: asks the on-device LLM (`Ai.model()`) to inspect the
     * top candidate stories against the reader's favorite SFF books and tropes and pick the top
     * stories to boost to the very top of the feed.
     */
    fun aiCurateTopPicks(state: Snapshot, clusters: List<SffStoryCluster>): Set<String>? {
        val model = Ai.model() ?: return null
        val candidates = clusters.take(12)
        if (candidates.size < 4) return null

        val lovedBooks = state.books.values
            .filter { !it.held && (it.status == ReadStatus.FINISHED || it.status == ReadStatus.READING) }
            .sortedByDescending { (it.rating ?: 3.5) }
            .take(8)
            .map { "\"${it.displayTitle}\"" + (it.author?.let { a -> " by $a" } ?: "") }
        val topTropes = state.facts.values
            .flatMap { it.genres + it.tropes + it.moods }
            .groupingBy { it }
            .eachCount()
            .entries.sortedByDescending { it.value }.take(8).map { it.key }

        val prompt = buildString {
            appendLine("You curate a personalized speculative fiction news and discovery feed (like the Artifact app).")
            if (lovedBooks.isNotEmpty()) appendLine("My favorite/current SFF books: ${lovedBooks.joinToString(", ")}")
            if (topTropes.isNotEmpty()) appendLine("My top SFF genres, moods & tropes: ${topTropes.joinToString(", ")}")
            appendLine()
            appendLine("Candidate SFF stories today:")
            candidates.forEachIndexed { idx, c ->
                appendLine("${idx + 1}. [${c.lead.source}] ${c.lead.title} (${c.canonicalTags.take(3).joinToString(", ")})")
            }
            appendLine()
            append("Reply with only the numbers of the 4 stories I would care about most, comma separated.")
        }
        val reply = runCatching { Assistant.clean(model.generate(prompt)) }.getOrNull() ?: return null
        val pickedIndices = Regex("\\b(\\d{1,2})\\b").findAll(reply)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1..candidates.size }
            .distinct()
            .take(4)
            .toList()
        if (pickedIndices.isEmpty()) return null
        return pickedIndices.map { candidates[it - 1].lead.id }.toSet()
    }

    /**
     * On-Device AI **Series & Lore Primer ("Explain Context")**: explains the SFF series, author,
     * or award in 2–3 spoiler-free sentences for readers who haven't started the series yet.
     */
    fun explainContext(cluster: SffStoryCluster, bodyMarkdown: String, state: Snapshot): Pair<String, Boolean> {
        val model: TextModel? = Ai.model()
        val item = cluster.lead
        val matchedBook = item.matchedLibraryBook?.let { title ->
            state.books.values.firstOrNull { it.displayTitle.equals(title, ignoreCase = true) }
        }
        val snippet = bodyMarkdown.ifBlank { item.summary.orEmpty() }.take(2000)
        if (model != null && snippet.isNotBlank()) {
            val prompt = buildString {
                appendLine("Give a 2-sentence spoiler-free background primer explaining the speculative fiction book, series, or event in this article and why SFF readers care about it.")
                if (matchedBook != null) {
                    val statusLabel = matchedBook.status?.label ?: "Reading"
                    appendLine("Note: The reader already has '${matchedBook.displayTitle}' in their library ($statusLabel at ${matchedBook.percent}%). Do not spoil anything past their progress.")
                }
                appendLine("Use only facts from the text below. Reply with the 2 sentences only.")
                appendLine()
                appendLine("Title: ${item.title}")
                appendLine("Tags: ${cluster.canonicalTags.joinToString(", ")}")
                appendLine("Text:")
                appendLine(snippet)
            }
            val reply = runCatching { Assistant.clean(model.generate(prompt)) }.getOrNull()?.trim()
            if (!reply.isNullOrBlank() && reply.length in 30..420) {
                return reply to true
            }
        }
        val shelfNote = if (matchedBook != null) {
            val statusLabel = matchedBook.status?.label ?: "Tracked"
            "Connects to \"${matchedBook.displayTitle}\" in your library ($statusLabel · ${matchedBook.percent}%)."
        } else {
            val tagStr = cluster.canonicalTags.take(3).joinToString(", ").ifBlank { "Speculative Fiction" }
            "Tagged under $tagStr."
        }
        val leadSentence = Regex("[^.!?\\n]{35,200}[.!?]").find(snippet.replace(Regex("(?m)^#+\\s+"), ""))?.value?.trim()
            ?: item.summary?.take(180) ?: item.title
        return "$shelfNote $leadSentence" to false
    }

    /**
     * On-Device AI **Multi-Source Perspective Synthesis**: compares how different sources in a
     * clubbed [SffStoryCluster] (e.g. news sites vs Reddit/Bluesky/Mastodon community) cover the story.
     */
    fun comparePerspectives(cluster: SffStoryCluster, bodyMarkdown: String): Pair<String, Boolean> {
        val model: TextModel? = Ai.model()
        val sourcesDesc = cluster.perspectives.mapIndexed { idx, p ->
            "${idx + 1}. [${p.source}] ${p.title}: ${(p.summary ?: p.subtitle).orEmpty().take(260)}"
        }.joinToString("\n")
        if (model != null && cluster.perspectives.size >= 2) {
            val prompt = buildString {
                appendLine("Compare the perspectives across these ${cluster.perspectives.size} sources covering the same speculative fiction story in 2 concise sentences.")
                appendLine("Highlight what the official news/review says versus how the community is reacting. Use only the text below.")
                appendLine()
                appendLine(sourcesDesc)
                appendLine(bodyMarkdown.take(1200))
            }
            val reply = runCatching { Assistant.clean(model.generate(prompt)) }.getOrNull()?.trim()
            if (!reply.isNullOrBlank() && reply.length in 30..420) {
                return reply to true
            }
        }
        val names = cluster.perspectives.map { it.source }.distinct()
        val synthesis: String = if (names.size >= 2) {
            val second = cluster.perspectives.drop(1).first()
            val secondSub = second.subtitle ?: "thread"
            "Covered across ${names.size} sources (${names.joinToString(", ")}): ${cluster.lead.source} leads with \"${cluster.lead.title}\", while ${second.source} adds community discussion ($secondSub)."
        } else {
            "Single-source dispatch from ${cluster.lead.source} under ${cluster.topicBadge}."
        }
        return synthesis to false
    }

    /**
     * On-Device AI **Spoiler Shield for SFF Articles**: checks whether an article or thread risks
     * spoiling a book the reader is currently reading or has on their To-Read shelf.
     */
    fun checkArticleSpoilers(cluster: SffStoryCluster, bodyMarkdown: String, state: Snapshot): Pair<String, Boolean> {
        val model: TextModel? = Ai.model()
        val text = cluster.lead.title + "\n" + bodyMarkdown
        val activeBooks = state.books.values.filter {
            !it.held && (it.status == ReadStatus.READING || it.status == ReadStatus.TO_READ || it.status == ReadStatus.PAUSED)
        }
        val mentionedUnfinished = activeBooks.filter { b ->
            val base = Matcher.baseTitle(b.displayTitle)
            base.length >= 4 && text.lowercase().contains(base)
        }
        val explicitSpoilerMarkers = Regex("(?i)(\\bspoiler\\b|\\bspoilers\\b|ending explained|death of|dies in|plot twist|final chapter|>!.+!<|\\|\\|.+\\|\\|)").containsMatchIn(text)

        if (model != null && bodyMarkdown.length >= 60) {
            val targetTitle = mentionedUnfinished.firstOrNull()?.displayTitle ?: cluster.lead.matchedLibraryBook ?: cluster.lead.title
            val spoilerIdxs = runCatching { Assistant.spoilerSentences(model, targetTitle, bodyMarkdown.take(1600)) }.getOrNull()
            if (spoilerIdxs != null) {
                return if (spoilerIdxs.isEmpty() && !explicitSpoilerMarkers) {
                    "Spoiler-Safe: No plot twists, character deaths, or ending giveaways detected for your unfinished books." to true
                } else {
                    val firstBook = mentionedUnfinished.firstOrNull()
                    val warnBook = if (firstBook != null) "\"${firstBook.displayTitle}\" (${firstBook.percent}% read)" else "\"$targetTitle\""
                    "Spoiler Warning: On-device AI flagged ${spoilerIdxs.size.coerceAtLeast(1)} passage(s) that may reveal plot details for $warnBook." to true
                }
            }
        }

        return if (explicitSpoilerMarkers || cluster.lead.spoilerWarning) {
            val firstBook = mentionedUnfinished.firstOrNull()
            val warnBook = if (firstBook != null) " (matches \"${firstBook.displayTitle}\" on your active shelf)" else ""
            "Spoiler Caution: Contains spoiler tags or plot-discussion keywords$warnBook. Proceed with care if you haven't caught up." to false
        } else {
            "Spoiler-Safe: No spoiler tags or ending keywords detected across this article." to false
        }
    }
}
