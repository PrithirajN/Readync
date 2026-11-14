package io.github.prithirajn.readync.trackers

import io.github.prithirajn.readync.util.str
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.LocalDate

/**
 * Goodreads' review editor (/review/edit/{id}) is a Next.js page. Review text and read dates
 * are saved through its "submitReviewFormAction" Server Action, whose id is compiled into the
 * page's script chunks and changes between builds, so it's found from the page each time.
 * Ported from ShelfSync's Goodreads client (MIT).
 */
internal object GoodreadsEditor {

    /** What the editor page holds for this book. */
    class State(
        val bookId: String,
        val sessions: JSONArray,
        val reviewText: String,
        val privateNotes: String,
        val postToBlog: Boolean,
        val addToFeed: Boolean,
        val spoiler: Boolean,
        val owned: Boolean,
    )

    /** The page's streamed React data: every self.__next_f.push([1,"…"]) string, joined. */
    fun flight(html: String): String? {
        val rows = ArrayList<String>()
        val marker = "self.__next_f.push"
        var from = 0
        while (true) {
            val at = html.indexOf(marker, from)
            if (at < 0) break
            val open = html.indexOf('[', at + marker.length)
            if (open < 0) break
            val end = balancedEnd(html, open) ?: break
            runCatching { JSONArray(html.substring(open, end + 1)) }.getOrNull()?.let { a ->
                if (a.optInt(0, -1) == 1 && a.opt(1) is String) rows.add(a.getString(1))
            }
            from = end + 1
        }
        return if (rows.isEmpty()) null else rows.joinToString("\n")
    }

