package io.github.prithirajn.trackersync.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.prithirajn.trackersync.R
import io.github.prithirajn.trackersync.trackers.OAuthStart
import io.github.prithirajn.trackersync.trackers.Tracker
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType

/** A server you run (Audiobookshelf, Jellyfin, BookOrbit, another KOSync server): its address, then your login or key. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSignInSheet(vm: AppViewModel, t: Tracker, onClose: () -> Unit) {
    val login = t.serverLogin ?: return
    var server by remember { mutableStateOf(login.defaultServer ?: "") }
    var user by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val c = Cp.colors
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text("Link ${t.name}", style = CpType.section, color = c.text)
            Quiet(login.help)
            VSpace(16)
            CpTextField(server, { server = it.trim(); error = null }, "Server address", login.serverPlaceholder, keyboard = KeyboardType.Uri)
            login.userLabel?.let { label ->
                VSpace(12)
                CpTextField(user, { user = it; error = null }, label)
            }
            VSpace(12)
            CpTextField(secret, { secret = it; error = null }, login.secretLabel, password = login.secretIsPassword, mono = !login.secretIsPassword)
            error?.let { VSpace(10); Text(it, style = CpType.small, color = c.errorText) }
            VSpace(16)
            BigButton(
                "Link ${t.name}",
                onClick = {
                    busy = true; error = null
                    vm.linkTracker(t, { t.signInWithServer(server.trim(), user.trim(), secret) }) { e ->
                        busy = false
                        if (e == null) onClose() else error = e
                    }
                },
                busy = busy,
                enabled = server.isNotBlank() && secret.isNotEmpty() && (login.userLabel == null || user.isNotBlank()),
            )
            VSpace(12)
            Quiet(login.keptNote, color = c.faint)
        }
    }
}

/**
 * Your own app at the service (Spotify), then its sign-in page in here: the page sends you back
 * to a loopback address with a code, which is caught before anything loads and swapped for a login.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OAuthSignInSheet(vm: AppViewModel, t: Tracker, onClose: () -> Unit) {
    var clientId by remember { mutableStateOf("") }
    var started by remember { mutableStateOf<OAuthStart?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val c = Cp.colors
    val clipboard = LocalClipboardManager.current
    val redirect = remember { runCatching { t.oauthStart("0".repeat(32)).redirectUri }.getOrNull() }
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text("Link ${t.name}", style = CpType.section, color = c.text)
            Quiet(t.oauthHelp)
            redirect?.let { r ->
                VSpace(12)
                CpCard {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 10.dp)) {
                            Quiet("Redirect URI", color = c.faint)
                            Text(r, style = CpType.body, color = c.text)
                        }
                        PillButton("Copy", { clipboard.setText(AnnotatedString(r)) })
                    }
                }
            }
            VSpace(12)
            CpTextField(clientId, { clientId = it.trim(); error = null }, "Client ID", mono = true)
            error?.let { VSpace(10); Text(it, style = CpType.small, color = c.errorText) }
            VSpace(16)
            BigButton(
                "Sign in to ${t.name}",
                onClick = { error = null; started = runCatching { t.oauthStart(clientId) }.onFailure { error = it.message }.getOrNull() },
                busy = busy,
                enabled = clientId.length >= 16,
            )
            VSpace(12)
            Quiet("Only the sign-in tokens are kept, encrypted on this phone, with your app's client ID. No secret is needed.", color = c.faint)
        }
    }
    started?.let { s ->
        OAuthPage(t, s, onClose = { started = null }) { code ->
            started = null
            busy = true
            vm.linkTracker(t, { t.oauthFinish(clientId, code, s) }) { e ->
                busy = false
                if (e == null) onClose() else error = e
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OAuthPage(t: Tracker, start: OAuthStart, onClose: () -> Unit, onCode: (String) -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val c = Cp.colors
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconCircleButton(R.drawable.lucide_x, "Cancel", onClose)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text("Sign in to ${t.name}", style = CpType.bodyStrong, color = c.text)
                    Quiet("Sign in and agree; this closes by itself.", maxLines = 1)
                }
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = c.faint, strokeWidth = 2.dp)
                HSpace(8)
            }
            error?.let { Text(it, style = CpType.small, color = c.errorText, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            RowDivider()
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            /** The way back with the code: caught here, never loaded. */
                            var handled = false
                            fun caught(url: String?): Boolean {
                                if (url == null || !url.startsWith(start.redirectUri)) return false
                                if (handled) return true
                                handled = true
                                val u = Uri.parse(url)
                                val code = u.getQueryParameter("code")
                                when {
                                    u.getQueryParameter("state") != start.state -> error = "That sign-in didn't come from here. Try again."
                                    code != null -> onCode(code)
                                    else -> error = when (val e = u.getQueryParameter("error")) {
                                        "access_denied" -> "You didn't agree, so ${t.name} isn't linked."
                                        null -> "${t.name} didn't send a sign-in code."
                                        else -> "${t.name} said: $e"
                                    }
                                }
                                return true
                            }
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = caught(request?.url?.toString())
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                if (caught(url)) view?.stopLoading() else loading = true
                            }
                            override fun onPageFinished(view: WebView?, url: String?) { loading = false }
                        }
                        loadUrl(start.url)
                    }
                },
            )
        }
    }
}
