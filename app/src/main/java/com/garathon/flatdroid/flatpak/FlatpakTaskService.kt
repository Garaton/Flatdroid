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
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.launcher.ElectronSwap
import com.garathon.flatdroid.proot.RunInterruptedException
import com.garathon.flatdroid.session.SessionController
import com.garathon.flatdroid.setup.AlpineSetup
import com.garathon.flatdroid.setup.AndroidTarFs
import com.garathon.flatdroid.setup.deleteTree
import com.garathon.flatdroid.storage.SavedSizes
import com.garathon.flatdroid.storage.TempHomes
import com.garathon.flatdroid.util.writeTextAtomically
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            // flatpak gets time to stop by itself (see ProcessStopper), which can take a while.
            if (job?.isActive == true) FlatpakTasks.cancelling(getString(R.string.task_cancelling))
            job?.cancel()
            return START_NOT_STICKY
        }
        val task = intent?.let(::taskOf) ?: run { stopSelf(); return START_NOT_STICKY }
        startInForeground(notification(task))
        if (job?.isActive == true || !FlatpakTasks.begin(task)) return START_NOT_STICKY

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Flatdroid:flatpak")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        // Installs are left out: an interrupted download is already safe (see cleanUpAfter).
        val marker = AppData.taskMarker(applicationContext).takeIf { task !is FlatpakTask.Install }
        killed = false
        job = scope.launch {
            var error: String? = null
            try {
                marker?.writeTextAtomically(task.toString())
                SavedSizes.forgetAll(applicationContext)
                runInterruptible { perform(task) }
            } catch (e: CancellationException) {
                error = getString(R.string.task_cancelled)
                withContext(NonCancellable) { runInterruptible { cleanUpAfter(task) } }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                runInterruptible { cleanUpAfter(task) }
            } finally {
                marker?.delete()
                SavedSizes.changedAll(applicationContext)
                FlatpakTasks.log(error?.let { getString(R.string.task_log_failed, it) } ?: getString(R.string.task_log_done))
                FlatpakTasks.end(task, error)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    /** Android 15 limits dataSync services to 6 hours a day. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        stopSelf()
    }

    /**
     * Runs the extra-data scripts Flatpak's CLI left to Flatdroid ([ExtraData]). An app whose
     * script failed can't run, so the task fails; an extension's failure is only logged.
     */
    private fun applyExtraData(env: AlpineEnvironment) {
        val extraData = ExtraData(env, File(filesDir, "extra-data-applied"), File(cacheDir, "extra-data"))
        if (extraData.pending().isEmpty()) return
        FlatpakTasks.newCommand(getString(R.string.task_extra_data))
        val failedApp = extraData.applyAll(FlatpakTasks::log).firstOrNull { it.isApp && it.exitCode != 0 }
        if (failedApp != null) {
            throw IOException(getString(R.string.task_extra_data_failed, failedApp.deployment.parentFile!!.parentFile!!.parentFile!!.name, failedApp.exitCode))
        }
    }

    /**
     * Alpine's packages: upgraded, then setup's list installed, which adds any package setup gained
     * since this Alpine was set up. A failure is only logged: Flatpak's update doesn't depend on it.
     */
    private fun updateAlpine(env: AlpineEnvironment) {
        val cache = File(cacheDir, "apk-cache").apply { mkdirs() }
        try {
            for (args in listOf(listOf("upgrade"), listOf("add") + AlpineSetup.PACKAGES)) {
                val command = AlpineSetup.apkCommand(args)
                FlatpakTasks.newCommand(getString(R.string.task_updating_alpine))
                FlatpakTasks.log("$ ${command.joinToString(" ")}")
                val result = env.run(command, ALPINE_TIMEOUT_SECONDS, extraBinds = listOf(cache.path to AlpineSetup.APK_CACHE), onLine = FlatpakTasks::log)
                if (result.timedOut || result.exitCode != 0) {
                    FlatpakTasks.log(getString(R.string.task_alpine_failed, result.output.lineSequence().lastOrNull { it.isNotBlank() } ?: "exit ${result.exitCode}"))
                    return
                }
            }
        } finally {
            AndroidTarFs.deleteTree(cache)
        }
    }

    private fun perform(task: FlatpakTask) {
        val env = AlpineEnvironment.get(applicationContext)
        when (task) {
            is FlatpakTask.Install -> try {
                run(env, FlatpakCommands.install(task.appId))
            } catch (e: IOException) {
                // Data an earlier interrupted removal left half-deleted makes the install fail
                // instead of downloading it again: remove it, then try once more.
                if (!MISSING_REPO_DATA.containsMatchIn(e.message ?: "")) throw e
                // Possibly left by a kill some time before, so the links are checked too.
                cleanRepository(env, checkLinks = true)
                run(env, FlatpakCommands.install(task.appId))
            }.also { applyExtraData(env) }
            FlatpakTask.UpdateAll -> {
                updateAlpine(env)
                // Unused runtimes first, so they aren't downloaded again only to be removed.
                run(env, FlatpakCommands.uninstallUnused, getString(R.string.task_removing_unused))
                run(env, FlatpakCommands.update)
                applyExtraData(env)
            }
            // After a force stop, which kills processes anywhere.
            FlatpakTask.Repair -> cleanRepository(env, checkLinks = true)
            is FlatpakTask.Uninstall -> {
                run(env, FlatpakCommands.uninstall(task.appId))
                if (!task.keepRuntimes) run(env, FlatpakCommands.uninstallUnused, getString(R.string.task_removing_unused))
                removeAppFiles(task.appId, task.deleteData)
            }
        }
    }

    /**
     * After an update or uninstall that stopped part way. flatpak's own SIGINT handling exits in
     * the middle of removing unused repository objects (ostree's prune isn't atomic), which can
     * leave a commit whose files are partly gone, and a hard kill can leave files whose content is
     * gone (see BrokenLinks); a later install then fails rather than downloading them again. Not
     * after installs: an interrupted download is already safe, and cleaning would throw away
     * what it fetched. Links are checked only after a kill: a command that ended on SIGINT
     * stopped between system calls, which leaves them whole.
     */
    private fun cleanUpAfter(task: FlatpakTask) {
        if (task is FlatpakTask.Install) return
        try {
            cleanRepository(AlpineEnvironment.get(applicationContext), checkLinks = killed)
        } catch (e: Exception) {
            FlatpakTasks.log(getString(R.string.task_log_cleanup_failed, e.message ?: e.toString()))
        }
    }

    /** Removes broken files (from outside proot, which can't) when [checkLinks], then unreferenced data. */
    private fun cleanRepository(env: AlpineEnvironment, checkLinks: Boolean) {
        FlatpakTasks.newCommand(getString(R.string.task_cleaning_up))
        if (checkLinks) {
            val removed = BrokenLinks(File(env.paths().l2sDir), AndroidLinkFs).removeUnder(env.flatpakDir)
            if (removed > 0) FlatpakTasks.log(resources.getQuantityString(R.plurals.task_log_removed_broken, removed, removed))
        }
        run(env, FlatpakCommands.cleanRepository, getString(R.string.task_cleaning_up))
    }

    private fun run(env: AlpineEnvironment, command: List<String>, phase: String? = null) {
        FlatpakTasks.newCommand(phase)
        FlatpakTasks.log("$ ${command.joinToString(" ")}")
        val result = try {
            env.run(command, timeoutSeconds = TIMEOUT_SECONDS, onLine = FlatpakTasks::log)
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

    private fun notification(task: FlatpakTask): Notification {
        createChannel()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, FlatpakTaskService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(describe(this, task))
            .setContentIntent(open)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .addAction(0, getString(R.string.command_cancel), cancel)
            .build()
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
        private const val ACTION_CANCEL = "com.garathon.flatdroid.action.CANCEL_TASK"
        private const val TIMEOUT_SECONDS = 2 * 60 * 60L
        private const val ALPINE_TIMEOUT_SECONDS = 60 * 60L
        /** How flatpak reports repository data that is referenced but missing. */
        private val MISSING_REPO_DATA = Regex("No such metadata object|While trying to checkout|Opening content object|Failed to read commit")
        private const val WAKE_LOCK_TIMEOUT_MS = TIMEOUT_SECONDS * 1000

        private const val EXTRA_TASK = "task"
        private const val EXTRA_APP_ID = "appId"
        private const val EXTRA_DELETE_DATA = "deleteData"
        private const val EXTRA_KEEP_RUNTIMES = "keepRuntimes"

        /**
         * One task at a time. An install may run alongside an app (it only adds files); an update
         * or uninstall may not, since it changes or removes files the app may be using.
         */
        fun start(context: Context, task: FlatpakTask) {
            if (FlatpakTasks.ui.value.running != null) return
            if (SessionController.isBusy && task !is FlatpakTask.Install) return
            val intent = Intent(context, FlatpakTaskService::class.java)
            when (task) {
                is FlatpakTask.Install -> intent.putExtra(EXTRA_TASK, "install").putExtra(EXTRA_APP_ID, task.appId)
                FlatpakTask.UpdateAll -> intent.putExtra(EXTRA_TASK, "update")
                FlatpakTask.Repair -> intent.putExtra(EXTRA_TASK, "repair")
                is FlatpakTask.Uninstall -> intent.putExtra(EXTRA_TASK, "uninstall").putExtra(EXTRA_APP_ID, task.appId)
                    .putExtra(EXTRA_DELETE_DATA, task.deleteData).putExtra(EXTRA_KEEP_RUNTIMES, task.keepRuntimes)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        private fun taskOf(intent: Intent): FlatpakTask? {
            val appId = intent.getStringExtra(EXTRA_APP_ID)?.takeIf(InstalledApps::isValidId)
            return when (intent.getStringExtra(EXTRA_TASK)) {
                "install" -> appId?.let { FlatpakTask.Install(it) }
                "update" -> FlatpakTask.UpdateAll
                "repair" -> FlatpakTask.Repair
                "uninstall" -> appId?.let {
                    FlatpakTask.Uninstall(it, intent.getBooleanExtra(EXTRA_DELETE_DATA, false), intent.getBooleanExtra(EXTRA_KEEP_RUNTIMES, false))
                }
                else -> null
            }
        }

        fun cancel(context: Context) {
            if (FlatpakTasks.ui.value.running == null) return
            context.startService(Intent(context, FlatpakTaskService::class.java).setAction(ACTION_CANCEL))
        }

        fun describe(context: Context, task: FlatpakTask): String = when (task) {
            is FlatpakTask.Install -> context.getString(R.string.task_installing, task.appId)
            FlatpakTask.UpdateAll -> context.getString(R.string.task_updating)
            FlatpakTask.Repair -> context.getString(R.string.task_repairing)
            is FlatpakTask.Uninstall -> context.getString(R.string.task_uninstalling, task.appId)
        }
    }
}
