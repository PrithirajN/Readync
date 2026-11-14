package io.github.prithirajn.readync.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.prithirajn.readync.source.BookCopies
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Services › Storage: what the app keeps on this phone, by kind, with the size of each and a way to
 * clear what can be cleared (the AI model, the copies for quotes, pictures kept to load faster).
 * Your reading data and website sign-ins are shown but not cleared here.
 */
@Composable
fun StorageCard(vm: AppViewModel, onAi: () -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var round by remember { mutableIntStateOf(0) }
    var sizes by remember { mutableStateOf<Map<String, Long>?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(round) {
        sizes = withContext(Dispatchers.IO) { measure(context) }
    }
    val s = sizes
    Quiet("Android's \"Data\" for Readync is all of this together; \"Cache\" is the pictures line, which Android may clear by itself when space is short.", Modifier.padding(start = 4.dp, bottom = 10.dp))
    CpCard {
        Column {
            fun row(label: String, key: String, note: String, action: String? = null, onAction: (() -> Unit)? = null): @Composable () -> Unit = {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = CpType.bodyStrong, color = c.text)
                        Quiet(note, maxLines = 3)
                    }
                    HSpace(10)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(s?.get(key)?.let { storageSize(it) } ?: "…", style = CpType.bodyStrong, color = c.text)
                        if (action != null && onAction != null && (s?.get(key) ?: 0L) > 0L) {
                            VSpace(6)
                            PillButton(action, onAction)
                        }
                    }
                }
            }
            row("On-device AI model", "ai", "The model the AI features run on. Usually the biggest thing here.", "Manage", onAi)()
            RowDivider()
            row("Copies for quotes", "copies", "Books kept for the lines around your quotes (Catalogs › Copies for quotes).", "Remove all") {
                scope.launch { withContext(Dispatchers.IO) { BookCopies.removeAll(context) }; said = "Removed the copies"; round++ }
            }()
            RowDivider()
            row("Pictures", "cache", "Covers, share images and pages kept so they load quickly. Fetched again when needed.", "Clear") {
                scope.launch { withContext(Dispatchers.IO) { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }; said = "Cleared the pictures"; round++ }
            }()
            RowDivider()
            row("Your reading data", "data", "Books, highlights, history, prices and settings. Kept: it's your library (back it up under Backup & import).")()
            RowDivider()
            row("Website sign-ins", "web", "Kept by the app's browser for Goodreads, StoryGraph and the sites read through it. Clearing would sign you out, so it's left alone.")()
            RowDivider()
            row("Everything else", "other", "Background sync's bookkeeping, the on-device text reader and the like.")()
        }
    }
    said?.let { VSpace(8); AnimatedQuiet(it, color = c.textMid) }
    s?.let {
        VSpace(8)
        Quiet("In all about ${storageSize(it.values.sum())} on this phone.", Modifier.padding(start = 4.dp), color = c.faint)
    }
}

private fun bytesIn(f: File?): Long = when {
    f == null || !f.exists() -> 0L
    f.isFile -> f.length()
    else -> f.listFiles()?.sumOf { bytesIn(it) } ?: 0L
}

/** Each kind's bytes: the AI model, the copies, the cache, your data, the browser's, and the rest of the app's own storage. */
private fun measure(context: android.content.Context): Map<String, Long> {
    val files = context.filesDir
    val data = files.parentFile
    val ai = bytesIn(File(files, "models"))
    val copies = bytesIn(File(files, "book-copies"))
    val cache = bytesIn(context.cacheDir) + bytesIn(context.externalCacheDir)
    val mine = bytesIn(File(files, "tracker-sync.json")) + bytesIn(File(data, "shared_prefs")) + bytesIn(File(files, "tracker-sync.json.bak"))
    val web = bytesIn(File(data, "app_webview")) + bytesIn(File(data, "app_webview_PageBrowser"))
    val all = bytesIn(data) + bytesIn(context.getExternalFilesDir(null))
    val other = (all - ai - copies - bytesIn(context.cacheDir) - mine - web).coerceAtLeast(0)
    return linkedMapOf("ai" to ai, "copies" to copies, "cache" to cache, "data" to mine, "web" to web, "other" to other)
}

private fun storageSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
    else -> "${(bytes / 1000).coerceAtLeast(0)} KB"
}
