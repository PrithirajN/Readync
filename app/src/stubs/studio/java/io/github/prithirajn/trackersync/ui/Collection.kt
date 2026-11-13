package io.github.prithirajn.trackersync.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.Snapshot

/*
 * THE STAND-IN for Your collection, which isn't part of the public code: the same names, so the
 * rest of the app builds the same, with nothing shown. The official Readync APK, from
 * github.com/PrithirajN/readync, has the real one.
 */

object CollectionHooks {
    const val available = false
    fun summary(state: Snapshot): String = ""
    fun ownedLabel(state: Snapshot, book: Book): String? = null
}

@Composable
fun CollectionSheet(vm: AppViewModel, state: Snapshot, onClose: () -> Unit) {
    LaunchedEffect(Unit) { onClose() }
}

@Composable
fun OwnedCard(vm: AppViewModel, state: Snapshot, book: Book, startOpen: Boolean = false) {
}

@Composable
fun JournalSheet(state: Snapshot, book: Book, onClose: () -> Unit) {
    LaunchedEffect(Unit) { onClose() }
}
