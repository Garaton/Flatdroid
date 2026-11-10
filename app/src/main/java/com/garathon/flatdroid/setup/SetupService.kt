package com.garathon.flatdroid.setup

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
import androidx.core.content.ContextCompat
import com.garathon.flatdroid.MainActivity
import com.garathon.flatdroid.R
import com.garathon.flatdroid.util.DoneNotice
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.flatpak.AppData
import com.garathon.flatdroid.storage.SavedSizes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Runs first-run setup in the foreground (dataSync), so it continues while the user is in other
 * apps. If the process dies anyway, setup resumes at the unfinished step next time.
 */
class SetupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(notification(SetupController.state.value))
        if (job?.isActive == true) return START_NOT_STICKY
        if (!SetupController.begin()) {
            stopSelf()
            return START_NOT_STICKY
        }

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Flatdroid:setup")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }

        val notifications = getSystemService(NotificationManager::class.java)
        val progress = scope.launch {
            // Percent steps only, to keep notification updates rare.
            SetupController.state
                .map { Triple(it.current, it.fraction?.let { f -> (f * 100).toInt() }, it.failed) }
                .distinctUntilChanged()
                .collect { notifications.notify(NOTIFICATION_ID, notification(SetupController.state.value)) }
        }
        val resetOnly = intent?.action == ACTION_RESET
        job = scope.launch {
            try {
                SavedSizes.forgetAll(applicationContext)
                val env = AlpineEnvironment.get(applicationContext)
                val reset = SetupController.reset(applicationContext, env)
                if (reset.isPending()) {
                    SetupController.beginReset()
                    SetupController.reporter.progress(null, getString(R.string.reset_running))
                    runInterruptible { reset.finish() }
                    SetupController.cleared()
                    SetupController.reporter.log(getString(R.string.reset_done))
                }
                if (!resetOnly) {
                    // Setup is started from the settings screen, so they are chosen by now; recorded here,
                    // after any reset, which clears the record.
                    SetupController.settingsChosen(applicationContext)
                    val store = SetupController.store(applicationContext)
                    val setup = AlpineSetup(applicationContext, env, AppData.launcherFiles(applicationContext))
                    runInterruptible { SetupRunner(store, setup::perform).run(SetupController.reporter) }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) SetupController.error(e.message ?: e.toString())
            } finally {
                withContext(NonCancellable) { progress.cancelAndJoin() }
                SavedSizes.changedAll(applicationContext)
                SetupController.end()
                val ui = SetupController.state.value
                if (!resetOnly && (ui.isComplete || ui.failed != null)) {
                    val title = getString(if (ui.isComplete) R.string.setup_notification_done else R.string.setup_notification_failed)
                    DoneNotice.post(applicationContext, DoneNotice.SETUP_ID, title, ui.error, failed = !ui.isComplete)
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 limits dataSync services to 6 hours a day. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        SetupController.reporter.log(getString(R.string.setup_time_limit))
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        SetupController.end()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(ui: SetupUi): Notification {
        createChannel()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when {
            ui.failed != null -> getString(R.string.setup_notification_failed)
            ui.current != null -> getString(ui.current.title)
            else -> getString(R.string.setup_notification_title)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(ui.error ?: ui.detail)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val fraction = ui.fraction
        if (ui.failed == null) {
            builder.setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.setup_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CHANNEL_ID = "setup"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L

        private const val ACTION_RESET = "com.garathon.flatdroid.action.RESET_ALPINE"

        /** Runs setup (finishing a pending reset first). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SetupService::class.java))
        }

        /** Requests a reset of the Alpine environment and carries it out; setup isn't started. */
        fun reset(context: Context, mode: ResetMode) {
            SetupController.reset(context).request(mode)
            SetupController.beginReset()
            SetupController.refresh(context)
            ContextCompat.startForegroundService(
                context, Intent(context, SetupService::class.java).setAction(ACTION_RESET),
            )
        }
    }
}
