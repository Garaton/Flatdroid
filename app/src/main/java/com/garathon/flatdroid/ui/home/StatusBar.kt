package com.garathon.flatdroid.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
    onClearQueue: () -> Unit,
    onUnqueue: (FlatpakTask) -> Unit = {},
    /** An app's name for the waiting tasks (its ID when unknown). */
    queuedName: (String) -> String = { it },
    modifier: Modifier = Modifier,
) {
    var manage by remember { mutableStateOf(false) }
    Column(modifier) {
        // Tasks waiting for the running task or app, above what runs; tapped, a list to remove them from.
        AnimatedSlot(task.queue.ifEmpty { null }, from = Alignment.Bottom) { queue ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().clickable { manage = true }) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.task_queued, queue.map { queuedLabel(it, queuedName) }.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    IconAction(Icons.Filled.Close, R.string.task_queue_clear, onClearQueue, size = 36.dp)
                }
            }
        }
        if (manage && task.queue.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { manage = false },
                title = { Text(stringResource(R.string.task_queue_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        task.queue.forEachIndexed { i, waiting ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}. ${queuedLabel(waiting, queuedName)}", modifier = Modifier.weight(1f))
                                IconAction(Icons.Filled.Close, R.string.task_queue_remove, { onUnqueue(waiting) }, size = 36.dp)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { manage = false }) { Text(stringResource(R.string.dismiss)) } },
                dismissButton = { TextButton(onClick = { onClearQueue(); manage = false }) { Text(stringResource(R.string.task_queue_clear_all)) } },
            )
        }
        TaskAndSessionBar(task, session, describe, developerOptions, onCancelTask, onDismissTask, onTaskLog, onDismissSession, onSessionLog)
    }
}

/** A waiting task as the queue shows it: what it will do, with app names ([name]) where it has apps. */
@Composable
private fun queuedLabel(task: FlatpakTask, name: (String) -> String): String = when (task) {
    is FlatpakTask.Install -> stringResource(R.string.queued_install, task.appIds.joinToString(", ", transform = name))
    is FlatpakTask.Uninstall -> stringResource(R.string.queued_uninstall, task.appIds.joinToString(", ", transform = name))
    is FlatpakTask.Reinstall -> stringResource(R.string.queued_reinstall, name(task.appId))
    is FlatpakTask.RemoveRuntime -> stringResource(R.string.queued_remove_runtime, task.runtimeId)
    FlatpakTask.UpdateAll -> stringResource(R.string.queued_update_all)
    FlatpakTask.RefreshCatalog -> stringResource(R.string.queued_refresh_catalog)
    FlatpakTask.ReinstallAll -> stringResource(R.string.queued_reinstall_all)
    FlatpakTask.BaselineAll -> stringResource(R.string.queued_baseline_all)
    FlatpakTask.Repair -> stringResource(R.string.queued_repair)
}

@Composable
private fun TaskAndSessionBar(
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
            BarContent(task.phase ?: describe(running), task.detail(LocalContext.current), false, true, task.fraction, onCancelTask, !task.cancelling, onTaskLog.takeIf { developerOptions })
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
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(bar.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        bar.detail?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = if (bar.failed) 2 else 1, overflow = TextOverflow.Ellipsis)
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
