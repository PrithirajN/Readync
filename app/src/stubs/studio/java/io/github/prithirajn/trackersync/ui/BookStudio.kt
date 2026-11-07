package io.github.prithirajn.trackersync.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * THE STAND-IN for the Book photo studio, which isn't part of the public code. The official
 * Readync APK has the real studio.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun BookStudio(
    base: BookShot.Content,
    pageText: String?,
    onPageText: (String?) -> Unit,
    plain: String,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
        title = { Text("Book photo") },
        text = { Text("The Book photo studio isn't part of the public code. It's in the official Readync app, from github.com/PrithirajN/readync.") },
    )
}
