package com.garathon.flatdroid.flatpak

import android.content.Context
import com.garathon.flatdroid.R

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
    /** Uninstalls [apps], each with its own choices; the queue keeps every waiting uninstall in one. */
    data class Uninstall(val apps: List<UninstallApp>) : FlatpakTask {
        constructor(appId: String, deleteData: Boolean, keepRuntimes: Boolean = false) : this(listOf(UninstallApp(appId, deleteData, keepRuntimes)))

        val appIds: List<String> get() = apps.map { it.appId }
    }
    /** Uninstalls the runtime [runtimeId] (every branch of it), which no installed app uses. */
    data class RemoveRuntime(val runtimeId: String) : FlatpakTask
    /** Downloads Flathub's catalog again (`flatpak update --appstream`) for the browser, and Popular. */
    data object RefreshCatalog : FlatpakTask
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
    /** How far the running task is when it isn't a Flatpak operation with a percent (recording or checking file baselines); null when it can't say. */
    val progress: Float? = null,
    /** What the running task is doing when that differs from the task itself (removing unused runtimes). */
    val phase: String? = null,
    /** Cancel was pressed; the task is stopping. */
    val cancelling: Boolean = false,
    /** The last task and whether it succeeded, until dismissed. */
    val finished: FlatpakTask? = null,
    val error: String? = null,
    /** Bumped after every task, so lists of installed apps reload. */
    val generation: Int = 0,
    /** Tasks waiting for the running task or app, first in first out ([TaskQueue]). */
    val queue: List<FlatpakTask> = emptyList(),
) {
    /**
     * How far the running task is, for a progress bar: Flatpak's percent of the current step, or
     * the task's own fraction; null when it can't say (deploying reports nothing, so a full bar
     * would look stuck).
     */
    val fraction: Float? get() = step?.percent?.takeIf { !step.deploying }?.let { it / 100f } ?: progress.takeIf { step == null }

    /** What the running task does now, for under its name: the step ("Installing 2/3: …"), finishing, or its last status line. */
    fun detail(context: Context): String? {
        val step = step
        return when {
            step != null -> context.getString(if (step.deploying) R.string.task_step_deploying else R.string.task_step, step.verb, step.index, step.total, step.ref ?: "")
            finishing -> context.getString(R.string.task_finishing)
            else -> status
        }
    }

    /** The apps a waiting install has. */
    val queuedInstalls: Set<String> get() = queue.flatMapTo(LinkedHashSet()) { (it as? FlatpakTask.Install)?.appIds.orEmpty() }
}

/** One app of an [FlatpakTask.Uninstall], with the user's choices for it. */
data class UninstallApp(val appId: String, val deleteData: Boolean, val keepRuntimes: Boolean = false)

/**
 * The rules of the task queue, apart from Android for tests. First in, first out, except that the
 * queue holds at most one install and one uninstall: a later one joins the waiting one, in its
 * place (install A, uninstall B, install C, uninstall D runs as install A and C, then uninstall B
 * and D). A duplicate is queued once, and opposites cancel out: an uninstall of an app whose
 * install waits drops that install (and isn't queued), and an install of an app whose uninstall
 * waits drops that uninstall (the app stays as it is).
 */
object TaskQueue {
    fun add(queue: List<FlatpakTask>, task: FlatpakTask, running: FlatpakTask?): List<FlatpakTask> = when (task) {
        is FlatpakTask.Install -> addInstall(queue, task.appIds, running)
        is FlatpakTask.Uninstall -> addUninstall(queue, task.apps, running)
        // The same again, or what runs now (an update or refresh right after one changes nothing).
        else -> if (task in queue || task == running) queue else queue + task
    }

    private fun addInstall(queue: List<FlatpakTask>, appIds: List<String>, running: FlatpakTask?): List<FlatpakTask> {
        val installing = (running as? FlatpakTask.Install)?.appIds.orEmpty()
        val queuedInstalls = queue.flatMap { (it as? FlatpakTask.Install)?.appIds.orEmpty() }
        val waitingUninstalls = queue.flatMap { (it as? FlatpakTask.Uninstall)?.appIds.orEmpty() }.toSet()
        // Cancelled out: the app's waiting uninstall goes, and the app stays installed.
        val cancelled = appIds.filter { it in waitingUninstalls }.toSet()
        var result = cancelled.fold(queue, ::withoutUninstall)
        val ids = appIds.distinct().filter { it !in installing && it !in queuedInstalls && it !in cancelled }
        if (ids.isEmpty()) return result
        val waiting = result.indexOfFirst { it is FlatpakTask.Install }
        result = if (waiting >= 0) {
            result.toMutableList().also { it[waiting] = FlatpakTask.Install((it[waiting] as FlatpakTask.Install).appIds + ids) }
        } else {
            result + FlatpakTask.Install(ids)
        }
        return result
    }

