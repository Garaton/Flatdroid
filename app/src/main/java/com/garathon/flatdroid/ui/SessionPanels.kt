package com.garathon.flatdroid.ui

import android.content.ClipData
import android.os.PersistableBundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.FlathubLinkApproval
import com.garathon.flatdroid.flatpak.FlatpakTask
import com.garathon.flatdroid.flatpak.FlatpakTaskUi
import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.session.SessionController
import com.garathon.flatdroid.session.SessionScript
import com.garathon.flatdroid.session.SessionState
import com.garathon.flatdroid.session.SessionUi
import com.garathon.flatdroid.session.Viewer
import kotlinx.coroutines.launch

/** A download is about to start on a metered connection (mobile data, mostly): the user decides. */
@Composable
fun MobileDataDialog(onDownload: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mobile_data_title)) },
        text = { Text(stringResource(R.string.mobile_data_text)) },
        confirmButton = { Button(onClick = onDownload) { Text(stringResource(R.string.mobile_data_download)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reset_cancel)) } },
    )
}

/**
 * Typed app IDs to install. Install checks them first and names the ones that can't work: not an
 * app ID at all, or (when Flathub's catalog for this phone is loaded, [inCatalog]) not in it, which
 * would fail the whole install, other apps waiting with it included; or installed already
 * ([installed], when the list of installed apps is known).
 */
@Composable
fun InstallDialog(
    onInstall: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    inCatalog: ((String) -> Boolean)? = null,
    installed: Set<String>? = null,
) {
    var id by rememberSaveable { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val words = InstalledApps.splitIds(id)
    val notIds = words.filterNot(InstalledApps::isValidId)
    val problemText = when (problem) {
        PROBLEM_NOT_ID -> stringResource(R.string.install_not_an_id, notIds.joinToString(", "))
        PROBLEM_UNKNOWN -> stringResource(R.string.install_unknown_id, words.filter { inCatalog != null && !inCatalog(it) }.joinToString(", "))
        PROBLEM_INSTALLED -> stringResource(R.string.install_already_installed, words.filter { installed != null && it in installed }.joinToString(", "))
        else -> null
    }
    val submit = {
        problem = when {
            notIds.isNotEmpty() -> PROBLEM_NOT_ID
            inCatalog != null && words.any { !inCatalog(it) } -> PROBLEM_UNKNOWN
            installed != null && words.any { it in installed } -> PROBLEM_INSTALLED
            else -> null
        }
        if (problem == null && words.isNotEmpty()) onInstall(words)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.install_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.install_text))
                // Flathub links that Android doesn't open in Flatdroid yet: say where to allow them (Android 12 and later).
                val context = LocalContext.current
                if (rememberFlathubLinksAllowed() == false) {
                    // A link to Android's screen for it (Open by default).
                    Text(
                        stringResource(R.string.install_tip_links),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable(role = Role.Button) { FlathubLinkApproval.openSettings(context) },
                    )
                }
                OutlinedTextField(
                    value = id,
                    onValueChange = {
                        id = it
                        problem = null
                    },
                    label = { Text(stringResource(R.string.install_id_label)) },
                    placeholder = { Text("org.xfce.mousepad") },
                    singleLine = true,
                    isError = notIds.isNotEmpty() || problemText != null,
                    supportingText = problemText?.let { { Text(it) } },
                    // The keyboard's action key installs, like the button.
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                )
            }
        },
        confirmButton = { Button(onClick = submit, enabled = words.isNotEmpty() && problemText == null) { Text(stringResource(R.string.install_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reset_cancel)) } },
    )
}

private const val PROBLEM_NOT_ID = "notId"
private const val PROBLEM_UNKNOWN = "unknown"
private const val PROBLEM_INSTALLED = "installed"

/** Asks before downloading Electron for this device, or explains why a swap can't work. */
@Composable
fun ElectronDialog(offer: SessionState.ElectronOffer, appName: String, deviceArch: String, onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.electron_title)) },
        text = {
            Text(
                offer.problem?.let { stringResource(R.string.electron_cannot, appName, offer.fromArch, it) }
                    ?: stringResource(R.string.electron_offer, appName, offer.version ?: "?", offer.fromArch, deviceArch),
            )
        },
        confirmButton = {
            if (offer.problem == null) Button(onClick = onAccept) { Text(stringResource(R.string.electron_download)) }
            else TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        },
        dismissButton = { if (offer.problem == null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.reset_cancel)) } },
    )
}

/** The file check found changed files: the launch was refused; Reinstall restores the packages. */
@Composable
fun FilesChangedDialog(state: SessionState.FilesChanged, appName: String, onReinstall: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_changed_title, appName)) },
        text = { SelectionContainer { Text(state.detail, style = MaterialTheme.typography.bodyMedium) } },
        confirmButton = { Button(onClick = onReinstall) { Text(stringResource(R.string.session_reinstall)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reset_cancel)) } },
    )
}

/**
 * Before launching an app whose microphone asks, when Flatdroid can't post the question later
 * (notifications are off): the answer holds for the session. Dismissing it cancels the launch.
 */
@Composable
fun MicrophoneLaunchDialog(appName: String, onAnswer: (allow: Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mic_launch_title, appName)) },
        text = { Text(stringResource(R.string.mic_launch_text)) },
        confirmButton = { Button(onClick = { onAnswer(true) }) { Text(stringResource(R.string.mic_allow)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.mic_deny)) } },
    )
}

/** Output lines, selectable so they can be copied into a bug report. */
@Composable
fun LogDialog(title: String, lines: List<String>, onDismiss: () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Opens at the end, where the latest output is.
    LaunchedEffect(Unit) { scroll.scrollTo(scroll.maxValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(scroll)) {
                LogText(lines.joinToString("\n").ifEmpty { "—" })
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { scope.launch { scroll.animateScrollTo(0) } }) { Text(stringResource(R.string.log_top)) }
                TextButton(onClick = { scope.launch { scroll.animateScrollTo(scroll.maxValue) } }) { Text(stringResource(R.string.log_bottom)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) } },
    )
}
