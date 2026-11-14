package io.github.prithirajn.trackersync.source

import android.content.Context

/** Public-build stand-in: Wikipedia archive discovery is private studio functionality. */
object WikipediaMirrorLookup {
    const val DEFAULT_ARTICLE_URL = "https://en.wikipedia.org/wiki/Anna%27s_Archive"

    data class Result(val url: String, val reachable: Boolean, val detail: String)

    fun articleUrl(context: Context): String = DEFAULT_ARTICLE_URL

    fun saveArticleUrl(context: Context, value: String) = Unit

    fun discoverAndCheck(articleUrl: String): List<Result> = emptyList()
}