    private fun addUninstall(queue: List<FlatpakTask>, apps: List<UninstallApp>, running: FlatpakTask?): List<FlatpakTask> {
        val uninstalling = (running as? FlatpakTask.Uninstall)?.appIds.orEmpty()
        val waitingInstalls = queue.flatMap { (it as? FlatpakTask.Install)?.appIds.orEmpty() }.toSet()
        // Cancelled out: the app's waiting install goes, and nothing is uninstalled.
        val cancelled = apps.map { it.appId }.filter { it in waitingInstalls }.toSet()
        val added = apps.distinctBy { it.appId }.filter { it.appId !in cancelled && it.appId !in uninstalling }
        // Their reinstalls would be undone.
        var result = cancelled.fold(queue, ::withoutInstall).filterNot { it is FlatpakTask.Reinstall && it.appId in added.map(UninstallApp::appId) }
        if (added.isEmpty()) return result
        val waiting = result.indexOfFirst { it is FlatpakTask.Uninstall }
        result = if (waiting >= 0) {
            // An app already waiting keeps its place, with the newer choices.
            val earlier = (result[waiting] as FlatpakTask.Uninstall).apps
            val merged = earlier.map { old -> added.firstOrNull { it.appId == old.appId } ?: old } +
                added.filter { new -> earlier.none { it.appId == new.appId } }
            result.toMutableList().also { it[waiting] = FlatpakTask.Uninstall(merged) }
        } else {
            result + FlatpakTask.Uninstall(added)
        }
        return result
    }

    /** [appId] taken out of the waiting installs (an install left with no app goes). */
    fun withoutInstall(queue: List<FlatpakTask>, appId: String): List<FlatpakTask> = queue.mapNotNull { task ->
        if (task is FlatpakTask.Install && appId in task.appIds) (task.appIds - appId).takeIf { it.isNotEmpty() }?.let(FlatpakTask::Install) else task
    }

    /** [appId] taken out of the waiting uninstall (an uninstall left with no app goes). */
    fun withoutUninstall(queue: List<FlatpakTask>, appId: String): List<FlatpakTask> = queue.mapNotNull { task ->
        if (task is FlatpakTask.Uninstall && appId in task.appIds) task.apps.filter { it.appId != appId }.takeIf { it.isNotEmpty() }?.let(FlatpakTask::Uninstall) else task
    }
}

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
                it.copy(running = task, step = null, finishing = false, status = null, progress = null, finished = null, error = null, cancelling = false)
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

    /** The running task's progress, 0 to 1; null when it can't say. */
    internal fun progress(fraction: Float?) = _ui.update { it.copy(progress = fraction?.coerceIn(0f, 1f)) }

    internal fun cancelling(phase: String) = _ui.update { it.copy(phase = phase, cancelling = true) }

    /** Starts following a new flatpak command's output; [phase] describes it, if not the task itself. */
    internal fun newCommand(phase: String?) {
        progress = FlatpakProgress()
        _ui.update { it.copy(step = null, finishing = false, phase = phase, status = null, progress = null) }
    }

    /** [task] was claimed ([begin]) but its service couldn't start: nothing ran, so nothing is reported. */
    internal fun abandon(task: FlatpakTask) = _ui.update { if (it.running == task) it.copy(running = null, step = null, cancelling = false) else it }

    internal fun end(task: FlatpakTask, error: String?) =
        _ui.update { it.copy(running = null, step = null, phase = null, progress = null, cancelling = false, finished = task, error = error, generation = it.generation + 1) }

    /** Queues [task] behind what runs ([TaskQueue.add]). */
    internal fun enqueue(task: FlatpakTask) = _ui.update { it.copy(queue = TaskQueue.add(it.queue, task, it.running)) }

    /** Takes [task] out of the queue. */
    fun unqueue(task: FlatpakTask) = _ui.update { it.copy(queue = it.queue - task) }

    /** Takes [appId] out of the waiting installs. */
    fun unqueueInstall(appId: String) = _ui.update { it.copy(queue = TaskQueue.withoutInstall(it.queue, appId)) }

    fun clearQueue() = _ui.update { it.copy(queue = emptyList()) }

    /** The next waiting task, taken out of the queue; null when none waits. */
    internal fun takeNext(): FlatpakTask? {
        var taken: FlatpakTask? = null
        _ui.update { taken = it.queue.firstOrNull(); it.copy(queue = it.queue.drop(1)) }
        return taken
    }

    /** [task] back at the front of the queue (it couldn't start after all). */
    internal fun putBack(task: FlatpakTask) = _ui.update { it.copy(queue = listOf(task) + it.queue) }

    fun dismiss() {
        _ui.update { it.copy(finished = null, error = null) }
        if (_ui.value.running == null) logBuffer.clear()
    }
}
