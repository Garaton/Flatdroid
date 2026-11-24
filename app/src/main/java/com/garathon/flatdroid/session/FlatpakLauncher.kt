package com.garathon.flatdroid.session

import com.garathon.flatdroid.FlatdroidApplication
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.view.Display
import android.view.Surface
import android.widget.Toast
import com.garathon.flatdroid.BuildConfig
import com.garathon.flatdroid.R
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.alpine.GuestFiles
import com.garathon.flatdroid.alpine.programs.AlpineChanges
import com.garathon.flatdroid.alpine.programs.AlpinePackages
import com.garathon.flatdroid.alpine.programs.AlpineProgramIds
import com.garathon.flatdroid.alpine.programs.AlpinePrograms
import com.garathon.flatdroid.alpine.programs.AlpineRoot
import com.garathon.flatdroid.alpine.dns.ResolvConfRefresher
import com.garathon.flatdroid.audio.AudioPlayer
import com.garathon.flatdroid.audio.MicStreamer
import com.garathon.flatdroid.audio.MicrophoneSession
import com.garathon.flatdroid.camera.AndroidCameras
import com.garathon.flatdroid.camera.CameraScript
import com.garathon.flatdroid.camera.CameraSession
import com.garathon.flatdroid.camera.CameraStreamer
import com.garathon.flatdroid.camera.FrameStreamer
import com.garathon.flatdroid.camera.ScreenStreamer
import com.garathon.flatdroid.flatpak.integrity.AndroidLinkFs
import com.garathon.flatdroid.session.helpers.PulseScript
import com.garathon.flatdroid.session.bus.AndroidAppearance
import com.garathon.flatdroid.session.bus.Portal
import com.garathon.flatdroid.session.bus.SessionPortal
import com.garathon.flatdroid.session.helpers.SessionBus
import com.garathon.flatdroid.session.helpers.SessionScript
import com.garathon.flatdroid.session.helpers.WaylandVnc
import com.garathon.flatdroid.storage.AppData
import com.garathon.flatdroid.flatpak.integrity.FileIntegrity
import com.garathon.flatdroid.flatpak.FlatpakInstallation
import com.garathon.flatdroid.flatpak.integrity.IntegrityGuard
import com.garathon.flatdroid.launcher.DesktopConfig
import com.garathon.flatdroid.launcher.electron.Electron
import com.garathon.flatdroid.launcher.electron.ElectronSwap
import com.garathon.flatdroid.launcher.HostCommands
import com.garathon.flatdroid.launcher.HostDirs
import com.garathon.flatdroid.launcher.LdCache
import com.garathon.flatdroid.launcher.LaunchOptions
import com.garathon.flatdroid.launcher.LaunchPlan
import com.garathon.flatdroid.launcher.LaunchPlanner
import com.garathon.flatdroid.launcher.AppEnding
import com.garathon.flatdroid.launcher.ProotExit
import com.garathon.flatdroid.launcher.PlanResult
import com.garathon.flatdroid.launcher.RootLayout
import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.proot.DevNodes
import com.garathon.flatdroid.proot.Fdsec
import com.garathon.flatdroid.proot.Fdv4l2
import com.garathon.flatdroid.proot.FlatpakBinds
import com.garathon.flatdroid.proot.hostOf
import com.garathon.flatdroid.proot.GuestPaths
import com.garathon.flatdroid.proot.processes.HelperProcesses
import com.garathon.flatdroid.proot.processes.LeftoverProcesses
import com.garathon.flatdroid.proot.ProotCommand
import com.garathon.flatdroid.proot.ProotPaths
import com.garathon.flatdroid.proot.ProotProcess
import com.garathon.flatdroid.proot.ProotRunner
import com.garathon.flatdroid.setup.rootfs.AndroidTarFs
import com.garathon.flatdroid.settings.AppSettings
import com.garathon.flatdroid.settings.AppSettingsStore
import com.garathon.flatdroid.settings.AudioOutputMode
import com.garathon.flatdroid.settings.VideoMode
import com.garathon.flatdroid.settings.FlatpakTheme
import com.garathon.flatdroid.settings.HomeMode
import com.garathon.flatdroid.settings.Rendering
import com.garathon.flatdroid.settings.Resampler
import com.garathon.flatdroid.settings.SecuritySettings
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.setup.AlpineSetup
import com.garathon.flatdroid.setup.ApkProgress
import com.garathon.flatdroid.setup.ApkSummary
import com.garathon.flatdroid.setup.PackageGroup
import com.garathon.flatdroid.setup.SetupController
import com.garathon.flatdroid.setup.rootfs.deleteTree
import com.garathon.flatdroid.setup.rootfs.hasLinkBelow
import com.garathon.flatdroid.setup.rootfs.makeDirsBelow
import com.garathon.flatdroid.storage.AppFiles
import com.garathon.flatdroid.storage.HomeFolders
import com.garathon.flatdroid.storage.SharedHome
import com.garathon.flatdroid.storage.tasks.StorageTaskService
import com.garathon.flatdroid.storage.tasks.StorageTasks
import com.garathon.flatdroid.storage.TempHomes
import com.garathon.flatdroid.viewer.ViewerSize
import com.garathon.flatdroid.util.io.readTextUpTo
import com.garathon.flatdroid.util.io.httpDownload
import com.garathon.flatdroid.util.io.sha256
import com.garathon.flatdroid.util.io.writeAtomically
import com.garathon.flatdroid.util.io.writeTextAtomically
import java.io.File
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A launch that can't go ahead; [message] is shown to the user. */
class LaunchException(message: String) : IOException(message)

/**
 * Runs one Flatpak session: a fresh display (labwc and wayvnc, or Xvnc and Openbox for an app that
 * only takes X11) and PulseAudio in Alpine, then the app in its own root, with the audio player
 * and, when the app may use it, the microphone. Blocking; interrupting the thread stops the app
 * and the helpers.
 */
class FlatpakLauncher(private val context: Context) {
    private val env = AlpineEnvironment.get(context)
    private val arch = env.arch
    private val installation = FlatpakInstallation(env.flatpakDir, arch)
    private val planner = LaunchPlanner(installation, arch, File(env.paths().l2sDir), AndroidLinkFs::readlink, DevNodes::hostHas)
    /** The helpers of the session [run] starts. */
    private val helpers = SessionHelpers(context, env)
    private val sessionDir = helpers.sessionDir
    private val etcDir = File(sessionDir, "etc")
    private val hostCommandsDir = File(sessionDir, "host-commands")
    private val desktopDir = File(sessionDir, "xdg")
    private val cameraPipeDir = File(sessionDir, "camera")
    private val cameraConfigDir = File(sessionDir, "camera-config")
    /** Device mode: the cameras' pipes, each bound at its /dev/video* in the app's root, and their list ([Fdv4l2]). */
    private val videoDir = File(sessionDir, "video")
    private val cameraList = File(sessionDir, "cameras")
    /** Device mode: the cameras' `/sys` and udev entries ([Fdv4l2.tree]). */
    private val cameraSysDir = File(sessionDir, "camera-sys")
    /** Bound over the helpers' /proc entries in the app's root, when they are hidden. */
    private val emptyDir = File(sessionDir, "empty")
    private val rootfs = File(env.paths().rootfs)
    private val alpinePrograms = AlpinePrograms.of(rootfs)
    /** An Alpine program's root ([AlpineRoot]) while [run] launches one; null for a Flatpak. */
    private var alpineRoot: AlpineRoot? = null
    /** An Alpine program's empty /app, emptied for each of its launches. */
    private val emptyApp = File(sessionDir, "alpine-app")

    fun electronSwap(appId: String) = ElectronSwap(
        dir = File(AppData.electronDir(context), appId),
        cacheDir = env.cacheDir,
        arch = arch,
        allowedSources = listOf(env.flatpakDir, File(env.paths().l2sDir)),
        fs = AndroidTarFs,
        download = ::httpDownload,
        sha256 = ::sha256,
    )

    /** Prepares the Electron swap the app needs, after the user agreed. */
    fun prepareElectron(appId: String) {
        val app = installation.app(appId) ?: throw LaunchException(context.getString(R.string.launch_not_installed, appId))
        val needed = plan(appId, AppData.settings(context, appId).load(), SetupController.settings(context).load()) as? PlanResult.ElectronSwapNeeded ?: return
        var last: Pair<String, Int?>? = null
        electronSwap(appId).prepare(app, needed) { step, zip, fraction ->
            val detail = when (step) {
                ElectronSwap.Step.CHECKSUMS -> context.getString(R.string.electron_step_checksums)
                ElectronSwap.Step.DOWNLOAD -> context.getString(R.string.electron_step_download, zip)
                ElectronSwap.Step.VERIFY -> context.getString(R.string.electron_step_verify, zip)
                ElectronSwap.Step.EXTRACT -> context.getString(R.string.electron_step_extract, zip)
                ElectronSwap.Step.RESOURCES -> context.getString(R.string.electron_step_resources)
            }
            // Whole percents only, so the UI isn't updated for every block downloaded.
            val now = detail to fraction?.let { (it * 100).toInt() }
            if (now != last) {
                last = now
                SessionController.preparing(appId, detail, fraction)
            }
        }
    }

