package com.garathon.flatdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.setup.SetupStep
import com.garathon.flatdroid.setup.SetupUi
import com.garathon.flatdroid.ui.theme.FlatdroidTheme

/**
 * First-run setup, the same for everyone: the steps and their state, progress of the current one,
 * recent output, and Start/Resume/Retry. The user's choices come afterwards, on the settings screen.
 */
@Composable
fun SetupScreen(
    ui: SetupUi,
    onStart: () -> Unit,
    onReset: (keepFlatpaks: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** The command field, for looking into a failed or interrupted setup; null hides it. */
    command: CommandState? = null,
) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    if (confirmReset) {
        ResetDialog(onConfirm = { confirmReset = false; onReset(it) }, onDismiss = { confirmReset = false })
    }
    var showLog by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()

    // The whole screen scrolls, so the log keeps a usable height on small screens; the log and
    // command output scroll inside their own fixed-height boxes.
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyMedium)

        for (step in SetupStep.entries) StepRow(step, ui)

        if (ui.running) {
            val fraction = ui.fraction
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(ui.detail, style = MaterialTheme.typography.bodySmall)
        }
        ui.error?.let {
            SelectionContainer {
                Text(stringResource(R.string.setup_failed, it), color = MaterialTheme.colorScheme.error)
            }
        }

        if (!ui.running) {
            val label = when {
                ui.failed != null -> R.string.setup_retry
                ui.completed.isNotEmpty() -> R.string.setup_resume
                else -> R.string.setup_start
            }
            val commandRunning = command?.running == true
            Button(onClick = onStart, enabled = !commandRunning) { Text(stringResource(label)) }
            if (ui.completed.isNotEmpty() || ui.failed != null) {
                TextButton(onClick = { confirmReset = true }, enabled = !commandRunning) { Text(stringResource(R.string.reset_button)) }
            }
        }

        if (command != null && !ui.running) {
            CommandInput(command.running, command.onRun, command.onCancel, Modifier.fillMaxWidth())
        }

        if (command != null && command.output.isNotEmpty() && !ui.running) {
            LaunchedEffect(command.output) { scroll.scrollTo(scroll.maxValue) }
            SelectionContainer(modifier = Modifier.fillMaxWidth().height(OUTPUT_HEIGHT).verticalScroll(scroll)) {
                Text(command.output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        } else if (ui.log.isNotEmpty()) {
            TextButton(onClick = { showLog = !showLog }) {
                Text(stringResource(if (showLog) R.string.setup_hide_log else R.string.setup_show_log))
            }
            LaunchedEffect(ui.log.size, showLog) { scroll.scrollTo(scroll.maxValue) }
            val logModifier = if (showLog) Modifier.height(OUTPUT_HEIGHT).verticalScroll(scroll) else Modifier
            SelectionContainer(modifier = Modifier.fillMaxWidth().then(logModifier)) {
                Text(
                    text = (if (showLog) ui.log else ui.log.takeLast(5)).joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private val OUTPUT_HEIGHT = 400.dp

@Composable
private fun StepRow(step: SetupStep, ui: SetupUi) {
    val mark = when {
        step in ui.completed -> "✓"
        step == ui.failed -> "✗"
        step == ui.current -> "…"
        else -> "·"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(mark, modifier = Modifier.width(24.dp))
        Text(stringResource(step.title))
    }
}

@Preview(showBackground = true)
@Composable
private fun SetupScreenPreview() {
    FlatdroidTheme {
        SetupScreen(
            ui = SetupUi(
                running = true,
                completed = setOf(SetupStep.ROOTFS),
                current = SetupStep.PACKAGES,
                fraction = 0.4f,
                detail = "Installing glib (2.88.1-r1)",
                log = listOf("( 74/186) Installing glib (2.88.1-r1)"),
            ),
            onStart = {},
            onReset = {},
        )
    }
}

/** What the setup screen needs to show the command field. */
data class CommandState(val output: String, val running: Boolean, val onRun: (String) -> Unit, val onCancel: () -> Unit)
