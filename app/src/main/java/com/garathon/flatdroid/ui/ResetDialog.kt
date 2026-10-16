package com.garathon.flatdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.setup.ResetMode
import com.garathon.flatdroid.ui.theme.FlatdroidTheme

/** Confirms a reset of the Alpine environment; keeping Flatpaks is the default. */
@Composable
fun ResetDialog(onConfirm: (ResetMode) -> Unit, onDismiss: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(ResetMode.KEEP_FLATPAKS) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reset_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.reset_text))
                Column(Modifier.selectableGroup()) {
                    RadioRow(mode == ResetMode.KEEP_FLATPAKS, { mode = ResetMode.KEEP_FLATPAKS }, stringResource(R.string.reset_keep))
                    RadioRow(mode == ResetMode.REMOVE_FLATPAKS, { mode = ResetMode.REMOVE_FLATPAKS }, stringResource(R.string.reset_remove))
                    RadioRow(mode == ResetMode.WIPE, { mode = ResetMode.WIPE }, stringResource(R.string.reset_wipe))
                }
                Text(
                    stringResource(
                        when (mode) {
                            ResetMode.KEEP_FLATPAKS -> R.string.reset_keep_hint
                            ResetMode.REMOVE_FLATPAKS -> R.string.reset_remove_hint
                            ResetMode.WIPE -> R.string.reset_wipe_hint
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (mode == ResetMode.WIPE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(mode) }) { Text(stringResource(R.string.reset_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reset_cancel)) } },
    )
}

@Preview
@Composable
private fun ResetDialogPreview() {
    FlatdroidTheme { ResetDialog(onConfirm = {}, onDismiss = {}) }
}
