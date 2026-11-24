package com.garathon.flatdroid.ui.terminal

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.garathon.flatdroid.R
import com.garathon.flatdroid.ui.common.keepInViewWhileFocused

/**
 * The single command field: one command for Alpine, with a run button and an ✕ beside it for the
 * running command (first press Ctrl-C, second press force-stop).
 */
@Composable
fun CommandInput(
    running: Boolean,
    onRun: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var command by rememberSaveable { mutableStateOf("") }
    // The field takes the width the buttons leave, so the row never scrolls sideways.
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
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
        IconButton(onClick = { onRun(command) }, enabled = !running && command.isNotBlank()) {
            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(if (running) R.string.ready_running else R.string.ready_run))
        }
        val cancel = stringResource(R.string.cancel)
        IconButton(onClick = onCancel, enabled = running, modifier = Modifier.semantics { contentDescription = cancel }) {
            Text("✕", style = MaterialTheme.typography.titleLarge)
        }
    }
}
