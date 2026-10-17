package com.garathon.flatdroid.flatpak

import com.garathon.flatdroid.session.SessionController
import com.garathon.flatdroid.util.DebugLog
import com.garathon.flatdroid.util.LineBuffer
import com.garathon.flatdroid.util.appScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One Flatpak operation run in Alpine. */
sealed interface FlatpakTask {
    data class Install(val appId: String) : FlatpakTask
    data object UpdateAll : FlatpakTask
    /** [deleteData] also removes the app's private settings, data and home. */
    data class Uninstall(val appId: String, val deleteData: Boolean) : FlatpakTask
    /** The repository clean-up on its own, after an update or uninstall Flatdroid didn't see end. */
    data object Repair : FlatpakTask
}

data class FlatpakTaskUi(
    val running: FlatpakTask? = null,
    /** The operation in progress (which runtime or app, its percent), when flatpak reports one. */
    val step: FlatpakProgress.Step? = null,
    /** The last non-blank output line. */
    val status: String? = null,
    /** What the running task is doing when that differs from the task itself (removing unused runtimes). */
    val phase: String? = null,
    /** Cancel was pressed; the task is stopping. */
    val cancelling: Boolean = false,
    /** The last task and whether it succeeded, until dismissed. */
    val finished: FlatpakTask? = null,
    val error: String? = null,
    /** Bumped after every task, so lists of installed apps reload. */
    val generation: Int = 0,
)

/** Process-wide task state: written by [FlatpakTaskService], read by the UI. */
object FlatpakTasks {
    private val _ui = MutableStateFlow(FlatpakTaskUi())
    val ui: StateFlow<FlatpakTaskUi> = _ui.asStateFlow()
    private val logBuffer = LineBuffer(maxLines = 1_000, scope = appScope)
    /** The running or last task's output; repeated progress of one step is kept as one line. */
    val log: StateFlow<List<String>> = logBuffer.lines
    private var progress = FlatpakProgress()

    /** Also clears how the last app session ended: one banner at a time. */
    internal fun begin(task: FlatpakTask): Boolean {
        SessionController.dismissMessage()
        var started = false
        _ui.update {
            if (it.running != null) it else {
                started = true
                it.copy(running = task, step = null, status = null, finished = null, error = null, cancelling = false)
            }
        }
        if (started) logBuffer.clear()
        return started
    }

    internal fun log(line: String) {
        DebugLog.i(line)
        val isProgress = progress.feed(line)
        // Each step reports its progress many times: keep only its latest line.
        logBuffer.add(line) { last -> isProgress && FlatpakProgress.sameStep(last, line) }
        _ui.update { it.copy(step = progress.current, status = if (line.isNotBlank()) line else it.status) }
    }

    internal fun cancelling(phase: String) = _ui.update { it.copy(phase = phase, cancelling = true) }

    /** Starts following a new flatpak command's output; [phase] describes it, if not the task itself. */
    internal fun newCommand(phase: String?) {
        progress = FlatpakProgress()
        _ui.update { it.copy(step = null, phase = phase) }
    }

    internal fun end(task: FlatpakTask, error: String?) =
        _ui.update { it.copy(running = null, step = null, phase = null, cancelling = false, finished = task, error = error, generation = it.generation + 1) }

    fun dismiss() {
        _ui.update { it.copy(finished = null, error = null) }
        if (_ui.value.running == null) logBuffer.clear()
    }
}
