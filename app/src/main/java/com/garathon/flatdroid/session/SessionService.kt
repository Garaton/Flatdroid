package com.garathon.flatdroid.session

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.IntentCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.garathon.flatdroid.MainActivity
import com.garathon.flatdroid.R
import com.garathon.flatdroid.session.helpers.SessionScript
import com.garathon.flatdroid.viewer.ViewerActivity
import com.garathon.flatdroid.alpine.AlpineCommands
import com.garathon.flatdroid.audio.MicStreamer
import com.garathon.flatdroid.camera.AndroidCameras
import com.garathon.flatdroid.camera.ScreenShare
import com.garathon.flatdroid.storage.AppData
import com.garathon.flatdroid.launcher.AppEnding
import com.garathon.flatdroid.launcher.ProotExit
import com.garathon.flatdroid.flatpak.tasks.FlatpakTaskService
import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.settings.HomeMode
import com.garathon.flatdroid.setup.PackageGroup
import com.garathon.flatdroid.setup.SetupController
import com.garathon.flatdroid.storage.sizes.SavedSizes
import com.garathon.flatdroid.storage.TempHomePrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.combine
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
 * doesn't stop it while the user is in the viewer (Flatdroid's own, or an external VNC one). specialUse: dataSync is limited to 6 hours
 * a day on Android 15. With the microphone type too when the app may use the microphone: Android
 * lets a service record in the background only with that type, given while Flatdroid is in the
 * foreground (at launch, or when the user allows it in Flatdroid). The camera type likewise, at
 * launch, when the app may use the camera. The media projection type only while the screen is
 * shared: taken after Android's consent, before the share starts, and dropped when it ends.
 */
