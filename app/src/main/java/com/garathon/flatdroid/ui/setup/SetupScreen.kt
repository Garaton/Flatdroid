package com.garathon.flatdroid.ui.setup

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.setup.ResetMode
import com.garathon.flatdroid.setup.SetupStep
import com.garathon.flatdroid.setup.SetupUi
import com.garathon.flatdroid.ui.terminal.CommandInput
import com.garathon.flatdroid.ui.common.FollowEnd
import com.garathon.flatdroid.ui.common.LogText
import com.garathon.flatdroid.ui.dialogs.ResetDialog
import com.garathon.flatdroid.ui.theme.FlatdroidTheme
import com.garathon.flatdroid.ui.theme.Spacing

/**
 * The second screen of setup, the same for everyone: the steps with their state, how far the whole
 * setup is, the current step's detail, the whole output (filling the room below, scrolling on its
 * own), and Start/Resume/Retry. The user's
 * choices come before, on [SetupSettingsScreen].
 */
@Composable
fun SetupScreen(
    ui: SetupUi,
    onStart: () -> Unit,
    onReset: (ResetMode) -> Unit,
    modifier: Modifier = Modifier,
    /** The command field, for looking into a failed or interrupted setup; null hides it. */
    command: CommandState? = null,
) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    if (confirmReset) {
        ResetDialog(onConfirm = { confirmReset = false; onReset(it) }, onDismiss = { confirmReset = false })
    }
    val scroll = rememberScrollState()
    val commandRunning = command?.running == true
    // After a failure the command's output takes the log's place.
    val commandOutput = command?.output?.takeIf { it.isNotEmpty() && !ui.running }
    val log = commandOutput ?: ui.log.joinToString("\n")
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    SetupFrame(
        title = stringResource(if (ui.running) R.string.setup_title_running else R.string.setup_title),
        screen = 2,
        modifier = modifier,
        scrolls = false,
        actions = {
            if (ui.running) {
                Text(
                    stringResource(R.string.setup_running_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                val label = when {
                    ui.failed != null -> R.string.setup_retry
                    ui.completed.isNotEmpty() -> R.string.setup_resume
                    else -> R.string.setup_start
                }
                // Side by side, so the height goes to the steps and the log. Reset as in Settings' danger zone.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.row)) {
                    Button(onClick = onStart, enabled = !commandRunning, modifier = Modifier.weight(1f)) { Text(stringResource(label)) }
                    if (ui.completed.isNotEmpty() || ui.failed != null) {
                        OutlinedButton(onClick = { confirmReset = true }, enabled = !commandRunning) {
                            Text(stringResource(R.string.reset_button), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
    ) {
        val showCommand = command != null && !ui.running
        val commandInput = @Composable {
            if (command != null && showCommand) CommandInput(command.running, command.onRun, command.onCancel, Modifier.fillMaxWidth())
        }
        // In landscape the command and the log share the right half, at its top.
        val sideBySide = landscape && (log.isNotEmpty() || showCommand)
        // The steps and what goes with them; they scroll on their own only when they don't fit.
        val steps = @Composable { stepsModifier: Modifier ->
            Column(stepsModifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.row)) {
                if (!ui.running && ui.completed.isEmpty() && ui.failed == null) {
                    Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyMedium)
                }
                OverallProgress(ui)
                Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
                    for (step in SetupStep.entries) StepRow(step, ui)
                }
                ui.error?.let { SetupError(stringResource(R.string.setup_failed, it)) }
                if (!sideBySide) commandInput()
            }
        }
        // The whole log, filling the room down to the buttons, scrolling by itself and following its end.
        val logBox = @Composable { boxModifier: Modifier ->
            FollowEnd(scroll, log)
            Surface(boxModifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer) {
                Box(Modifier.padding(8.dp).verticalScroll(scroll)) { LogText(log, Modifier.fillMaxWidth()) }
            }
        }
        when {
            // Too little height for both: side by side.
            sideBySide -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Spacing.row)) {
                steps(Modifier.weight(1f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    commandInput()
                    if (log.isNotEmpty()) logBox(Modifier.weight(1f))
                }
            }
            log.isEmpty() -> steps(Modifier.weight(1f, fill = false))
            else -> BoxWithConstraints(Modifier.weight(1f)) {
                // The steps keep at most most of the room (a long error scrolls), the log the rest.
                val stepsMax = maxHeight * 0.6f
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.row)) {
                    steps(Modifier.heightIn(max = stepsMax))
                    logBox(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * How far the whole setup is, as a bar with "n of m steps": the finished steps only. A step's own
 * progress is its dial's (some restart, as apk's upgrade and install do), so it isn't in the bar.
 */
@Composable
private fun OverallProgress(ui: SetupUi) {
    val steps = SetupStep.entries.size
    val done = ui.completed.size
    val target = (done.toFloat() / steps).coerceIn(0f, 1f)
    val progress by animateFloatAsState(target, tween(400), label = "setup progress")
    // Nothing yet to say before setup starts.
    if (!ui.running && done == 0 && ui.failed == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.setup_steps_done, done, steps),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val MARK_SIZE = 24.dp
/** The circle inside a 24 dp check-circle icon, so empty, running and done line up. */
private val PROGRESS_SIZE = 20.dp
private val PROGRESS_STROKE = 3.dp

/**
 * A step and its state: an empty ring before it runs; while it runs, a ring filling with its
 * progress (a spinner when the step can't tell) and its detail below; a check once done; a
 * warning sign when it failed.
 */
@Composable
private fun StepRow(step: SetupStep, ui: SetupUi) {
    val current = step == ui.current
    val state = when {
        step in ui.completed -> StepState.DONE
        step == ui.failed -> StepState.FAILED
        current -> StepState.RUNNING
        else -> StepState.WAITING
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Fades between states only; progress within a state just moves the ring.
        Crossfade(targetState = state, label = "step") { shown ->
            StepMark {
                val fraction = ui.fraction
                when (shown) {
                    StepState.DONE ->
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(MARK_SIZE))
                    StepState.FAILED ->
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(MARK_SIZE))
                    StepState.RUNNING ->
                        if (fraction != null) CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(PROGRESS_SIZE), strokeWidth = PROGRESS_STROKE)
                        else CircularProgressIndicator(Modifier.size(PROGRESS_SIZE), strokeWidth = PROGRESS_STROKE)
                    StepState.WAITING -> CircularProgressIndicator(progress = { 0f }, modifier = Modifier.size(PROGRESS_SIZE), strokeWidth = PROGRESS_STROKE)
                }
            }
        }
        Column(Modifier.animateContentSize()) {
            Text(
                stringResource(step.title),
                style = MaterialTheme.typography.bodyLarge,
                color = if (state == StepState.WAITING) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (current && ui.detail.isNotEmpty()) {
                Text(ui.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private enum class StepState { WAITING, RUNNING, DONE, FAILED }

@Composable
private fun StepMark(content: @Composable () -> Unit) {
    Box(Modifier.size(MARK_SIZE), contentAlignment = Alignment.Center) { content() }
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
