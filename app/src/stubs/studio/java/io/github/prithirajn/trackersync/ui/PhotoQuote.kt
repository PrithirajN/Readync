package io.github.prithirajn.trackersync.ui

import androidx.compose.runtime.Composable
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.Snapshot

/**
 * THE STAND-IN for "Book photo from a page", part of the Book photo studio, which isn't in the
 * public code. The official Readync APK has the real one.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun PhotoQuoteSheet(vm: AppViewModel, state: Snapshot, book: Book, onClose: () -> Unit) {
    BookStudio(BookShot.Content(text = "", author = null, title = book.displayTitle, chapter = null, page = null), null, {}, "", onClose)
}
