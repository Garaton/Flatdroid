package com.garathon.flatdroid.session

import android.content.Context
import android.system.Os
import com.garathon.flatdroid.R
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.alpine.GuestFiles
import com.garathon.flatdroid.flatpak.AndroidLinkFs
import com.garathon.flatdroid.flatpak.AppData
import com.garathon.flatdroid.flatpak.FlatpakInstallation
import com.garathon.flatdroid.launcher.Electron
import com.garathon.flatdroid.launcher.ElectronSwap
import com.garathon.flatdroid.launcher.HostDirs
import com.garathon.flatdroid.launcher.LaunchOptions
import com.garathon.flatdroid.launcher.LaunchPlan
import com.garathon.flatdroid.launcher.LaunchPlanner
import com.garathon.flatdroid.launcher.PlanResult
import com.garathon.flatdroid.launcher.VncPassword
import com.garathon.flatdroid.proot.GuestPaths
import com.garathon.flatdroid.proot.ProotCommand
import com.garathon.flatdroid.proot.ProotProcess
import com.garathon.flatdroid.proot.ProotRunner
import com.garathon.flatdroid.setup.AndroidTarFs
import com.garathon.flatdroid.setup.deleteTree
import com.garathon.flatdroid.util.httpDownload
import com.garathon.flatdroid.util.sha256
import com.garathon.flatdroid.util.writeTextAtomically
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** A launch that can't go ahead; [message] is shown to the user. */
class LaunchException(message: String) : IOException(message)

/**
 * Runs one Flatpak session: a fresh Xvnc, Openbox and PulseAudio in Alpine, then the app in its
 * own root. Blocking; interrupting the thread stops the app and the helpers.
 */
class FlatpakLauncher(private val context: Context) {
    private val env = AlpineEnvironment.get(context)
    private val arch = env.arch
    private val installation = FlatpakInstallation(env.flatpakDir, arch)
    private val planner = LaunchPlanner(installation, arch, File(env.paths().l2sDir), AndroidLinkFs::readlink)
    private val sessionDir = AppData.sessionDir(context)
    private val x11Dir = File(sessionDir, "x11")
    private val pulseDir = File(sessionDir, "pulse")
    private val vncDir = File(sessionDir, "vnc")
    private val etcDir = File(sessionDir, "etc")

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
    fun run(appId: String): Boolean {
        SessionController.starting(appId, context.getString(R.string.session_reading_metadata), clearLog = true)
        val store = AppData.settings(context, appId)
        var settings = store.load()
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
        prepareDirs(appId, plan)
        checkBindSources(plan)
        val password = VncPassword.random()
        writeSessionFiles(password)

        SessionController.starting(appId, context.getString(R.string.session_starting_display))
        val helpers = startHelpers(settings.geometry)
        var app: ProotProcess? = null
        try {
            waitForFile(File(x11Dir, SessionScript.X_SOCKET), helpers, DISPLAY_TIMEOUT_MS, context.getString(R.string.launch_display_failed))
            if (!waitForFile(File(pulseDir, "native"), helpers, AUDIO_TIMEOUT_MS, null)) {
                SessionController.log("[flatdroid] PulseAudio did not start; the app has no audio")
            }
            var triedNewerFlags = false
            while (true) {
                SessionController.log("[flatdroid] ${plan.command.joinToString(" ")}")
                for ((host, guest) in plan.binds.extensions) SessionController.log("[flatdroid] extension $host -> $guest")
                val gpuFailed = AtomicBoolean(false)
                app = ProotRunner.start(
                    ProotCommand.flatpak(env.paths(), plan.binds, plan.environment, plan.command),
                    File(plan.binds.root),
                ) { line ->
                    if (Electron.GPU_FAILURE in line) gpuFailed.set(true)
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
                    continue
                }
                if (triedNewerFlags && (gpuFailed.get() || code != 0)) {
                    // The flags didn't fix it; don't keep them for next time.
                    store.update { it.copy(newerElectronFlags = false) }
                    SessionController.log("[flatdroid] the newer Electron flags didn't help; turned them off again")
                    throw LaunchException(context.getString(R.string.launch_newer_flags_failed, code))
                }
                return true
            }
        } finally {
            app?.stop()
            helpers.stop()
        }
    }

    private fun options(appId: String): LaunchOptions {
        val settings = AppData.settings(context, appId).load()
        val app = installation.app(appId)
        return LaunchOptions(
            newerElectronFlags = settings.newerElectronFlags,
            electronSwap = app?.let { electronSwap(appId).preparedFor(it) },
        )
    }

