package com.garathon.flatdroid.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.FlatpakTask
import com.garathon.flatdroid.flatpak.FlatpakTaskUi
import com.garathon.flatdroid.session.SessionState
import com.garathon.flatdroid.session.SessionUi
import com.garathon.flatdroid.ui.IconAction

/**
 * What the bottom bar says now: a Flatpak task under way or finished, or how the last session
 * ended or why it couldn't start. One line (two for an error), a progress line along its top
 * edge, the way to cancel or dismiss, and the log at the far right.
 */
private data class BarContent(
    val title: String,
    /** What it is doing now, or the error; under the title. */
    val detail: String?,
    val failed: Boolean,
    /** A task under way: its progress, or null when it can't say how far. */
    val running: Boolean,
    val progress: Float?,
    val onClose: () -> Unit,
    val closeEnabled: Boolean,
    val onLog: (() -> Unit)?,
)

/**
 * The bottom bar for [task] (shown first) and the outcome of the session [session]; it comes up
 * from the bottom when there is something to say and goes away when there isn't. The log is
 * offered with developer options, and always for an error.
 */
@Composable
fun StatusBar(
    task: FlatpakTaskUi,
    session: SessionUi,
    describe: (FlatpakTask) -> String,
    developerOptions: Boolean,
    onCancelTask: () -> Unit,
    onDismissTask: () -> Unit,
    onTaskLog: () -> Unit,
    onDismissSession: () -> Unit,
    onSessionLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = task.running
    val finished = task.finished
    val content: BarContent? = when {
        running != null -> {
            val step = task.step
            val detail = when {
                step != null -> stringResource(if (step.deploying) R.string.task_step_deploying else R.string.task_step, step.verb, step.index, step.total, step.ref ?: "")
                task.finishing -> stringResource(R.string.task_finishing)
                else -> task.status
            }
            // Deploying reports nothing, so a full bar would look stuck.
            val progress = step?.percent?.takeIf { !step.deploying }?.let { it / 100f } ?: task.progress.takeIf { step == null }
            BarContent(task.phase ?: describe(running), detail, false, true, progress, onCancelTask, !task.cancelling, onTaskLog.takeIf { developerOptions })
        }
        finished != null -> {
            val failed = task.error != null
            val title = if (failed) stringResource(R.string.task_failed_title, describe(finished)) else stringResource(R.string.task_done, describe(finished))
            BarContent(title, task.error, failed, false, null, onDismissTask, true, onTaskLog.takeIf { developerOptions || failed })
        }
        session.state is SessionState.Idle && session.message != null ->
            BarContent(session.message, null, session.failed, false, null, onDismissSession, true, onSessionLog.takeIf { developerOptions || session.failed })
        else -> null
    }
    AnimatedSlot(content, from = Alignment.Bottom, modifier = modifier) { bar ->
        val colors = MaterialTheme.colorScheme
        Surface(
            color = if (bar.failed) colors.errorContainer else colors.secondaryContainer,
            contentColor = if (bar.failed) colors.onErrorContainer else colors.onSecondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                if (bar.running) {
                    val progress = bar.progress
                    if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(0.dp))
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!bar.running) Icon(if (bar.failed) Icons.Filled.Error else Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                    SelectionContainer(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Column {
                            Text(bar.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            bar.detail?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = if (bar.failed) 2 else 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    // The log is always left of cancel or dismiss, as the running app's is left of its icons.
                    bar.onLog?.let { IconAction(Icons.Filled.Info, R.string.show_log, it, size = 36.dp) }
                    IconAction(
                        if (bar.running) Icons.Filled.Cancel else Icons.Filled.Close,
                        if (bar.running) R.string.command_cancel else R.string.dismiss,
                        bar.onClose, enabled = bar.closeEnabled, size = 36.dp,
                    )
                }
            }
        }
    }
}
