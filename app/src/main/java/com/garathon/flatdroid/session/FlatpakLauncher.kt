package com.garathon.flatdroid.session

import com.garathon.flatdroid.FlatdroidApplication
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Build
import android.os.FileObserver
import android.system.Os
import android.view.Display
import android.view.Surface
import com.garathon.flatdroid.R
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.alpine.ResolvConfRefresher
import com.garathon.flatdroid.alpine.GuestFiles
import com.garathon.flatdroid.audio.AudioPlayer
import com.garathon.flatdroid.audio.MicStreamer
import com.garathon.flatdroid.camera.AndroidCameras
import com.garathon.flatdroid.camera.CameraScript
import com.garathon.flatdroid.camera.CameraStreamer
import com.garathon.flatdroid.flatpak.AndroidLinkFs
import com.garathon.flatdroid.flatpak.AppData
import com.garathon.flatdroid.flatpak.FlatpakInstallation
import com.garathon.flatdroid.flatpak.IntegrityGuard
import com.garathon.flatdroid.launcher.DesktopConfig
import com.garathon.flatdroid.launcher.Electron
import com.garathon.flatdroid.launcher.ElectronSwap
import com.garathon.flatdroid.launcher.HostDirs
import com.garathon.flatdroid.launcher.LaunchOptions
import com.garathon.flatdroid.launcher.LaunchPlan
import com.garathon.flatdroid.launcher.LaunchPlanner
import com.garathon.flatdroid.launcher.PlanResult
import com.garathon.flatdroid.launcher.VncPassword
import com.garathon.flatdroid.proot.AlpineBinds
import com.garathon.flatdroid.proot.DevNodes
import com.garathon.flatdroid.proot.Fdsec
import com.garathon.flatdroid.proot.GuestPaths
import com.garathon.flatdroid.proot.HelperProcesses
import com.garathon.flatdroid.proot.ProotCommand
import com.garathon.flatdroid.proot.ProotProcess
import com.garathon.flatdroid.proot.ProotRunner
import com.garathon.flatdroid.setup.AndroidTarFs
import com.garathon.flatdroid.settings.AppSettings
import com.garathon.flatdroid.settings.AudioOutputMode
import com.garathon.flatdroid.settings.FlatpakTheme
import com.garathon.flatdroid.settings.HomeMode
import com.garathon.flatdroid.settings.Resampler
import com.garathon.flatdroid.settings.SecuritySettings
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.setup.SetupController
import com.garathon.flatdroid.setup.deleteTree
import com.garathon.flatdroid.storage.AppFiles
import com.garathon.flatdroid.storage.HomeFolders
import com.garathon.flatdroid.storage.SharedHome
import com.garathon.flatdroid.storage.StorageTaskService
import com.garathon.flatdroid.storage.StorageTasks
import com.garathon.flatdroid.storage.TempHomes
import com.garathon.flatdroid.util.readTextUpTo
import com.garathon.flatdroid.util.httpDownload
import com.garathon.flatdroid.util.sha256
import com.garathon.flatdroid.util.writeAtomically
import com.garathon.flatdroid.util.writeTextAtomically
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A launch that can't go ahead; [message] is shown to the user. */
class LaunchException(message: String) : IOException(message)

/**
 * Runs one Flatpak session: a fresh Xvnc, Openbox and PulseAudio in Alpine, then the app in its
 * own root, with the audio player and, when the app may use it, the microphone. Blocking;
 * interrupting the thread stops the app and the helpers.
 */
