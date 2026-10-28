package com.garathon.flatdroid.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.garathon.flatdroid.R
import com.garathon.flatdroid.storage.HomeZip

/** A message with only OK, which (like dismissing it) calls [onClose]. */
@Composable
fun MessageDialog(title: String, text: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.ok)) } },
    )
}

/**
 * Asked in Flatdroid's window once the app with a temporary home has closed. It can't be
 * dismissed: only Delete deletes the home.
 */
@Composable
fun TempHomeDialog(appName: String, onExport: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.temp_home_prompt_title, appName)) },
        text = { Text(stringResource(R.string.temp_home_prompt_text)) },
        confirmButton = { Button(onClick = onExport) { Text(stringResource(R.string.temp_home_export)) } },
        dismissButton = { TextButton(onClick = onDelete) { Text(stringResource(R.string.temp_home_delete)) } },
    )
}

/**
 * Shown when the file check is turned on and no app has a baseline yet: reinstall everything for a
 * baseline from known-good files, or trust what is installed (baselines then recorded on launch).
 * Both choices save the settings; the dialog has no cancel, so one of them is always made.
 */
@Composable
fun BaselineChoiceDialog(onReinstall: () -> Unit, onTrust: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.baseline_choice_title)) },
        text = { Text(stringResource(R.string.baseline_choice_message)) },
        confirmButton = { Button(onClick = onReinstall) { Text(stringResource(R.string.baseline_choice_reinstall)) } },
        dismissButton = { TextButton(onClick = onTrust) { Text(stringResource(R.string.baseline_choice_trust)) } },
    )
}

/** Asks before something that replaces or deletes files; [confirm] is the button, in the error color. */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reset_cancel)) } },
    )
}

/**
 * A storage task that stopped before it finished: continue (or start again), or cancel. Without
 * [canContinue] (Flatdroid can no longer read the picked files) only OK, which cancels it.
 */
@Composable
fun UnfinishedDialog(title: String, text: String, canContinue: Boolean, onContinue: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            if (canContinue) Button(onClick = onContinue) { Text(stringResource(R.string.storage_continue)) }
            else TextButton(onClick = onCancel) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { if (canContinue) TextButton(onClick = onCancel) { Text(stringResource(R.string.reset_cancel)) } },
    )
}

/** Before extracting a zip into the shared home: what to do with files whose names are taken. */
@Composable
fun ClashDialog(onChoice: (HomeZip.Clash) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clash_title)) },
        text = { Text(stringResource(R.string.clash_unknown)) },
        confirmButton = {
            Row {
                TextButton(onClick = { onChoice(HomeZip.Clash.REPLACE) }) { Text(stringResource(R.string.clash_replace), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { onChoice(HomeZip.Clash.SKIP) }) { Text(stringResource(R.string.clash_skip)) }
                TextButton(onClick = { onChoice(HomeZip.Clash.RENAME) }) { Text(stringResource(R.string.clash_keep_both)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reset_cancel)) } },
    )
}
