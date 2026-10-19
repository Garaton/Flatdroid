package com.garathon.flatdroid

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.alpine.GuestFiles
import com.garathon.flatdroid.audio.MicStreamer
import com.garathon.flatdroid.flatpak.AppData
import com.garathon.flatdroid.flatpak.FlatpakTask
import com.garathon.flatdroid.flatpak.FlatpakTaskService
import com.garathon.flatdroid.flatpak.FlatpakTasks
import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.session.SessionController
import com.garathon.flatdroid.session.SessionService
import com.garathon.flatdroid.session.SessionState
import com.garathon.flatdroid.session.Viewer
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.setup.ResetMode
import com.garathon.flatdroid.setup.SetupController
import com.garathon.flatdroid.setup.SetupService
import com.garathon.flatdroid.setup.SetupStep
import com.garathon.flatdroid.ui.AppSettingsScreen
import com.garathon.flatdroid.ui.CommandState
import com.garathon.flatdroid.ui.ElectronDialog
import com.garathon.flatdroid.ui.HomeScreen
import com.garathon.flatdroid.ui.InstallDialog
import com.garathon.flatdroid.ui.LogDialog
import com.garathon.flatdroid.ui.MicrophoneLaunchDialog
import com.garathon.flatdroid.ui.SessionPanel
import com.garathon.flatdroid.ui.TaskPanel
import com.garathon.flatdroid.ui.SettingsScreen
import com.garathon.flatdroid.ui.SetupSettingsScreen
import com.garathon.flatdroid.ui.TerminalScreen
import com.garathon.flatdroid.ui.SetupScreen
import com.garathon.flatdroid.ui.theme.FlatdroidTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val home: HomeViewModel by viewModels()

    /** An app to launch once the notification permission has been answered. */
    private var pendingLaunch: String? = null

    /** A launch waiting for the microphone question ([needsMicrophoneQuestion]). */
    private var microphoneQuestion by mutableStateOf<PendingLaunch?>(null)

    /** Called with Android's answer to the microphone permission request in flight. */
    private var onMicrophoneResult: ((Boolean) -> Unit)? = null

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onMicrophoneResult?.invoke(granted)
            onMicrophoneResult = null
        }

    /**
     * Setup and launches go ahead whatever the answer; without the permission their notification
     * is just hidden.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            val appId = pendingLaunch
            pendingLaunch = null
            if (appId != null) continueLaunch(PendingLaunch(appId, prepareElectron = false)) else SetupService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val languages = GuestFiles.flatpakLanguages(deviceLanguageTags())
        setContent {
            FlatdroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val setup by SetupController.state.collectAsState()
                    val modifier = Modifier.padding(innerPadding)
                    val output by viewModel.output.collectAsState()
                    val running by viewModel.running.collectAsState()
                    var screen by rememberSaveable(stateSaver = Screen.Saver) { mutableStateOf<Screen>(Screen.Home) }
                    when {
                        // Screen 1: the user's settings, asked before setup (again after a reset,
                        // pre-filled); setup's last step applies them.
                        !setup.settingsChosen && !setup.isComplete && !setup.running -> SetupSettingsScreen(
                            settings = remember { savedSettings(languages) },
                            deviceLanguages = languages,
                            onContinue = { SetupController.settings(this).save(it); startSetup() },
                            modifier = modifier,
                        )
                        // Screen 2: setup, the same for everyone.
                        !setup.isComplete || setup.running -> SetupScreen(
                            ui = setup,
                            onStart = ::startSetup,
                            onReset = ::resetAlpine,
                            modifier = modifier,
                            // Once Alpine is extracted, commands can look into a failed setup.
                            command = if (SetupStep.ROOTFS in setup.completed) CommandState(output, running, viewModel::run, viewModel::cancel) else null,
                        )
                        else -> Main(screen, { screen = it }, languages, modifier)
                    }
                }
            }
        }
    }

    /** Everything after setup: home, Settings (with the terminal) and each app's settings. */
    @Composable
    private fun Main(screen: Screen, navigate: (Screen) -> Unit, languages: String, modifier: Modifier) {
        val output by viewModel.output.collectAsState()
        val running by viewModel.running.collectAsState()
        val applying by viewModel.applying.collectAsState()
        val settingsError by viewModel.settingsError.collectAsState()
        val apps by home.apps.collectAsState()
        val session by SessionController.ui.collectAsState()
        val task by FlatpakTasks.ui.collectAsState()
        val sessionBusy = SessionController.isBusy || session.state is SessionState.ElectronOffer
        val taskRunning = task.running != null
        // Installing adds new files only, so it can run alongside an app; update and uninstall
        // change or remove files a running app may use.
        val installRunning = task.running is FlatpakTask.Install
        val nameOf = { id: String -> apps?.firstOrNull { it.id == id }?.name ?: id }
        BackHandler(enabled = screen != Screen.Home) {
            navigate(screen.parent ?: Screen.Home)
        }

        when (screen) {
            Screen.Settings -> SettingsScreen(
                settings = remember { savedSettings(languages) },
                deviceLanguages = languages,
                viewers = remember { Viewer.installed(this) },
                applying = applying,
                error = settingsError,
                onSave = { viewModel.applySettings(it) { navigate(Screen.Home) } },
                onBack = { navigate(Screen.Home); viewModel.clearSettingsError() },
                onReset = { mode -> navigate(Screen.Home); resetAlpine(mode) },
                onTerminal = { navigate(Screen.Terminal) },
                canReset = !sessionBusy && !taskRunning,
                modifier = modifier,
            )
            Screen.Terminal -> TerminalScreen(output, running, viewModel::run, viewModel::cancel, onBack = { navigate(Screen.Settings) }, modifier = modifier)
            is Screen.AppSettings -> {
                val list = apps ?: return
                val app = list.firstOrNull { it.id == screen.appId }
                if (app == null || session.state.appIdOrNull == app.id) {
                    // Uninstalled meanwhile, or running (the home screen doesn't open a running app's settings).
                    LaunchedEffect(Unit) { navigate(Screen.Home) }
                    return
                }
                val store = remember(app.id) { AppData.settings(this, app.id) }
                val userSettings = remember { savedSettings(languages) }
                val developerOptions = userSettings.developerOptions
                val cacheBytes by home.cacheBytes.collectAsState()
                LaunchedEffect(app.id) { home.measureCache(app.id) }
                AppSettingsScreen(
                    app = app,
                    cacheBytes = cacheBytes[app.id],
                    settings = remember(app.id) { store.load() },
                    canClearCache = session.state.appIdOrNull != app.id,
                    canUninstall = !sessionBusy && !taskRunning,
                    developerOptions = developerOptions,
                    onRequestMicrophone = ::requestMicrophone,
                    onSave = { new ->
                        // Keep what the launcher manages: last opened and the Electron flags.
                        store.update { old ->
                            new.copy(
                                lastOpened = old.lastOpened,
                                newerElectronFlags = old.newerElectronFlags,
                                wholeDev = if (developerOptions) new.wholeDev else old.wholeDev,
                            )
                        }
                        navigate(Screen.Home)
                    },
                    onBack = { navigate(Screen.Home) },
                    onClearCache = { home.clearCache(app.id) },
                    onUninstall = { deleteData ->
                        FlatpakTaskService.start(this, FlatpakTask.Uninstall(app.id, deleteData))
                        navigate(Screen.Home)
                    },
                    modifier = modifier,
                )
            }
            Screen.Home -> {
                var installDialog by rememberSaveable { mutableStateOf(false) }
                var log by rememberSaveable { mutableStateOf<LogKind?>(null) }
                if (installDialog) {
                    InstallDialog(
                        onInstall = { FlatpakTaskService.start(this, FlatpakTask.Install(it)); installDialog = false },
                        onDismiss = { installDialog = false },
                    )
                }
                (session.state as? SessionState.ElectronOffer)?.let { offer ->
                    ElectronDialog(
                        offer, nameOf(offer.appId), remember { AlpineEnvironment.get(this).arch },
                        onAccept = { SessionController.ended(null); continueLaunch(PendingLaunch(offer.appId, prepareElectron = true)) },
                        onDismiss = { SessionController.ended(null) },
                    )
                }
                when (log) {
                    LogKind.SESSION -> {
                        val lines by SessionController.log.collectAsState()
                        LogDialog(getString(R.string.session_log_title), lines) { log = null }
                    }
                    LogKind.TASK -> {
                        val lines by FlatpakTasks.log.collectAsState()
                        LogDialog(getString(R.string.task_log_title), lines) { log = null }
                    }
                    null -> Unit
                }
                microphoneQuestion?.let { pending ->
                    MicrophoneLaunchDialog(
                        nameOf(pending.appId),
                        onAnswer = { allow -> microphoneQuestion = null; start(pending, allow) },
                        onDismiss = { microphoneQuestion = null },
                    )
                }
                HomeScreen(
                    apps = apps,
                    runningAppId = session.state.appIdOrNull,
                    canLaunch = !sessionBusy && (!taskRunning || installRunning),
                    canInstall = !taskRunning,
                    canUpdate = !sessionBusy && !taskRunning,
                    onLaunch = { launch(it.id) },
                    onAppSettings = { navigate(Screen.AppSettings(it.id)) },
                    onInstall = { installDialog = true },
                    onUpdateAll = { FlatpakTaskService.start(this, FlatpakTask.UpdateAll) },
                    onSettings = { navigate(Screen.Settings) },
                    modifier = modifier,
                ) {
                    TaskPanel(
                        task, describe = { FlatpakTaskService.describe(this, it) },
                        onCancel = { FlatpakTaskService.cancel(this) },
                        onShowLog = { log = LogKind.TASK },
                        onDismiss = FlatpakTasks::dismiss,
                    )
                    SessionPanel(
                        session, nameOf,
                        onOpenViewer = ::openViewer,
                        onStop = { SessionService.stop(this) },
                        onShowLog = { log = LogKind.SESSION },
                        onDismiss = SessionController::dismissMessage,
                    )
                }
            }
        }
    }

    /** Launches [appId]; the notification permission is asked first, as for setup. */
    private fun launch(appId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingLaunch = appId
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            continueLaunch(PendingLaunch(appId, prepareElectron = false))
        }
    }

    /** Asks the microphone question first when needed, then starts the session. */
    private fun continueLaunch(pending: PendingLaunch) {
        if (needsMicrophoneQuestion(pending.appId)) microphoneQuestion = pending else start(pending, micAnswer = null)
    }

    private fun start(pending: PendingLaunch, micAnswer: Boolean?) {
        if (pending.prepareElectron) SessionService.prepareElectronAndLaunch(this, pending.appId, micAnswer)
        else SessionService.launch(this, pending.appId, micAnswer)
    }

    /**
     * The app's microphone is on but will ask, and Flatdroid can't post the question when the app
     * records (notifications are off): it is asked before the launch instead, for the session.
     */
    private fun needsMicrophoneQuestion(appId: String): Boolean {
        val settings = AppData.settings(this, appId).load()
        val developerOptions = SetupController.settings(this).load()?.developerOptions == true
        return settings.microphone && !(settings.alwaysAllowMicrophone && developerOptions) &&
            MicStreamer.hasPermission(this) && !SessionService.canAskInNotification(this) &&
            settings.audioAllowed(InstalledApps(this).sockets(appId))
    }

    /** Asks Android for the microphone permission, then calls [onResult] with the answer. */
    private fun requestMicrophone(onResult: (Boolean) -> Unit) {
        if (MicStreamer.hasPermission(this)) return onResult(true)
        onMicrophoneResult = onResult
        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun openViewer(password: String) {
        try {
            val intent = Viewer.intent(this, password, SetupController.settings(this).load()?.vncViewer)
                ?: throw ActivityNotFoundException()
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.session_no_viewer, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        SetupController.refresh(this)
        home.reload()
        repairAfterKilledTask()
    }

    /**
     * An update or uninstall that never ended (Flatdroid was killed in the middle) can leave the
     * repository half-pruned; its marker is still there, so clean up now, before anything else
     * uses the repository. Not while setup or a reset is unfinished, or anything else runs.
     */
    private fun repairAfterKilledTask() {
        val setup = SetupController.state.value
        if (!AppData.taskMarker(this).exists() || !setup.isComplete || setup.running) return
        if (FlatpakTasks.ui.value.running != null || SessionController.isBusy) return
        FlatpakTaskService.start(this, FlatpakTask.Repair)
    }

    private fun startSetup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            SetupService.start(this)
        }
    }

    private fun savedSettings(deviceLanguages: String) =
        SetupController.settings(this).load() ?: UserSettings.defaults(deviceLanguages)

    private fun resetAlpine(mode: ResetMode) = SetupService.reset(this, mode)

    private fun deviceLanguageTags(): List<String> {
        val locales = LocaleList.getDefault()
        return (0 until locales.size()).map { locales[it].toLanguageTag() }
    }
}

/** Where the user is once setup is done. Saved across recreation as its [key]. */
private sealed interface Screen {
    val key: String

    data object Home : Screen {
        override val key = "home"
    }

    data object Settings : Screen {
        override val key = "settings"
    }

    data object Terminal : Screen {
        override val key = "terminal"
    }

    data class AppSettings(val appId: String) : Screen {
        override val key get() = "$APP_PREFIX$appId"
    }

    /** Where Back goes; null on the home screen. */
    val parent: Screen?
        get() = when (this) {
            Home -> null
            Terminal -> Settings
            Settings, is AppSettings -> Home
        }

    companion object {
        private const val APP_PREFIX = "app:"

        val Saver = Saver<Screen, String>(
            save = { it.key },
            restore = { key ->
                when {
                    key.startsWith(APP_PREFIX) -> AppSettings(key.removePrefix(APP_PREFIX))
                    else -> listOf(Home, Settings, Terminal).firstOrNull { it.key == key } ?: Home
                }
            },
        )
    }
}

/** A launch on hold while the user answers a question. */
private data class PendingLaunch(val appId: String, val prepareElectron: Boolean)

/** Which output the log dialog shows. */
private enum class LogKind { SESSION, TASK }
