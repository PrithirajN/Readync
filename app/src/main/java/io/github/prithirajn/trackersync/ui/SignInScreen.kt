package io.github.prithirajn.trackersync.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpType

@Composable
fun SignInScreen(vm: AppViewModel) {
    var server by remember { mutableStateOf("sync.crosspointreader.com") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 40.dp)
    ) {
        Eyebrow("hello, reader")
        VSpace(4)
        PageTitle(io.github.prithirajn.trackersync.Edition.name)
        VSpace(8)
        Text(
            "Sends your CrossPoint reading progress to Fable, Pagebound, StoryGraph and Goodreads.",
            style = CpType.body,
            color = Cp.colors.quiet,
        )
        VSpace(28)
        Text("Sign in with your CrossPoint Sync account", style = CpType.bodyStrong, color = Cp.colors.text)
        Text(
            "The same username and password your reader uses for KOReader Sync.",
            style = CpType.small,
            color = Cp.colors.quiet,
        )
        VSpace(16)
        CpTextField(server, { server = it }, "Server", "sync.crosspointreader.com", keyboard = KeyboardType.Uri, mono = true)
        VSpace(12)
        CpTextField(username, { username = it }, "Username")
        VSpace(12)
        CpTextField(password, { password = it }, "Password", password = true)
        error?.let {
            VSpace(12)
            Text(it, style = CpType.small, color = Cp.colors.errorText)
        }
        VSpace(20)
        BigButton(
            "Sign in",
            onClick = {
                busy = true
                error = null
                vm.signIn(server, username, password) { e ->
                    busy = false
                    error = e
                }
            },
            busy = busy,
            enabled = username.isNotBlank() && password.isNotEmpty(),
        )
        VSpace(16)
        Quiet(
            "Your password is turned into the same key the reader sends and stored encrypted on this phone. " +
                "Turn on \"Send Metadata\" in the reader's sync settings so books can be matched by title.",
        )
    }
}