class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var microphoneType = false
    private var cameraType = false
    private var screenType = false
    /** The screen share the service took last, while it runs. */
    private var projection: MediaProjection? = null

    /** The screen turning off pauses a hidden app at once ([HiddenPause.screenOff]). */
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> HiddenPause.screenOff(true)
                Intent.ACTION_SCREEN_ON -> HiddenPause.screenOff(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        HiddenPause.screenOff(!getSystemService(PowerManager::class.java).isInteractive)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, screen, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

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
            ACTION_ALLOW, ACTION_DENY -> {
                val sensor = intent.getStringExtra(EXTRA_SENSOR)?.let { name -> Sensor.entries.firstOrNull { it.name == name } } ?: return START_NOT_STICKY
                val allow = intent.action == ACTION_ALLOW
                // The screen's Allow comes only from UsePermissionActivity, with Android's consent; without a share, no answer, so the question stays.
                if (allow && sensor == Sensor.SCREEN && !share(intent)) return START_NOT_STICKY
                if (allow && sensor != Sensor.SCREEN) takeType(sensor)
                SessionController.answer(sensor, allow)
                return START_NOT_STICKY
            }
            ACTION_RESUME_NO_WINDOW -> {
                HiddenPause.resumeNoWindow()
                return START_NOT_STICKY
            }
            ACTION_LAUNCH, ACTION_PREPARE_ELECTRON, ACTION_ADD_PACKAGES, ACTION_ALPINE_SHELL -> Unit
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (job?.isActive != true && appId != null && InstalledApps.isValidId(appId)) {
            // The service takes the microphone type at launch, while Flatdroid is in the foreground: the
            // app's sound and microphone are on and Android's permission is granted. Android then lets the
            // session record later from the background, once the user allows it in the notification; until
            // then the type is held but the microphone isn't used. The camera type likewise.
            val settings = AppData.settings(this, appId).load()
            val appContext = InstalledApps(this).context(appId)
            microphoneType = MicStreamer.hasPermission(this) && settings.microphoneAllowed(appContext?.sockets.orEmpty())
            cameraType = AndroidCameras.hasPermission(this) && appContext?.let { settings.cameraAllowed(it) } == true
        }
        startInForeground(notification(SessionController.ui.value.state))
        if (job?.isActive == true) return START_NOT_STICKY
        // An app, or Alpine's shell (whose ID no app can have).
        val alpineShell = appId == AlpineShell.ID
        if (appId == null || !(InstalledApps.isValidId(appId) || alpineShell)) {
            SessionController.ended(null)
            stopSelf()
            return START_NOT_STICKY
        }

        val prepareElectron = intent.action == ACTION_PREPARE_ELECTRON
        val packages = if (intent.action != ACTION_ADD_PACKAGES) null else PackageGroup.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_PACKAGES) }
        val shellUser = AlpineShellUser.entries.firstOrNull { it.key == intent.getStringExtra(EXTRA_SHELL_USER) }
        val answers = Sensor.entries.filter { intent.hasExtra(answerExtra(it)) }.associateWith { intent.getBooleanExtra(answerExtra(it), false) }
        val notifications = getSystemService(NotificationManager::class.java)
        val updates = scope.launch {
            SessionController.ui.distinctUntilChangedBy { Triple(it.state::class, it.state.appIdOrNull, stage(it.state)) }
                .filter { it.state !is SessionState.Idle && it.state !is SessionState.Question }
                .collect { notifications.notify(NOTIFICATION_ID, notification(it.state)) }
        }
        val asks = scope.launch {
            // Only a new question is posted: posting one already shown again would pop it up again.
            var shown = emptySet<Sensor>()
            SessionController.ui.map { ui -> ui.state.appIdOrNull?.let { app -> ui.asks to app } }.distinctUntilChanged().collect { asked ->
                val now = asked?.first.orEmpty()
                for (sensor in shown - now) notifications.cancel(askId(sensor))
                for (sensor in now - shown) notifications.notify(askId(sensor), askNotification(sensor, asked!!.second))
                shown = now
            }
        }
        // The question about an app with no window goes to a notification only while Flatdroid's viewer, which asks itself, isn't on screen.
        val noWindowAsks = scope.launch {
            combine(
                SessionController.ui.map { (it.state as? SessionState.Running)?.takeIf { running -> running.noWindow }?.let { running -> running.appId to running.noWindowNeverOpened } },
                SessionController.viewerVisible,
            ) { asked, visible -> asked.takeUnless { visible } }
                .distinctUntilChanged()
                .collect { asked ->
                    if (asked == null) notifications.cancel(NO_WINDOW_NOTIFICATION_ID)
                    else notifications.notify(NO_WINDOW_NOTIFICATION_ID, noWindowNotification(asked.first, asked.second))
                }
        }
        job = scope.launch {
            var message: String? = null
            var failed = false
            // The app changes its own files, and an Electron swap may be prepared.
            // The shell may change anything in Alpine, Flatpak's installation included.
            if (alpineShell) SavedSizes.forgetAll(applicationContext) else SavedSizes.forget(applicationContext, appId)
            try {
                runInterruptible {
                    packages?.let { SessionPackages.add(applicationContext, appId, it) }
                    val ending = if (alpineShell) {
                        val alpine = AlpineShell(applicationContext)
                        if (!alpine.run(shellUser)) return@runInterruptible
                        alpine.ending
                    } else {
                        val launcher = FlatpakLauncher(applicationContext)
                        if (prepareElectron) {
                            SessionController.preparing(appId, getString(R.string.electron_preparing), null)
                            launcher.prepareElectron(appId)
                        }
                        if (!launcher.run(appId, answers)) return@runInterruptible
                        launcher.ending
                    }
                    // A crash, a kill or an error exit is an error, not a plain close.
                    message = when (ending) {
                        is AppEnding.Crashed -> getString(R.string.session_crashed, label(appId), ProotExit.signalName(ending.signal))
                        is AppEnding.Ended -> getString(R.string.session_signal_ended, label(appId), ProotExit.signalName(ending.signal))
                        is AppEnding.Failed -> getString(R.string.session_exit_error, label(appId), ending.code)
                        null -> getString(R.string.session_ended, label(appId))
                    }
                    failed = ending != null
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
                    asks.cancelAndJoin()
                    noWindowAsks.cancelAndJoin()
                }
                notifications.cancel(MIC_NOTIFICATION_ID)
                notifications.cancel(CAMERA_NOTIFICATION_ID)
                notifications.cancel(SCREEN_NOTIFICATION_ID)
                ScreenShare.clear()
                projection?.stop()
                notifications.cancel(NO_WINDOW_NOTIFICATION_ID)
                removeNotification()
                if (alpineShell) SavedSizes.changedAll(applicationContext, measureNow = false) else SavedSizes.changed(applicationContext, appId)
                // Before the session counts as ended, so Flatdroid's window doesn't ask as well.
                if (!alpineShell && AppData.settings(applicationContext, appId).load().homeMode == HomeMode.TEMPORARY) {
                    TempHomePrompt.sessionEnded(applicationContext, appId)
                }
                // A question (an Electron offer, a files-changed report) stays up for the UI to answer.
                val awaitingUi = SessionController.ui.value.state is SessionState.Question
                if (!awaitingUi) SessionController.ended(message, failed)
                // Tasks that waited for the app start now (while this service still runs in the foreground).
                // On the main thread, where a launch is checked against tasks: both claims on one thread.
                if (!awaitingUi) withContext(NonCancellable + Dispatchers.Main) { FlatpakTaskService.startNext(applicationContext) }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(screen)
        scope.cancel()
        removeNotification()
        if (SessionController.isBusy) SessionController.ended(null)
        super.onDestroy()
    }

    private fun removeNotification() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    /** What the notification's text says: a new stage reaches it, an unchanged one (the file check's progress goes on) doesn't repost it. */
    private fun stage(state: SessionState): String? = when (state) {
        is SessionState.Starting -> state.detail
        is SessionState.Preparing -> state.detail
        else -> null
    }

    private fun label(appId: String) =
        if (appId == AlpineShell.ID) getString(R.string.alpine_shell) else InstalledApps(applicationContext).name(appId) ?: appId

    private fun startInForeground(notification: Notification) {
        var type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        if (microphoneType && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (cameraType && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (screenType && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    /**
     * The session may use [sensor] from now on: the foreground service's type for it, which
     * Android grants only with its permission and while Flatdroid is in front (Allow from
     * [UsePermissionActivity], or from the notification while the viewer shows). Not taken
     * otherwise: the app then gets silence or black frames, logged.
     */
    private fun takeType(sensor: Sensor) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || job?.isActive != true || hasType(sensor) || !hasPermission(this, sensor)) return
        when (sensor) {
            Sensor.MICROPHONE -> microphoneType = true
            Sensor.CAMERA -> cameraType = true
            Sensor.SCREEN -> return
        }
        try {
            startInForeground(notification(SessionController.ui.value.state))
        } catch (e: Exception) {
            when (sensor) {
                Sensor.MICROPHONE -> microphoneType = false
                Sensor.CAMERA -> cameraType = false
                Sensor.SCREEN -> Unit
            }
            SessionController.log("[flatdroid] Android didn't let Flatdroid use the ${sensor.name.lowercase()} from the background: $e")
        }
    }

    private fun hasType(sensor: Sensor) = when (sensor) {
        Sensor.MICROPHONE -> microphoneType
        Sensor.CAMERA -> cameraType
        Sensor.SCREEN -> screenType
    }

    /**
     * Allow for the screen, with Android's consent in [intent] ([UsePermissionActivity]): the
     * media projection type (Android 14 and later want it after the consent and before the share),
     * then the share, handed to the session ([ScreenShare]). False, logged, when either fails.
     */
    private fun share(intent: Intent): Boolean {
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (job?.isActive != true || data == null) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            screenType = true
            try {
                startInForeground(notification(SessionController.ui.value.state))
            } catch (e: Exception) {
                screenType = false
                SessionController.log("[flatdroid] Android didn't let Flatdroid share the screen: $e")
                return false
            }
        }
        val started = try {
            getSystemService(MediaProjectionManager::class.java)?.getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), data)
        } catch (e: Exception) {
            SessionController.log("[flatdroid] Android didn't start the screen share: $e")
            null
        }
        if (started == null) {
            shareEnded(null)
            return false
        }
        projection = started
        // Before the share is used: Android 14 and later refuse a share without one.
        started.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = shareEnded(started)
        }, Handler(Looper.getMainLooper()))
        ScreenShare.offer(started)
        return true
    }

    /** The share [ended] (null: none started): the media projection type goes, unless a newer share runs. */
    private fun shareEnded(ended: MediaProjection?) {
        if (projection != ended) return
        projection = null
        if (!screenType) return
        screenType = false
        if (job?.isActive == true) runCatching { startInForeground(notification(SessionController.ui.value.state)) }
    }

    /**
     * Asks about [sensor], answered in the notification itself: Allow or Deny holds for the
     * session; swiping it away denies. Allow needs the device unlocked (Android 12+). Without
     * Android's permission, or the service's type for it, Allow opens [UsePermissionActivity],
     * which asks Android and takes the type with Flatdroid in front.
     */
    private fun askNotification(sensor: Sensor, appId: String): Notification {
        createChannel(ASK_CHANNEL_ID, R.string.session_ask_channel, NotificationManager.IMPORTANCE_HIGH)
        val code = ASK_REQUEST_CODE + 2 * sensor.ordinal
        val ready = hasPermission(this, sensor) && (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || hasType(sensor))
        val allow = if (ready) {
            PendingIntent.getService(this, code, answerIntent(this, sensor, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            PendingIntent.getActivity(this, code, UsePermissionActivity.intent(this, sensor), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val deny = PendingIntent.getService(this, code + 1, answerIntent(this, sensor, false), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val allowAction = NotificationCompat.Action.Builder(0, getString(R.string.ask_allow), allow)
            .setAuthenticationRequired(true)
            .build()
        val (icon, title) = when (sensor) {
            Sensor.MICROPHONE -> android.R.drawable.ic_btn_speak_now to R.string.mic_request_title
            Sensor.CAMERA -> android.R.drawable.ic_menu_camera to R.string.camera_request_title
            Sensor.SCREEN -> android.R.drawable.ic_menu_slideshow to R.string.screen_request_title
        }
        return NotificationCompat.Builder(this, ASK_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(getString(title, label(appId)))
            .setContentText(getString(if (sensor == Sensor.SCREEN) R.string.ask_screen_notification else R.string.ask_request_notification))
            .setDeleteIntent(deny)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Its own group, so Android doesn't bundle it with the session's and hide its buttons.
            .setGroup(askGroup(sensor))
            .addAction(0, getString(R.string.ask_deny), deny)
            .addAction(allowAction)
            .build()
    }

    /** Asks what to do with an app that was paused for having no window open, answered in the notification: Resume or Stop. */
    private fun noWindowNotification(appId: String, neverOpened: Boolean): Notification {
        // High importance: it pops up, as the microphone's question does, since the app is waiting on the answer.
        createChannel(NO_WINDOW_CHANNEL_ID, R.string.no_window_channel, NotificationManager.IMPORTANCE_HIGH)
        val open = PendingIntent.getActivity(
            this, 5, Intent(this, ViewerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE,
        )
        val resume = PendingIntent.getService(
            this, 6, Intent(this, SessionService::class.java).setAction(ACTION_RESUME_NO_WINDOW), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, SessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NO_WINDOW_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle(getString(if (neverOpened) R.string.no_window_never_title else R.string.no_window_title, label(appId)))
            .setContentText(getString(R.string.no_window_notification))
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            // Its own group, so Android doesn't bundle it with the session's and hide its buttons.
            .setGroup(NO_WINDOW_CHANNEL_ID)
            .addAction(0, getString(R.string.no_window_resume), resume)
            .addAction(0, getString(R.string.session_stop), stop)
            .build()
    }

    private fun notification(state: SessionState): Notification {
        createChannel(CHANNEL_ID, R.string.session_channel, NotificationManager.IMPORTANCE_LOW)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val appId = state.appIdOrNull
        val name = appId?.let(::label) ?: getString(R.string.app_name)
        val text = when (state) {
            is SessionState.Running ->
                if (state.socket != null) getString(R.string.session_notification_viewer)
                else getString(R.string.session_notification_running, SessionScript.VNC_PORT)
            is SessionState.Starting -> state.detail
            is SessionState.Preparing -> state.detail
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
        val running = state as? SessionState.Running
        val viewerIntent = running?.let {
            // An open viewer comes forward rather than a second one.
            if (it.socket != null) Intent(this, ViewerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            else it.password?.let { password -> ExternalViewer.intent(this, password, SetupController.settings(this).load()?.vncViewer, username = it.username) }
        }
        if (running != null && viewerIntent != null) {
            // Cancel, not update: an update keeps the old intent's flags (one from before an app update, say).
            val viewer = PendingIntent.getActivity(this, 1, viewerIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
            val label = if (running.socket != null) R.string.session_open_app else R.string.session_open_viewer
            builder.addAction(0, getString(label), viewer)
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
        /** The questions' channel (its ID is from when only the microphone asked). */
        private const val ASK_CHANNEL_ID = "permissions"
        private const val MIC_NOTIFICATION_ID = 11
        private const val CAMERA_NOTIFICATION_ID = 13
        private const val SCREEN_NOTIFICATION_ID = 14
        /** The questions' pending intents: 7 and 8 for the microphone, 9 and 10 for the camera, 11 and 12 for the screen. */
        private const val ASK_REQUEST_CODE = 7
        private const val NO_WINDOW_CHANNEL_ID = "no_window_alert"
        private const val NO_WINDOW_NOTIFICATION_ID = 12
        private const val EXTRA_APP_ID = "appId"
        private const val EXTRA_SENSOR = "sensor"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val ACTION_LAUNCH = "com.garathon.flatdroid.action.LAUNCH"
        private const val ACTION_PREPARE_ELECTRON = "com.garathon.flatdroid.action.PREPARE_ELECTRON"
        private const val ACTION_ADD_PACKAGES = "com.garathon.flatdroid.action.ADD_PACKAGES"
        private const val ACTION_ALPINE_SHELL = "com.garathon.flatdroid.action.ALPINE_SHELL"
        private const val EXTRA_PACKAGES = "packages"
        private const val EXTRA_SHELL_USER = "shellUser"
        private const val ACTION_STOP = "com.garathon.flatdroid.action.STOP"
        private const val ACTION_ALLOW = "com.garathon.flatdroid.action.ALLOW"
        private const val ACTION_DENY = "com.garathon.flatdroid.action.DENY"
        private const val ACTION_RESUME_NO_WINDOW = "com.garathon.flatdroid.action.RESUME_NO_WINDOW"

        /** [answers]: the user's answers about the microphone and camera for the session, when asked before the launch. */
        fun launch(context: Context, appId: String, answers: Map<Sensor, Boolean> = emptyMap()) = start(context, ACTION_LAUNCH, appId, answers)

        /** Alpine's shell in a terminal window (a developer option), after adding [packages] when the user agreed to. */
        fun alpineShell(context: Context, user: AlpineShellUser?, packages: PackageGroup? = null) =
            start(context, if (packages != null) ACTION_ADD_PACKAGES else ACTION_ALPINE_SHELL, AlpineShell.ID, emptyMap()) {
                packages?.let { putExtra(EXTRA_PACKAGES, it.name) }
                user?.let { putExtra(EXTRA_SHELL_USER, it.key) }
            }

        /** After the user agreed to download Electron for this device: prepare it, then launch. */
        fun prepareElectronAndLaunch(context: Context, appId: String, answers: Map<Sensor, Boolean> = emptyMap()) =
            start(context, ACTION_PREPARE_ELECTRON, appId, answers)

        /** After the user agreed to add [group] to Alpine: add it, then launch. */
        fun addPackagesAndLaunch(context: Context, appId: String, group: PackageGroup, answers: Map<Sensor, Boolean> = emptyMap()) =
            start(context, ACTION_ADD_PACKAGES, appId, answers) { putExtra(EXTRA_PACKAGES, group.name) }

        /** Whether the microphone and camera questions can be posted: notifications, and their channel, aren't blocked. */
        fun canAskInNotification(context: Context): Boolean {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return false
            val channel = manager.getNotificationChannelCompat(ASK_CHANNEL_ID) ?: return true
            return channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
        }

        /** Android's permission for [sensor]; never for the screen, which Android asks about at each share. */
        fun hasPermission(context: Context, sensor: Sensor): Boolean = when (sensor) {
            Sensor.MICROPHONE -> MicStreamer.hasPermission(context)
            Sensor.CAMERA -> AndroidCameras.hasPermission(context)
            Sensor.SCREEN -> false
        }

        /** Android's permission [sensor] needs; null for the screen, which takes Android's consent instead ([share]). */
        fun permission(sensor: Sensor): String? = when (sensor) {
            Sensor.MICROPHONE -> Manifest.permission.RECORD_AUDIO
            Sensor.CAMERA -> Manifest.permission.CAMERA
            Sensor.SCREEN -> null
        }

        /** Allow for the screen, with Android's consent ([resultCode] and [data] from its dialog). */
        fun share(context: Context, resultCode: Int, data: Intent) {
            context.startService(answerIntent(context, Sensor.SCREEN, true).putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_RESULT_DATA, data))
        }

        /** The user's answer about [sensor] for the running session; Allow also takes the service's type for it. */
        fun answer(context: Context, sensor: Sensor, allow: Boolean) {
            context.startService(answerIntent(context, sensor, allow))
        }

        private fun answerIntent(context: Context, sensor: Sensor, allow: Boolean) =
            Intent(context, SessionService::class.java).setAction(if (allow) ACTION_ALLOW else ACTION_DENY).putExtra(EXTRA_SENSOR, sensor.name)

        private fun answerExtra(sensor: Sensor) = "answer.${sensor.name}"

        private fun askId(sensor: Sensor) = when (sensor) {
            Sensor.MICROPHONE -> MIC_NOTIFICATION_ID
            Sensor.CAMERA -> CAMERA_NOTIFICATION_ID
            Sensor.SCREEN -> SCREEN_NOTIFICATION_ID
        }

        private fun askGroup(sensor: Sensor) = "$ASK_CHANNEL_ID.${sensor.name.lowercase()}"

        fun stop(context: Context) {
            val state = SessionController.ui.value.state
            if (!SessionController.isBusy) return
            state.appIdOrNull?.let { SessionController.stopping(it) }
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_STOP))
        }

        /** Not while a Flatpak task runs ([AlpineCommands.sessionMayStart]). */
        private fun start(context: Context, action: String, appId: String, answers: Map<Sensor, Boolean>, extras: Intent.() -> Unit = {}) {
            if (SessionController.isBusy || !AlpineCommands.sessionMayStart()) return
            SessionController.starting(appId, context.getString(R.string.session_starting), clearLog = true)
            ContextCompat.startForegroundService(
                context,
                Intent(context, SessionService::class.java).setAction(action).putExtra(EXTRA_APP_ID, appId)
                    .apply { for ((sensor, allow) in answers) putExtra(answerExtra(sensor), allow) }.apply(extras),
            )
        }
    }
}
