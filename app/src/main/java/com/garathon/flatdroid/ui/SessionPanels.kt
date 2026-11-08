package com.garathon.flatdroid.ui

import android.content.ClipData
import android.os.PersistableBundle
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.FlatpakTask
import com.garathon.flatdroid.flatpak.FlatpakTaskUi
import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.session.SessionController
import com.garathon.flatdroid.session.SessionScript
import com.garathon.flatdroid.session.SessionState
import com.garathon.flatdroid.session.SessionUi
import com.garathon.flatdroid.session.Viewer
import kotlinx.coroutines.launch

/**
 * The running session, or how the last one ended. Nothing when idle with no message. An external
 * viewer's password shows only on tap.
 */
@Composable
fun SessionPanel(
    session: SessionUi,
    nameOf: (String) -> String,
    onOpenViewer: (SessionState.Running) -> Unit,
    onStop: () -> Unit,
    onShowLog: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state = session.state
    if (state is SessionState.Idle && session.message == null) return
    if (state is SessionState.Question) return
    val colors = MaterialTheme.colorScheme
    val failed = state is SessionState.Idle && session.failed
    Surface(
        color = if (failed) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (failed) colors.onErrorContainer else colors.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is SessionState.Starting -> {
                    Text(stringResource(R.string.session_starting_app, nameOf(state.appId)), style = MaterialTheme.typography.titleSmall)
                    Text(state.detail, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is SessionState.PreparingElectron -> {
                    Text(stringResource(R.string.electron_preparing_app, nameOf(state.appId)), style = MaterialTheme.typography.titleSmall)
                    Text(state.detail, style = MaterialTheme.typography.bodySmall)
                    val fraction = state.fraction
                    if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is SessionState.Running -> {
                    Text(stringResource(R.string.session_running_app, nameOf(state.appId)), style = MaterialTheme.typography.titleSmall)
                    // Flatdroid's own viewer needs no address or login: only an external one does.
                    if (state.socket == null) {
                        state.username?.let { Text(stringResource(R.string.session_username, it), style = MaterialTheme.typography.bodyMedium) }
                        PasswordLine(state.password)
                    }
                }
                is SessionState.Stopping -> Text(stringResource(R.string.session_stopping, nameOf(state.appId)), style = MaterialTheme.typography.titleSmall)
                else -> session.message?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) } }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state is SessionState.Running) Button(onClick = { onOpenViewer(state) }) { Text(stringResource(if (state.socket != null) R.string.session_open_app else R.string.session_open_viewer)) }
                if (state is SessionState.Starting && state.skippable) OutlinedButton(onClick = { SessionController.skipFileCheck() }) { Text(stringResource(R.string.session_skip_check)) }
                when (state) {
                    is SessionState.Starting, is SessionState.Running, is SessionState.PreparingElectron ->
                        OutlinedButton(onClick = onStop) { Text(stringResource(R.string.session_stop)) }
                    else -> Unit
                }
                TextButton(onClick = onShowLog) { Text(stringResource(R.string.show_log)) }
                if (state is SessionState.Idle) TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
            }
        }
    }
}

/** The display's address and password; the password only once Show is tapped. */
@Composable
private fun PasswordLine(password: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var shown by rememberSaveable { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.session_address, Viewer.HOST, SessionScript.VNC_PORT),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!shown) {
            TextButton(onClick = { shown = true }) { Text(stringResource(R.string.session_show_password)) }
        } else {
            Text(
                stringResource(R.string.session_password, password),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(sensitiveText("VNC password", password))) } }) {
            Text(stringResource(R.string.session_copy_password))
        }
    }
}

/**
 * Clipboard text marked sensitive, so keyboards and Android's clipboard preview don't show or keep
 * it (Android 13+; older versions ignore the mark).
 */
private fun sensitiveText(label: String, text: String): ClipData = ClipData.newPlainText(label, text).apply {
    description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
}

/** `ClipDescription.EXTRA_IS_SENSITIVE`, which only exists in the API from Android 13. */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

/** The running or finished Flatpak task. Nothing when there is none. */
@Composable
fun TaskPanel(task: FlatpakTaskUi, describe: (FlatpakTask) -> String, onCancel: () -> Unit, onShowLog: () -> Unit, onDismiss: () -> Unit) {
    val running = task.running
    val finished = task.finished
    if (running == null && finished == null) return
    val failed = running == null && task.error != null
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (failed) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (failed) colors.onErrorContainer else colors.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running != null) {
                Text(task.phase ?: describe(running), style = MaterialTheme.typography.titleSmall)
                val step = task.step
                if (step != null) {
                    Text(
                        stringResource(if (step.deploying) R.string.task_step_deploying else R.string.task_step, step.verb, step.index, step.total, step.ref ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                    )
                } else if (task.finishing) {
                    Text(stringResource(R.string.task_finishing), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                } else {
                    task.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                }
                // Deploying reports nothing, so a full bar would look stuck.
                val percent = step?.percent?.takeIf { !step.deploying }
                if (percent != null) {
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            } else if (finished != null) {
                Text(
                    task.error?.let { stringResource(R.string.task_failed, describe(finished), it) }
                        ?: stringResource(R.string.task_done, describe(finished)),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running != null) OutlinedButton(onClick = onCancel, enabled = !task.cancelling) { Text(stringResource(R.string.command_cancel)) }
                TextButton(onClick = onShowLog) { Text(stringResource(R.string.show_log)) }
                if (running == null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
            }
        }
    }
}

/** Asks for one or more app IDs (separated by spaces) to install from Flathub. */
@Composable
fun InstallDialog(onInstall: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var id by rememberSaveable { mutableStateOf("") }
    val ids = InstalledApps.parseIds(id)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.install_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.install_text))
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text(stringResource(R.string.install_id_label)) },
                    placeholder = { Text("org.xfce.mousepad") },
                    singleLine = true,
                    isError = id.isNotBlank() && ids == null,
                    // The keyboard's action key installs, like the button.
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { ids?.let(onInstall) }),
                )
            }
        },
        confirmButton = { Button(onClick = { ids?.let(onInstall) }, enabled = ids != null) { Text(stringResource(R.string.install_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reset_cancel)) } },
    )
}

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
