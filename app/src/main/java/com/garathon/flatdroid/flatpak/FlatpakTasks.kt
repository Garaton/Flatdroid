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
    /** Installs [appIds] (one or more) from Flathub in one transaction, so shared runtimes download once. */
    data class Install(val appIds: List<String>) : FlatpakTask
    data object UpdateAll : FlatpakTask
    /**
     * [deleteData] also removes the app's private settings, data and home; [keepRuntimes] leaves
     * runtimes nothing uses any more for the next update to remove.
     */
    data class Uninstall(val appId: String, val deleteData: Boolean, val keepRuntimes: Boolean = false) : FlatpakTask
    /** The repository clean-up on its own, after an update or uninstall Flatdroid didn't see end. */
    data object Repair : FlatpakTask
    /** Reinstalls [appId] and its runtime, to restore files the integrity check found changed. */
    data class Reinstall(val appId: String) : FlatpakTask
    /**
     * Reinstalls every installed app and its runtimes from Flathub, to record a baseline from
     * known-good files when the file check is turned on and no deployment has one yet.
     */
    data object ReinstallAll : FlatpakTask
    /** Records the file baseline of every installed app that has none yet (with the file check on). */
    data object BaselineAll : FlatpakTask
}

data class FlatpakTaskUi(
    val running: FlatpakTask? = null,
    /** The operation in progress (which runtime or app, its percent), when flatpak reports one. */
    val step: FlatpakProgress.Step? = null,
    /** Flatpak finished its steps and is cleaning up ([FlatpakProgress.finishing]). */
    val finishing: Boolean = false,
    /** The last status line: Flatdroid's own, or a command's that [FlatpakProgress.isStatus] lets through. */
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
                it.copy(running = task, step = null, finishing = false, status = null, finished = null, error = null, cancelling = false)
            }
        }
        if (started) logBuffer.clear()
        return started
    }

    /** A line of Flatdroid's own; [status] also shows it as the task's status line. */
    internal fun log(line: String, status: Boolean = true) {
        DebugLog.i(line)
        val isProgress = progress.feed(line)
        // Each step reports its progress many times: keep only its latest line.
        logBuffer.add(line) { last -> isProgress && FlatpakProgress.sameStep(last, line) }
        _ui.update { it.copy(step = progress.current, finishing = progress.finishing, status = if (status && line.isNotBlank()) line else it.status) }
    }

    /** A line of a command's output: logged, and the status line only if it is one ([FlatpakProgress.isStatus]). */
    internal fun output(line: String) = log(line, status = FlatpakProgress.isStatus(line))

    internal fun cancelling(phase: String) = _ui.update { it.copy(phase = phase, cancelling = true) }

    /** Starts following a new flatpak command's output; [phase] describes it, if not the task itself. */
    internal fun newCommand(phase: String?) {
        progress = FlatpakProgress()
        _ui.update { it.copy(step = null, finishing = false, phase = phase) }
    }

    internal fun end(task: FlatpakTask, error: String?) =
        _ui.update { it.copy(running = null, step = null, phase = null, cancelling = false, finished = task, error = error, generation = it.generation + 1) }

    fun dismiss() {
        _ui.update { it.copy(finished = null, error = null) }
        if (_ui.value.running == null) logBuffer.clear()
    }
}
