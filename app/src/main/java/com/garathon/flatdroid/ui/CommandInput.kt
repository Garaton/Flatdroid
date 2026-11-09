package com.garathon.flatdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R

/**
 * The single command field: one command for Alpine with an ✕ beside it for the running command
 * (first press Ctrl-C, second press force-stop), and a Run button below; [buttons] go beside Run.
 */
@Composable
fun CommandInput(
    running: Boolean,
    onRun: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    buttons: @Composable RowScope.() -> Unit = {},
) {
    var command by rememberSaveable { mutableStateOf("") }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The field takes the width the ✕ leaves, so the row never scrolls sideways.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text(stringResource(R.string.ready_command_label)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { if (!running) onRun(command) }),
                modifier = Modifier.weight(1f).keepInViewWhileFocused(),
            )
            val cancel = stringResource(R.string.command_cancel)
            IconButton(onClick = onCancel, enabled = running, modifier = Modifier.semantics { contentDescription = cancel }) {
                Text("✕", style = MaterialTheme.typography.titleLarge)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onRun(command) }, enabled = !running && command.isNotBlank()) {
                Text(stringResource(if (running) R.string.ready_running else R.string.ready_run))
            }
            buttons()
        }
    }
}
