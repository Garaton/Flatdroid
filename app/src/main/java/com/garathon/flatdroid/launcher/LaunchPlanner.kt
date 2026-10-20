package com.garathon.flatdroid.launcher

import com.garathon.flatdroid.flatpak.Deployment
import com.garathon.flatdroid.flatpak.FlatpakInstallation
import com.garathon.flatdroid.flatpak.FlatpakRef
import com.garathon.flatdroid.proot.DevNodes
import com.garathon.flatdroid.proot.FlatpakBinds
import com.garathon.flatdroid.proot.GuestPaths
import java.io.File

/** Host directories for one launch of one app; see AppPaths for where they live. */
data class HostDirs(
    val root: File,
    val tmp: File,
    val shm: File,
    val home: File,
    /** The app's own private directories, over parts of the home and at Flatpak's /var ones: host to guest path. */
    val appDirs: List<Pair<File, String>>,
    /** Generated /etc files: name to host file. */
    val etc: Map<String, File>,
    val x11Sockets: File,
    /** PulseAudio's socket directory; null when the app gets no sound. */
    val pulseSockets: File?,
    /** Bound at [GuestPaths.FONT_DIRS]. */
    val fontDirs: File,
)

/** Per-app choices that change a launch. */
data class LaunchOptions(
    /** Add Electron's flags for newer versions (set after a "GPU process isn't usable" exit). */
    val newerElectronFlags: Boolean = false,
    /** A prepared arm64/x64 Electron for an app that ships the other architecture's, if any. */
    val electronSwap: File? = null,
    val extraArgs: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    /** For an app asking for `devices=all`: the host's whole /dev, Binder included (the app's own setting). */
    val wholeDev: Boolean = false,
    /** The app gets PulseAudio's socket (see AppSettings.audioAllowed). */
    val audio: Boolean = true,
    /** The root gets /.flatpak-info ([LaunchPlanner.flatpakInfo]). */
    val flatpakInfo: Boolean = true,
)

/** Everything needed to start an app: its proot binds, environment and command, and its root's layout. */
data class LaunchPlan(
    val appId: String,
    val app: Deployment,
    val runtime: Deployment,
    val binds: FlatpakBinds,
    val environment: Map<String, String>,
    val command: List<String>,
    /** Content of the root's /.flatpak-info; null for none ([flatpakInfo]). */
    val flatpakInfo: String?,
    /** Top-level names that are links into /usr (bin, lib, …), as Flatpak makes them. */
    val usrLinks: List<String>,
    val isElectron: Boolean,
)

sealed interface PlanResult {
    data class Ready(val plan: LaunchPlan) : PlanResult
    data class NotInstalled(val appId: String) : PlanResult
    data class RuntimeMissing(val runtime: String) : PlanResult
    data class NoCommand(val appId: String) : PlanResult
    /** The binary is built for another architecture and can't be replaced. */
    data class WrongArchitecture(val path: String, val arch: String) : PlanResult
    /**
     * An Electron app ships Electron for [binaryArch]; Electron [version] for this device can
     * replace it in [appDir] (an /app directory), unless [hasNativeModules].
     */
    data class ElectronSwapNeeded(
        val version: String?,
        val binaryArch: String,
        val appDir: String,
        val binaryName: String,
        val hostAppDir: File,
        val hasNativeModules: Boolean,
    ) : PlanResult
}

/**
 * Builds a [LaunchPlan] from the installation's metadata. [readlink] returns a symlink's target
 * (null if not a link); it is needed to follow links that point to guest paths such as /app/….
 */