    /**
     * How the app the last [run] ran ended when not as it should: killed by a signal or with an
     * exit code other than 0; null when it closed normally or was stopped.
     */
    var ending: AppEnding? = null
        private set

    /**
     * Launches [appId] and returns when it exits. Returns false without launching if the user
     * must first agree to an Electron swap or to Alpine packages (the offer is then in [SessionController]). [answers]:
     * the user's answers about the microphone and camera when asked before the launch.
     */
    fun run(appId: String, answers: Map<Sensor, Boolean> = emptyMap()): Boolean {
        ending = null
        SessionController.starting(appId, context.getString(R.string.session_reading_metadata), clearLog = true)
        // Every Flatpak could otherwise open Flatdroid's memory through /proc (same user).
        if (!FlatdroidApplication.nonDumpable) throw LaunchException(context.getString(R.string.launch_dumpable))
        // Before the file check: a process left from an earlier session could change files after it.
        endEscaped()
        // Read once for the whole launch; the app's settings change only through [store] below.
        val userSettings = SetupController.settings(context).load()
        val store = AppData.settings(context, appId)
        var settings = store.load()
        // An Alpine program: its root from Alpine's folders, found by the walk that also records them for Alpine safe launch.
        val alpineProgram = if (!AlpineProgramIds.isId(appId)) null else alpinePrograms.program(appId) ?: throw LaunchException(context.getString(R.string.launch_alpine_program_missing))
        val alpineBefore = alpineProgram?.let {
            SessionController.starting(appId, context.getString(R.string.session_checking_alpine))
            AlpineChanges.requireUnchanged(context)
            // Before the record: the helpers' binds would otherwise put stand-ins into Alpine's /etc meanwhile.
            env.ensureBindTargets()
            val started = SystemClock.elapsedRealtime()
            AlpineChanges.snapshot(rootfs, File(env.paths().l2sDir)).also {
                SessionController.log("[flatdroid] Alpine's files: ${it.stamps.size} entries recorded in ${SystemClock.elapsedRealtime() - started} ms")
            }
        }
        alpineRoot = alpineProgram?.let { program ->
            AndroidTarFs.deleteTree(emptyApp)
            emptyApp.mkdirs()
            val bwrap = File(hostCommandsDir, ALPINE_BWRAP).apply {
                parentFile!!.mkdirs()
                writeTextAtomically(GuestFiles.NO_NAMESPACES_BWRAP, durable = false)
                setExecutable(true, false)
            }
            AlpineRoot.of(program, rootfs, emptyApp, arch, BuildConfig.ALPINE_BRANCH, alpineBefore!!.l2sBinds, bwrap)
        }
        val checkAlpine = alpineProgram != null && userSettings?.checkAlpine != false
        // Android's storage as the home needs All files access; without it the app gets the shared home.
        if (settings.homeMode == HomeMode.ANDROID && !SharedHome.broadAccess(context)) {
            settings = settings.copy(homeModeChoice = HomeMode.SHARED)
            SessionController.log("[flatdroid] All files access is off; the app gets the shared home instead of Android's storage")
        }
        // A launch in Temporary Home mode replaces the temporary home, so not while it is exported.
        if (StorageTasks.busyWith(appId) || (settings.homeMode == HomeMode.TEMPORARY && StorageTasks.exportingTempHome(StorageTaskService.pendingTasks(context)))) {
            throw LaunchException(context.getString(R.string.launch_storage_busy))
        }
        var plan = when (val result = plan(appId, settings, userSettings)) {
            is PlanResult.Ready -> result.plan
            is PlanResult.ElectronSwapNeeded -> {
                SessionController.offerElectron(
                    SessionState.ElectronOffer(
                        appId, result.version, result.binaryArch,
                        problem = when {
                            result.version == null -> context.getString(R.string.electron_version_unknown)
                            result.hasNativeModules -> context.getString(R.string.electron_native_modules, result.binaryArch)
                            else -> null
                        },
                    )
                )
                return false
            }
            else -> throw LaunchException(describe(result))
        }
        // X11, or a terminal app's terminal, the first time an app needs them: the user is asked first.
        SessionPackages.missing(context, PackageGroup.needed(plan.wayland, plan.terminal))?.let { group ->
            SessionController.offerPackages(SessionState.PackagesOffer(appId, group))
            return false
        }
        store.update { it.copy(lastOpened = System.currentTimeMillis()) }

        // Alpine's files aren't the installation's: Alpine safe launch checks those.
        if (alpineRoot == null && userSettings?.security?.checkFilesBeforeLaunch == true && !checkFiles(appId)) return false

        SessionController.starting(appId, context.getString(R.string.session_preparing_files))
        val home = prepareHome(appId, settings.homeMode)
        // What is there before the launch is the user's, even when empty: the clean-up afterwards leaves it.
        val existedBefore = mountPointChains(plan).filter { rel -> File(home, rel).let { it.exists() || AndroidTarFs.isSymlink(it) } }.toSet()
        // Before prepareDirs, which makes the mount points by what they are bound from.
        writeDesktopFiles(home, plan, userSettings)
        writeHostCommands(plan)
        writeTimeZone(plan, (userSettings?.security ?: SecuritySettings()).shareLocale)
        prepareDirs(appId, plan)
        val security = userSettings?.security ?: SecuritySettings()
        val externalViewer = userSettings?.externalViewer == true
        // Only an external viewer logs in: Flatdroid's own uses a private socket and no password.
        val password = if (!externalViewer) null else if (plan.wayland) VncPassword.random(length = WaylandVnc.PASSWORD_LENGTH) else VncPassword.random()
        val audio = plan.binds.pulseSockets != null
        val appContext = plan.app.metadata.context
        // On by setting or by default. Android's permission is asked at the first Allow; "Always
        // allow" without it (revoked since) asks instead.
        val microphone = audio && settings.microphoneAllowed(appContext.sockets)
        val micAlways = settings.alwaysAllowMicrophone && MicStreamer.hasPermission(context)
        if (microphone && settings.alwaysAllowMicrophone && !micAlways) SessionController.log("[flatdroid] Android's microphone permission is off; the app's microphone asks instead")
        // Device, portal or none ([AppSettings.videoFor]).
        val videoMode = settings.videoFor(appContext)
        val cameraWanted = videoMode != null && settings.cameraOn
        // The screen asks at each use, in a notification only: Android's consent can't be asked before the launch.
        val screenWanted = videoMode != null && settings.screenOn && SessionService.canAskInNotification(context)
        if (videoMode != null && settings.screenOn && !screenWanted) SessionController.log("[flatdroid] notifications are off, so the app doesn't get the screen: Flatdroid couldn't ask when it uses it")
        val cameraAlways = settings.alwaysAllowCamera && AndroidCameras.hasPermission(context)
        if (cameraWanted && settings.alwaysAllowCamera && !cameraAlways) SessionController.log("[flatdroid] Android's camera permission is off; the app's camera asks instead")
        val cameras = if (cameraWanted) AndroidCameras.list(context, settings.cameraResolution, settings.cameraFrameRate) else emptyList()
        if (cameraWanted && cameras.isEmpty()) SessionController.log("[flatdroid] the app's camera is on, but Android has no camera Flatdroid can use")
        // Upright for the app's display: a landscape display as the device held landscape, a
        // portrait one as held portrait (Flatdroid itself is in portrait at launch whatever the app).
        // The screen starts at the size the viewer will show it in (Flatdroid's viewer then keeps it so).
        val (viewWidth, viewHeight) = ViewerSize.expected(context, bar = !externalViewer)
        val (screenWidth, screenHeight) = settings.size(viewWidth, viewHeight)
        val displayRotation = displayRotation(landscape = screenWidth > screenHeight)
        val cameraRotations = cameras.map { it.rotation(displayRotation) }
        // The cameras, then the screen: an app's first camera stays a real one.
        val cameraNodes = cameraNodes(cameras, cameraRotations) + listOfNotNull(
            if (!screenWanted) null else ScreenStreamer.size(context, landscape = screenWidth > screenHeight, settings.screenScale).let { (width, height) ->
                CameraScript.Node(CameraScript.nodeName(cameras.size), context.getString(R.string.camera_screen), width, height, ScreenStreamer.FRAME_RATE)
            },
        )
        // Device mode: a /dev/video* per camera, no PipeWire. Portal mode: the camera portal's helpers.
        val cameraDevices = cameraNodes.isNotEmpty() && videoMode == VideoMode.DEVICE
        val portalNodes = if (cameraDevices) emptyList() else cameraNodes
        // Without the real portal, Flatdroid answers its camera to learn that the app wants it.
        val watchCameraPortal = portalNodes.isEmpty() && settings.watchesCameraPortal(appContext) && (settings.screenOn || AndroidCameras.present(context))
        val deviceList = if (!cameraDevices) emptyList() else cameraNodes.mapIndexed { index, node ->
            Fdv4l2.Device(index, node.width, node.height, node.frameRate, node.description, front = node.location == CameraScript.Location.FRONT)
        }
        // With the whole of /sys, Android's classes stay readable by path from the cameras' /sys/class.
        val androidClasses = if (deviceList.isNotEmpty() && plan.binds.sys == null) Fdv4l2.KNOWN_CLASSES.filter { File("/sys/class/$it").exists() } else emptyList()
        val cameraBinds = cameraBinds(deviceList, androidClasses.isNotEmpty())
        if (cameraDevices) {
            SessionController.log("[flatdroid] camera in device mode: " + cameraNodes.indices.joinToString { "${Fdv4l2.devicePath(it)} (${cameraNodes[it].description})" })
        }
        val outputRate = outputRate()
        val micRate = MicStreamer.rate()
        helpers.writeFiles(
            SessionHelpers.Files(
                password, wayvncLogin = plan.wayland && externalViewer, titleBars = userSettings?.windowTitleBars ?: true,
                darkDecorations = isDarkTheme(userSettings),
                pulseScript = if (audio) PulseScript.text(outputRate, microphone, micRate) else null,
                busArgs = SessionBus.proxyArgs(
                    alpineProgram?.let { it.id.removePrefix(AlpineProgramIds.PREFIX) } ?: appId, plan.app.metadata.sessionBusPolicy, camera = portalNodes.isNotEmpty(),
                    wholeBus = SessionBus.WHOLE_BUS in plan.app.metadata.context.sockets,
                    watchCamera = watchCameraPortal,
                    notifications = settings.sendsNotifications,
                ),
                terminalTitle = if (plan.terminal) InstalledApps(context).name(appId) ?: appId else null,
                clipboard = settings.sharesClipboard,
            ),
        )
        writeSessionFiles(cameraNodes = portalNodes, cameraDevices = deviceList, androidClasses = androidClasses)
        createCameraMountPoints(plan.binds.copy(cameraBinds = cameraBinds))
        // After both, which create the directories it checks: they live in the work folder, which a start of Flatdroid empties, or in the cache, which Android may
        // clear at any time, and a fresh install has none yet.
        checkBindSources(plan)

        SessionController.starting(appId, context.getString(R.string.session_starting_display))
        val frameRate = userSettings?.frameRate ?: UserSettings.DEFAULT_FRAME_RATE
        val resampler = userSettings?.resampler ?: Resampler.FAST
        var app: ProotProcess? = null
        // When the app first started running, for the time it was used.
        var runningSince: Long? = null
        var player: AudioPlayer? = null
        var cameraHelpers: ProotProcess? = null
        // What each of these starts is stopped in the finally, whichever of the later ones fails.
        var mic: MicrophoneSession? = null
        var dns: ResolvConfRefresher? = null
        var portal: SessionPortal? = null
        val streamers = LinkedHashMap<String, FrameStreamer>()
        var camera: CameraSession? = null
        try {
            mic = if (!microphone) null else {
                MicrophoneSession(context, File(helpers.pulseDir, PulseScript.MIC_PIPE), micRate, micAlways, answers[Sensor.MICROPHONE])
            }
            helpers.start(
                SessionScript.command(
                    "${screenWidth}x$screenHeight", frameRate, audio, resampler, sessionBus = true,
                    camera = portalNodes.isNotEmpty(), clipboard = settings.sharesClipboard, wayland = plan.wayland,
                    externalViewer = externalViewer, interfaceSize = settings.interfaceSize,
                    memfd = SessionScript.kernelHasMemfd(Os.uname().release), terminal = plan.terminal, glx = plan.glx,
                ),
                audio,
                Fdsec.Filter(extraSyscalls = security.extraSyscallFilter),
                terminal = plan.terminal,
            )
            for (index in cameraNodes.indices) {
                val node = cameraNodes[index]
                val pipe = if (cameraDevices) File(videoDir, "video$index") else File(cameraPipeDir, node.name)
                val control = File(videoDir, "control$index").takeIf { cameraDevices }
                val media = File(videoDir, "media$index").takeIf { cameraDevices }
                streamers[node.name] = if (index < cameras.size) {
                    CameraStreamer(context, cameras[index], pipe, cameraRotations[index], deviceMode = cameraDevices, control = control, media = media)
                } else {
                    ScreenStreamer(
                        context, pipe, node.width, node.height, settings.screenScale, deviceMode = cameraDevices, control = control, media = media,
                        onEnded = { camera?.screenEnded() },
                    )
                }
            }
            if (streamers.isNotEmpty()) {
                camera = CameraSession(
                    streamers, if (cameraDevices) null else File(cameraPipeDir, CameraScript.EVENTS), cameraAlways, answers[Sensor.CAMERA],
                    onFirstUse = if (!cameraDevices || settings.cameraDeviceUsed) null else ({ cameraDeviceUsed(store) }),
                )
                // Device mode needs no helpers: its frames wait for the app from now.
                if (cameraDevices) camera.start()
            }
            // The app's display turned with the phone: the pictures turn with it, staying upright. Not
            // in device mode, where the app fixed the size when it started streaming.
            if (portalNodes.isNotEmpty()) SessionController.onDisplayResized = { width, height ->
                val rotation = displayRotation(landscape = width > height)
                cameras.forEachIndexed { index, it -> (streamers[cameraNodes[index].name] as? CameraStreamer)?.turn(it.rotation(rotation)) }
            }
            // A network change during the session (Wi-Fi to mobile data, Private DNS) reaches the app's resolv.conf.
            dns = ResolvConfRefresher(context, listOf(File(etcDir, "resolv.conf"))).apply { start() }
            helpers.awaitDisplay(plan.wayland)
            // A terminal app's terminal, which foot's command names once it has given it up.
            val tty = if (plan.terminal) helpers.awaitTerminal() else null
            if (!helpers.waitForFile(File(helpers.busProxyDir, SessionBus.SOCKET), BUS_TIMEOUT_MS, null)) {
                SessionController.log("[flatdroid] the session bus did not start; the app has none")
            } else {
                // Before the camera's portal, which asks Flatdroid for the settings, and before the app.
                portal = SessionPortal.open(
                    context, File(helpers.busDaemonDir, SessionBus.SOCKET),
                    role = if (portalNodes.isNotEmpty()) Portal.Role.BACKEND else Portal.Role.PORTAL,
                    appNetwork = settings.networkAllowed(appContext.shared), user = userSettings,
                    hostPath = plan.binds::hostOf, keepScreenOn = SessionController::keepScreenOn,
                    log = SessionController::log,
                    cameraAsked = if (!watchCameraPortal) null else AtomicBoolean().let { seen -> { if (seen.compareAndSet(false, true)) cameraPortalSeen(store) } },
                    appId = appId, notify = settings.sendsNotifications,
                )
                camera?.takeIf { portalNodes.isNotEmpty() }?.let {
                    cameraHelpers = startCamera(it, Fdsec.Filter(extraSyscalls = security.extraSyscallFilter, noInternet = true))
                }
            }
            if (!audio) {
                SessionController.log("[flatdroid] sound is off for this app; PulseAudio isn't started")
            } else if (!helpers.waitForFile(File(helpers.pulseDir, PulseScript.NATIVE_SOCKET), AUDIO_TIMEOUT_MS, null)) {
                SessionController.log("[flatdroid] PulseAudio did not start; the app has no audio")
            } else {
                player = AudioPlayer(
                    File(helpers.pulseDir, PulseScript.SIMPLE_SOCKET), outputRate,
                    latencyMs = userSettings?.audioLatencyMs ?: UserSettings.DEFAULT_AUDIO_LATENCY_MS,
                    outputMode = userSettings?.audioOutputMode ?: AudioOutputMode.POWER_SAVING,
                )
                player.start()
            }
            // The helpers run under proot, which keeps them dumpable: hide what runs now from the app.
            val hidden = if (security.hideHelperProcesses) HelperProcesses.procPaths(ProotRunner.runningPids()) else emptyList()
            // /proc entries Android doesn't let apps read, where Flatdroid knows the real value (the kernel's version, say).
            val procStandIns = env.realProcStandIns()
            // Device mode: the cameras' devices, and the library that answers on them, preloaded first.
            // An Alpine program gets the library without cameras too: it stands in for the device
            // event socket Android refuses, which Alpine's libudev needs (Fdv4l2).
            val preload = cameraBinds.isNotEmpty() || alpineRoot != null
            fun forSession(plan: LaunchPlan) = plan.copy(
                binds = plan.binds.copy(
                    fakeProc = procStandIns,
                    cameraBinds = cameraBinds,
                    fdv4l2 = preload,
                ).let { binds ->
                    if (hidden.isEmpty()) binds else binds.copy(hiddenProc = hidden, emptyDir = emptyDir.path)
                },
                environment = if (!preload) plan.environment
                    else plan.environment + (if (cameraBinds.isEmpty()) emptyMap() else Fdv4l2.ENVIRONMENT) + (PRELOAD to Fdv4l2.preload(plan.environment[PRELOAD])),
            )
            plan = forSession(plan)
            SessionController.log("[flatdroid] " + describeFilter(plan.filter, hidden.size))
            var triedNewerFlags = false
            var triedWithoutFlatpakInfo = false
            while (true) {
                SessionController.log("[flatdroid] ${plan.command.joinToString(" ")}")
                for ((host, guest) in plan.binds.extensions) SessionController.log("[flatdroid] extension $host -> $guest")
                val gpuFailed = AtomicBoolean(false)
                val kdeHostProcess = AtomicBoolean(false)
                val killedBySignal = AtomicBoolean(false)
                // The signal that ended the app itself (its process 1), from its proot's own report.
                val appSignal = AtomicInteger(0)
                // The app's own: a helper's fdsec refusing later (sound, say) doesn't make the app's exit a failure.
                val appRefused = AtomicBoolean(false)
                app = ProotRunner.start(
                    ProotCommand.flatpak(prootPaths(appId), plan.binds, plan.environment, plan.command, plan.filter, tty),
                    File(plan.binds.root),
                ) { line ->
                    if (Fdsec.refused(line)) appRefused.set(true)
                    if (Electron.GPU_FAILURE in line) gpuFailed.set(true)
                    if (LaunchPlanner.KDE_HOST_PROCESS_WARNING in line) kdeHostProcess.set(true)
                    if (LaunchPlanner.PROOT_KILLED_BY_SIGNAL in line) killedBySignal.set(true)
                    ProotExit.signalOf(line)?.let { appSignal.compareAndSet(0, it) }
                    // Tagged: the app writes these, and a line of its own claiming to be Flatdroid's would show as the app's.
                    SessionController.log("[app] $line")
                }
                // Hidden, the app goes quiet and then pauses, unless it continues while hidden, and with no
                // window it is paused whatever it continues; with an external viewer Flatdroid can't tell
                // what is on screen or open.
                if (!externalViewer) app.pid?.let { HiddenPause.attach(it, player, pauseWhenHidden = !settings.continueWhileHidden, onPaused = { on -> camera?.paused(on); mic?.paused(on) }) }
                if (runningSince == null) {
                    runningSince = System.currentTimeMillis()
                    // Counted once the app runs, so a launch that failed or was stopped while starting isn't.
                    store.update { it.copy(launches = it.launches + 1) }
                }
                SessionController.running(
                    if (externalViewer) SessionState.Running(appId, password, username = WaylandVnc.USERNAME.takeIf { plan.wayland })
                    else SessionState.Running(
                        appId, password, socket = File(helpers.vncDir, SessionScript.VNC_SOCKET).path, wayland = plan.wayland,
                        followPercent = settings.resolution.percent,
                        fitAboveKeyboard = plan.terminal,
                        waylandSocket = File(helpers.waylandDir, GuestPaths.WAYLAND_SOCKET).path.takeIf { plan.wayland },
                        shareClipboard = settings.sharesClipboard,
                        outputRefresh = frameRate.takeIf { plan.wayland && it <= SessionScript.MAX_OUTPUT_REFRESH },
                    ),
                )
                val code = app.waitFor()
                val signal = appSignal.get()
                SessionController.log(
                    if (signal != 0) "[flatdroid] the app was ended by ${ProotExit.signalName(signal)} ($signal)" else "[flatdroid] the app exited ($code)",
                )
                if (appRefused.get()) throw LaunchException(context.getString(R.string.launch_filter_refused))
                if (plan.isElectron && !settings.newerElectronFlags && gpuFailed.get() && !triedNewerFlags) {
                    // Newer Electron: relaunch once with its extra flags, and keep them if they help.
                    triedNewerFlags = true
                    settings = settings.copy(newerElectronFlags = true)
                    store.update { it.copy(newerElectronFlags = true) }
                    SessionController.log("[flatdroid] relaunching with ${Electron.NEWER_FLAGS.joinToString(" ")}")
                    SessionController.starting(appId, context.getString(R.string.session_relaunching_newer_flags))
                    plan = (plan(appId, settings, userSettings) as? PlanResult.Ready)?.plan?.let(::forSession)
                        ?: throw LaunchException(context.getString(R.string.launch_app_changed))
                    // The app has run, so it may have replaced its own directories with links meanwhile.
                    prepareDirs(appId, plan)
                    // Flatdroid's zypak carries the flags.
                    writeHostCommands(plan)
                    checkBindSources(plan)
                    continue
                }
                if (plan.flatpakInfo != null && kdeHostProcess.get()) {
                    // KDE's frameworks would run processes on the host: no /.flatpak-info for this
                    // app from now on, and if it failed (KDE terminal apps crash), relaunch it without.
                    settings = settings.copy(withoutFlatpakInfo = true)
                    store.update { it.copy(withoutFlatpakInfo = true) }
                    SessionController.log("[flatdroid] KDE tried to run processes on the host; this app runs without /.flatpak-info from now on")
                    if ((code != 0 || killedBySignal.get()) && !triedWithoutFlatpakInfo) {
                        triedWithoutFlatpakInfo = true
                        SessionController.starting(appId, context.getString(R.string.session_relaunching_without_flatpak_info))
                        plan = (plan(appId, settings, userSettings) as? PlanResult.Ready)?.plan?.let(::forSession)
                            ?: throw LaunchException(context.getString(R.string.launch_app_changed))
                        prepareDirs(appId, plan)
                        writeHostCommands(plan)
                        checkBindSources(plan)
                        continue
                    }
                }
                if (triedNewerFlags && (gpuFailed.get() || code != 0)) {
                    // The flags didn't fix it; don't keep them for next time.
                    store.update { it.copy(newerElectronFlags = false) }
                    SessionController.log("[flatdroid] the newer Electron flags didn't help; turned them off again")
                    throw LaunchException(context.getString(R.string.launch_newer_flags_failed, code))
                }
                // Here the app ended by itself (a Stop cancels the session before this). proot exits 0
                // when the app was killed by a signal, so its report decides; otherwise the exit code does.
                ending = ProotExit.ending(signal, code)
                // A terminal app that failed keeps its window until a key, so what it printed can be
                // read. One whose window was closed (its terminal hung up, which apps end on in their
                // own ways) closed as the user asked, whatever it exited with.
                if (tty != null && ending != null && !helpers.holdTerminal(tty, ending!!)) ending = null
                return true
            }
        } finally {
            // Every step runs whatever the one before it did: a failed write or a Stop arriving now
            // (an interrupt ends the first wait it meets) must not leave the app or the helpers running.
            var interrupted = Thread.interrupted()
            fun step(action: () -> Unit) {
                try {
                    action()
                } catch (e: InterruptedException) {
                    interrupted = true
                } catch (e: Exception) {
                    SessionController.log("[flatdroid] while ending the session: $e")
                }
            }
            // The time the app ran, simply from its start to here: nothing is measured while it runs.
            step { runningSince?.let { start -> store.update { it.copy(usedMillis = it.usedMillis + (System.currentTimeMillis() - start)) } } }
            step { dns?.stop() }
            // The sound first: a paused app is let go on below, and what it plays while it ends
            // would be heard though it was muted.
            step { player?.stop() }
            // A paused app ignores the signals that end it: let it go on first.
            step { HiddenPause.detach() }
            step { app?.stop() }
            step { portal?.close() }
            step { mic?.stop() }
            // The helpers first, so a streamer still writing to its pipe fails instead of waiting.
            step { cameraHelpers?.stop() }
            step { camera?.close() ?: streamers.values.forEach { it.close() } }
            SessionController.onDisplayResized = null
            step { helpers.stop() }
            step { endEscaped() }
            // Once nothing of the program's runs any more: what it changed in Alpine.
            if (alpineBefore != null && checkAlpine && runningSince != null) step {
                val started = SystemClock.elapsedRealtime()
                val after = AlpineChanges.snapshot(rootfs, File(env.paths().l2sDir)).stamps
                val suspects = AlpineChanges.changes(alpineBefore.stamps, after)
                // Content checked only for suspects, against apk's own record (read now: a change to it is reported too).
                val installed by lazy { alpinePrograms.installedChecksums() }
                val changes = AlpineChanges.real(suspects, alpineBefore.stamps, after) { rel ->
                    installed[rel]?.let { sum -> runCatching { File(rootfs, rel).inputStream().use { AlpinePackages.matches(sum, it) } }.getOrDefault(false) } == true
                }
                if (changes.size < suspects.size) SessionController.log("[flatdroid] ${suspects.size - changes.size} of Alpine's entries changed only their times; left out")
                SessionController.log("[flatdroid] Alpine's files compared in ${SystemClock.elapsedRealtime() - started} ms")
                if (changes.isNotEmpty()) {
                    AlpineChanges.keep(context, alpineProgram!!.name, changes)
                    SessionController.log("[flatdroid] the program changed ${changes.size} of Alpine's files: ${changes.take(5).joinToString()}")
                }
            }
            // proot leaves the private binds' mount points behind in the shared home, where the
            // user sees them (empty .ssh, .bashrc, .config and the rest). Remove the empty ones.
            // A private home gets them too when it shares hidden files (a temporary one goes anyway).
            if (settings.homeMode == HomeMode.SHARED || settings.homeMode == HomeMode.ANDROID || (settings.shareHiddenFiles && settings.homeMode == HomeMode.PRIVATE)) {
                step { cleanSharedHomeMountPoints(home, plan, existedBefore) }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /**
     * The home-relative mount points of the private binds under the home, deepest first, so parents
     * are empty by their turn. Paths that would leave the home are left out.
     */
    private fun mountPoints(plan: LaunchPlan): List<String> {
        val prefix = GuestPaths.HOME + "/"
        return (plan.binds.appDirs + plan.binds.privateFiles)
            .map { it.second }
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { rel -> rel.split('/').none { it.isEmpty() || it == "." || it == ".." } }
            .distinct()
            .sortedByDescending { rel -> rel.count { it == '/' } }
    }

    /** The mount points and every folder between them and the home ([mountPoints]). */
    private fun mountPointChains(plan: LaunchPlan): Set<String> =
        mountPoints(plan).flatMap { rel -> generateSequence(rel) { it.substringBeforeLast('/', "").ifEmpty { null } }.toList() }.toSet()

    /**
     * proot leaves the private binds' mount points behind (empty files and folders) where the user
     * sees them. Removes the empty ones it made: the mount point, then the folders made for it, up to
     * the home; never one in [existedBefore] (there before the launch, so the user's). Only an empty
     * file or folder goes, so a real file under one of these names (a hand-written `.bashrc`) stays.
     */
    private fun cleanSharedHomeMountPoints(home: File, plan: LaunchPlan, existedBefore: Set<String>) {
        for (rel in mountPoints(plan)) {
            // Not below a link the app left in the home: that would remove an empty file or folder where it leads.
            if (AndroidTarFs.hasLinkBelow(home, rel.substringBeforeLast('/', ""))) continue
            var current: String? = rel
            while (current != null && current !in existedBefore && deleteIfEmpty(File(home, current))) {
                current = current.substringBeforeLast('/', "").ifEmpty { null }
            }
        }
    }

    /** Deletes [file] if it is an empty file or an empty folder (never a link); whether it did. */
    private fun deleteIfEmpty(file: File): Boolean = when {
        AndroidTarFs.isSymlink(file) -> false
        file.isDirectory -> file.list()?.isEmpty() == true && file.delete()
        file.isFile -> file.length() == 0L && file.delete()
        else -> false
    }

    /**
     * The Hardened file check: verifies the app's, its runtime's and their extensions' files
     * against the baseline recorded at install, reporting progress and letting the user skip.
     * Returns true to go ahead; false when files changed, having put the refusal and a reinstall
     * offer in [SessionController].
     */
    private fun checkFiles(appId: String): Boolean {
        val cancelled = AtomicBoolean(false)
        // False once the check is over (done, skipped or stopped): workers still finishing a file
        // must not put "Checking files" back over the launch or the ended session.
        val checking = AtomicBoolean(true)
        SessionController.onSkipFileCheck = { cancelled.set(true) }
        val problems = try {
            IntegrityGuard(context).verify(
                appId,
                onProgress = { ref, done, total ->
                    // Throttled: a per-file UI emission would flood the flow over a large runtime.
                    // 0 of 0: the deployment's files are still being listed.
                    // No counts in the text (the notification and the pinned row would show them cut off or stale): the dial has them, the log gets the total.
                    val detail = if (total == 0) context.getString(R.string.session_listing_files, ref.name)
                        else context.getString(R.string.session_checking_files, ref.name)
                    if (total > 0 && done == total && checking.get()) SessionController.log("[flatdroid] Checked $total files: ${ref.name}")
                    if (checking.get() && !cancelled.get() && (done == total || done % 500 == 0))
                        SessionController.starting(appId, detail, skippable = true, fraction = if (total > 0) done.toFloat() / total else null)
                },
                // Skip, or Stop (which interrupts this thread): either ends the walk and the hashing at once.
                cancelled = { cancelled.get() || Thread.currentThread().isInterrupted },
            )
        } finally {
            checking.set(false)
            SessionController.onSkipFileCheck = null
        }
        // Stopped: end here, before anything starts (the interrupt is still pending for the caller).
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        // Skip drops the check entirely and launches, even if an earlier deployment already flagged.
        if (cancelled.get()) return true
        if (problems.isEmpty()) return true
        for (p in problems) {
            p.tampered.forEach { SessionController.log("[flatdroid] changed in ${p.ref.name}: ${FileIntegrity.escape(it)}") }
            p.removed.forEach { SessionController.log("[flatdroid] removed in ${p.ref.name}: ${FileIntegrity.escape(it)}") }
        }
        val detail = context.getString(R.string.session_files_changed) + "\n" +
            problems.joinToString("\n") { context.getString(R.string.session_files_changed_item, it.ref.name, it.tampered.size, it.removed.size) }
        SessionController.filesChanged(SessionState.FilesChanged(appId, detail))
        return false
    }

    /** Ends processes that left proot ([LeftoverProcesses.killEscaped]), saying so in the session's log. */
    private fun endEscaped() {
        val count = LeftoverProcesses.killEscaped()
        if (count > 0) SessionController.log("[flatdroid] ended $count processes that had left proot")
    }

    /** The session log's line about the app's filter and the helpers' hidden /proc entries. */
    private fun describeFilter(filter: Fdsec.Filter, hidden: Int): String =
        "fdsec: " + (listOf("base list") + listOfNotNull("extra system calls".takeIf { filter.extraSyscalls }, "no Internet".takeIf { filter.noInternet }))
            .joinToString(", ") + "; $hidden helper /proc entries hidden"

    /**
     * How far the device would be turned from its natural orientation to be [landscape] (or
     * portrait), in degrees: 0 or 90 for a phone (naturally portrait), 0 or 270 for a tablet.
     */
    private fun displayRotation(landscape: Boolean): Int {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val turned = (display?.rotation ?: Surface.ROTATION_0) % 2 == 1
        val landscapeNow = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val naturallyLandscape = landscapeNow != turned
        return when {
            landscape == naturallyLandscape -> 0
            naturallyLandscape -> 270
            else -> 90
        }
    }

    /** Each camera as a node at its turned size and its rate, described by where it faces ("Back camera", "Front camera 2"). */
    private fun cameraNodes(cameras: List<AndroidCameras.Camera>, rotations: List<Int>): List<CameraScript.Node> {
        val labels = cameras.map {
            context.getString(
                when (it.facing) {
                    AndroidCameras.Facing.BACK -> R.string.camera_back
                    AndroidCameras.Facing.FRONT -> R.string.camera_front
                    AndroidCameras.Facing.EXTERNAL -> R.string.camera_external
                }
            )
        }
        return cameras.mapIndexed { index, camera ->
            val same = labels.indices.filter { labels[it] == labels[index] }
            val label = if (same.size > 1) "${labels[index]} ${same.indexOf(index) + 1}" else labels[index]
            val (width, height) = camera.turnedSize(rotations[index])
            val location = when (camera.facing) {
                AndroidCameras.Facing.BACK -> CameraScript.Location.BACK
                AndroidCameras.Facing.FRONT -> CameraScript.Location.FRONT
                AndroidCameras.Facing.EXTERNAL -> CameraScript.Location.EXTERNAL
            }
            CameraScript.Node(CameraScript.nodeName(index), label, width, height, camera.frameRate, location)
        }
    }

    /**
     * Starts the camera's helpers ([CameraScript]) in a proot of their own once PipeWire runs, and
     * the [camera]'s streamers writing to their pipes; waits until the nodes exist and the portal has
     * started, so the app sees its cameras from the start. Null when PipeWire didn't start.
     */
    private fun startCamera(camera: CameraSession, filter: Fdsec.Filter): ProotProcess? {
        if (!helpers.waitForFile(File(helpers.pipewireDir, CameraScript.PIPEWIRE_SOCKET), CAMERA_TIMEOUT_MS, null)) {
            SessionController.log("[flatdroid] PipeWire did not start; the app has no camera")
            return null
        }
        val binds = listOf(
            File(sessionDir, "camera.sh").path to CameraScript.GUEST_SCRIPT,
            helpers.pipewireDir.path to CameraScript.GUEST_PIPEWIRE_DIR,
            cameraPipeDir.path to CameraScript.GUEST_PIPE_DIR,
            cameraConfigDir.path to CameraScript.GUEST_CONFIG_DIR,
            helpers.busDaemonDir.path to SessionBus.GUEST_DAEMON_DIR,
        )
        val process = env.start(listOf("/bin/sh", CameraScript.GUEST_SCRIPT), binds, asAppUser = true, filter = filter) { line ->
            if (Fdsec.refused(line)) helpers.filterRefused.set(true)
            SessionController.log("[camera] $line")
        }
        // Links and readiness come through the events pipe, which the reader opens as the script does.
        camera.start()
        if (!camera.awaitReady(CAMERA_TIMEOUT_MS)) SessionController.log("[flatdroid] the camera's helpers did not get ready in time")
        return process
    }

    /** The rate Android's mixer runs at, so PulseAudio's output needs no second resampling. */
    private fun outputRate(): Int =
        context.getSystemService(AudioManager::class.java)?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            ?: DEFAULT_OUTPUT_RATE

    /**
     * The app's home for this launch: a new temporary one, the private one (created with the
     * standard folders the first time), or the shared one (created likewise when missing).
     */
    private fun prepareHome(appId: String, mode: HomeMode): File = when (mode) {
        // Flatdroid asked about the last one before this launch (see MainActivity).
        HomeMode.TEMPORARY -> TempHomes.recreate(context, appId)
        // create() leaves an existing home as it is.
        HomeMode.PRIVATE -> AppData.privateHome(context, appId).also { HomeFolders.create(it) }
        HomeMode.SHARED -> SharedHome.ensure(context)
            ?: throw LaunchException(context.getString(R.string.storage_shared_home_missing, SharedHome.display(SharedHome.dir(context))))
        // Android's storage has its own folders already, so none are made.
        HomeMode.ANDROID -> SharedHome.storageRoot()
    }

    /** Generated for every launch: `user-dirs.dirs` for [home], and the desktop settings ([DesktopConfig]). */
    private fun writeDesktopFiles(home: File, plan: LaunchPlan, user: UserSettings?) {
        File(etcDir, USER_DIRS).writeTextAtomically(HomeFolders.userDirs { File(home, it).isDirectory }, durable = false)
        val dark = isDarkTheme(user)
        AndroidTarFs.deleteTree(desktopDir)
        for (gtk in listOf("gtk-3.0", "gtk-4.0")) {
            File(desktopDir, "$gtk/settings.ini").apply { parentFile!!.mkdirs() }.writeTextAtomically(DesktopConfig.gtkSettings(dark, user?.reduceMotion == true), durable = false)
        }
        // Only KDE runtimes have it.
        val darkScheme = File(plan.runtime.files, DesktopConfig.BREEZE_DARK).takeIf { dark && it.isFile }?.let { runCatching { it.readTextUpTo() }.getOrNull() }
        DesktopConfig.kdeglobals(dark, darkScheme)?.let { File(desktopDir, "kdeglobals").writeTextAtomically(it, durable = false) }
    }

    /** The "run on the host" stand-ins, for whether this app may ask for the host, and Flatdroid's zypak when the plan has it. */
    private fun writeHostCommands(plan: LaunchPlan) {
        val metadata = plan.app.metadata
        val allowed = HostCommands.hostAllowed(metadata.sessionBusPolicy, metadata.context.sockets)
        hostCommandsDir.mkdirs()
        for ((name, text) in listOf(HostCommands.FLATPAK_SPAWN to HostCommands.flatpakSpawn(allowed), HostCommands.HOST_SPAWN to HostCommands.hostSpawn(allowed))) {
            File(hostCommandsDir, name).apply { writeTextAtomically(text, durable = false) }.setExecutable(true, false)
        }
        plan.zypakStandIn?.let { text ->
            File(hostCommandsDir, ZYPAK).apply { writeTextAtomically(text, durable = false) }.setExecutable(true, false)
        }
    }

    /** The theme setting; with System, Android's dark mode now. */
    private fun isDarkTheme(user: UserSettings?) =
        (user?.flatpakTheme ?: FlatpakTheme.SYSTEM).isDark(AndroidAppearance.androidDark(context.resources.configuration))

    /**
     * proot's paths for [appId]: an Alpine program's hard links go to a store of its own, since its
     * root has only Alpine's files of the shared one ([AlpineRoot]).
     */
    private fun prootPaths(appId: String): ProotPaths = env.paths().let { paths ->
        if (alpineRoot == null) paths else paths.withL2sDir(File(File(AppData.root(context), appId), ALPINE_L2S_DIR).apply { mkdirs() }.path)
    }

    /** [appId]'s launch with its [settings] and the [user]'s. */
    private fun plan(appId: String, settings: AppSettings, user: UserSettings?): PlanResult =
        planner.plan(appId, hostDirs(appId, settings, user?.security?.androidFonts ?: SecuritySettings().androidFonts), options(appId, settings, user))

    private fun options(appId: String, settings: AppSettings, user: UserSettings?): LaunchOptions {
        val alpine = alpineRoot
        val app = alpine?.app ?: installation.app(appId)
        val security = user?.security ?: SecuritySettings()
        // Developer settings count only while developer options are on.
        val developerOptions = user?.developerOptions == true
        return LaunchOptions(
            environment = if (developerOptions) settings.environment else emptyMap(),
            newerElectronFlags = settings.newerElectronFlags,
            extraArgs = if (developerOptions) settings.launchArgs else emptyList(),
            forceX11 = settings.forceX11,
            electronSwap = app?.takeIf { alpine == null }?.let { electronSwap(appId).preparedFor(it) },
            wholeDev = settings.wholeDev,
            flatpakInfo = alpine == null && !settings.withoutFlatpakInfo,
            flatpakVersion = if (alpine == null && !settings.withoutFlatpakInfo) flatpakVersion() else null,
            languageTags = if (security.shareLocale) context.resources.configuration.locales.toLanguageTags().split(',').filter { it.isNotBlank() } else emptyList(),
            audio = settings.audioAllowed(app?.metadata?.context?.sockets.orEmpty()),
            network = settings.networkAllowed(app?.metadata?.context?.shared.orEmpty()),
            extraSyscalls = if (developerOptions) settings.blocksExtraSyscalls else security.extraSyscallFilter,
            limitDevices = security.limitDevices,
            reduceMotion = user?.reduceMotion == true,
            terminal = InstalledApps(context).runsInTerminal(appId),
            alpine = alpine,
            softwareRendering = when (settings.rendering) {
                Rendering.SOFTWARE -> true
                Rendering.GL -> false
                // A camera preview needs GL to keep up, and some fail without it: for every app that gets a camera.
                Rendering.AUTOMATIC -> !(app != null && settings.cameraAllowed(app.metadata.context) && AndroidCameras.present(context))
            },
        )
    }

    /** Alpine's Flatpak version, from apk's database (read up to Flatpak's entry); null if it can't be read. */
    private fun flatpakVersion(): String? = runCatching {
        File(rootfs, AlpinePackages.INSTALLED_DB).useLines { AlpinePackages.version(FLATPAK_PACKAGE, it) }
    }.getOrNull()

    /** The app asked for the camera portal in a session without it: the portal runs from its next launch ([AppSettings.videoFor]). */
    private fun cameraPortalSeen(store: AppSettingsStore) {
        runCatching { store.update { it.copy(cameraPortalSeen = true) } }
        SessionController.log("[flatdroid] the app asked for the camera portal; it gets cameras there from its next launch")
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, R.string.camera_portal_next_launch, Toast.LENGTH_LONG).show() }
    }

    /** The app read a camera's or the screen's device: [AppSettings.videoFor] keeps it there. */
    private fun cameraDeviceUsed(store: AppSettingsStore) {
        runCatching { store.update { it.copy(cameraDeviceUsed = true) } }
    }

    private fun hostDirs(appId: String, settings: AppSettings, androidFonts: Boolean): HostDirs {
        val temporary = settings.homeMode == HomeMode.TEMPORARY
        // In Temporary Home mode the app's settings and cache are new at every launch too.
        val appData = if (temporary) TempHomes.root(context) else File(AppData.root(context), appId)
        val cache = if (temporary) File(appData, "cache") else File(AppData.cacheRoot(context), appId)
        val tmpBase = File(AppData.appTmpDir(context), appId)
        val launcher = AppData.launcherFiles(context)
        val home = GuestPaths.HOME
        val varApp = "$home/.var/app/$appId"
        // A shared home's dot-files would be every app's (and readable by other Android apps): the app's
        // own instead. The developer setting shares one set across the apps that opt in, in any home.
        val dotFiles = when {
            settings.shareHiddenFiles -> AppData.sharedDotfilesDir(context)
            settings.homeMode != HomeMode.SHARED && settings.homeMode != HomeMode.ANDROID -> null
            else -> File(appData, AppFiles.DOT_FILES_DIR)
        }
        return HostDirs(
            root = File(AppData.rootsDir(context), appId),
            tmp = File(tmpBase, "tmp"),
            shm = File(tmpBase, "shm"),
            home = when (settings.homeMode) {
                HomeMode.SHARED -> SharedHome.dir(context)
                HomeMode.PRIVATE -> AppData.privateHome(context, appId)
                HomeMode.TEMPORARY -> TempHomes.home(context)
                HomeMode.ANDROID -> SharedHome.storageRoot()
            },
            appDirs = listOf(
                File(appData, "config") to "$home/.config",
                File(appData, "local") to "$home/.local",
                File(appData, "pki") to "$home/.pki",
                File(appData, "var") to varApp,
                cache to "$home/.cache",
                // Flatpak's /var/config and the rest are links into these (RootLayout).
                cache to "$varApp/cache",
            ) + dotFiles?.let { dir -> AppFiles.PRIVATE_DOT_DIRS.map { File(dir, it) to "$home/$it" } }.orEmpty(),
            privateFiles = dotFiles?.let { dir -> AppFiles.PRIVATE_DOT_FILES.map { File(dir, it) to "$home/$it" } }.orEmpty(),
            // These /etc files are overridden at launch, so the integrity check leaves the
            // deployment's copies out (IntegrityGuard.OVERRIDDEN_ETC must list the same names).
            etc = LaunchPlanner.ETC_OVERRIDES.associateWithTo(linkedMapOf()) { name ->
                when (name) {
                    "hosts" -> launcher.hosts
                    "passwd" -> launcher.passwd
                    "group" -> launcher.group
                    else -> File(etcDir, name) // resolv.conf and the machine-id's copy, written per launch
                }
            },
            x11Sockets = helpers.x11Dir,
            waylandSockets = helpers.waylandDir,
            pulseSockets = helpers.pulseDir,
            fontDirs = launcher.fontDirs,
            hostFonts = SessionHelpers.ANDROID_FONTS.takeIf { androidFonts && it.isDirectory },
            desktopConfig = desktopDir,
            userDirs = File(etcDir, USER_DIRS),
            hostCommands = hostCommandsDir,
            zypakStandIn = File(hostCommandsDir, ZYPAK),
            sdcard = SharedHome.storageRoot().takeIf { settings.sdcard && settings.homeMode != HomeMode.ANDROID && SharedHome.broadAccess(context) },
            sessionBus = helpers.busProxyDir,
            // Under the app's cache, so clearing caches drops it (fontconfig then regenerates it).
            runtimeCache = File(cache, RUNTIME_CACHE_DIR),
            runtimeDir = File(tmpBase, "run"),
            mergedDirs = File(tmpBase, "merged"),
            userId = Os.getuid(),
            // Also under the app's cache: cleared with it, and only this app's own launches read it.
            ldCache = File(cache, LD_CACHE_DIR),
        )
    }

    /** Rebuilds the app's root and private /tmp, and creates its data directories. */
    private fun prepareDirs(appId: String, plan: LaunchPlan) {
        val root = File(plan.binds.root)
        AndroidTarFs.deleteTree(root)
        val mountPoints = listOf("usr", "app", "etc", "dev", "proc", "sys", "tmp") +
            listOfNotNull(GuestPaths.PULSE_DIR.takeIf { plan.binds.pulseSockets != null }, GuestPaths.HOME).map { it.removePrefix("/") }
        for (dir in mountPoints) File(root, dir).mkdirs()
        for ((_, guest) in plan.binds.appDirs) {
            if (!guest.startsWith(GuestPaths.HOME + "/")) File(root, guest.removePrefix("/")).mkdirs()
        }
        if (!plan.binds.wholeDev) DevNodes.createMountPoints(root, plan.binds.dev)
        createCameraMountPoints(plan.binds)
        val links = RootLayout.links(appId, plan.usrLinks, plan.binds.wholeDev)
        for ((path, target) in links) Os.symlink(target, File(root, path).apply { parentFile!!.mkdirs() }.path)
        plan.flatpakInfo?.let { File(root, ".flatpak-info").writeText(it) }
        File(root, Fdsec.APP_PATH.removePrefix("/")).apply { parentFile!!.mkdirs() }.createNewFile()

        AndroidTarFs.deleteTree(File(AppData.appTmpDir(context), appId))
        File(plan.binds.tmp).mkdirs()
        File(plan.binds.shm).mkdirs()
        prepareRuntimeDir(appId, plan)
        // The extensions' merged folders: links only, rebuilt with the rest of this folder each launch.
        for (merged in plan.mergedDirs) {
            merged.dir.mkdirs()
            for ((name, target) in merged.links) Os.symlink(target, File(merged.dir, name).path)
        }
        // The app can write these, so it can replace one with a link (see checkBindSources).
        for (dir in listOf(File(plan.binds.home)) + plan.binds.appDirs.map { File(it.first) }) {
            if (AndroidTarFs.isSymlink(dir)) dir.delete()
            dir.mkdirs()
        }
        for ((host, _) in plan.binds.privateFiles) {
            val file = File(host)
            if (AndroidTarFs.isSymlink(file)) file.delete()
            if (!file.exists()) file.apply { parentFile!!.mkdirs() }.createNewFile()
        }
        // Flatpak makes the app's XDG directories before it starts; some apps write into them
        // without making them first.
        plan.binds.appDirs.firstOrNull { it.second == "${GuestPaths.HOME}/.var/app/$appId" }?.let { (host, _) ->
            // A link the app left in its own folder is removed first: made through it, they would land wherever it leads.
            for (dir in listOf("config", "data", ".local/state")) AndroidTarFs.makeDirsBelow(File(host), dir)
        }
        // What the root's links lead to in the app's own directories (/var/tmp's folder in its cache, say).
        for (target in links.values) {
            val owner = plan.binds.appDirs.filter { target.startsWith(it.second + "/") }.maxByOrNull { it.second.length } ?: continue
            AndroidTarFs.makeDirsBelow(File(owner.first), target.removePrefix(owner.second + "/"))
        }
        // Writable scratch for the runtime caches shadowed over the deployment (their guest paths
        // are under /usr, so the loop below skips making mount points for them — the runtime already
        // has those directories). Persisted across launches, so fontconfig regenerates them once.
        for ((host, guest) in plan.binds.extra) {
            if (guest in LaunchPlanner.RUNTIME_CACHE_DIRS) File(host).mkdirs()
        }
        plan.ldCache?.let(::prepareLdCache)
        // Mount points for the further binds: in the root, or in the app's own directory they fall in.
        for ((host, guest) in plan.binds.extra) {
            if (guest == "/app" || guest.startsWith("/app/") || guest.startsWith("/usr/")) continue
            val owner = plan.binds.appDirs.filter { guest.startsWith(it.second + "/") }.maxByOrNull { it.second.length }
            val rel = guest.removePrefix(if (owner != null) owner.second + "/" else "/")
            val target = File(owner?.first ?: root.path, rel)
            // The app's own folder can hold a link it made: removed, not followed. The root is Flatdroid's alone.
            if (owner != null) {
                AndroidTarFs.makeDirsBelow(File(owner.first), rel.substringBeforeLast('/', ""))
                if (AndroidTarFs.isSymlink(target)) target.delete()
            }
            if (target.exists()) continue
            if (File(host).isDirectory) target.mkdirs() else target.apply { parentFile?.mkdirs() }.createNewFile()
        }
    }

    /**
     * Keeps only [ld]'s directory under its base (older ones were for other commits). One to build
     * gets the configuration and an empty cache file to bind at /etc/ld.so.cache, which `ldconfig`
     * replaces. The app can write here, so links in the way are removed rather than followed.
     */
    private fun prepareLdCache(ld: LdCache) {
        val base = ld.dir.parentFile!!
        if (AndroidTarFs.isSymlink(base)) base.delete()
        base.listFiles()?.filter { it.name != ld.dir.name }?.forEach { AndroidTarFs.deleteTree(it) }
        if (!ld.generate) return
        AndroidTarFs.deleteTree(ld.dir)
        ld.dir.mkdirs()
        ld.confFile.writeTextAtomically(ld.conf, durable = false)
        ld.cache.createNewFile()
    }

    /**
     * The app's XDG_RUNTIME_DIR (`/run/user/<uid>`), laid out as Flatpak lays it out: `app/<ID>`
     * for the app's own sockets (an editor's single-instance socket, say), and links to the
     * session bus, /.flatpak-info and the sound server's directory where the app has them.
     */
    private fun prepareRuntimeDir(appId: String, plan: LaunchPlan) {
        val guest = GuestPaths.runtimeDir(Os.getuid())
        val dir = plan.binds.extra.firstOrNull { it.second == guest }?.let { File(it.first) } ?: return
        File(dir, "app/$appId").mkdirs()
        // Relative, as Flatpak's: proot resolves them inside the root.
        if (plan.binds.extra.any { it.second == GuestPaths.SESSION_BUS_DIR }) {
            Os.symlink("../..${GuestPaths.SESSION_BUS_DIR.removePrefix("/run")}/${GuestPaths.SESSION_BUS_SOCKET}", File(dir, "bus").path)
        }
        if (plan.flatpakInfo != null) Os.symlink("../../../.flatpak-info", File(dir, "flatpak-info").path)
        if (plan.binds.pulseSockets != null) Os.symlink("../..${GuestPaths.PULSE_DIR.removePrefix("/run")}", File(dir, "pulse").path)
    }

    /**
     * `/etc/localtime` and `/etc/timezone` for this launch: Android's time zone with [share], as
     * Flatpak gives the host's, else UTC (no hint at the device's location). localtime is a copy of
     * the zone's file from the runtime (or its time zone extension), not a link, since binds are
     * files; an unknown zone falls back to UTC.
     */
    private fun writeTimeZone(plan: LaunchPlan, share: Boolean) {
        val wanted = TimeZone.getDefault().id.takeIf { share && ZONE_ID.matches(it) }
        val zoneDirs = plan.binds.extensions.filter { it.second == "/usr/share/zoneinfo" }.map { File(it.first) } +
            File(plan.runtime.files, "share/zoneinfo")
        fun zoneFile(id: String) = zoneDirs.map { File(it, id) }.firstOrNull { it.isFile }
        val (id, file) = wanted?.let { zone -> zoneFile(zone)?.let { zone to it } } ?: (UTC to zoneFile(UTC))
        val bytes = file?.takeIf { it.length() <= MAX_ZONE_BYTES }?.let { runCatching { it.readBytes() }.getOrNull() } ?: ByteArray(0)
        File(etcDir, LaunchPlanner.LOCALTIME).writeAtomically(durable = false) { it.write(bytes) }
        File(etcDir, LaunchPlanner.TIMEZONE).writeTextAtomically("$id\n", durable = false)
    }

    /**
     * Every host path bound into the root must be what Flatdroid named, with no link anywhere in
     * it. Binds are writable: from inside, an app can remove a bound directory and put a link in
     * its place, and proot would follow that link when binding it at the next launch (a home
     * linked to Flatdroid's own files, say). The paths are built from resolved directories, so a
     * resolved path that differs means something was replaced.
     */
    private fun checkBindSources(plan: LaunchPlan) {
        val b = plan.binds
        val sources = listOfNotNull(b.root, b.runtimeFiles, b.appFiles, b.etc(), b.tmp, b.shm, b.x11Sockets, b.pulseSockets, b.waylandSockets, b.home, b.emptyDir, prootPaths(plan.appId).l2sDir) +
            b.etc.values + b.extensions.map { it.first } + b.appDirs.map { it.first } + b.privateFiles.map { it.first } + b.extra.map { it.first } +
            b.cameraBinds.map { it.first }
        for (path in sources) {
            val file = File(path)
            if (!file.exists() || file.canonicalPath != file.absolutePath) {
                throw LaunchException(context.getString(R.string.launch_bind_replaced, path))
            }
        }
    }

    /**
     * The app's side of the session's files, fresh for every launch: the shared /etc files (an app
     * could have changed them, since binds are writable), a copy of the machine-id, resolv.conf for
     * the current network, and the camera's script, pipes and device-mode entries.
     */
    private fun writeSessionFiles(cameraNodes: List<CameraScript.Node>, cameraDevices: List<Fdv4l2.Device>, androidClasses: List<String>) {
        val launcher = AppData.launcherFiles(context)
        launcher.write()
        File(etcDir, "machine-id").writeTextAtomically(launcher.machineId.readText(), durable = false)
        File(etcDir, "resolv.conf").writeTextAtomically(env.resolvConf(), durable = false)
        for (dir in listOf(cameraPipeDir, cameraConfigDir, videoDir, cameraSysDir, emptyDir)) {
            AndroidTarFs.deleteTree(dir)
            dir.mkdirs()
        }
        val cameraScript = File(sessionDir, "camera.sh")
        if (cameraNodes.isEmpty()) {
            cameraScript.delete()
        } else {
            cameraScript.writeTextAtomically(CameraScript.text(cameraNodes), durable = false)
            for ((path, text) in CameraScript.CONFIG_FILES) {
                File(cameraConfigDir, path).apply { parentFile!!.mkdirs() }.writeTextAtomically(text, durable = false)
            }
            for (node in cameraNodes) Os.mkfifo(File(cameraPipeDir, node.name).path, 0x180) // 0600
            Os.mkfifo(File(cameraPipeDir, CameraScript.EVENTS).path, 0x180)
        }
        if (cameraDevices.isEmpty()) {
            cameraList.delete()
        } else {
            cameraList.writeTextAtomically(Fdv4l2.camerasList(cameraDevices, cameraFormats()), durable = false)
            for (device in cameraDevices) {
                for (name in listOf("video", "media", "control")) Os.mkfifo(File(videoDir, "$name${device.index}").path, 0x180)
            }
            for ((path, entry) in Fdv4l2.tree(cameraDevices, androidClasses)) {
                val file = File(cameraSysDir, path)
                file.parentFile!!.mkdirs()
                when (entry) {
                    Fdv4l2.Entry.Dir -> file.mkdirs()
                    is Fdv4l2.Entry.Text -> file.writeText(entry.text)
                    is Fdv4l2.Entry.Link -> Os.symlink(entry.target, file.path)
                }
            }
        }
    }

    /**
     * Device mode's binds for [devices] ([Fdv4l2]): each camera's video and media device and control
     * pipe, the cameras list, and the `/sys` and udev entries, with Android's `/sys/class` beside
     * the cameras' own for [androidClasses]; none without devices.
     */
    private fun cameraBinds(devices: List<Fdv4l2.Device>, androidClasses: Boolean): List<Pair<String, String>> {
        if (devices.isEmpty()) return emptyList()
        return devices.flatMap { device ->
            val i = device.index
            listOf(
                File(videoDir, "video$i").path to Fdv4l2.devicePath(i),
                File(videoDir, "media$i").path to Fdv4l2.mediaPath(i),
                File(videoDir, "control$i").path to Fdv4l2.controlPath(i),
            )
        } + (cameraList.path to Fdv4l2.CAMERAS_PATH) +
            (Fdv4l2.TREE_BINDS + Fdv4l2.charBinds(devices)).map { (relative, guest) -> File(cameraSysDir, relative).path to guest } +
            listOfNotNull(("/sys/class" to Fdv4l2.ANDROID_CLASSES).takeIf { androidClasses })
    }

    /**
     * Entries in the app's root where device mode binds, so apps listing a folder find them (proot
     * lists only what is there): the devices in /dev, and the `/sys` folders while /sys is limited.
     * With the whole of /dev or /sys, which come from Android, listing misses them (verify). The
     * devices' entries are pipes, as what is bound there: a listing gives each entry's own type, and
     * apps that list only special files (not regular ones) in /dev would skip an empty file.
     */
    private fun createCameraMountPoints(binds: FlatpakBinds) {
        val root = File(binds.root)
        for ((_, guest) in binds.cameraBinds) {
            val target = File(root, guest.removePrefix("/"))
            when {
                guest.startsWith("/dev/") -> if (!binds.wholeDev && !target.exists()) {
                    target.parentFile?.mkdirs()
                    Os.mkfifo(target.path, 0x180) // 0600
                }
                guest.startsWith("/sys/") -> if (binds.sys != null) target.mkdirs()
                guest.startsWith("/run/") -> target.mkdirs()
            }
        }
    }

    /**
     * The formats offered, in order: [Fdv4l2.DEFAULT_FORMATS]; debug builds read another order from
     * `debug-camera-formats` in the cache (comma-separated, for measuring one format at a time).
     */
    private fun cameraFormats(): List<Fdv4l2.Format> {
        if (!BuildConfig.DEBUG) return Fdv4l2.DEFAULT_FORMATS
        val override = File(context.cacheDir, "debug-camera-formats").takeIf { it.isFile }?.readText()
            ?.split(',')?.mapNotNull { Fdv4l2.Format.of(it.trim()) }?.takeIf { it.isNotEmpty() }
        return override ?: Fdv4l2.DEFAULT_FORMATS
    }

    private fun describe(result: PlanResult): String = when (result) {
        is PlanResult.NotInstalled -> context.getString(R.string.launch_not_installed, result.appId)
        is PlanResult.RuntimeMissing -> context.getString(R.string.launch_runtime_missing, result.runtime)
        is PlanResult.NoCommand -> context.getString(R.string.launch_no_command, result.appId)
        is PlanResult.WrongArchitecture -> context.getString(R.string.launch_wrong_arch, result.path, result.arch, arch)
        else -> result.toString()
    }

    private companion object {
        /** Alpine's Flatpak package, whose version /.flatpak-info gives. */
        const val FLATPAK_PACKAGE = "flatpak"
        /** An Alpine program's own link2symlink store, in its data folder ([prootPaths]). */
        const val ALPINE_L2S_DIR = "l2s"
        /** An Alpine program's bwrap stand-in, beside the host-command stand-ins. */
        const val ALPINE_BWRAP = "alpine-bwrap"
        const val AUDIO_TIMEOUT_MS = 10_000L
        const val BUS_TIMEOUT_MS = 10_000L
        /** Flatdroid's zypak, beside the "run on the host" stand-ins. */
        private const val ZYPAK = "zypak"
        const val CAMERA_TIMEOUT_MS = 20_000L
        const val PRELOAD = "LD_PRELOAD"
        const val DEFAULT_OUTPUT_RATE = 48_000
        const val USER_DIRS = "user-dirs.dirs"
        const val UTC = "UTC"
        /** An IANA zone ID, as Android reports them ("America/New_York", "Etc/GMT+5"): no `..`, no leading `/`. */
        val ZONE_ID = Regex("[A-Za-z0-9_+-]+(/[A-Za-z0-9_+-]+)*")
        /** Zone files are a few KB. */
        const val MAX_ZONE_BYTES = 1L shl 16
        /** Hidden subdirectory of the app's cache holding the runtime-cache scratch overlays. */
        const val RUNTIME_CACHE_DIR = ".flatdroid-runtime"
        const val LD_CACHE_DIR = ".flatdroid-ld"
    }
}

