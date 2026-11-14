package io.github.prithirajn.trackersync.util

object Html {
    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "ndash" to "–", "mdash" to "—", "hellip" to "…",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
    )
    private val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val tag = Regex("<[^>]*>")
    private val space = Regex("\\s+")

    /** Text made safe to put inside HTML. */
    fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun decode(s: String): String = entity.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") ->
                e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") ->
                e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> named[e] ?: m.value
        }
    }

    /** Visible text of an HTML fragment: tags removed, entities decoded, spaces collapsed. */
    fun text(fragment: String): String = space.replace(decode(tag.replace(fragment, " ")), " ").trim()

    /** The Rails CSRF token from a page's meta tag or hidden form field. */
    fun csrf(html: String): String? {
        val patterns = listOf(
            Regex("<meta\\s+[^>]*name=[\"']csrf-token[\"']\\s+[^>]*content=[\"']([^\"']+)[\"']"),
            Regex("<meta\\s+[^>]*content=[\"']([^\"']+)[\"']\\s+[^>]*name=[\"']csrf-token[\"']"),
            Regex("<input\\s+[^>]*name=[\"']authenticity_token[\"']\\s+[^>]*value=[\"']([^\"']+)[\"']"),
        )
        for (p in patterns) {
            p.find(html)?.let { return decode(it.groupValues[1]) }
        }
        return null
    }
}