class FlatpakLauncher(private val context: Context) {
    private val env = AlpineEnvironment.get(context)
    private val arch = env.arch
    private val installation = FlatpakInstallation(env.flatpakDir, arch)
    private val planner = LaunchPlanner(installation, arch, File(env.paths().l2sDir), AndroidLinkFs::readlink, DevNodes::hostHas)
    private val sessionDir = AppData.sessionDir(context)
    private val x11Dir = File(sessionDir, "x11")
    private val pulseDir = File(sessionDir, "pulse")
    private val vncDir = File(sessionDir, "vnc")
    private val etcDir = File(sessionDir, "etc")
    private val desktopDir = File(sessionDir, "xdg")
    // The daemon's socket stays among the helpers; the app's root gets only the proxy's directory.
    private val busDaemonDir = File(sessionDir, "dbus")
    private val busProxyDir = File(sessionDir, "bus")
    private val pipewireDir = File(sessionDir, "pipewire")
    private val cameraPipeDir = File(sessionDir, "camera")
    private val cameraConfigDir = File(sessionDir, "camera-config")
    /** Bound over the helpers' /proc entries in the app's root, when they are hidden. */
    private val emptyDir = File(sessionDir, "empty")

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
        val needed = planner.plan(appId, hostDirs(appId), options(appId)) as? PlanResult.ElectronSwapNeeded ?: return
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
                SessionController.preparingElectron(appId, detail, fraction)
            }
        }
    }

    /**
     * Launches [appId] and returns when it exits. Returns false without launching if the user
     * must first agree to an Electron swap (the offer is then in [SessionController]).
     */
    fun run(appId: String, micAnswer: Boolean? = null): Boolean {
        SessionController.starting(appId, context.getString(R.string.session_reading_metadata), clearLog = true)
        // Every Flatpak could otherwise open Flatdroid's memory through /proc (same user).
        if (!FlatdroidApplication.nonDumpable) throw LaunchException(context.getString(R.string.launch_dumpable))
        val store = AppData.settings(context, appId)
        var settings = store.load()
        // A launch in Temporary Home mode replaces the temporary home, so not while it is exported.
        if (StorageTasks.busyWith(appId) || (settings.homeMode == HomeMode.TEMPORARY && StorageTasks.exportingTempHome(StorageTaskService.pendingTasks(context)))) {
            throw LaunchException(context.getString(R.string.launch_storage_busy))
        }
        var plan = when (val result = planner.plan(appId, hostDirs(appId), options(appId))) {
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
        store.update { it.copy(lastOpened = System.currentTimeMillis()) }

        if (SetupController.settings(context).load()?.security?.checkFilesBeforeLaunch == true && !checkFiles(appId)) return false

        SessionController.starting(appId, context.getString(R.string.session_preparing_files))
        val home = prepareHome(appId, settings.homeMode)
        // Before prepareDirs, which makes the mount points by what they are bound from.
        writeDesktopFiles(home, plan)
        prepareDirs(appId, plan)
        val userSettings = SetupController.settings(context).load()
        val security = userSettings?.security ?: SecuritySettings()
        val password = VncPassword.random()
        val audio = plan.binds.pulseSockets != null
        if (settings.microphone && !MicStreamer.hasPermission(context)) {
            // Android's permission was revoked since the microphone was turned on: off again, as
            // when Android refuses it in the app's settings.
            settings = settings.copy(microphone = false)
            store.update { it.copy(microphone = false) }
            SessionController.log("[flatdroid] Android's microphone permission was revoked; the app's microphone is off")
        }
        val microphone = audio && settings.microphone
        if (settings.camera && !AndroidCameras.hasPermission(context)) {
            // As for the microphone: Android's permission was revoked since the camera was turned on.
            settings = settings.copy(camera = false)
            store.update { it.copy(camera = false) }
            SessionController.log("[flatdroid] Android's camera permission was revoked; the app's camera is off")
        }
        val cameras = if (settings.camera) AndroidCameras.list(context, settings.cameraResolution, settings.cameraFrameRate) else emptyList()
        if (settings.camera && cameras.isEmpty()) SessionController.log("[flatdroid] the app's camera is on, but Android has no camera Flatdroid can use")
        // Upright for the app's display: a landscape display as the device held landscape, a
        // portrait one as held portrait (Flatdroid itself is in portrait at launch whatever the app).
        val displayRotation = displayRotation(landscape = settings.width > settings.height)
        val cameraRotations = cameras.map { it.rotation(displayRotation) }
        val cameraNodes = cameraNodes(cameras, cameraRotations)
        val outputRate = outputRate()
        val micRate = MicStreamer.rate()
        writeSessionFiles(
            password, titleBars = userSettings?.windowTitleBars ?: true,
            pulseScript = if (audio) PulseScript.text(outputRate, microphone, micRate) else null,
            busArgs = SessionBus.proxyArgs(appId, plan.app.metadata.sessionBusPolicy, camera = cameraNodes.isNotEmpty()),
            cameraNodes = cameraNodes,
        )
        // After both, which create the directories it checks: they live in the cache, which Android may
        // clear at any time, and a fresh install has none yet.
        checkBindSources(plan)

        SessionController.starting(appId, context.getString(R.string.session_starting_display))
        val frameRate = userSettings?.frameRate ?: UserSettings.DEFAULT_FRAME_RATE
        val mic = if (!microphone) null else {
            MicrophoneSession(context, appId, File(pulseDir, PulseScript.MIC_PIPE), micRate, settings.alwaysAllowMicrophone, micAnswer)
        }
        SessionController.onMicrophoneAnswer = mic?.let { it::answer }
        val resampler = userSettings?.resampler ?: Resampler.FAST
        // Any of the proots below: fdsec refused to start its command, so nothing ran in it.
        val filterRefused = AtomicBoolean(false)
        val helpers = startHelpers(
            SessionScript.command(
                settings.geometry, frameRate, audio, microphone = mic != null, resampler, sessionBus = true,
                camera = cameraNodes.isNotEmpty(), clipboard = settings.sharesClipboard,
            ),
            audio,
            Fdsec.Filter(extraSyscalls = security.extraSyscallFilter),
        ) { line ->
            if (Fdsec.refused(line)) filterRefused.set(true)
            SessionScript.micUsers(line)?.let { mic?.onUsers(it) }
        }
        var app: ProotProcess? = null
        var player: AudioPlayer? = null
        var cameraHelpers: ProotProcess? = null
        val streamers = cameraNodes.indices.associate {
            cameraNodes[it].name to CameraStreamer(context, cameras[it], File(cameraPipeDir, cameraNodes[it].name), cameraRotations[it])
        }
        // A network change during the session (Wi-Fi to mobile data, Private DNS) reaches the app's resolv.conf.
        val dns = ResolvConfRefresher(context, listOf(File(etcDir, "resolv.conf"))).apply { start() }
        try {
            try {
                waitForFile(File(x11Dir, SessionScript.X_SOCKET), helpers, DISPLAY_TIMEOUT_MS, context.getString(R.string.launch_display_failed))
            } catch (e: LaunchException) {
                throw if (filterRefused.get()) LaunchException(context.getString(R.string.launch_filter_refused)) else e
            }
            if (!waitForFile(File(busProxyDir, SessionBus.SOCKET), helpers, BUS_TIMEOUT_MS, null)) {
                SessionController.log("[flatdroid] the session bus did not start; the app has none")
            } else if (streamers.isNotEmpty()) {
                cameraHelpers = startCamera(helpers, streamers, Fdsec.Filter(extraSyscalls = security.extraSyscallFilter, noInternet = true), filterRefused)
            }
            if (!audio) {
                SessionController.log("[flatdroid] sound is off for this app; PulseAudio isn't started")
            } else if (!waitForFile(File(pulseDir, PulseScript.NATIVE_SOCKET), helpers, AUDIO_TIMEOUT_MS, null)) {
                SessionController.log("[flatdroid] PulseAudio did not start; the app has no audio")
            } else {
                player = AudioPlayer(
                    File(pulseDir, PulseScript.SIMPLE_SOCKET), outputRate,
                    latencyMs = userSettings?.audioLatencyMs ?: UserSettings.DEFAULT_AUDIO_LATENCY_MS,
                    outputMode = userSettings?.audioOutputMode ?: AudioOutputMode.POWER_SAVING,
                )
                player.start()
            }
            // The helpers run under proot, which keeps them dumpable: hide what runs now from the app.
            val hidden = if (security.hideHelperProcesses) HelperProcesses.procPaths(listOfNotNull(helpers.pid, cameraHelpers?.pid)) else emptyList()
            fun hideHelpers(plan: LaunchPlan) =
                if (hidden.isEmpty()) plan else plan.copy(binds = plan.binds.copy(hiddenProc = hidden, emptyDir = emptyDir.path))
            plan = hideHelpers(plan)
            SessionController.log("[flatdroid] " + describeFilter(plan.filter, hidden.size))
            var triedNewerFlags = false
            var triedWithoutFlatpakInfo = false
            while (true) {
                SessionController.log("[flatdroid] ${plan.command.joinToString(" ")}")
                for ((host, guest) in plan.binds.extensions) SessionController.log("[flatdroid] extension $host -> $guest")
                val gpuFailed = AtomicBoolean(false)
                val kdeHostProcess = AtomicBoolean(false)
                val killedBySignal = AtomicBoolean(false)
                // The app's own: a helper's fdsec refusing later (sound, say) doesn't make the app's exit a failure.
                val appRefused = AtomicBoolean(false)
                app = ProotRunner.start(
                    ProotCommand.flatpak(env.paths(), plan.binds, plan.environment, plan.command, plan.filter),
                    File(plan.binds.root),
                ) { line ->
                    if (Fdsec.refused(line)) appRefused.set(true)
                    if (Electron.GPU_FAILURE in line) gpuFailed.set(true)
                    if (LaunchPlanner.KDE_HOST_PROCESS_WARNING in line) kdeHostProcess.set(true)
                    if (LaunchPlanner.PROOT_KILLED_BY_SIGNAL in line) killedBySignal.set(true)
                    SessionController.log(line)
                }
                SessionController.running(appId, password)
                val code = app.waitFor()
                SessionController.log("[flatdroid] the app exited ($code)")
                if (appRefused.get()) throw LaunchException(context.getString(R.string.launch_filter_refused))
                if (plan.isElectron && !settings.newerElectronFlags && gpuFailed.get() && !triedNewerFlags) {
                    // Newer Electron: relaunch once with its extra flags, and keep them if they help.
                    triedNewerFlags = true
                    settings = settings.copy(newerElectronFlags = true)
                    store.update { it.copy(newerElectronFlags = true) }
                    SessionController.log("[flatdroid] relaunching with ${Electron.NEWER_FLAGS.joinToString(" ")}")
                    SessionController.starting(appId, context.getString(R.string.session_relaunching_newer_flags))
                    plan = (planner.plan(appId, hostDirs(appId), options(appId)) as? PlanResult.Ready)?.plan?.let(::hideHelpers)
                        ?: throw LaunchException(context.getString(R.string.launch_app_changed))
                    // The app has run, so it may have replaced its own directories with links meanwhile.
                    prepareDirs(appId, plan)
                    checkBindSources(plan)
                    continue
                }
                if (plan.flatpakInfo != null && kdeHostProcess.get()) {
                    // KDE's frameworks would run processes on the host: no /.flatpak-info for this
                    // app from now on, and if it failed (Konsole crashes), relaunch it without.
                    settings = settings.copy(withoutFlatpakInfo = true)
                    store.update { it.copy(withoutFlatpakInfo = true) }
                    SessionController.log("[flatdroid] KDE tried to run processes on the host; this app runs without /.flatpak-info from now on")
                    if ((code != 0 || killedBySignal.get()) && !triedWithoutFlatpakInfo) {
                        triedWithoutFlatpakInfo = true
                        SessionController.starting(appId, context.getString(R.string.session_relaunching_without_flatpak_info))
                        plan = (planner.plan(appId, hostDirs(appId), options(appId)) as? PlanResult.Ready)?.plan?.let(::hideHelpers)
                            ?: throw LaunchException(context.getString(R.string.launch_app_changed))
                        prepareDirs(appId, plan)
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
                if (code != 0 && plan.app.metadata.context.waylandOnly) {
                    // Most likely why: the app asks only for Wayland, and Flatdroid has only X11.
                    throw LaunchException(context.getString(R.string.launch_needs_wayland, code))
                }
                return true
            }
        } finally {
            dns.stop()
            app?.stop()
            player?.stop()
            mic?.stop()
            // The helpers first, so a streamer still writing to its pipe fails instead of waiting.
            cameraHelpers?.stop()
            streamers.values.forEach { it.close() }
            SessionController.onMicrophoneAnswer = null
            helpers.stop()
            // proot leaves the private binds' mount points behind in the shared home, where the
            // user sees them (empty .ssh, .bashrc, .config and the rest). Remove the empty ones.
            // A private home gets them too when it shares hidden files (a temporary one goes anyway).
            if (settings.homeMode == HomeMode.SHARED || (settings.shareHiddenFiles && settings.homeMode == HomeMode.PRIVATE)) {
                cleanSharedHomeMountPoints(home, plan)
            }
        }
    }

    /**
     * Removes the mount points a launch's private binds leave empty in [home] (each bind's path
     * under [GuestPaths.HOME]), and the folders above one that are left empty, up to the home.
     * Only an empty file or an empty folder goes, so a real file the user keeps under one of these
     * names (a hand-written `.bashrc`), or an empty folder of their own elsewhere, stays.
     */
    private fun cleanSharedHomeMountPoints(home: File, plan: LaunchPlan) {
        val prefix = GuestPaths.HOME + "/"
        val points = (plan.binds.appDirs + plan.binds.privateFiles)
            .map { it.second }
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { rel -> rel.split('/').none { it.isEmpty() || it == "." || it == ".." } }
            .distinct()
            .sortedByDescending { rel -> rel.count { it == '/' } } // deepest first, so parents are empty by their turn
        for (rel in points) {
            // The mount point itself, then the folders proot made for it, up to the home.
            var file: File? = File(home, rel)
            while (file != null && file != home && deleteIfEmpty(file)) file = file.parentFile
        }
    }

    /** Deletes [file] if it is an empty file or a directory whose whole tree is empty; never a link. */
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
                    if (checking.get() && !cancelled.get() && (done == total || done % 500 == 0))
                        SessionController.starting(appId, context.getString(R.string.session_checking_files, ref.name, done, total), skippable = true)
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
            p.tampered.forEach { SessionController.log("[flatdroid] changed in ${p.ref.name}: $it") }
            p.removed.forEach { SessionController.log("[flatdroid] removed in ${p.ref.name}: $it") }
        }
        val detail = context.getString(R.string.session_files_changed) + "\n" +
            problems.joinToString("\n") { context.getString(R.string.session_files_changed_item, it.ref.name, it.tampered.size, it.removed.size) }
        SessionController.filesChanged(SessionState.FilesChanged(appId, detail))
        return false
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
     * the [streamers] writing to their pipes; waits until the nodes exist and the portal has
     * started, so the app sees its cameras from the start. Null when PipeWire didn't start.
     */
    private fun startCamera(helpers: ProotProcess, streamers: Map<String, CameraStreamer>, filter: Fdsec.Filter, refused: AtomicBoolean): ProotProcess? {
        if (!waitForFile(File(pipewireDir, CameraScript.PIPEWIRE_SOCKET), helpers, CAMERA_TIMEOUT_MS, null)) {
            SessionController.log("[flatdroid] PipeWire did not start; the app has no camera")
            return null
        }
        val ready = CountDownLatch(1)
        val binds = listOf(
            File(sessionDir, "camera.sh").path to CameraScript.GUEST_SCRIPT,
            pipewireDir.path to CameraScript.GUEST_PIPEWIRE_DIR,
            cameraPipeDir.path to CameraScript.GUEST_PIPE_DIR,
            cameraConfigDir.path to CameraScript.GUEST_CONFIG_DIR,
            busDaemonDir.path to SessionBus.GUEST_DAEMON_DIR,
        )
        val process = env.start(listOf("/bin/sh", CameraScript.GUEST_SCRIPT), binds, asAppUser = true, filter = filter) { line ->
            if (Fdsec.refused(line)) refused.set(true)
            CameraScript.users(line)?.let { (node, count) -> streamers[node]?.setUsers(count) }
            if (line == CameraScript.READY) ready.countDown()
            SessionController.log("[camera] $line")
        }
        streamers.values.forEach { it.open() }
        if (!ready.await(CAMERA_TIMEOUT_MS, TimeUnit.MILLISECONDS)) SessionController.log("[flatdroid] the camera's helpers did not get ready in time")
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
    }

    /** Generated for every launch: `user-dirs.dirs` for [home], and the desktop settings ([DesktopConfig]). */
    private fun writeDesktopFiles(home: File, plan: LaunchPlan) {
        File(etcDir, USER_DIRS).writeTextAtomically(HomeFolders.userDirs { File(home, it).isDirectory }, durable = false)
        val dark = isDarkTheme()
        AndroidTarFs.deleteTree(desktopDir)
        for (gtk in listOf("gtk-3.0", "gtk-4.0")) {
            File(desktopDir, "$gtk/settings.ini").apply { parentFile!!.mkdirs() }.writeTextAtomically(DesktopConfig.gtkSettings(dark), durable = false)
        }
        // Only KDE runtimes have it.
        val darkScheme = File(plan.runtime.files, DesktopConfig.BREEZE_DARK).takeIf { dark && it.isFile }?.let { runCatching { it.readTextUpTo() }.getOrNull() }
        DesktopConfig.kdeglobals(dark, darkScheme)?.let { File(desktopDir, "kdeglobals").writeTextAtomically(it, durable = false) }
    }

    /** The theme setting; with System, Android's dark mode now. */
    private fun isDarkTheme() = when (SetupController.settings(context).load()?.flatpakTheme ?: FlatpakTheme.SYSTEM) {
        FlatpakTheme.SYSTEM -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        FlatpakTheme.DARK -> true
        FlatpakTheme.LIGHT -> false
    }

    private fun options(appId: String): LaunchOptions {
        val settings = AppData.settings(context, appId).load()
        val app = installation.app(appId)
        val user = SetupController.settings(context).load()
        val security = user?.security ?: SecuritySettings()
        // Developer settings count only while developer options are on.
        val developerOptions = user?.developerOptions == true
        return LaunchOptions(
            environment = if (developerOptions) settings.environment else emptyMap(),
            newerElectronFlags = settings.newerElectronFlags,
            electronSwap = app?.let { electronSwap(appId).preparedFor(it) },
            wholeDev = settings.wholeDev,
            flatpakInfo = !settings.withoutFlatpakInfo,
            darkTheme = isDarkTheme(),
            audio = settings.audioAllowed(app?.metadata?.context?.sockets.orEmpty()),
            network = settings.networkAllowed(app?.metadata?.context?.shared.orEmpty()),
            extraSyscalls = security.extraSyscallFilter && !(developerOptions && settings.allowExtraSyscalls),
            limitDevices = security.limitDevices,
        )
    }

    private fun hostDirs(appId: String, settings: AppSettings = AppData.settings(context, appId).load()): HostDirs {
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
            settings.homeMode != HomeMode.SHARED -> null
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
            },
            appDirs = listOf(
                File(appData, "config") to "$home/.config",
                File(appData, "local") to "$home/.local",
                File(appData, "pki") to "$home/.pki",
                File(appData, "var") to varApp,
                cache to "$home/.cache",
                cache to "$varApp/cache",
                // Where Flatpak also binds them; apps' own scripts use these paths (Ghidra's /var/config).
                File(appData, "var/config") to "/var/config",
                File(appData, "var/data") to "/var/data",
                cache to "/var/cache",
                File(cache, "tmp") to "/var/tmp",
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
            x11Sockets = x11Dir,
            pulseSockets = pulseDir,
            fontDirs = launcher.fontDirs,
            hostFonts = ANDROID_FONTS.takeIf { settings.usesAndroidFonts && it.isDirectory },
            desktopConfig = desktopDir,
            userDirs = File(etcDir, USER_DIRS),
            sdcard = SharedHome.storageRoot().takeIf { settings.sdcard && SharedHome.broadAccess(context) },
            sessionBus = busProxyDir,
            // Under the app's cache, so clearing caches drops it (fontconfig then regenerates it).
            runtimeCache = File(cache, RUNTIME_CACHE_DIR),
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
        if (!plan.binds.wholeDev) DevNodes.createMountPoints(root, plan.binds.dev + AlpineBinds.FD_LINKS.keys)
        for (name in plan.usrLinks) Os.symlink("usr/$name", File(root, name).path)
        plan.flatpakInfo?.let { File(root, ".flatpak-info").writeText(it) }
        File(root, Fdsec.APP_PATH.removePrefix("/")).apply { parentFile!!.mkdirs() }.createNewFile()

        AndroidTarFs.deleteTree(File(AppData.appTmpDir(context), appId))
        File(plan.binds.tmp).mkdirs()
        File(plan.binds.shm).mkdirs()
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
        // Writable scratch for the runtime caches shadowed over the deployment (their guest paths
        // are under /usr, so the loop below skips making mount points for them — the runtime already
        // has those directories). Persisted across launches, so fontconfig regenerates them once.
        for ((host, guest) in plan.binds.extra) {
            if (guest in LaunchPlanner.RUNTIME_CACHE_DIRS) File(host).mkdirs()
        }
        // Mount points for the further binds: in the root, or in the app's own directory they fall in.
        for ((host, guest) in plan.binds.extra) {
            if (guest == "/app" || guest.startsWith("/app/") || guest.startsWith("/usr/")) continue
            val owner = plan.binds.appDirs.filter { guest.startsWith(it.second + "/") }.maxByOrNull { it.second.length }
            val target = if (owner != null) File(owner.first, guest.removePrefix(owner.second + "/")) else File(root, guest.removePrefix("/"))
            if (target.exists()) continue
            if (File(host).isDirectory) target.mkdirs() else target.apply { parentFile?.mkdirs() }.createNewFile()
        }
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
        val sources = listOfNotNull(b.root, b.runtimeFiles, b.appFiles, "${b.runtimeFiles}/etc", b.tmp, b.shm, b.x11Sockets, b.pulseSockets, b.home, b.emptyDir, env.paths().l2sDir) +
            b.etc.values + b.extensions.map { it.first } + b.appDirs.map { it.first } + b.privateFiles.map { it.first } + b.extra.map { it.first }
        for (path in sources) {
            val file = File(path)
            if (!file.exists() || file.canonicalPath != file.absolutePath) {
                throw LaunchException(context.getString(R.string.launch_bind_replaced, path))
            }
        }
    }

    /**
     * Files every launch gets fresh: the shared /etc files (an app could have changed them, since
     * binds are writable), a copy of the machine-id, resolv.conf for the current network, and the
     * VNC password file, and the helpers' scripts ([pulseScript] only with sound) and the session
     * bus's configuration and proxy arguments ([busArgs]). Old sockets are removed.
     */
    private fun writeSessionFiles(password: String, titleBars: Boolean, pulseScript: String?, busArgs: List<String>, cameraNodes: List<CameraScript.Node>) {
        val launcher = AppData.launcherFiles(context)
        launcher.write()
        File(etcDir, "machine-id").writeTextAtomically(launcher.machineId.readText(), durable = false)
        File(etcDir, "resolv.conf").writeTextAtomically(env.resolvConf(), durable = false)
        for (dir in listOf(x11Dir, pulseDir, vncDir, busDaemonDir, busProxyDir, pipewireDir, cameraPipeDir, cameraConfigDir, emptyDir)) {
            AndroidTarFs.deleteTree(dir)
            dir.mkdirs()
        }
        File(vncDir, "passwd").apply {
            writeBytes(VncPassword.file(password))
            Os.chmod(path, 0x180) // 0600
        }
        File(sessionDir, "session.sh").writeTextAtomically(SessionScript.TEXT, durable = false)
        File(sessionDir, "openbox-rc.xml").writeTextAtomically(GuestFiles.openboxRc(titleBars), durable = false)
        val pulseFile = File(sessionDir, "pulse.pa")
        if (pulseScript != null) pulseFile.writeTextAtomically(pulseScript, durable = false) else pulseFile.delete()
        File(sessionDir, "dbus.conf").writeTextAtomically(SessionBus.CONFIG, durable = false)
        File(sessionDir, "proxy.args").writeAtomically(durable = false) { it.write(SessionBus.argsFile(busArgs)) }
        val cameraScript = File(sessionDir, "camera.sh")
        if (cameraNodes.isEmpty()) {
            cameraScript.delete()
        } else {
            cameraScript.writeTextAtomically(CameraScript.text(cameraNodes), durable = false)
            for ((path, text) in CameraScript.CONFIG_FILES) {
                File(cameraConfigDir, path).apply { parentFile!!.mkdirs() }.writeTextAtomically(text, durable = false)
            }
            for (node in cameraNodes) Os.mkfifo(File(cameraPipeDir, node.name).path, 0x180) // 0600
        }
    }

    /**
     * Starts [command] ([SessionScript.command]) behind [filter], with PulseAudio's script bound
     * when there is [sound]; [onLine] also gets each line of the helpers' output, before it is logged.
     */
    private fun startHelpers(command: List<String>, sound: Boolean, filter: Fdsec.Filter, onLine: (String) -> Unit): ProotProcess {
        val binds = listOf(
            x11Dir.path to GuestPaths.X11_DIR,
            pulseDir.path to GuestPaths.PULSE_DIR,
            vncDir.path to SessionScript.GUEST_VNC_DIR,
            File(sessionDir, "session.sh").path to SessionScript.GUEST_SCRIPT,
            File(sessionDir, "openbox-rc.xml").path to SessionScript.GUEST_OPENBOX_RC,
            busDaemonDir.path to SessionBus.GUEST_DAEMON_DIR,
            busProxyDir.path to SessionBus.GUEST_PROXY_DIR,
            pipewireDir.path to CameraScript.GUEST_PIPEWIRE_DIR,
            File(sessionDir, "dbus.conf").path to SessionBus.GUEST_CONFIG,
            File(sessionDir, "proxy.args").path to SessionBus.GUEST_PROXY_ARGS,
        ) + if (sound) listOf(File(sessionDir, "pulse.pa").path to SessionScript.GUEST_PULSE_SCRIPT) else emptyList()
        return env.start(command, binds, asAppUser = true, filter = filter) { line ->
            onLine(line)
            SessionController.log("[display] $line")
        }
    }

    /**
     * Waits until [file] exists, woken by a [FileObserver] on its directory as soon as it is
     * created. Fails with [error] (or returns false when it is null) on timeout or when the
     * helpers have exited, which is checked every [HELPER_CHECK_MS].
     */
    private fun waitForFile(file: File, helpers: ProotProcess, timeoutMs: Long, error: String?): Boolean {
        val created = CountDownLatch(1)
        val observer = directoryObserver(file.parentFile!!) { name -> if (name == file.name) created.countDown() }
        observer.startWatching()
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!file.exists()) {
                if (!helpers.isAlive || System.currentTimeMillis() > deadline) {
                    if (error != null) throw LaunchException(error)
                    return false
                }
                created.await(HELPER_CHECK_MS, TimeUnit.MILLISECONDS)
            }
            return true
        } finally {
            observer.stopWatching()
        }
    }

    /** Calls [onCreated] with the name of each entry created in [dir]. */
    private fun directoryObserver(dir: File, onCreated: (String) -> Unit): FileObserver {
        val mask = FileObserver.CREATE or FileObserver.MOVED_TO
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) { path?.let(onCreated) }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.path, mask) {
                override fun onEvent(event: Int, path: String?) { path?.let(onCreated) }
            }
        }
    }

    private fun describe(result: PlanResult): String = when (result) {
        is PlanResult.NotInstalled -> context.getString(R.string.launch_not_installed, result.appId)
        is PlanResult.RuntimeMissing -> context.getString(R.string.launch_runtime_missing, result.runtime)
        is PlanResult.NoCommand -> context.getString(R.string.launch_no_command, result.appId)
        is PlanResult.WrongArchitecture -> context.getString(R.string.launch_wrong_arch, result.path, result.arch, arch)
        else -> result.toString()
    }

    private companion object {
        /** Android's own fonts, readable by every app. */
        val ANDROID_FONTS = File("/system/fonts")
        const val DISPLAY_TIMEOUT_MS = 30_000L
        const val AUDIO_TIMEOUT_MS = 10_000L
        const val BUS_TIMEOUT_MS = 10_000L
        const val CAMERA_TIMEOUT_MS = 20_000L
        const val HELPER_CHECK_MS = 250L
        const val DEFAULT_OUTPUT_RATE = 48_000
        const val USER_DIRS = "user-dirs.dirs"
        /** Hidden subdirectory of the app's cache holding the runtime-cache scratch overlays. */
        const val RUNTIME_CACHE_DIR = ".flatdroid-runtime"
    }
}
