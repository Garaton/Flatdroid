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
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.garathon.flatdroid.MainActivity
import com.garathon.flatdroid.R
import com.garathon.flatdroid.alpine.AlpineCommands
import com.garathon.flatdroid.audio.MicStreamer
import com.garathon.flatdroid.camera.AndroidCameras
import com.garathon.flatdroid.flatpak.AppData
import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.settings.HomeMode
import com.garathon.flatdroid.setup.SetupController
import com.garathon.flatdroid.storage.SavedSizes
import com.garathon.flatdroid.storage.TempHomePrompt
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * Keeps one Flatpak session (display helpers and the app) running in the foreground, so Android
 * doesn't stop it while the user is in the VNC viewer. specialUse: dataSync is limited to 6 hours
 * a day on Android 15. With the microphone type too when the app may use the microphone: Android
 * lets a service record in the background only with that type, given while Flatdroid is in the
 * foreground (at launch, or when the user allows it in Flatdroid). The camera type likewise, at
 * launch, when the app may use the camera.
 */
class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var microphoneType = false
    private var cameraType = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appId = intent?.getStringExtra(EXTRA_APP_ID)
        when (intent?.action) {
            ACTION_STOP -> {
                job?.cancel()
                // A cancelled job is no longer active but still finishing; it stops the service itself.
                if (job?.isCompleted != false) {
                    removeNotification()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_ALLOW_MICROPHONE -> {
                SessionController.answerMicrophone(true)
                return START_NOT_STICKY
            }
            ACTION_DENY_MICROPHONE -> {
                SessionController.answerMicrophone(false)
                return START_NOT_STICKY
            }
            ACTION_LAUNCH, ACTION_PREPARE_ELECTRON -> Unit
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (job?.isActive != true && appId != null && InstalledApps.isValidId(appId)) {
            microphoneType = mayRecord(appId)
            cameraType = AppData.settings(this, appId).load().camera && AndroidCameras.hasPermission(this)
        }
        startInForeground(notification(SessionController.ui.value.state))
        if (job?.isActive == true) return START_NOT_STICKY
        if (appId == null || !InstalledApps.isValidId(appId)) {
            SessionController.ended(null)
            stopSelf()
            return START_NOT_STICKY
        }

        val prepareElectron = intent.action == ACTION_PREPARE_ELECTRON
        val micAnswer = if (intent.hasExtra(EXTRA_MIC_ANSWER)) intent.getBooleanExtra(EXTRA_MIC_ANSWER, false) else null
        val notifications = getSystemService(NotificationManager::class.java)
        val updates = scope.launch {
            SessionController.ui.distinctUntilChangedBy { it.state::class to it.state.appIdOrNull }
                .filter { it.state !is SessionState.Idle && it.state !is SessionState.ElectronOffer && it.state !is SessionState.FilesChanged }
                .collect { notifications.notify(NOTIFICATION_ID, notification(it.state)) }
        }
        val micRequests = scope.launch {
            SessionController.ui.map { it.micRequest }.distinctUntilChanged().collect { request ->
                if (request == null) notifications.cancel(MIC_NOTIFICATION_ID)
                else notifications.notify(MIC_NOTIFICATION_ID, micNotification(request))
            }
        }
        job = scope.launch {
            var message: String? = null
            var failed = false
            // The app changes its own files, and an Electron swap may be prepared.
            SavedSizes.forget(applicationContext, appId)
            try {
                runInterruptible {
                    val launcher = FlatpakLauncher(applicationContext)
                    if (prepareElectron) {
                        SessionController.preparingElectron(appId, getString(R.string.electron_preparing), null)
                        launcher.prepareElectron(appId)
                    }
                    if (!launcher.run(appId, micAnswer)) return@runInterruptible
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
                withContext(NonCancellable) {
                    updates.cancelAndJoin()
                    micRequests.cancelAndJoin()
                }
                notifications.cancel(MIC_NOTIFICATION_ID)
                removeNotification()
                SavedSizes.changed(applicationContext, appId)
                // Before the session counts as ended, so Flatdroid's window doesn't ask as well.
                if (AppData.settings(applicationContext, appId).load().homeMode == HomeMode.TEMPORARY) {
                    TempHomePrompt.sessionEnded(applicationContext, appId)
                }
                // An Electron offer or a files-changed report stays up for the UI to answer.
                val awaitingUi = SessionController.ui.value.state.let {
                    it is SessionState.ElectronOffer || it is SessionState.FilesChanged
                }
                if (!awaitingUi) SessionController.ended(message, failed)
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

    /**
     * Whether [appId] may record, so the session takes the microphone type at launch, while
     * Flatdroid is in the foreground: its sound and microphone are on, and Android's permission is
     * granted. Android then lets the session record later from the background, once the user
     * allows it in the notification; until then the type is held but the microphone isn't used.
     */
    private fun mayRecord(appId: String): Boolean {
        val settings = AppData.settings(this, appId).load()
        return settings.microphone && MicStreamer.hasPermission(this) &&
            settings.audioAllowed(InstalledApps(applicationContext).sockets(appId))
    }

    private fun startInForeground(notification: Notification) {
        var type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        if (microphoneType && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (cameraType && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    /**
     * Asks about the microphone, answered in the notification itself: Allow or Deny holds for the
     * session; swiping it away denies. Allow needs the device unlocked (Android 12+).
     */
    private fun micNotification(appId: String): Notification {
        createChannel(MIC_CHANNEL_ID, R.string.session_mic_channel, NotificationManager.IMPORTANCE_HIGH)
        val allow = PendingIntent.getService(
            this, 3, Intent(this, SessionService::class.java).setAction(ACTION_ALLOW_MICROPHONE), PendingIntent.FLAG_IMMUTABLE,
        )
        val deny = PendingIntent.getService(
            this, 4, Intent(this, SessionService::class.java).setAction(ACTION_DENY_MICROPHONE), PendingIntent.FLAG_IMMUTABLE,
        )
        val allowAction = NotificationCompat.Action.Builder(0, getString(R.string.mic_allow), allow)
            .setAuthenticationRequired(true)
            .build()
        return NotificationCompat.Builder(this, MIC_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.mic_request_title, label(appId)))
            .setContentText(getString(R.string.mic_request_notification))
            .setDeleteIntent(deny)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Its own group, so Android doesn't bundle it with the session's and hide its buttons.
            .setGroup(MIC_CHANNEL_ID)
            .addAction(0, getString(R.string.mic_deny), deny)
            .addAction(allowAction)
            .build()
    }

    private fun notification(state: SessionState): Notification {
        createChannel(CHANNEL_ID, R.string.session_channel, NotificationManager.IMPORTANCE_LOW)
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
            .setGroup(CHANNEL_ID)
        val viewerIntent = (state as? SessionState.Running)?.let { Viewer.intent(this, it.password, SetupController.settings(this).load()?.vncViewer) }
        if (viewerIntent != null) {
            val viewer = PendingIntent.getActivity(this, 1, viewerIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(0, getString(R.string.session_open_viewer), viewer)
        }
        val stop = PendingIntent.getService(
            this, 2, Intent(this, SessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(0, getString(R.string.session_stop), stop)
        return builder.build()
    }

    private fun createChannel(id: String, name: Int, importance: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(id) != null) return
        manager.createNotificationChannel(NotificationChannel(id, getString(name), importance))
    }

    companion object {
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 10
        private const val MIC_CHANNEL_ID = "permissions"
        private const val MIC_NOTIFICATION_ID = 11
        private const val EXTRA_APP_ID = "appId"
        private const val EXTRA_MIC_ANSWER = "micAnswer"
        private const val ACTION_LAUNCH = "com.garathon.flatdroid.action.LAUNCH"
        private const val ACTION_PREPARE_ELECTRON = "com.garathon.flatdroid.action.PREPARE_ELECTRON"
        private const val ACTION_STOP = "com.garathon.flatdroid.action.STOP"
        private const val ACTION_ALLOW_MICROPHONE = "com.garathon.flatdroid.action.ALLOW_MICROPHONE"
        private const val ACTION_DENY_MICROPHONE = "com.garathon.flatdroid.action.DENY_MICROPHONE"

        /** [micAnswer]: the microphone's answer for the session, when asked before the launch. */
        fun launch(context: Context, appId: String, micAnswer: Boolean? = null) = start(context, ACTION_LAUNCH, appId, micAnswer)

        /** After the user agreed to download Electron for this device: prepare it, then launch. */
        fun prepareElectronAndLaunch(context: Context, appId: String, micAnswer: Boolean? = null) =
            start(context, ACTION_PREPARE_ELECTRON, appId, micAnswer)

        /** Whether the microphone question can be posted: notifications, and their channel, aren't blocked. */
        fun canAskInNotification(context: Context): Boolean {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return false
            val channel = manager.getNotificationChannelCompat(MIC_CHANNEL_ID) ?: return true
            return channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
        }

        fun stop(context: Context) {
            val state = SessionController.ui.value.state
            if (!SessionController.isBusy) return
            state.appIdOrNull?.let { SessionController.stopping(it) }
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_STOP))
        }

        /** Not while a Flatpak task or a terminal command runs ([AlpineCommands.sessionMayStart]). */
        private fun start(context: Context, action: String, appId: String, micAnswer: Boolean?) {
            if (SessionController.isBusy || !AlpineCommands.sessionMayStart(context)) return
            SessionController.starting(appId, context.getString(R.string.session_starting), clearLog = true)
            ContextCompat.startForegroundService(
                context,
                Intent(context, SessionService::class.java).setAction(action).putExtra(EXTRA_APP_ID, appId)
                    .apply { if (micAnswer != null) putExtra(EXTRA_MIC_ANSWER, micAnswer) },
            )
        }
    }
}