    private fun hostDirs(appId: String): HostDirs {
        val appData = File(AppData.root(context), appId)
        val cache = File(AppData.cacheRoot(context), appId)
        val tmpBase = File(AppData.appTmpDir(context), appId)
        val launcher = AppData.launcherFiles(context)
        val home = GuestPaths.HOME
        val varApp = "$home/.var/app/$appId"
        return HostDirs(
            root = File(AppData.rootsDir(context), appId),
            tmp = File(tmpBase, "tmp"),
            shm = File(tmpBase, "shm"),
            // A private home per app for now; the shared home (Documents/Flatdroid) comes later.
            home = File(appData, "home"),
            homeOverlays = listOf(
                File(appData, "config") to "$home/.config",
                File(appData, "local") to "$home/.local",
                File(appData, "pki") to "$home/.pki",
                File(appData, "var") to varApp,
                cache to "$home/.cache",
                cache to "$varApp/cache",
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
            missingFdLinks = env.missingFdLinks(),
        )
    }

    /** Rebuilds the app's root and private /tmp, and creates its data directories. */
    private fun prepareDirs(appId: String, plan: LaunchPlan) {
        val root = File(plan.binds.root)
        AndroidTarFs.deleteTree(root)
        val mountPoints = listOf("usr", "app", "etc", "dev", "proc", "sys", "tmp") + listOf(GuestPaths.PULSE_DIR, GuestPaths.HOME).map { it.removePrefix("/") }
        for (dir in mountPoints) File(root, dir).mkdirs()
        for (name in plan.usrLinks) Os.symlink("usr/$name", File(root, name).path)
        File(root, ".flatpak-info").writeText(plan.flatpakInfo)

        AndroidTarFs.deleteTree(File(AppData.appTmpDir(context), appId))
        File(plan.binds.tmp).mkdirs()
        File(plan.binds.shm).mkdirs()
        // The app can write these, so it can replace one with a link (see checkBindSources).
        for (dir in listOf(File(plan.binds.home)) + plan.binds.homeOverlays.map { File(it.first) }) {
            if (AndroidTarFs.isSymlink(dir)) dir.delete()
            dir.mkdirs()
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
        val sources = listOf(b.root, b.runtimeFiles, b.appFiles, "${b.runtimeFiles}/etc", b.tmp, b.shm, b.x11Sockets, b.pulseSockets, b.home, env.paths().l2sDir) +
            b.etc.values + b.extensions.map { it.first } + b.homeOverlays.map { it.first } + b.extra.map { it.first }
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
     * VNC password file. Old sockets are removed.
     */
    private fun writeSessionFiles(password: String) {
        val launcher = AppData.launcherFiles(context)
        launcher.write()
        File(etcDir, "machine-id").writeTextAtomically(launcher.machineId.readText())
        File(etcDir, "resolv.conf").writeTextAtomically(env.resolvConf())
        for (dir in listOf(x11Dir, pulseDir, vncDir)) {
            AndroidTarFs.deleteTree(dir)
            dir.mkdirs()
        }
        File(vncDir, "passwd").apply {
            writeBytes(VncPassword.file(password))
            Os.chmod(path, 0x180) // 0600
        }
        File(sessionDir, "session.sh").writeTextAtomically(SessionScript.TEXT)
        File(sessionDir, "openbox-rc.xml").writeTextAtomically(GuestFiles.openboxRc())
    }

    private fun startHelpers(geometry: String): ProotProcess {
        val binds = listOf(
            x11Dir.path to GuestPaths.X11_DIR,
            pulseDir.path to GuestPaths.PULSE_DIR,
            vncDir.path to SessionScript.GUEST_VNC_DIR,
            File(sessionDir, "session.sh").path to SessionScript.GUEST_SCRIPT,
            File(sessionDir, "openbox-rc.xml").path to SessionScript.GUEST_OPENBOX_RC,
        )
        return env.start(
            listOf("/bin/sh", SessionScript.GUEST_SCRIPT, geometry, SessionScript.VNC_PORT.toString()),
            binds,
        ) { SessionController.log("[display] $it") }
    }

    /**
     * Waits until [file] exists. Fails with [error] (or returns false when it is null) on timeout
     * or when the helpers have exited.
     */
    private fun waitForFile(file: File, helpers: ProotProcess, timeoutMs: Long, error: String?): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!file.exists()) {
            if (!helpers.isAlive || System.currentTimeMillis() > deadline) {
                if (error != null) throw LaunchException(error)
                return false
            }
            Thread.sleep(100)
        }
        return true
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
    }
}
