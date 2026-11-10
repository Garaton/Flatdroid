package com.garathon.flatdroid.flatpak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.garathon.flatdroid.MainActivity
import com.garathon.flatdroid.R
import com.garathon.flatdroid.alpine.AlpineCommands
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.alpine.ResolvConfRefresher
import com.garathon.flatdroid.setup.SetupController
import com.garathon.flatdroid.launcher.ElectronSwap
import com.garathon.flatdroid.proot.LeftoverProcesses
import com.garathon.flatdroid.proot.RunInterruptedException
import com.garathon.flatdroid.session.SessionController
import com.garathon.flatdroid.setup.AlpineSetup
import com.garathon.flatdroid.setup.AndroidTarFs
import com.garathon.flatdroid.setup.deleteTree
import com.garathon.flatdroid.flatpak.catalog.Catalog
import com.garathon.flatdroid.storage.SavedSizes
import com.garathon.flatdroid.storage.TempHomes
import com.garathon.flatdroid.util.DebugLog
import com.garathon.flatdroid.util.DoneNotice
import com.garathon.flatdroid.util.writeTextAtomically
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Installs, updates and uninstalls Flatpaks in the foreground (dataSync), so downloads continue
 * while the user is in other apps.
 */
class FlatpakTaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    /** A command of the running task had to be killed rather than ending on SIGINT. */
    @Volatile private var killed = false
    /** The newest start this service was given (a task or a cancel): the service stops only by it. */
    @Volatile private var lastStartId = 0
    /** How the tasks ended since the queue last ran dry (task, error or null): told in one notification then. */
    private val results = mutableListOf<Pair<FlatpakTask, String?>>()
    /** The task whose job was launched last, until that job ends: a start for it again is a duplicate. */
    @Volatile private var launched: FlatpakTask? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            // flatpak gets time to stop by itself (see ProcessStopper), which can take a while.
            if (job?.isActive == true) FlatpakTasks.cancelling(getString(R.string.task_cancelling))
            job?.cancel()
            // A cancel from an old notification, with nothing running: nothing keeps the service.
            if (job?.isActive != true && FlatpakTasks.ui.value.running == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val task = intent?.let(::taskOf) ?: run { stopSelf(startId); return START_NOT_STICKY }
        // Android wants the notification for every foreground start, even one that isn't run.
        startInForeground(notification(task))
        // start() claimed the task; anything else (a start for a task already launched) isn't run,
        // and the notification goes back to what runs.
        if (FlatpakTasks.ui.value.running != task || launched == task) {
            val running = FlatpakTasks.ui.value.running
            if (job?.isActive == true && running != null) startInForeground(notification(running)) else if (running == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        launched = task
        // The previous job may still be finishing (it started this one from the queue): the new one waits for it.
        val previous = job

        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Flatdroid:flatpak")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        // Installs are left out: an interrupted download is already safe (see cleanUpAfter); so is a
        // baseline, which changes no Flatpak files.
        val marker = AppData.taskMarker(applicationContext).takeIf {
            task !is FlatpakTask.Install && task != FlatpakTask.BaselineAll && task != FlatpakTask.RefreshCatalog
        }
        // A task that ends with baselines: if it doesn't finish (cancelled, failed, killed), they are
        // recorded at the next start instead. A Repair finishes them too, if they were pending.
        val baselineMarker = AppData.baselineMarker(applicationContext).takeIf {
            checkFilesOn(this) && task !is FlatpakTask.Uninstall && task !is FlatpakTask.RemoveRuntime && task != FlatpakTask.RefreshCatalog &&
                (task != FlatpakTask.Repair || it.exists())
        }
        killed = false
        job = scope.launch {
            previous?.join()
            val following = followInNotification(task)
            var error: String? = null
            // A download can outlast a switch between networks: keep Alpine's resolv.conf current.
            val dns = ResolvConfRefresher(applicationContext, listOf(AlpineEnvironment.get(applicationContext).resolvConfFile)).apply { start() }
            try {
                // Before the task's file checks; see LeftoverProcesses.killEscaped.
                endEscaped()
                marker?.writeTextAtomically(task.toString())
                baselineMarker?.writeTextAtomically(task.toString())
                // A catalog refresh changes only Flatpak's catalog: the apps' and runtimes' sizes stay.
                if (task != FlatpakTask.RefreshCatalog) SavedSizes.forgetAll(applicationContext)
                runInterruptible { performRelinking(task) }
                baselineMarker?.delete()
            } catch (e: CancellationException) {
                error = getString(R.string.task_cancelled)
                withContext(NonCancellable) { runInterruptible { cleanUpAfter(task) } }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                runInterruptible { cleanUpAfter(task) }
            } finally {
                dns.stop()
                endEscaped()
                marker?.delete()
                if (task == FlatpakTask.RefreshCatalog) SavedSizes.changedShared(applicationContext) else SavedSizes.changedAll(applicationContext)
                FlatpakTasks.log(error?.let { getString(R.string.task_log_failed, it) } ?: getString(R.string.task_log_done))
                // Not after the end: it would post this task's notification over the next one's.
                withContext(NonCancellable) { following.cancelAndJoin() }
                // Before the end: once nothing runs, a new start must not look like a duplicate of this one.
                launched = null
                FlatpakTasks.end(task, error)
                // What waited for this task starts now. Without a claimed task, the
                // service stops by the newest start it was given: a cancel's, say. A start Android
                // hasn't delivered yet is newer still, so it isn't stopped under that one.
                startNext(applicationContext)
                // A cancel is the user's own doing: not told again.
                synchronized(results) { if (error != getString(R.string.task_cancelled)) results += task to error }
                if (FlatpakTasks.ui.value.running == null) {
                    tellFinished()
                    stopSelf(lastStartId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    /**
     * Nothing is left to run: one notification for what ran (the task, or how many; with what
     * failed), when Flatdroid isn't in front ([DoneNotice]).
     */
    private fun tellFinished() {
        val ended = synchronized(results) { results.toList().also { results.clear() } }
        if (ended.isEmpty()) return
        val failed = ended.filter { it.second != null }
        val (title, text) = when {
            ended.size == 1 && failed.isEmpty() -> getString(R.string.task_done, describe(this, ended[0].first)) to null
            ended.size == 1 -> getString(R.string.task_failed_title, describe(this, ended[0].first)) to ended[0].second
            failed.isEmpty() -> resources.getQuantityString(R.plurals.tasks_done, ended.size, ended.size) to
                ended.joinToString("\n") { describe(this, it.first) }
            else -> resources.getQuantityString(R.plurals.tasks_done_failed, ended.size, ended.size, failed.size) to
                failed.joinToString("\n") { getString(R.string.task_failed_title, describe(this, it.first)) }
        }
        DoneNotice.post(applicationContext, DoneNotice.TASKS_ID, title, text, failed = failed.isNotEmpty())
    }

    /** Android 15 limits dataSync services to 6 hours a day. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        stopSelf()
    }

    /**
     * Runs the extra-data scripts Flatpak's CLI left to Flatdroid ([ExtraData]) and records the
     * baselines of [appIds]' deployments ([baseline]). A script is the app's own code and reaches
     * every Flatpak's files through link2symlink's store, so with the file check on, nothing is
     * baselined after a script that the script could have changed: every deployment but the
     * scripts' own is recorded before they run, each script's own right after it ran, and once any
     * ran, everything [appIds] use must still match ([requireIntact]), unless [verify] is false
     * (the caller checks afterwards). An app whose script failed can't run, so the task fails; an
     * extension's failure is only logged.
     */
    private fun applyExtraDataAndBaseline(env: AlpineEnvironment, appIds: List<String>, verify: Boolean = true) {
        val extraData = extraData(env)
        val pending = extraData.pending()
        baseline(env, appIds, skip = pending.map { it.name }.toSet())
        if (pending.isEmpty()) return
        FlatpakTasks.newCommand(getString(R.string.task_extra_data))
        val guard = IntegrityGuard(applicationContext).takeIf { checkFilesOn(this) }
        val failedApp = extraData.applyAll(FlatpakTasks::output) { guard?.recordDeployment(it) }.firstOrNull { it.isApp && it.exitCode != 0 }
        if (failedApp != null) {
            throw IOException(getString(R.string.task_extra_data_failed, failedApp.deployment.parentFile!!.parentFile!!.parentFile!!.name, failedApp.exitCode))
        }
        if (verify) requireIntact(appIds, R.string.task_extra_data_changed_files)
    }

    /**
     * Alpine's packages: upgraded, then setup's list installed when one of them is missing (setup
     * gained a package since this Alpine was set up). Whether one is missing is asked while the
     * upgrade runs: `apk info` only reads the package database, which the upgrade replaces whole,
     * and an upgrade removes none of them. A failure is only logged: Flatpak's update doesn't
     * depend on it.
     */
    private fun updateAlpine(env: AlpineEnvironment) {
        val cache = File(cacheDir, "apk-cache").apply { mkdirs() }
        var allInstalled = false
        val check = thread(name = "apk-info") {
            allInstalled = runCatching { env.run(listOf("/sbin/apk", "info", "-e") + AlpineSetup.PACKAGES).exitCode == 0 }.getOrDefault(false)
        }
        try {
            for (args in listOf(listOf("upgrade"), listOf("add") + AlpineSetup.PACKAGES)) {
                if (args.first() == "add") {
                    check.join()
                    if (allInstalled) return
                }
                val command = AlpineSetup.apkCommand(args)
                FlatpakTasks.newCommand(getString(R.string.task_updating_alpine))
                FlatpakTasks.log("$ ${command.joinToString(" ")}", status = false)
                val result = env.run(command, ALPINE_TIMEOUT_SECONDS, extraBinds = listOf(cache.path to AlpineSetup.APK_CACHE), onLine = FlatpakTasks::output)
                if (result.timedOut || result.exitCode != 0) {
                    FlatpakTasks.log(getString(R.string.task_alpine_failed, result.output.lineSequence().lastOrNull { it.isNotBlank() } ?: "exit ${result.exitCode}"))
                    return
                }
            }
        } finally {
            // Stopped with the task (an interrupt stops its proot); already over otherwise.
            check.interrupt()
            check.join()
            AndroidTarFs.deleteTree(cache)
        }
    }

    private fun perform(task: FlatpakTask) {
        val env = AlpineEnvironment.get(applicationContext)
        // Deployments removed since (by the last task, or from the terminal) get their extra data
        // again if they come back.
        extraData(env).prune()
        when (task) {
            is FlatpakTask.Install -> try {
                run(env, FlatpakCommands.install(task.appIds))
            } catch (e: IOException) {
                // Data an earlier interrupted removal left half-deleted makes the install fail
                // instead of downloading it again: remove it, then try once more.
                if (!MISSING_REPO_DATA.containsMatchIn(e.message ?: "")) throw e
                // Possibly left by a kill some time before, so the links are checked too.
                cleanRepository(env, checkLinks = true)
                run(env, FlatpakCommands.install(task.appIds))
            }.also {
                applyExtraDataAndBaseline(env, task.appIds)
                catalogRefreshed(popular = false)
            }
            FlatpakTask.UpdateAll -> {
                // An update reuses every file it doesn't change, so a changed one would end up in the new
                // commit's baseline: check what is installed first.
                requireIntact(FlatpakInstallation(env.flatpakDir, env.arch).appIds(), R.string.task_update_files_changed)
                updateAlpine(env)
                // Unused runtimes first, so they aren't downloaded again only to be removed; skipped
                // (it starts Flatpak under proot for nothing) when every installed branch is in use.
                if (FlatpakInstallation(env.flatpakDir, env.arch).unusedRuntimeRefs().isNotEmpty()) {
                    run(env, FlatpakCommands.uninstallUnused, getString(R.string.task_removing_unused))
                }
                run(env, FlatpakCommands.update)
                applyExtraDataAndBaseline(env, FlatpakInstallation(env.flatpakDir, env.arch).appIds())
                catalogRefreshed(popular = false)
            }
            // After a force stop, which kills processes anywhere.
            FlatpakTask.Repair -> {
                cleanRepository(env, checkLinks = true)
                // Baselines a task before the kill didn't get to record.
                if (AppData.baselineMarker(applicationContext).exists()) baseline(env, FlatpakInstallation(env.flatpakDir, env.arch).appIds())
            }
            FlatpakTask.BaselineAll -> baseline(env, FlatpakInstallation(env.flatpakDir, env.arch).appIds())
            is FlatpakTask.Uninstall -> {
                // The apps whose runtimes may go first, then the unused runtimes, then the apps whose
                // runtimes stay: a runtime they use is still in use when the unused ones go.
                val (keep, remove) = task.apps.partition { it.keepRuntimes }
                if (remove.isNotEmpty()) {
                    run(env, FlatpakCommands.uninstall(remove.map { it.appId }))
                    run(env, FlatpakCommands.uninstallUnused, getString(R.string.task_removing_unused))
                }
                if (keep.isNotEmpty()) run(env, FlatpakCommands.uninstall(keep.map { it.appId }))
                for (app in task.apps) removeAppFiles(app.appId, app.deleteData)
                IntegrityGuard(applicationContext).cleanOrphans()
            }
            FlatpakTask.RefreshCatalog -> {
                run(env, FlatpakCommands.updateCatalog)
                catalogRefreshed(popular = true)
            }
            is FlatpakTask.RemoveRuntime -> {
                // Checked again here: an app installed since the list was shown may use it now.
                val installation = FlatpakInstallation(env.flatpakDir, env.arch)
                if (task.runtimeId !in installation.unusedRuntimes(setOf(task.runtimeId))) {
                    throw IOException(getString(R.string.task_runtime_in_use, task.runtimeId))
                }
                run(env, FlatpakCommands.uninstallRuntime(task.runtimeId))
                IntegrityGuard(applicationContext).cleanOrphans()
            }
            is FlatpakTask.Reinstall -> {
                // Only the deployments whose files changed (the app, its runtime or an extension), as
                // the check finds them now; everything [appId] uses when the check is off.
                val installation = FlatpakInstallation(env.flatpakDir, env.arch)
                val problems = changedDeployments(listOf(task.appId))
                val reinstalled = problems?.map { it.deployment } ?: installation.deploymentsFor(task.appId)
                if (reinstalled.isEmpty()) {
                    FlatpakTasks.log(getString(R.string.task_log_nothing_changed))
                    return
                }
                problems?.let { discardChanged(env, it) }
                run(env, FlatpakCommands.reinstall(reinstalled.map { it.installRef }.distinct()))
                // The same commit comes back at the same path, without what its extra-data script made.
                extraData(env).forget(reinstalled.map { it.branch }.distinct())
                applyExtraDataAndBaseline(env, listOf(task.appId), verify = false)
                // The removed files came from Flathub again; everything else must still match its baseline.
                problems?.let { IntegrityGuard(applicationContext).adoptRedownloaded(it) }
                requireIntact(listOf(task.appId), R.string.task_reinstall_not_restored)
            }
            FlatpakTask.ReinstallAll -> {
                val installation = FlatpakInstallation(env.flatpakDir, env.arch)
                val appIds = installation.appIds()
                // Every deployment, each ref once, so a shared runtime or extension isn't re-downloaded per app.
                val refs = LinkedHashSet<String>()
                for (id in appIds) refs += installation.reinstallRefsFor(id)
                val problems = changedDeployments(appIds)?.also { discardChanged(env, it) }
                if (refs.isNotEmpty()) run(env, FlatpakCommands.reinstall(refs.toList()))
                extraData(env).forget()
                applyExtraDataAndBaseline(env, appIds, verify = false)
                problems?.let { IntegrityGuard(applicationContext).adoptRedownloaded(it) }
                requireIntact(appIds, R.string.task_reinstall_not_restored)
            }
        }
    }

    /**
     * Flatpak may have downloaded Flathub's catalog again (as an install or update does when it is
     * old): the browser's index is rebuilt if it changed, with Popular ([Catalog.refreshed]); with
     * [popular] (the user's own refresh), Popular is fetched in any case. A failure here is only
     * logged: the task itself did what it was for.
     */
    private fun catalogRefreshed(popular: Boolean) {
        if (!popular && !Catalog.changed(applicationContext)) return
        FlatpakTasks.newCommand(getString(R.string.task_reading_catalog))
        try {
            Catalog.refreshed(applicationContext, popular)
        } catch (e: Exception) {
            FlatpakTasks.log(getString(R.string.task_catalog_failed, e.message ?: e.toString()))
        }
    }

    /**
     * Records the file-integrity baseline of the given apps (their deployments and runtimes), when
     * the file check is on, except for the commits in [skip]; and removes manifests for
     * deployments no longer present.
     */
    private fun baseline(env: AlpineEnvironment, appIds: List<String>, skip: Set<String> = emptySet()) {
        if (!checkFilesOn(this)) return
        val guard = IntegrityGuard(applicationContext)
        // Shared across apps so a runtime or extension several apps use is hashed once, not per app.
        val baselinedCommits = mutableSetOf<String>()
        for ((index, id) in appIds.withIndex()) {
            guard.recordBaseline(
                id,
                baselinedCommits = baselinedCommits,
                skip = skip,
                onStart = { FlatpakTasks.newCommand(getString(R.string.task_recording_baseline)) },
            ) { ref, done, total ->
                if (done == total || done % 1000 == 0) {
                    FlatpakTasks.log(getString(R.string.task_baseline_progress, ref.name, done, total))
                    // Across the apps: this one's files are a share of all of them.
                    if (total > 0) FlatpakTasks.progress((index + done.toFloat() / total) / appIds.size)
                }
            }
        }
        guard.cleanOrphans()
    }

    /**
     * With the file check on, fails the task with [message] (naming the affected packages) unless
     * every deployment [appIds] use matches its baseline; the changed paths go to the task's log.
     */
    private fun requireIntact(appIds: List<String>, message: Int) {
        if (!checkFilesOn(this) || appIds.isEmpty()) return
        FlatpakTasks.newCommand(getString(R.string.task_checking_files))
        val problems = IntegrityGuard(applicationContext).verifyAll(appIds, ::logCheckProgress)
        if (problems.isEmpty()) return
        logProblems(problems)
        throw IOException(getString(message, problems.joinToString(", ") { it.ref.name }))
    }

    /** The deployments of [appIds] whose files changed, each listed in the task's log; null with the check off. */
    private fun changedDeployments(appIds: List<String>): List<IntegrityGuard.Problem>? {
        if (!checkFilesOn(this) || appIds.isEmpty()) return null
        FlatpakTasks.newCommand(getString(R.string.task_checking_files))
        val problems = IntegrityGuard(applicationContext).verifyAll(appIds, ::logCheckProgress)
        logProblems(problems)
        return problems
    }

    /** Each changed and removed path of [problems] in the task's log. */
    private fun logProblems(problems: List<IntegrityGuard.Problem>) {
        for (p in problems) {
            p.tampered.forEach { FlatpakTasks.log(getString(R.string.task_log_changed, p.ref.name, FileIntegrity.escape(it))) }
            p.removed.forEach { FlatpakTasks.log(getString(R.string.task_log_removed_file, p.ref.name, FileIntegrity.escape(it))) }
        }
    }

    /** Ends processes that left proot ([LeftoverProcesses.killEscaped]), saying so in the task's log. */
    private fun endEscaped() {
        val count = LeftoverProcesses.killEscaped()
        if (count > 0) FlatpakTasks.log(resources.getQuantityString(R.plurals.task_log_escaped, count, count))
    }

    /**
     * Before a reinstall: Flatpak reuses every object it already has, and a deployed file and its
     * repository object are one link2symlink file, so changed content would come back as it is.
     * Removes the changed files' links and their repository objects' ([ChangedContent]) and marks
     * their commits partial, so the pull fetches those objects again. Only these links are
     * touched: no scan of the installation and no prune.
     */
    private fun discardChanged(env: AlpineEnvironment, problems: List<IntegrityGuard.Problem>) {
        if (problems.isEmpty()) return
        val links = ChangedContent(File(env.paths().l2sDir), File(env.flatpakDir, "repo/objects"), AndroidLinkFs)
            .discard(problems.flatMap { it.changedFiles })
        FlatpakTasks.log(resources.getQuantityString(R.plurals.task_log_discarded, links, links))
        val state = File(env.flatpakDir, "repo/state").apply { mkdirs() }
        for (p in problems) {
            // A deployment's folder is its commit, sometimes with the subpaths it holds after a dash.
            val commit = p.deployment.dir.name.take(COMMIT_LENGTH).takeIf { it.length == COMMIT_LENGTH && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
            commit?.let { File(state, "$it.commitpartial").createNewFile() }
        }
    }

    /** The check's progress in the task's log (its last line shows under the task), every so often. */
    private fun logCheckProgress(ref: FlatpakRef, done: Int, total: Int) {
        if (total > 0 && (done == total || done % 2000 == 0)) {
            FlatpakTasks.log(getString(R.string.task_check_progress, ref.name, done, total))
            FlatpakTasks.progress(done.toFloat() / total)
        }
    }

    private fun extraData(env: AlpineEnvironment) = ExtraData(env, AppData.extraDataState(applicationContext), File(cacheDir, "extra-data"))

    /**
     * After an update or uninstall that stopped part way. flatpak's own SIGINT handling exits in
     * the middle of removing unused repository objects (ostree's prune isn't atomic), which can
     * leave a commit whose files are partly gone, and a hard kill can leave files whose content is
     * gone (see BrokenLinks); a later install then fails rather than downloading them again. Not
     * after installs: an interrupted download is already safe, and cleaning would throw away
     * what it fetched, nor after a baseline, which changes no Flatpak files. Links are checked only after a kill: a command that ended on SIGINT
     * stopped between system calls, which leaves them whole.
     */
    private fun cleanUpAfter(task: FlatpakTask) {
        // A baseline changes no Flatpak files, so there is nothing half-done to clean up after it either.
        if (task is FlatpakTask.Install || task == FlatpakTask.BaselineAll) return
        try {
            cleanRepository(AlpineEnvironment.get(applicationContext), checkLinks = killed)
        } catch (e: Exception) {
            FlatpakTasks.log(getString(R.string.task_log_cleanup_failed, e.message ?: e.toString()))
        }
    }

    /**
     * [perform], and once more if it failed on a stranded link2symlink intermediate
     * ([BrokenLinks.mayBeStranded]) and pointing such intermediates back at their content fixed any.
     * Nothing costly runs otherwise: the check reads `.l2s` only after that error.
     */
    private fun performRelinking(task: FlatpakTask) {
        try {
            perform(task)
        } catch (e: IOException) {
            if (!BrokenLinks.mayBeStranded(e.message) || relinkStranded(AlpineEnvironment.get(applicationContext), e.message) == 0) throw e
            FlatpakTasks.log(getString(R.string.task_log_retrying))
            perform(task)
        }
    }

    /**
     * Repairs stranded link2symlink intermediates ([BrokenLinks]): only those of the objects [error]
     * names when it names any and that finds one, else every one in the store. Baselines recorded
     * through them while they were broken are recorded again. Logged; how many were repaired.
     */
    private fun relinkStranded(env: AlpineEnvironment, error: String? = null): Int {
        val links = BrokenLinks(File(env.paths().l2sDir), AndroidLinkFs)
        val named = BrokenLinks.namedObjects(error)
        val relinked = named.takeIf { it.isNotEmpty() }?.let { links.relinkObjects(File(env.flatpakDir, "repo/objects"), it) }?.ifEmpty { null }
            ?: links.relinkStranded()
        if (relinked.isEmpty()) return 0
        FlatpakTasks.log(resources.getQuantityString(R.plurals.task_log_relinked, relinked.size, relinked.size))
        val reread = FileIntegrityCheck(AppData.integrityDir(applicationContext), File(env.paths().l2sDir)).rereadRepaired(relinked)
        if (reread > 0) DebugLog.i("Baseline entries read again after the repair: $reread")
        return relinked.size
    }

    /**
     * Removes broken files (from outside proot, which can't) when [checkLinks], after pointing
     * stranded intermediates back at their content (a kill can leave either), then unreferenced data.
     */
    private fun cleanRepository(env: AlpineEnvironment, checkLinks: Boolean) {
        FlatpakTasks.newCommand(getString(R.string.task_cleaning_up))
        if (checkLinks) {
            relinkStranded(env)
            val removed = BrokenLinks(File(env.paths().l2sDir), AndroidLinkFs).removeUnder(env.flatpakDir)
            if (removed > 0) FlatpakTasks.log(resources.getQuantityString(R.plurals.task_log_removed_broken, removed, removed))
        }
        run(env, FlatpakCommands.cleanRepository, getString(R.string.task_cleaning_up))
    }

    private fun run(env: AlpineEnvironment, command: List<String>, phase: String? = null) {
        FlatpakTasks.newCommand(phase)
        FlatpakTasks.log("$ ${command.joinToString(" ")}", status = false)
        val result = try {
            env.run(command, timeoutSeconds = TIMEOUT_SECONDS, onLine = FlatpakTasks::output)
        } catch (e: RunInterruptedException) {
            if (e.killed) killed = true
            throw e
        }
        if (result.killed) killed = true
        when {
            result.timedOut -> throw IOException(getString(R.string.task_timed_out))
            result.exitCode != 0 -> throw IOException(
                result.output.lines().lastOrNull { it.isNotBlank() } ?: "exit ${result.exitCode}"
            )
        }
    }

    /** What Flatdroid kept for the app: always its caches and Electron swap; its data on request. */
    private fun removeAppFiles(appId: String, deleteData: Boolean) {
        val ctx = applicationContext
        val remove = mutableListOf(
            File(AppData.cacheRoot(ctx), appId), File(AppData.rootsDir(ctx), appId), File(AppData.appTmpDir(ctx), appId),
            File(AppData.electronDir(ctx), appId), File(AlpineEnvironment.get(ctx).cacheDir, "${ElectronSwap.BUILD_DIR}/$appId"),
        )
        if (TempHomes.owner(ctx) == appId) TempHomes.delete(ctx)
        if (deleteData) {
            remove += File(AppData.root(ctx), appId)
            AppData.settings(ctx, appId).delete()
        }
        remove.forEach { AndroidTarFs.deleteTree(it) }
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    /**
     * The task's notification: its name (or what it does now, [FlatpakTaskUi.phase]), the step or
     * status under it, and a progress bar that fills when the task can say how far it is (a
     * download's percent, baselines and checks), moving without an end otherwise.
     */
    private fun notification(task: FlatpakTask, title: String? = null, detail: String? = null, fraction: Float? = null): Notification {
        createChannel()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, FlatpakTaskService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title ?: describe(this, task))
            .apply { if (!detail.isNullOrBlank()) setContentText(detail) }
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(PROGRESS_MAX, fraction?.let { (it * PROGRESS_MAX).toInt() } ?: 0, fraction == null)
            .addAction(0, getString(R.string.command_cancel), cancel)
            .build()
    }

    /**
     * Keeps [task]'s notification up with what the bottom bar shows, at most every
     * [NOTIFICATION_INTERVAL_MS] (Android drops updates that come faster). On the service's own
     * coroutines; cancelled when the task ends.
     */
    private fun followInNotification(task: FlatpakTask): Job = scope.launch {
        val manager = getSystemService(NotificationManager::class.java)
        FlatpakTasks.ui
            .filter { it.running == task }
            .map { ui -> Triple(ui.phase ?: describe(this@FlatpakTaskService, task), ui.detail(this@FlatpakTaskService), ui.fraction?.let { (it * 100).toInt() }) }
            .distinctUntilChanged()
            .conflate()
            .collect { (title, detail, percent) ->
                manager.notify(NOTIFICATION_ID, notification(task, title, detail, percent?.let { it / 100f }))
                delay(NOTIFICATION_INTERVAL_MS)
            }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.task_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CHANNEL_ID = "flatpak"
        private const val NOTIFICATION_ID = 20
        private const val PROGRESS_MAX = 1000
        private const val NOTIFICATION_INTERVAL_MS = 500L
        private const val ACTION_CANCEL = "com.garathon.flatdroid.action.CANCEL_TASK"
        private const val TIMEOUT_SECONDS = 2 * 60 * 60L
        /** An OSTree commit's checksum, in hex. */
        private const val COMMIT_LENGTH = 64
        private const val ALPINE_TIMEOUT_SECONDS = 60 * 60L
        /** How flatpak reports repository data that is referenced but missing. */
        private val MISSING_REPO_DATA = Regex("No such metadata object|While trying to checkout|Opening content object|Failed to read commit")
        private const val WAKE_LOCK_TIMEOUT_MS = TIMEOUT_SECONDS * 1000

        private const val EXTRA_TASK = "task"
        private const val EXTRA_APP_ID = "appId"
        private const val EXTRA_APP_IDS = "appIds"
        private const val EXTRA_DELETE_DATA = "deleteData"
        private const val EXTRA_KEEP_RUNTIMES = "keepRuntimes"

        /**
         * Starts [task]; false when it can't start now. One task at a time, and none while an app
         * runs: its commands would be visible to the app ([AlpineCommands]), and an update or
         * uninstall changes files the app may be using.
         */
        fun start(context: Context, task: FlatpakTask): Boolean {
            if (SessionController.isBusy) return false
            // Claimed here, before the service runs it: a second start meanwhile (a double tap, the
            // queue and a repair on return) sees it running and waits or is refused.
            if (!FlatpakTasks.begin(task)) return false
            val intent = Intent(context, FlatpakTaskService::class.java)
            when (task) {
                is FlatpakTask.Install -> intent.putExtra(EXTRA_TASK, "install").putExtra(EXTRA_APP_IDS, task.appIds.toTypedArray())
                FlatpakTask.UpdateAll -> intent.putExtra(EXTRA_TASK, "update")
                FlatpakTask.Repair -> intent.putExtra(EXTRA_TASK, "repair")
                FlatpakTask.BaselineAll -> intent.putExtra(EXTRA_TASK, "baselineAll")
                is FlatpakTask.Uninstall -> intent.putExtra(EXTRA_TASK, "uninstall").putExtra(EXTRA_APP_IDS, task.appIds.toTypedArray())
                    .putExtra(EXTRA_DELETE_DATA, task.apps.map { it.deleteData }.toBooleanArray())
                    .putExtra(EXTRA_KEEP_RUNTIMES, task.apps.map { it.keepRuntimes }.toBooleanArray())
                is FlatpakTask.Reinstall -> intent.putExtra(EXTRA_TASK, "reinstall").putExtra(EXTRA_APP_ID, task.appId)
                FlatpakTask.RefreshCatalog -> intent.putExtra(EXTRA_TASK, "refreshCatalog")
                is FlatpakTask.RemoveRuntime -> intent.putExtra(EXTRA_TASK, "removeRuntime").putExtra(EXTRA_APP_ID, task.runtimeId)
                FlatpakTask.ReinstallAll -> intent.putExtra(EXTRA_TASK, "reinstallAll")
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                // Android 12 and later refuse it while Flatdroid is in the background (an app ended
                // there, and installs were waiting): they wait until Flatdroid is in front again.
                DebugLog.w("Task not started from the background: $e")
                FlatpakTasks.abandon(task)
                return false
            }
            return true
        }

        /**
         * Runs [task] now, or queues it behind the running task or app ([FlatpakTasks.enqueue]); true
         * when it started now. It goes through the queue in either case, so it never jumps ahead of
         * what already waits, or of a repair Flatdroid owes ([startNext]).
         */
        fun run(context: Context, task: FlatpakTask): Boolean {
            FlatpakTasks.enqueue(task)
            startNext(context)
            return FlatpakTasks.ui.value.running == task
        }

        /**
         * Starts what comes next, when no task or app runs: first the clean-ups Flatdroid owes the
         * installation ([repairNeeded], then [baselinesNeeded]), then the first waiting task. Called
         * when a task or app ends, and when Flatdroid comes to the front (Android may have refused a
         * start while it was in the background); the queue lives as long as Flatdroid's process.
         */
        fun startNext(context: Context) {
            if (FlatpakTasks.ui.value.running != null || SessionController.isBusy) return
            if (repairNeeded(context)) { start(context, FlatpakTask.Repair); return }
            if (baselinesNeeded(context)) { start(context, FlatpakTask.BaselineAll); return }
            val next = FlatpakTasks.takeNext() ?: return
            if (!start(context, next)) FlatpakTasks.putBack(next)
        }

        /**
         * An update or uninstall that never ended (Flatdroid was killed in the middle) can leave the
         * repository half-pruned; its marker is still there, so it is cleaned up before anything else
         * uses the repository. Not while setup or a reset is unfinished.
         */
        private fun repairNeeded(context: Context): Boolean {
            val setup = SetupController.state.value
            return AppData.taskMarker(context).exists() && setup.isComplete && !setup.running
        }

        /**
         * File baselines a task didn't get to record (it was cancelled, failed or Flatdroid was
         * killed): recorded before the queue goes on, unless a Repair runs first (it records them
         * itself). With the file check off, the marker just goes.
         */
        private fun baselinesNeeded(context: Context): Boolean {
            val setup = SetupController.state.value
            val marker = AppData.baselineMarker(context)
            if (!marker.exists() || !setup.isComplete || setup.running) return false
            if (!checkFilesOn(context)) {
                marker.delete()
                return false
            }
            return true
        }

        private fun checkFilesOn(context: Context) = SetupController.settings(context).load()?.security?.checkFilesBeforeLaunch == true

        private fun taskOf(intent: Intent): FlatpakTask? {
            val appId = intent.getStringExtra(EXTRA_APP_ID)?.takeIf(InstalledApps::isValidId)
            return when (intent.getStringExtra(EXTRA_TASK)) {
                "install" -> intent.getStringArrayExtra(EXTRA_APP_IDS)?.toList()
                    ?.takeIf { ids -> ids.isNotEmpty() && ids.all(InstalledApps::isValidId) }?.let { FlatpakTask.Install(it) }
                "update" -> FlatpakTask.UpdateAll
                "repair" -> FlatpakTask.Repair
                "baselineAll" -> FlatpakTask.BaselineAll
                "reinstall" -> appId?.let { FlatpakTask.Reinstall(it) }
                "refreshCatalog" -> FlatpakTask.RefreshCatalog
                "removeRuntime" -> appId?.let { FlatpakTask.RemoveRuntime(it) }
                "reinstallAll" -> FlatpakTask.ReinstallAll
                "uninstall" -> {
                    val ids = intent.getStringArrayExtra(EXTRA_APP_IDS)?.toList()?.takeIf { ids -> ids.isNotEmpty() && ids.all(InstalledApps::isValidId) }
                    val deleteData = intent.getBooleanArrayExtra(EXTRA_DELETE_DATA)
                    val keepRuntimes = intent.getBooleanArrayExtra(EXTRA_KEEP_RUNTIMES)
                    if (ids == null || deleteData?.size != ids.size || keepRuntimes?.size != ids.size) null
                    else FlatpakTask.Uninstall(ids.indices.map { UninstallApp(ids[it], deleteData[it], keepRuntimes[it]) })
                }
                else -> null
            }
        }

        fun cancel(context: Context) {
            if (FlatpakTasks.ui.value.running == null) return
            context.startService(Intent(context, FlatpakTaskService::class.java).setAction(ACTION_CANCEL))
        }

        fun describe(context: Context, task: FlatpakTask): String = when (task) {
            is FlatpakTask.Install -> context.getString(R.string.task_installing, task.appIds.joinToString(", "))
            FlatpakTask.UpdateAll -> context.getString(R.string.task_updating)
            FlatpakTask.Repair -> context.getString(R.string.task_repairing)
            FlatpakTask.BaselineAll -> context.getString(R.string.task_baselining_all)
            is FlatpakTask.Uninstall -> context.getString(R.string.task_uninstalling, task.appIds.joinToString(", "))
            is FlatpakTask.Reinstall -> context.getString(R.string.task_reinstalling, task.appId)
            is FlatpakTask.RemoveRuntime -> context.getString(R.string.task_removing_runtime, task.runtimeId)
            FlatpakTask.RefreshCatalog -> context.getString(R.string.task_refreshing_catalog)
            FlatpakTask.ReinstallAll -> context.getString(R.string.task_reinstalling_all)
        }
    }
}
