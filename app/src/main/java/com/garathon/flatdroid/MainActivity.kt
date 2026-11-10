package com.garathon.flatdroid

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.alpine.GuestFiles
import com.garathon.flatdroid.audio.MicStreamer
import com.garathon.flatdroid.camera.AndroidCameras
import com.garathon.flatdroid.flatpak.AppData
import com.garathon.flatdroid.flatpak.AppDetails
import com.garathon.flatdroid.flatpak.FlatpakInstallation
import com.garathon.flatdroid.flatpak.FlatpakTask
import com.garathon.flatdroid.flatpak.FlatpakTaskService
import com.garathon.flatdroid.flatpak.FlatpakTasks
import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.session.SessionController
import com.garathon.flatdroid.viewer.ViewerActivity
import com.garathon.flatdroid.session.SessionService
import com.garathon.flatdroid.session.SessionState
import com.garathon.flatdroid.session.Viewer
import com.garathon.flatdroid.settings.AppTime
import com.garathon.flatdroid.settings.MobileData
import com.garathon.flatdroid.settings.mobileDataPolicy
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.storage.ImportTarget
import com.garathon.flatdroid.storage.SavedSizes
import com.garathon.flatdroid.storage.StorageTasks
import com.garathon.flatdroid.setup.ResetMode
import com.garathon.flatdroid.setup.SetupController
import com.garathon.flatdroid.setup.SetupService
import com.garathon.flatdroid.setup.SetupStep
import com.garathon.flatdroid.ui.setup.CommandState
import com.garathon.flatdroid.ui.ElectronDialog
import com.garathon.flatdroid.ui.FilesChangedDialog
import com.garathon.flatdroid.ui.home.HomeScreen
import com.garathon.flatdroid.ui.home.InstallLinkDialog
import com.garathon.flatdroid.ui.home.StatusBar
import com.garathon.flatdroid.ui.browse.BrowseAppScreen
import com.garathon.flatdroid.ui.browse.BrowseScreen
import com.garathon.flatdroid.ui.browse.InstallState
import com.garathon.flatdroid.ui.browse.rememberBrowseListState
import com.garathon.flatdroid.flatpak.catalog.Catalog
import com.garathon.flatdroid.flatpak.catalog.CatalogImages
import com.garathon.flatdroid.flatpak.catalog.CatalogState
import com.garathon.flatdroid.ui.home.forgetIcons
import com.garathon.flatdroid.util.DoneNotice
import com.garathon.flatdroid.ui.home.rememberHomeListState
import com.garathon.flatdroid.ui.InstallDialog
import com.garathon.flatdroid.ui.LogDialog
import com.garathon.flatdroid.ui.MicrophoneLaunchDialog
import com.garathon.flatdroid.ui.MobileDataDialog
import com.garathon.flatdroid.ui.setup.ResetScreen
import com.garathon.flatdroid.ui.setup.SetupSettingsScreen
import com.garathon.flatdroid.ui.TerminalScreen
import com.garathon.flatdroid.ui.setup.SetupScreen
import com.garathon.flatdroid.ui.followHideScreen
import com.garathon.flatdroid.ui.settings.AppPage
import com.garathon.flatdroid.ui.settings.AppSettingsEnv
import com.garathon.flatdroid.ui.settings.AppSettingsScreen
import com.garathon.flatdroid.ui.settings.GlobalPage
import com.garathon.flatdroid.ui.settings.GlobalSettingsEnv
import com.garathon.flatdroid.ui.settings.GlobalSettingsScreen
import com.garathon.flatdroid.ui.settings.GlobalStorageActions
import com.garathon.flatdroid.ui.settings.SettingsDraftModel
import com.garathon.flatdroid.ui.theme.FlatdroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val home: HomeViewModel by viewModels()
    private val drafts: SettingsDraftModel by viewModels()

    /** An app to launch once the notification permission has been answered. */
    private var pendingLaunch: String? = null

    /** A launch waiting for the microphone question ([needsMicrophoneQuestion]). */
    private var microphoneQuestion by mutableStateOf<PendingLaunch?>(null)

    /** An install or update waiting for the answer to the mobile data question. */
    private var meteredQuestion by mutableStateOf<FlatpakTask?>(null)

    /** Called with Android's answer to the microphone or camera permission request in flight. */
    private var onPermissionResult: ((Boolean) -> Unit)? = null

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onPermissionResult?.invoke(granted)
            onPermissionResult = null
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

    private val storage = StorageFlows(this, { home }, { viewModel })

    /**
     * An app a Flathub link named ([LinkActivity]): its confirmation opens in the window. Not when
     * the window is reopened from Recents, which hands back the intent that first started it.
     */
    private fun handleLink(intent: Intent?) {
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val id = intent.getStringExtra(EXTRA_INSTALL)?.takeIf(InstalledApps::isValidId) ?: return
        intent.removeExtra(EXTRA_INSTALL)
        home.requestInstall(id)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Flatdroid's icon, on a task that is open already but was started some other way (from a
        // notification, say): Android adds a second window on top. Close it, so the task shows what
        // was open (the viewer of a running app) as the icon would otherwise.
        if (savedInstanceState == null && !isTaskRoot && isLauncherIntent(intent) && !intent.hasExtra(EXTRA_INSTALL)) {
            finish()
            return
        }
        // Only on the first creation: a recreation (rotation) still holds the link in the intent.
        if (savedInstanceState == null) handleLink(intent)
        followHideScreen()
        storage.restore()
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
                    // Screen 1: the user's settings, asked before setup (again after a reset, pre-filled);
                    // setup's last step applies them. Screen 2: setup, the same for everyone. Then the app.
                    val stage = when {
                        setup.resetting -> AppStage.RESETTING
                        !setup.settingsChosen && !setup.isComplete && !setup.running -> AppStage.CHOICES
                        !setup.isComplete || setup.running -> AppStage.SETUP
                        else -> AppStage.MAIN
                    }
                    AnimatedContent(
                        targetState = stage,
                        transitionSpec = {
                            val shift = { width: Int -> width / 12 }
                            // Forward goes in from the right, back (a reset) from the left.
                            val forward = targetState.ordinal > initialState.ordinal
                            if (targetState == AppStage.RESETTING || initialState == AppStage.RESETTING) {
                                return@AnimatedContent fadeIn(tween(300, delayMillis = 100)) togetherWith fadeOut(tween(150))
                            }
                            (fadeIn(tween(300, delayMillis = 100)) + slideInHorizontally(tween(350)) { if (forward) shift(it) else -shift(it) }) togetherWith
                                (fadeOut(tween(150)) + slideOutHorizontally(tween(350)) { if (forward) -shift(it) else shift(it) })
                        },
                        label = "setup stage",
                    ) { shown ->
                        when (shown) {
                            AppStage.CHOICES -> SetupSettingsScreen(
                                settings = remember { savedSettings(languages) },
                                deviceLanguages = languages,
                                error = setup.error,
                                onContinue = { SetupController.settings(this@MainActivity).save(it); startSetup() },
                                modifier = modifier,
                            )
                            AppStage.SETUP -> SetupScreen(
                                ui = setup,
                                onStart = ::startSetup,
                                onReset = ::resetAlpine,
                                modifier = modifier,
                                // Once Alpine is extracted, commands can look into a failed setup.
                                command = if (SetupStep.ROOTFS in setup.completed) CommandState(output, running, viewModel::run, viewModel::cancel) else null,
                            )
                            AppStage.MAIN -> Main(screen, { screen = it }, languages, modifier)
                            AppStage.RESETTING -> ResetScreen(setup.detail, modifier)
                        }
                    }
                }
            }
        }
    }

    /** Where the first run is: the user's choices, the installation, then the app itself; and the reset between. */
    private enum class AppStage { CHOICES, SETUP, MAIN, RESETTING }

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
        // The session's state is always read here (not in a short-circuited `||`): this body must
        // recompose whenever it changes, since the screens below are drawn in an AnimatedContent
        // whose lambda keeps what is computed here until this body runs again. Reading it only
        // while the session was idle left the buttons disabled after a session ended.
        val sessionState = session.state
        val sessionActive = sessionState !is SessionState.Idle && sessionState !is SessionState.Question
        val sessionBusy = sessionState !is SessionState.Idle
        // The home list's position and search, kept here so they survive a visit to Settings.
        val homeListState = rememberHomeListState()
        val browseListState = rememberBrowseListState()
        // Developer options show the logs of what runs; read again whenever the screen changes (Settings can change them).
        // Flatdroid's saved settings, read once per level (on the way back from Settings, which may have
        // changed them), not on every page of a settings screen.
        val saved = remember(screen.level) { savedSettings(languages) }
        val developerOptions = saved.developerOptions
        val showTimeUsed = saved.appTime == AppTime.TIME_USED
        // The app is up: Flatdroid's window opens it, once, when the user started it from here and is still here.
        val upSession = sessionState as? SessionState.Running
        LaunchedEffect(upSession?.password) {
            if (upSession != null && home.autoOpen) {
                home.autoOpen = false
                openViewer(upSession)
            }
        }
        val taskRunning = task.running != null
        // No task while an app runs, and no app while a task or (unless allowed) a terminal command
        // runs: a running app could take over Alpine processes started after it ([AlpineCommands]).
        val terminalWithApps = saved.terminalWithApps
        val terminalRunning = running
        val nameOf = { id: String -> apps?.firstOrNull { it.id == id }?.name ?: id }
        // Settings open with fresh drafts. They are not dropped when the screen is left: it is still
        // drawn while it slides out, and needs its draft until then.
        val go = { target: Screen ->
            if (screen == Screen.Home && target != Screen.Home) {
                drafts.clear()
                when (target) {
                    is Screen.Settings -> drafts.openGlobal(savedSettings(languages))
                    is Screen.AppSettings -> drafts.openApp(target.appId, AppData.settings(this, target.appId).load())
                    else -> Unit
                }
            }
            navigate(target)
        }
        BackHandler(enabled = screen != Screen.Home) {
            go(screen.parent ?: Screen.Home)
        }

        storage.Dialogs(nameOf)
        // A Flathub link (or appstream:// one) that named an app: confirm, then the normal install (queued if something runs).
        val installLink by home.installLink.collectAsState()
        installLink?.let { id ->
            InstallLinkDialog(
                id, remember { AlpineEnvironment.get(this@MainActivity).arch },
                // Unknown until the list has loaded: no Install before then.
                installed = apps?.any { it.id == id },
                busy = sessionBusy || taskRunning,
                mobileDataBlocked = { mobileDataPolicy() == MobileData.NEVER },
                runtimeInstalled = { ref ->
                    // Flathub names its own architecture (the app is the same on ours): look for this phone's.
                    val env = AlpineEnvironment.get(this@MainActivity)
                    FlatpakInstallation(env.flatpakDir, env.arch).runtime(ref.copy(arch = env.arch)) != null
                },
                onInstall = { home.clearInstallLink(); startDownload(FlatpakTask.Install(listOf(id))) },
                onDismiss = home::clearInstallLink,
            )
        }
        // Flathub's catalog (the browser's): app names for the waiting installs, IDs for the typed ones.
        val catalog by Catalog.state.collectAsState()
        val catalogIndex = (catalog as? CatalogState.Ready)?.index
        // Over Home and the browser: typed app IDs, the mobile data question, the logs of the bottom bar.
        var installDialog by rememberSaveable { mutableStateOf(false) }
        var log by rememberSaveable { mutableStateOf<LogKind?>(null) }
        if (installDialog) {
            InstallDialog(
                onInstall = {
                    startDownload(FlatpakTask.Install(it))
                    installDialog = false
                },
                onDismiss = { installDialog = false },
                inCatalog = catalogIndex?.let { index -> { id: String -> id in index } },
                installed = apps?.mapTo(HashSet()) { it.id },
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
        meteredQuestion?.let { task ->
            MobileDataDialog(
                onDownload = { meteredQuestion = null; startTask(task) },
                onDismiss = { meteredQuestion = null },
            )
        }
        val installedIds = remember(apps) { apps?.map { it.id }?.toSet() }
        val installingIds = (task.running as? FlatpakTask.Install)?.appIds.orEmpty()
        val bottomBar = @Composable {
            StatusBar(
                task, session, { FlatpakTaskService.describe(this@MainActivity, it) }, developerOptions,
                onCancelTask = { FlatpakTaskService.cancel(this@MainActivity) }, onDismissTask = FlatpakTasks::dismiss, onTaskLog = { log = LogKind.TASK },
                onDismissSession = SessionController::dismissMessage, onSessionLog = { log = LogKind.SESSION }, onClearQueue = FlatpakTasks::clearQueue,
                onUnqueue = FlatpakTasks::unqueue,
                queuedName = { id -> apps?.firstOrNull { it.id == id }?.name ?: catalogIndex?.app(id)?.name ?: id },
            )
        }
        // The browser was left: its pictures leave memory (those on disk stay for this run).
        val inBrowser = screen is Screen.Browse || screen is Screen.BrowseApp
        LaunchedEffect(inBrowser) {
            if (!inBrowser) {
                withContext(Dispatchers.IO) { CatalogImages.release() }
                forgetIcons(Catalog.iconsDir(this@MainActivity))
            }
        }
        val storageTasks by StorageTasks.ui.collectAsState()
        // A session that ends while Flatdroid is open may leave a temporary home to ask about.
        // Also the waiting tasks, when a question after the session (answered here) held them back.
        LaunchedEffect(sessionBusy) {
            if (!sessionBusy) {
                storage.checkTempHomes()
                FlatpakTaskService.startNext(this@MainActivity)
            }
        }

        // Measured again after storage tasks, which change it.
        val savedSizes by SavedSizes.updates.collectAsState()
        // Read again after storage tasks and once sizes measured in the background are saved.
        if (screen is Screen.Settings) LaunchedEffect(storageTasks.generation, savedSizes) { home.measureFlatdroid() }
        // Between levels (home, settings, terminal) the screen slides sideways; pages of one
        // level change inside their own screen.
        AnimatedContent(
            targetState = screen,
            contentKey = { it.level },
            transitionSpec = {
                val forward = targetState.level > initialState.level
                // The settings screen is on the home screen's side: an app's from the left, the rest from the right.
                val from = if (forward) targetState.side else -initialState.side
                (fadeIn(tween(250)) + slideInHorizontally(tween(300)) { from * it / 6 }) togetherWith
                    (fadeOut(tween(200)) + slideOutHorizontally(tween(300)) { -from * it / 6 })
            },
            label = "screen",
        ) { target ->
        when (target) {
            is Screen.Settings -> {
                val draft = drafts.global
                if (draft == null) {
                    LaunchedEffect(Unit) { drafts.openGlobal(savedSettings(languages)) }
                    return@AnimatedContent
                }
                // The shared home is saved when it is changed, so keep the saved one.
                fun withSavedHome(new: UserSettings) = new.copy(sharedHome = SetupController.settings(this@MainActivity).load()?.sharedHome)
                GlobalSettingsScreen(
                    page = target.page,
                    onPage = { go(Screen.Settings(it)) },
                    draft = draft,
                    env = GlobalSettingsEnv(
                        deviceLanguages = languages,
                        viewers = remember { Viewer.installed(this@MainActivity) },
                        applying = applying,
                        error = settingsError,
                        sharedHome = storage.sharedHomeDisplay,
                        allFilesAccess = storage.broadAccess.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.R },
                        storage = home.flatdroidStorage.collectAsState().value,
                        storageBusy = storageTasks.running != null || storageTasks.queued.isNotEmpty(),
                        canReset = !sessionBusy && !taskRunning,
                        canOpenTerminal = !sessionActive || terminalWithApps,
                        actions = GlobalStorageActions(
                            onChangeSharedHome = storage::changeSharedHome,
                            onAllFilesAccess = storage::openAllFilesAccess,
                            onClearAllCaches = home::clearAllCaches,
                            onClearUnusedAppData = home::clearUnusedAppData,
                            onClearUnusedHomes = home::clearUnusedHomes,
                            onExportAllHomes = storage::exportAllHomes,
                            onImportAllHomes = storage::importAllHomes,
                            onExportAllAppFiles = storage::exportAllAppFiles,
                            onImportAllAppFiles = storage::importAllAppFiles,
                            onExportSharedDotfiles = storage::exportSharedDotfiles,
                            onImportSharedDotfiles = storage::importSharedDotfiles,
                            onResetSharedDotfiles = home::resetSharedDotfiles,
                            onExportSettings = storage::exportFlatdroidSettings,
                            // Back to the home screen once applied, as Save does: this@MainActivity screen shows the old values.
                            onImportSettings = { storage.importFlatdroidSettings { go(Screen.Home) } },
                            onAddFiles = { storage.importFiles(ImportTarget.Shared) },
                            onAddZip = storage::importZipIntoSharedHome,
                            onResetAllHomes = home::resetAllHomes,
                            onResetAllAppData = home::resetAllAppData,
                            onAppStorage = { id, action -> storage.storageAction(id, action) },
                            onOpenHome = storage::openHomeInFiles,
                            onUninstall = { id, deleteData, keepRuntimes ->
                                if (FlatpakTaskService.run(this@MainActivity, FlatpakTask.Uninstall(id, deleteData, keepRuntimes))) home.uninstallStarted(id)
                            },
                            onRemoveRuntime = { id ->
                                FlatpakTaskService.run(this@MainActivity, FlatpakTask.RemoveRuntime(id))
                            },
                        ),
                        onReset = { mode -> go(Screen.Home); resetAlpine(mode) },
                        onResetSettings = { viewModel.applySettings(UserSettings.defaults(languages)) { go(Screen.Home) } },
                        onTerminal = { go(Screen.Terminal) },
                        apps = apps.orEmpty(),
                        taskText = task.running?.let { task.phase ?: FlatpakTaskService.describe(this@MainActivity, it) },
                        hasCamera = remember { AndroidCameras.present(this@MainActivity) },
                        // Asks the camera service, so off the main thread; none until it answers.
                        cameraResolutions = produceState(emptyMap<Int, List<AndroidCameras.Resolution>>()) {
                            value = withContext(Dispatchers.IO) { AndroidCameras.resolutions(this@MainActivity) }
                        }.value,
                    ),
                    onSave = { new -> viewModel.applySettings(withSavedHome(new)) { go(Screen.Home) } },
                    // Settings are persisted before onApplied runs, so the reinstall's baseline sees the check on.
                    onSaveAndReinstallAll = { new ->
                        viewModel.applySettings(withSavedHome(new)) { go(Screen.Home); FlatpakTaskService.start(this@MainActivity, FlatpakTask.ReinstallAll) }
                    },
                    onSaveAndBaselineAll = { new ->
                        viewModel.applySettings(withSavedHome(new)) {
                            if (FlatpakTaskService.start(this@MainActivity, FlatpakTask.BaselineAll)) go(Screen.Home)
                            else Toast.makeText(this@MainActivity, R.string.task_busy, Toast.LENGTH_LONG).show()
                        }
                    },
                    onDiscard = { go(Screen.Home); viewModel.clearSettingsError() },
                    modifier = modifier,
                )
            }
            Screen.Terminal -> TerminalScreen(output, running, viewModel::run, viewModel::cancel, onBack = { go(Screen.Settings(GlobalPage.DEVELOPER)) }, modifier = modifier)
            is Screen.AppSettings -> {
                val list = apps ?: return@AnimatedContent
                val app = list.firstOrNull { it.id == target.appId }
                if (app == null || session.state.appIdOrNull == app.id) {
                    // Uninstalled meanwhile, or running (the home screen doesn't open a running app's settings).
                    LaunchedEffect(Unit) { go(Screen.Home) }
                    return@AnimatedContent
                }
                val store = remember(app.id) { AppData.settings(this@MainActivity, app.id) }
                val draft = drafts.app?.takeIf { it.appId == app.id }?.draft
                if (draft == null) {
                    LaunchedEffect(app.id) { drafts.openApp(app.id, store.load()) }
                    return@AnimatedContent
                }
                val appStorage by home.storage.collectAsState()
                // Again after every copy, export, import or reset.
                LaunchedEffect(app.id, storageTasks.generation, savedSizes) { home.measureStorage(app.id) }
                val appBusy = storageTasks.busyWith(app.id)
                val showsWebContent by produceState(false, app.id) {
                    value = withContext(Dispatchers.IO) { InstalledApps(this@MainActivity).showsWebContent(app.id) }
                }
                val details by produceState<AppDetails?>(null, app.id) {
                    value = withContext(Dispatchers.IO) { InstalledApps(this@MainActivity).details(app.id) }
                }
                AppSettingsScreen(
                    page = target.page,
                    onPage = { go(target.copy(page = it)) },
                    draft = draft,
                    env = AppSettingsEnv(
                        app = app,
                        details = details,
                        storage = appStorage[app.id],
                        canChangeFiles = session.state.appIdOrNull != app.id && !appBusy,
                        // An uninstall waits in the queue behind what runs (this app too).
                        canUninstall = true,
                        developerOptions = developerOptions,
                        showsWebContent = showsWebContent,
                        hasCamera = remember { AndroidCameras.present(this@MainActivity) },
                        // Asks the camera service, so off the main thread; none until it answers.
                        cameraResolutions = produceState(emptyMap<Int, List<AndroidCameras.Resolution>>()) {
                            value = withContext(Dispatchers.IO) { AndroidCameras.resolutions(this@MainActivity) }
                        }.value,
                        onRequestMicrophone = ::requestMicrophone,
                        onRequestCamera = ::requestCamera,
                        onUninstall = { deleteData, keepRuntimes ->
                            if (FlatpakTaskService.run(this@MainActivity, FlatpakTask.Uninstall(app.id, deleteData, keepRuntimes))) home.uninstallStarted(app.id)
                            go(Screen.Home)
                        },
                        sharedHome = storage.sharedHomeDisplay,
                        broadAccess = storage.broadAccess,
                        onStorage = { action -> storage.storageAction(app.id, action) },
                        onOpenHome = { storage.openHomeInFiles(app.id) },
                        limitDevices = saved.security.limitDevices,
                        onResetFlatdroidSettings = { store.delete(); go(target.parent ?: Screen.Home) },
                    ),
                    onSave = { new ->
                        // Keep what the launcher manages: last opened, the Electron flags and /.flatpak-info.
                        store.update { old ->
                            new.copy(
                                lastOpened = old.lastOpened,
                                launches = old.launches,
                                usedMillis = old.usedMillis,
                                newerElectronFlags = old.newerElectronFlags,
                                withoutFlatpakInfo = old.withoutFlatpakInfo,
                                wholeDev = if (developerOptions) new.wholeDev else old.wholeDev,
                            )
                        }
                        go(target.parent ?: Screen.Home)
                    },
                    onDiscard = { go(target.parent ?: Screen.Home) },
                    modifier = modifier,
                )
            }
            Screen.Browse -> {
                LaunchedEffect(Unit) { Catalog.open(this@MainActivity) }
                BrowseScreen(
                    state = catalog,
                    listState = browseListState,
                    installed = installedIds.orEmpty(),
                    queued = task.queuedInstalls,
                    installing = installingIds,
                    // A refresh waits in the queue like any task; off while one runs or waits.
                    canRefresh = task.running != FlatpakTask.RefreshCatalog && FlatpakTask.RefreshCatalog !in task.queue,
                    onBack = { go(Screen.Home) },
                    onOpenApp = { go(Screen.BrowseApp(it)) },
                    onRefresh = { startDownload(FlatpakTask.RefreshCatalog) },
                    onInstallById = { installDialog = true },
                    onRetry = { Catalog.open(this@MainActivity) },
                    bottomBar = bottomBar,
                    modifier = modifier,
                )
            }
            is Screen.BrowseApp -> {
                LaunchedEffect(Unit) { Catalog.open(this@MainActivity) }
                val id = target.appId
                val entry = catalogIndex?.app(id)
                BrowseAppScreen(
                    appId = id,
                    app = entry,
                    index = catalogIndex,
                    catalogLoading = catalog is CatalogState.Loading || catalog is CatalogState.Idle,
                    arch = remember { AlpineEnvironment.get(this@MainActivity).arch },
                    installState = when {
                        id in installingIds -> InstallState.INSTALLING
                        id in task.queuedInstalls -> InstallState.QUEUED
                        installedIds == null -> InstallState.UNKNOWN
                        id in installedIds -> InstallState.INSTALLED
                        else -> InstallState.NOT_INSTALLED
                    },
                    // The setting as loaded for this screen: not read from disk again while the page composes.
                    mobileDataBlocked = { mobileDataPolicy(this@MainActivity, saved.mobileData) == MobileData.NEVER },
                    mobileData = { mobileDataPolicy(this@MainActivity, saved.mobileData) },
                    runtimeInstalled = { ref ->
                        // Flathub names its own architecture (the app is the same on ours): look for this phone's.
                        val env = AlpineEnvironment.get(this@MainActivity)
                        FlatpakInstallation(env.flatpakDir, env.arch).runtime(ref.copy(arch = env.arch)) != null
                    },
                    onInstall = { startDownload(FlatpakTask.Install(listOf(id))) },
                    onUnqueue = { FlatpakTasks.unqueueInstall(id) },
                    canOpen = !sessionBusy && !taskRunning && (!terminalRunning || terminalWithApps),
                    onOpen = { launch(id) },
                    onAppSettings = { go(Screen.AppSettings(id, fromBrowser = true)) },
                    onUninstall = { deleteData, keepRuntimes ->
                        if (FlatpakTaskService.run(this@MainActivity, FlatpakTask.Uninstall(id, deleteData, keepRuntimes))) home.uninstallStarted(id)
                    },
                    onBack = { go(Screen.Browse) },
                    bottomBar = bottomBar,
                    modifier = modifier,
                )
            }
            Screen.Home -> {
                (session.state as? SessionState.ElectronOffer)?.let { offer ->
                    ElectronDialog(
                        offer, nameOf(offer.appId), remember { AlpineEnvironment.get(this@MainActivity).arch },
                        onAccept = { SessionController.ended(null); continueLaunch(PendingLaunch(offer.appId, prepareElectron = true)) },
                        onDismiss = { SessionController.ended(null) },
                    )
                }
                (session.state as? SessionState.FilesChanged)?.let { changed ->
                    FilesChangedDialog(
                        changed, nameOf(changed.appId),
                        onReinstall = { SessionController.ended(null); startDownload(FlatpakTask.Reinstall(changed.appId)) },
                        onDismiss = { SessionController.ended(null) },
                    )
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
                    session = session,
                    task = task,
                    listState = homeListState,
                    developerOptions = developerOptions,
                    showTimeUsed = showTimeUsed,
                    canLaunch = !sessionBusy && !taskRunning && (!terminalRunning || terminalWithApps),
                    // Browsing needs nothing idle; an install then waits in the queue.
                    canInstall = true,
                    // Waits in the queue behind what runs; off while an update runs or waits.
                    canUpdate = task.running != FlatpakTask.UpdateAll && FlatpakTask.UpdateAll !in task.queue,
                    onLaunch = { launch(it.id) },
                    onAppSettings = { go(Screen.AppSettings(it.id)) },
                    onInstall = { go(Screen.Browse) },
                    onUpdateAll = { startDownload(FlatpakTask.UpdateAll) },
                    onSettings = { go(Screen.Settings()) },
                    onImportFiles = { storage.importFiles(ImportTarget.Shared) },
                    onImportZip = storage::importZipIntoSharedHome,
                    onOpenApp = ::openViewer,
                    onStopApp = { SessionService.stop(this@MainActivity) },
                    onSessionLog = { log = LogKind.SESSION },
                    onSkipCheck = { SessionController.skipFileCheck() },
                    bottomBar = bottomBar,
                    modifier = modifier,
                )
            }
        }
        }
    }

    /** Every task the user starts waits in the queue behind a running task or app (the bottom bar shows it). */
    private fun startTask(task: FlatpakTask) {
        FlatpakTaskService.run(this, task)
    }

    /** Starts a task that downloads, first doing what the mobile data setting says on a metered connection. */
    private fun startDownload(task: FlatpakTask) {
        when (mobileDataPolicy()) {
            MobileData.ALWAYS -> startTask(task)
            MobileData.ASK -> meteredQuestion = task
            MobileData.NEVER -> Toast.makeText(this, R.string.mobile_data_blocked, Toast.LENGTH_LONG).show()
        }
    }

    /** What a download does now: the mobile data setting on a metered connection, and always otherwise. */
    private fun mobileDataPolicy(): MobileData = mobileDataPolicy(this)

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

    /** The storage checks (StorageFlows.beforeLaunch), then the microphone question when needed, then the session. */
    private fun continueLaunch(pending: PendingLaunch) = storage.beforeLaunch(pending.appId, onReady = {
        if (needsMicrophoneQuestion(pending.appId)) microphoneQuestion = pending else start(pending, micAnswer = null)
    })

    private fun start(pending: PendingLaunch, micAnswer: Boolean?) {
        // Started from this window: when the app is up, its viewer opens, unless the user leaves Flatdroid first.
        home.autoOpen = true
        if (pending.prepareElectron) SessionService.prepareElectronAndLaunch(this, pending.appId, micAnswer)
        else SessionService.launch(this, pending.appId, micAnswer)
    }

    /**
     * The app's microphone is on but will ask, and Flatdroid can't post the question when the app
     * records (notifications are off): it is asked before the launch instead, for the session.
     */
    private fun needsMicrophoneQuestion(appId: String): Boolean {
        val settings = AppData.settings(this, appId).load()
        return !settings.alwaysAllowMicrophone && MicStreamer.hasPermission(this) && !SessionService.canAskInNotification(this) &&
            settings.microphoneAllowed(InstalledApps(this).sockets(appId))
    }

    /** Asks Android for the microphone permission, then calls [onResult] with the answer. */
    private fun requestMicrophone(onResult: (Boolean) -> Unit) {
        if (MicStreamer.hasPermission(this)) return onResult(true)
        onPermissionResult = onResult
        permissionRequest.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestCamera(onResult: (Boolean) -> Unit) {
        if (AndroidCameras.hasPermission(this)) return onResult(true)
        onPermissionResult = onResult
        permissionRequest.launch(Manifest.permission.CAMERA)
    }

    private fun openViewer(session: SessionState.Running) {
        // An open viewer comes forward rather than a second one (a link can bring this window above it).
        if (session.socket != null) return startActivity(Intent(this, ViewerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        val settings = SetupController.settings(this).load()
        try {
            val intent = Viewer.intent(this, session.password, settings?.vncViewer, username = session.username)
                ?: throw ActivityNotFoundException()
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            val message = if (Viewer.installed(this).isEmpty()) R.string.session_no_viewer else R.string.session_choose_viewer
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        super.onPause()
        home.autoOpen = false
    }

    override fun onResume() {
        super.onResume()
        SetupController.refresh(this)
        home.reload()
        storage.refresh()
        if (SetupController.state.value.isComplete && !SessionController.isBusy) {
            storage.checkUnfinished()
            storage.checkTempHomes()
        }
        // What finished while Flatdroid was away is on the screen now.
        DoneNotice.cancel(this, DoneNotice.TASKS_ID)
        DoneNotice.cancel(this, DoneNotice.SETUP_ID)
        // A repair or baselines Flatdroid owes, then tasks Android didn't let start while Flatdroid was in the background.
        if (SetupController.state.value.isComplete) FlatpakTaskService.startNext(this)
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

    companion object {
        private const val EXTRA_INSTALL = "com.garathon.flatdroid.INSTALL"

        private fun isLauncherIntent(intent: Intent?) = intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)

        /**
         * Brings Flatdroid's window forward, as it was, to ask about installing [appId]. The intent
         * is the launcher's (extras don't count), so the task it may start is the one the icon
         * opens later, and the window already open is reused, not stacked again.
         */
        fun installIntent(context: Context, appId: String): Intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClass(context, MainActivity::class.java)
            .putExtra(EXTRA_INSTALL, appId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }
}

/** Where the user is once setup is done. Saved across recreation as its [key]. */
private sealed interface Screen {
    val key: String

    /** Home is the first level, settings (with an app's) the second, the terminal the third: screens slide between levels. */
    val level: Int

    /** Which side of the home screen it slides in from: 1 right, -1 left. */
    val side: Int

    data object Home : Screen {
        override val key = "home"
        override val level = 0
        override val side = 0
    }

    data class Settings(val page: GlobalPage = GlobalPage.OVERVIEW) : Screen {
        override val key get() = "$SETTINGS_PREFIX${page.key}"
        override val level = 1
        override val side = 1
    }

    /** Flathub's apps; an app's page is a level deeper. */
    data object Browse : Screen {
        override val key = "browse"
        override val level = 1
        override val side = 1
    }

    data class BrowseApp(val appId: String) : Screen {
        override val key get() = "$BROWSE_PREFIX$appId"
        override val level = 2
        override val side = 1
    }

    data object Terminal : Screen {
        override val key = "terminal"
        override val level = 2
        override val side = 1
    }

    /** [fromBrowser]: opened from the app's page in the browser, which Back, Save and Discard return to. */
    data class AppSettings(val appId: String, val page: AppPage = AppPage.OVERVIEW, val fromBrowser: Boolean = false) : Screen {
        override val key get() = "${if (fromBrowser) BROWSER_APP_PREFIX else APP_PREFIX}${page.key}:$appId"
        override val level get() = if (fromBrowser) 3 else 1
        override val side = -1
    }

    /** Where Back goes; null on the home screen. */
    val parent: Screen?
        get() = when (this) {
            Home -> null
            Terminal -> Settings(GlobalPage.DEVELOPER)
            is BrowseApp -> Browse
            Browse -> Home
            is AppSettings -> if (fromBrowser) BrowseApp(appId) else Home
            is Settings -> Home
        }

    companion object {
        private const val SETTINGS_PREFIX = "settings:"
        private const val APP_PREFIX = "app:"
        private const val BROWSER_APP_PREFIX = "browserapp:"
        private const val BROWSE_PREFIX = "browse:"

        val Saver = Saver<Screen, String>(
            save = { it.key },
            restore = { key ->
                when {
                    key.startsWith(SETTINGS_PREFIX) -> Settings(GlobalPage.of(key.removePrefix(SETTINGS_PREFIX)))
                    key.startsWith(APP_PREFIX) -> key.removePrefix(APP_PREFIX).split(":", limit = 2).let { AppSettings(it.getOrElse(1) { "" }, AppPage.of(it[0])) }
                    key.startsWith(BROWSER_APP_PREFIX) -> key.removePrefix(BROWSER_APP_PREFIX).split(":", limit = 2)
                        .let { AppSettings(it.getOrElse(1) { "" }, AppPage.of(it[0]), fromBrowser = true) }
                    key == Terminal.key -> Terminal
                    key == Browse.key -> Browse
                    key.startsWith(BROWSE_PREFIX) -> key.removePrefix(BROWSE_PREFIX).takeIf(InstalledApps::isValidId)?.let { BrowseApp(it) } ?: Browse
                    else -> Home
                }
            },
        )
    }
}

/** A launch on hold while the user answers a question. */
private data class PendingLaunch(val appId: String, val prepareElectron: Boolean)

/** Which output the log dialog shows. */
private enum class LogKind { SESSION, TASK }
