package io.github.prithirajn.trackersync.source

import android.content.Context

/** Public-build stand-in: Archive search is available only in the private studio source set. */
object SamArchive {
    fun hasSource(context: Context): Boolean = false

    fun search(context: Context, query: String): List<Pair<Opds.Server, Opds.Entry>> = emptyList()
}
