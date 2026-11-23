package com.garathon.flatdroid.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.alpine.programs.AlpineChanges
import com.garathon.flatdroid.ui.theme.FlatdroidTheme

/**
 * Alpine safe launch found changes ([AlpineChanges]): which files, and the two ways on. Reinstall
 * gives a new Alpine (Flatpaks kept); Keep it trusts the change. It can't be dismissed: nothing
 * runs in Alpine until one is chosen.
 */
@Composable
fun AlpineChangedDialog(changes: AlpineChanges.Pending, onReinstall: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.alpine_changed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.alpine_changed_text, changes.program))
                Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                    for (path in changes.paths) {
                        Text(path, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                    val more = changes.lines - changes.paths.size
                    if (more > 0) Text(stringResource(R.string.alpine_changed_more, more), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { Button(onClick = onReinstall) { Text(stringResource(R.string.alpine_changed_reinstall)) } },
        dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.alpine_changed_keep)) } },
    )
}

@Preview
@Composable
private fun AlpineChangedDialogPreview() {
    FlatdroidTheme {
        AlpineChangedDialog(AlpineChanges.Pending("Mousepad", 14, 3, listOf("usr/bin/labwc", "usr/lib/ (12)", "etc/profile.d/x.sh")), {}, {})
    }
}