class LaunchPlanner(
    private val installation: FlatpakInstallation,
    private val arch: String,
    /** link2symlink's directory: the only absolute link targets that are host paths. */
    private val l2sDir: File,
    private val readlink: (File) -> String?,
    /** Whether a host /dev entry exists on this device. */
    private val devExists: (String) -> Boolean = { File(it).exists() },
) {
    fun plan(appId: String, dirs: HostDirs, options: LaunchOptions = LaunchOptions()): PlanResult {
        val app = installation.app(appId) ?: return PlanResult.NotInstalled(appId)
        val runtimeRef = app.metadata.runtime ?: return PlanResult.RuntimeMissing("(none in metadata)")
        val runtime = installation.runtime(runtimeRef) ?: return PlanResult.RuntimeMissing(runtimeRef.toString())
        val commandName = app.metadata.command?.takeIf { it.isNotBlank() } ?: return PlanResult.NoCommand(appId)
        val commandPath = if (commandName.startsWith("/")) commandName else "/app/bin/$commandName"

        // Runtime extension points mount under /usr, the app's under /app.
        val extensions = installation.extensions(runtime.metadata.extensionPoints, runtimeRef.branch).map { "/usr" to it } +
            installation.extensions(app.metadata.extensionPoints, app.ref.branch).map { "/app" to it }
        val glDefault = extensions.firstOrNull { (base, ext) ->
            base == "/usr" && ext.deployment.ref.name == GL_DEFAULT
        }?.second?.guestPath
        val ldPaths = extensions.mapNotNull { (base, ext) -> ext.addLdPath?.let { "$base/${ext.guestPath}/$it" } }

        // Electron apps start through zypak, which the launcher skips.
        var command = listOf(commandPath)
        var isElectron = false
        val extraBinds = mutableListOf(dirs.fontDirs.path to GuestPaths.FONT_DIRS)
        val script = (hostPath(app, commandPath)?.let { Executable.of(it) } as? Executable.Script)?.text
        val electronBinary = script?.let(Electron::zypakTarget)
        if (electronBinary != null) {
            isElectron = true
            val host = hostPath(app, electronBinary) ?: return PlanResult.NoCommand(appId)
            val binaryArch = (Executable.of(host) as? Executable.Elf)?.arch
            if (binaryArch != null && binaryArch != arch) {
                val swap = options.electronSwap
                val appDir = electronBinary.substringBeforeLast('/')
                // The swap replaces Electron's directory; all of /app can't be replaced.
                if (appDir == "/app") return PlanResult.WrongArchitecture(electronBinary, binaryArch)
                if (swap == null || !File(swap, electronBinary.substringAfterLast('/')).isFile) {
                    val hostAppDir = File(app.files, appDir.removePrefix("/app").trimStart('/'))
                    return PlanResult.ElectronSwapNeeded(
                        version = runCatching { host.inputStream().use(Electron::version) }.getOrNull(),
                        binaryArch = binaryArch,
                        appDir = appDir,
                        binaryName = electronBinary.substringAfterLast('/'),
                        hostAppDir = hostAppDir,
                        hasNativeModules = Electron.hasNativeModules(File(hostAppDir, "resources")),
                    )
                }
                extraBinds += swap.path to appDir
            }
            command = listOf(electronBinary) + Electron.BASE_FLAGS +
                (if (options.newerElectronFlags) Electron.NEWER_FLAGS else emptyList())
        } else {
            val binaryArch = (hostPath(app, commandPath)?.let(Executable::of) as? Executable.Elf)?.arch
            if (binaryArch != null && binaryArch != arch) return PlanResult.WrongArchitecture(commandPath, binaryArch)
        }
        command = command + options.extraArgs

        val environment = LaunchEnvironment.build(
            appId = appId,
            triplet = LaunchEnvironment.triplet(arch),
            // As Flatpak: the runtime's (e.g. QT_PLUGIN_PATH in KDE's), then the app's over it.
            metadataEnvironment = runtime.metadata.environment + app.metadata.environment,
            glDefault = glDefault,
            extensionLdPaths = ldPaths,
            overrides = options.environment,
        )
        val devices = app.metadata.context.devices
        val binds = FlatpakBinds(
            root = dirs.root.path,
            runtimeFiles = runtime.files.path,
            appFiles = app.files.path,
            etc = dirs.etc.mapValues { it.value.path },
            extensions = extensions.map { (base, ext) -> ext.deployment.files.path to "$base/${ext.guestPath}" },
            tmp = dirs.tmp.path,
            shm = dirs.shm.path,
            x11Sockets = dirs.x11Sockets.path,
            pulseSockets = dirs.pulseSockets?.takeIf { options.audio }?.path,
            home = dirs.home.path,
            appDirs = dirs.appDirs.map { it.first.path to it.second },
            dev = DevNodes.forApp(devices, devExists),
            wholeDev = options.wholeDev && DevNodes.ALL in devices,
            extra = extraBinds,
        )
        return PlanResult.Ready(
            LaunchPlan(
                appId = appId,
                app = app,
                runtime = runtime,
                binds = binds,
                environment = environment,
                command = command,
                flatpakInfo = if (options.flatpakInfo) flatpakInfo(appId, runtimeRef) else null,
                usrLinks = USR_LINKS.filter { File(runtime.files, it).exists() },
                isElectron = isElectron,
            )
        )
    }

    /**
     * The host file for an /app path, following symlinks: guest ones and relative ones within
     * /app, and link2symlink's (into .l2s, a host path). Null for anything else, such as a link
     * to the runtime's /usr (a guest path that means something else on the host), and for loops.
     */
    private fun hostPath(app: Deployment, guest: String): File? {
        var path = guest
        repeat(MAX_LINKS) {
            if (!path.startsWith("/app/")) return null
            val relative = path.removePrefix("/app/")
            if (relative.split('/').any { it == ".." }) return null
            var file = File(app.files, relative)
            val target = readlink(file) ?: return file
            when {
                target.startsWith("/app/") -> { path = target; return@repeat }
                target.startsWith(l2sDir.path + "/") -> return File(target)
                target.startsWith("/") -> return null
                else -> {
                    file = File(file.parentFile, target).normalize()
                    if (!file.path.startsWith(app.files.path + "/")) return null
                    path = "/app/" + file.path.removePrefix(app.files.path + "/")
                }
            }
        }
        return null
    }

    companion object {
        const val GL_DEFAULT = "org.freedesktop.Platform.GL.default"
        private const val MAX_LINKS = 8

        /** Flatpak links these to /usr when the runtime has them. */
        val USR_LINKS = listOf("bin", "lib", "lib32", "lib64", "sbin")

        /**
         * `.Devel` and `build=true` turn off old glycin's sandbox (GTK dialogs crash with it); the
         * file's presence also makes GLib use portals, hence GDK_DEBUG=no-portals.
         */
        fun flatpakInfo(appId: String, runtime: FlatpakRef): String =
            "[Application]\nname=$appId.Devel\nruntime=runtime/$runtime\n\n[Instance]\nbuild=true\n"

        /**
         * Printed by KDE's frameworks (KSandbox) when /.flatpak-info makes them run a process on
         * the host but the app may not ask Flatpak to: there is no host here, and Konsole crashes
         * in that mode. Such an app runs without the file ([LaunchOptions.flatpakInfo]).
         */
        const val KDE_HOST_PROCESS_WARNING = "Process execution expects 'org.freedesktop.Flatpak=talk'"

        /** proot's line when the app's first process was killed by a signal (its exit code is then 0). */
        const val PROOT_KILLED_BY_SIGNAL = "terminated with signal"
    }
}
