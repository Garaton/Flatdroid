package com.garathon.flatdroid.session

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.garathon.flatdroid.MainActivity
import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.FlatpakTask
import com.garathon.flatdroid.flatpak.FlatpakTasks
import com.garathon.flatdroid.flatpak.InstalledApps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * Keeps one Flatpak session (display helpers and the app) running in the foreground, so Android
 * doesn't stop it while the user is in the VNC viewer. specialUse: dataSync is limited to 6 hours
 * a day on Android 15.
 */
class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appId = intent?.getStringExtra(EXTRA_APP_ID)
        when (intent?.action) {
            ACTION_STOP -> {
                job?.cancel()
                if (job?.isActive != true) {
                    removeNotification()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_LAUNCH, ACTION_PREPARE_ELECTRON -> Unit
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startInForeground(notification(SessionController.ui.value.state))
        if (job?.isActive == true) return START_NOT_STICKY
        if (appId == null || !InstalledApps.isValidId(appId)) {
            SessionController.ended(null)
            stopSelf()
            return START_NOT_STICKY
        }

        val prepareElectron = intent.action == ACTION_PREPARE_ELECTRON
        val notifications = getSystemService(NotificationManager::class.java)
        val updates = scope.launch {
            SessionController.ui.distinctUntilChangedBy { it.state::class to it.state.appIdOrNull }
                .filter { it.state !is SessionState.Idle && it.state !is SessionState.ElectronOffer }
                .collect { notifications.notify(NOTIFICATION_ID, notification(it.state)) }
        }
        job = scope.launch {
            var message: String? = null
            var failed = false
            try {
                runInterruptible {
                    val launcher = FlatpakLauncher(applicationContext)
                    if (prepareElectron) {
                        SessionController.preparingElectron(appId, getString(R.string.electron_preparing), null)
                        launcher.prepareElectron(appId)
                    }
                    if (!launcher.run(appId)) return@runInterruptible
                    message = getString(R.string.session_ended, label(appId))
                }
            } catch (e: CancellationException) {
                message = null
            } catch (e: Exception) {
                message = getString(R.string.session_failed, e.message ?: e.toString())
                failed = true
                SessionController.log("[flatdroid] $message")
            } finally {
                // The updater must be gone first, or it can post the notification again after the
                // service has stopped, leaving one nothing removes (its Stop then does nothing).
                withContext(NonCancellable) { updates.cancelAndJoin() }
                removeNotification()
                // An Electron offer stays up for the UI to answer.
                if (SessionController.ui.value.state !is SessionState.ElectronOffer) SessionController.ended(message, failed)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        removeNotification()
        if (SessionController.isBusy) SessionController.ended(null)
        super.onDestroy()
    }

    private fun removeNotification() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun label(appId: String) = InstalledApps(applicationContext).name(appId) ?: appId

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun notification(state: SessionState): Notification {
        createChannel()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val appId = state.appIdOrNull
        val name = appId?.let(::label) ?: getString(R.string.app_name)
        val text = when (state) {
            is SessionState.Running -> getString(R.string.session_notification_running, SessionScript.VNC_PORT)
            is SessionState.Starting -> state.detail
            is SessionState.PreparingElectron -> state.detail
            else -> ""
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(name)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (state is SessionState.Running) {
            val viewer = PendingIntent.getActivity(
                this, 1, Viewer.intent(state.password), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, getString(R.string.session_open_viewer), viewer)
        }
        val stop = PendingIntent.getService(
            this, 2, Intent(this, SessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(0, getString(R.string.session_stop), stop)
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.session_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 10
        private const val EXTRA_APP_ID = "appId"
        private const val ACTION_LAUNCH = "com.garathon.flatdroid.action.LAUNCH"
        private const val ACTION_PREPARE_ELECTRON = "com.garathon.flatdroid.action.PREPARE_ELECTRON"
        private const val ACTION_STOP = "com.garathon.flatdroid.action.STOP"

        fun launch(context: Context, appId: String) = start(context, ACTION_LAUNCH, appId)

        /** After the user agreed to download Electron for this device: prepare it, then launch. */
        fun prepareElectronAndLaunch(context: Context, appId: String) = start(context, ACTION_PREPARE_ELECTRON, appId)

        fun stop(context: Context) {
            val state = SessionController.ui.value.state
            if (!SessionController.isBusy) return
            state.appIdOrNull?.let { SessionController.stopping(it) }
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_STOP))
        }

        /** Not while an update or uninstall runs, which may be changing the app's files; an install is fine. */
        private fun start(context: Context, action: String, appId: String) {
            val task = FlatpakTasks.ui.value.running
            if (SessionController.isBusy || (task != null && task !is FlatpakTask.Install)) return
            SessionController.starting(appId, context.getString(R.string.session_starting), clearLog = true)
            ContextCompat.startForegroundService(
                context, Intent(context, SessionService::class.java).setAction(action).putExtra(EXTRA_APP_ID, appId),
            )
        }
    }
}
