package com.garathon.flatdroid.session

import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.FileObserver
import android.system.Os
import com.garathon.flatdroid.R
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.alpine.GuestFiles
import com.garathon.flatdroid.audio.AudioPlayer
import com.garathon.flatdroid.audio.MicStreamer
import com.garathon.flatdroid.flatpak.AndroidLinkFs
import com.garathon.flatdroid.flatpak.AppData
import com.garathon.flatdroid.flatpak.FlatpakInstallation
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
import com.garathon.flatdroid.proot.GuestPaths
import com.garathon.flatdroid.proot.ProotCommand
import com.garathon.flatdroid.proot.ProotProcess
import com.garathon.flatdroid.proot.ProotRunner
import com.garathon.flatdroid.setup.AndroidTarFs
import com.garathon.flatdroid.settings.AppSettings
import com.garathon.flatdroid.settings.AudioOutputMode
import com.garathon.flatdroid.settings.FlatpakTheme
import com.garathon.flatdroid.settings.HomeMode
import com.garathon.flatdroid.settings.Resampler
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.setup.SetupController
import com.garathon.flatdroid.setup.deleteTree
import com.garathon.flatdroid.storage.HomeFolders
import com.garathon.flatdroid.storage.SharedHome
import com.garathon.flatdroid.storage.StorageTaskService
import com.garathon.flatdroid.storage.StorageTasks
import com.garathon.flatdroid.storage.TempHomes
import com.garathon.flatdroid.util.readTextUpTo
import com.garathon.flatdroid.util.httpDownload
import com.garathon.flatdroid.util.sha256
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

        SessionController.starting(appId, context.getString(R.string.session_preparing_files))
        val home = prepareHome(appId, settings.homeMode)
        // Before prepareDirs, which makes the mount points by what they are bound from.
        writeDesktopFiles(home, plan)
        prepareDirs(appId, plan)
        val userSettings = SetupController.settings(context).load()
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
        val outputRate = outputRate()
        val micRate = MicStreamer.rate()
        writeSessionFiles(
            password, titleBars = userSettings?.windowTitleBars ?: true,
            pulseScript = if (audio) PulseScript.text(outputRate, microphone, micRate) else null,
        )
        // After both, which create the directories it checks: they live in the cache, which Android may
        // clear at any time, and a fresh install has none yet.
        checkBindSources(plan)

        SessionController.starting(appId, context.getString(R.string.session_starting_display))
        val frameRate = userSettings?.frameRate ?: UserSettings.DEFAULT_FRAME_RATE
        val mic = if (!microphone) null else {
            val alwaysAllowed = settings.alwaysAllowMicrophone && userSettings?.developerOptions == true
            MicrophoneSession(context, appId, File(pulseDir, PulseScript.MIC_SOCKET), micRate, alwaysAllowed, micAnswer)
        }
        SessionController.onMicrophoneAnswer = mic?.let { it::answer }
        val resampler = userSettings?.resampler ?: Resampler.FAST
        val helpers = startHelpers(SessionScript.command(settings.geometry, frameRate, audio, microphone = mic != null, resampler), audio) { line ->
            SessionScript.micUsers(line)?.let { mic?.onUsers(it) }
        }
        var app: ProotProcess? = null
        var player: AudioPlayer? = null
        try {
            waitForFile(File(x11Dir, SessionScript.X_SOCKET), helpers, DISPLAY_TIMEOUT_MS, context.getString(R.string.launch_display_failed))
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
            var triedNewerFlags = false
            var triedWithoutFlatpakInfo = false
            while (true) {
                SessionController.log("[flatdroid] ${plan.command.joinToString(" ")}")
                for ((host, guest) in plan.binds.extensions) SessionController.log("[flatdroid] extension $host -> $guest")
                val gpuFailed = AtomicBoolean(false)
                val kdeHostProcess = AtomicBoolean(false)
                val killedBySignal = AtomicBoolean(false)
                app = ProotRunner.start(
                    ProotCommand.flatpak(env.paths(), plan.binds, plan.environment, plan.command),
                    File(plan.binds.root),
                ) { line ->
                    if (Electron.GPU_FAILURE in line) gpuFailed.set(true)
                    if (LaunchPlanner.KDE_HOST_PROCESS_WARNING in line) kdeHostProcess.set(true)
                    if (LaunchPlanner.PROOT_KILLED_BY_SIGNAL in line) killedBySignal.set(true)
                    SessionController.log(line)
                }
                SessionController.running(appId, password)
                val code = app.waitFor()
                SessionController.log("[flatdroid] the app exited ($code)")
                if (plan.isElectron && !settings.newerElectronFlags && gpuFailed.get() && !triedNewerFlags) {
                    // Newer Electron: relaunch once with its extra flags, and keep them if they help.
                    triedNewerFlags = true
                    settings = settings.copy(newerElectronFlags = true)
                    store.update { it.copy(newerElectronFlags = true) }
                    SessionController.log("[flatdroid] relaunching with ${Electron.NEWER_FLAGS.joinToString(" ")}")
                    SessionController.starting(appId, context.getString(R.string.session_relaunching_newer_flags))
                    plan = (planner.plan(appId, hostDirs(appId), options(appId)) as? PlanResult.Ready)?.plan
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
                        plan = (planner.plan(appId, hostDirs(appId), options(appId)) as? PlanResult.Ready)?.plan
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
            app?.stop()
            player?.stop()
            mic?.stop()
            SessionController.onMicrophoneAnswer = null
            helpers.stop()
        }
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
        // Developer settings count only while developer options are on.
        val developerOptions = SetupController.settings(context).load()?.developerOptions == true
        return LaunchOptions(
            environment = if (developerOptions) settings.environment else emptyMap(),
            newerElectronFlags = settings.newerElectronFlags,
            electronSwap = app?.let { electronSwap(appId).preparedFor(it) },
            wholeDev = settings.wholeDev,
            flatpakInfo = !settings.withoutFlatpakInfo,
            darkTheme = isDarkTheme(),
            audio = settings.audioAllowed(app?.metadata?.context?.sockets.orEmpty()),
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
            ),
            etc = linkedMapOf(
                "resolv.conf" to File(etcDir, "resolv.conf"),
                "hosts" to launcher.hosts,
                "machine-id" to File(etcDir, "machine-id"),
                "passwd" to launcher.passwd,
                "group" to launcher.group,
            ),
            x11Sockets = x11Dir,
            pulseSockets = pulseDir,
            fontDirs = launcher.fontDirs,
            desktopConfig = desktopDir,
            userDirs = File(etcDir, USER_DIRS),
            sdcard = SharedHome.storageRoot().takeIf { settings.sdcard && SharedHome.broadAccess(context) },
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

        AndroidTarFs.deleteTree(File(AppData.appTmpDir(context), appId))
        File(plan.binds.tmp).mkdirs()
        File(plan.binds.shm).mkdirs()
        // The app can write these, so it can replace one with a link (see checkBindSources).
        for (dir in listOf(File(plan.binds.home)) + plan.binds.appDirs.map { File(it.first) }) {
            if (AndroidTarFs.isSymlink(dir)) dir.delete()
            dir.mkdirs()
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
        val sources = listOfNotNull(b.root, b.runtimeFiles, b.appFiles, "${b.runtimeFiles}/etc", b.tmp, b.shm, b.x11Sockets, b.pulseSockets, b.home, env.paths().l2sDir) +
            b.etc.values + b.extensions.map { it.first } + b.appDirs.map { it.first } + b.extra.map { it.first }
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
     * VNC password file, and the helpers' scripts ([pulseScript] only with sound). Old sockets are removed.
     */
    private fun writeSessionFiles(password: String, titleBars: Boolean, pulseScript: String?) {
        val launcher = AppData.launcherFiles(context)
        launcher.write()
        File(etcDir, "machine-id").writeTextAtomically(launcher.machineId.readText(), durable = false)
        File(etcDir, "resolv.conf").writeTextAtomically(env.resolvConf(), durable = false)
        for (dir in listOf(x11Dir, pulseDir, vncDir)) {
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
    }

    /**
     * Starts [command] ([SessionScript.command]), with PulseAudio's script bound when there is
     * [sound]; [onLine] also gets each line of the helpers' output, before it is logged.
     */
    private fun startHelpers(command: List<String>, sound: Boolean, onLine: (String) -> Unit): ProotProcess {
        val binds = listOf(
            x11Dir.path to GuestPaths.X11_DIR,
            pulseDir.path to GuestPaths.PULSE_DIR,
            vncDir.path to SessionScript.GUEST_VNC_DIR,
            File(sessionDir, "session.sh").path to SessionScript.GUEST_SCRIPT,
            File(sessionDir, "openbox-rc.xml").path to SessionScript.GUEST_OPENBOX_RC,
        ) + if (sound) listOf(File(sessionDir, "pulse.pa").path to SessionScript.GUEST_PULSE_SCRIPT) else emptyList()
        return env.start(command, binds) { line ->
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
        const val DISPLAY_TIMEOUT_MS = 30_000L
        const val AUDIO_TIMEOUT_MS = 10_000L
        const val HELPER_CHECK_MS = 250L
        const val DEFAULT_OUTPUT_RATE = 48_000
        const val USER_DIRS = "user-dirs.dirs"
    }
}