    /** Index of the bracket that closes the one at [start], skipping strings. */
    fun balancedEnd(text: String, start: Int): Int? {
        val stack = ArrayList<Char>()
        var quoted = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (quoted) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') quoted = false
                continue
            }
            when (ch) {
                '"' -> quoted = true
                '{' -> stack.add('}')
                '[' -> stack.add(']')
                '}', ']' -> {
                    if (stack.isEmpty() || stack.last() != ch) return null
                    stack.removeAt(stack.size - 1)
                    if (stack.isEmpty()) return i
                }
            }
        }
        return null
    }

    /** The first `"name":` value in [text] of the wanted type (JSONArray, String, Boolean). */
    inline fun <reified T> property(text: String, name: String): T? {
        val needle = "\"$name\":"
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return null
            val v = runCatching { JSONTokener(text.substring(at + needle.length)).nextValue() }.getOrNull()
            if (v is T) return v
            from = at + needle.length
        }
    }

    fun state(html: String): State? {
        val f = flight(html) ?: return null
        val sessions = property<JSONArray>(f, "readingSessions") ?: JSONArray()
        val bookId = sessions.optJSONObject(0)?.str("bookId")
            ?: Regex("\"bookId\":\"(kca://book/[^\"]*)\"").find(f)?.groupValues?.get(1)
            ?: Regex("\"id\":\"(kca://book/[^\"]*)\"").find(f)?.groupValues?.get(1)
            ?: return null
        return State(
            bookId = bookId,
            sessions = sessions,
            reviewText = property<String>(f, "userFormattedText") ?: property<String>(f, "reviewText") ?: "",
            privateNotes = property<String>(f, "initialPrivateNotes") ?: property<String>(f, "privateNotes") ?: "",
            postToBlog = property<Boolean>(f, "initialPostToBlog") == true,
            addToFeed = property<Boolean>(f, "initialAddToUpdateFeed") != false,
            spoiler = property<Boolean>(f, "spoilerStatus") == true,
            owned = property<Boolean>(f, "isAlreadyOwned") == true,
        )
    }

    /** Script chunk paths on the page that might hold the action, last first (the page chunk loads last). */
    fun chunkPaths(html: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in Regex("<script[^>]*\\ssrc=\"([^\"]+)\"").findAll(html)) {
            val src = m.groupValues[1].replace("&amp;", "&")
            val path = src.removePrefix("https://www.goodreads.com").substringBefore('?').substringBefore('#')
            if (!path.startsWith("/_next/static/chunks/") || !path.endsWith(".js") || path.contains("..")) continue
            if (path.contains("/polyfills-") || path.contains("/webpack-") || path.contains("/app/not-found-")) continue
            if (path.contains("/app/review/edit/") && path.contains("/error-")) continue
            out.add(path)
        }
        return out.toList().reversed()
    }

    /** The action id next to its name: createServerReference("abc123…", …, "submitReviewFormAction"). */
    fun actionId(source: String, name: String = "submitReviewFormAction"): String? {
        val label = "\"$name\""
        var from = 0
        while (true) {
            val at = source.indexOf(label, from)
            if (at < 0) return null
            val prefix = source.substring(maxOf(0, at - 220), at)
            val marker = prefix.lastIndexOf("createServerReference")
            if (marker >= 0) {
                val call = prefix.substring(marker)
                Regex("createServerReference[^(]*\\(\\s*[\"']([0-9a-fA-F]+)[\"']").find(call)?.groupValues?.get(1)
                    ?.takeIf { it.length >= 32 }?.let { return it }
            }
            from = at + label.length
        }
    }

    /** True unless the action's reply lists errors. */
    fun noErrors(response: String): Boolean {
        val ref = Regex("\"errors\":\"\\\$Q(\\w+)\"").find(response)?.groupValues?.get(1) ?: return true
        val row = Regex("[\\r\\n]$ref:([^\\r\\n]+)").find(response)?.groupValues?.get(1) ?: return false
        return runCatching { JSONArray(row).length() == 0 }.getOrDefault(false)
    }

    /** The router tree Next.js expects for /review/edit/[id]. */
    fun routerTree(bookId: String): String =
        "[\"\",{\"children\":[\"review\",{\"children\":[\"edit\",{\"children\":[[\"id\",\"$bookId\",\"d\"],{\"children\":[\"__PAGE__\",{},null,null]},null,null]}]},null,null]},null,null,true]"

    /** The Server Action's body: the form as the editor would send it. */
    fun body(state: State, reviewText: String, sessions: JSONArray, initialSessions: Any): String {
        val payload = JSONObject()
            .put("bookId", state.bookId)
            .put("reviewText", reviewText)
            .put("spoilerStatus", state.spoiler)
            .put("isOwnedEdition", state.owned)
            .put("privateNotes", state.privateNotes)
            .put("postToBlog", state.postToBlog)
            .put("addToUpdateFeed", state.addToFeed)
            .put("readingSessions", sessions)
            .put("initialReadingSessions", initialSessions)
        return JSONArray().put(payload).put("/review/edit/[id]").toString()
    }

    private fun dateObj(d: LocalDate) = JSONObject()
        .put("__typename", "NullableDate").put("year", d.year).put("month", d.monthValue).put("day", d.dayOfMonth)

    private fun sameDay(v: Any?, d: LocalDate): Boolean {
        val o = v as? JSONObject ?: return false
        return o.optInt("year") == d.year && o.optInt("month") == d.monthValue && o.optInt("day") == d.dayOfMonth
    }

    private fun after(v: Any?, d: LocalDate): Boolean {
        val o = v as? JSONObject ?: return false
        val y = o.optInt("year", 0); val m = o.optInt("month", 0); val day = o.optInt("day", 0)
        if (y == 0 || m == 0 || day == 0) return false
        return LocalDate.of(y, m, day).isAfter(d)
    }

    fun hasEnd(session: JSONObject): Boolean = (session.opt("endedDate") as? JSONObject)?.optInt("year", 0)?.let { it > 0 } == true

    /**
     * The reading sessions with this finish day: closes the open read if there is one,
     * else adds a completed read on that day. Null when it's already there.
     */
    fun finishedSessions(state: State, finished: LocalDate, started: LocalDate?, nowMs: Long): JSONArray? {
        val src = state.sessions
        for (i in 0 until src.length()) if (sameDay(src.optJSONObject(i)?.opt("endedDate"), finished)) return null
        val out = JSONArray()
        var closed = false
        for (i in 0 until src.length()) {
            val s = src.optJSONObject(i)
            if (s == null) { out.put(src.opt(i)); continue }
            if (!closed && s.str("bookId") == state.bookId && s.str("state") == "READING" && !hasEnd(s) && !after(s.opt("startedDate"), finished)) {
                val copy = JSONObject(s.toString()).put("endedDate", dateObj(finished))
                if (started != null) copy.put("startedDate", dateObj(started))
                out.put(copy)
                closed = true
            } else {
                out.put(s)
            }
        }
        if (!closed) {
            if (src.length() >= 25) throw IllegalStateException("Goodreads already has the most reading sessions it allows")
            out.put(
                JSONObject().put("__typename", "ReadingSession").put("id", "new-$nowMs").put("bookId", state.bookId)
                    .put("state", "COMPLETED").put("startedDate", dateObj(started ?: finished)).put("endedDate", dateObj(finished))
            )
        }
        return out
    }

    fun hasDay(state: State, finished: LocalDate): Boolean =
        (0 until state.sessions.length()).any { sameDay(state.sessions.optJSONObject(it)?.opt("endedDate"), finished) }
}
