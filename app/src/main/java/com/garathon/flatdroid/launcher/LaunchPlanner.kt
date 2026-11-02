package com.garathon.flatdroid.launcher

import com.garathon.flatdroid.flatpak.Deployment
import com.garathon.flatdroid.flatpak.FlatpakInstallation
import com.garathon.flatdroid.flatpak.FlatpakRef
import com.garathon.flatdroid.flatpak.L2s
import com.garathon.flatdroid.proot.DevNodes
import com.garathon.flatdroid.proot.Fdsec
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
    /** The app's own single files over the home's ([FlatpakBinds.privateFiles]): host to guest path. */
    val privateFiles: List<Pair<File, String>> = emptyList(),
    /** Generated /etc files: name to host file. */
    val etc: Map<String, File>,
    val x11Sockets: File,
    /** The Wayland compositor's socket directory, bound only for apps that ask only for Wayland. */
    val waylandSockets: File? = null,
    /** PulseAudio's socket directory; null when the app gets no sound. */
    val pulseSockets: File?,
    /** Bound at [GuestPaths.FONT_DIRS]. */
    val fontDirs: File,
    /** Android's fonts, bound at [GuestPaths.HOST_FONTS]; null for none. */
    val hostFonts: File? = null,
    /** Generated desktop settings (GTK, KDE), bound at [GuestPaths.XDG_CONFIG_DIR]. */
    val desktopConfig: File,
    /** Generated `user-dirs.dirs`, bound into the app's config directories. */
    val userDirs: File,
    /** Shared storage, bound at [GuestPaths.SDCARD]; null for none. */
    val sdcard: File? = null,
    /** Holds the session bus proxy's socket, bound at [GuestPaths.SESSION_BUS_DIR]; null for no bus. */
    val sessionBus: File? = null,
    /**
     * Writable per-app base for the runtime caches a tool rewrites at launch ([LaunchPlanner.RUNTIME_CACHE_DIRS]):
     * a scratch for each is bound over the deployment's copy, so the file-integrity check doesn't
     * see the app's own writes as tampering. Persisted (regenerated once), not bound anywhere else.
     */
    val runtimeCache: File,
    /**
     * The app's own XDG_RUNTIME_DIR, bound at `/run/user/<[userId]>` as in Flatpak (its `app/<ID>`
     * and the links to the bus and the sound socket are made at launch); null keeps /tmp.
     */
    val runtimeDir: File? = null,
    val userId: Int = 0,
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
    /** The app gets the Internet (see AppSettings.networkAllowed): resolv.conf, and Internet sockets. */
    val network: Boolean = true,
    /** fdsec also refuses the security level's extra system calls ([Fdsec.Filter.extraSyscalls]). */
    val extraSyscalls: Boolean = true,
    /** Only CPU and memory information from /sys, and `devices=all` gives no device group ([DevNodes.forApp]). */
    val limitDevices: Boolean = false,
    /** The root gets /.flatpak-info ([LaunchPlanner.flatpakInfo]). */
    val flatpakInfo: Boolean = true,
    /** Android is in dark mode; apps are asked to follow it. */
    val darkTheme: Boolean = false,
    /** The device's languages (BCP 47) for the app's locale; empty gives the generic C.UTF-8. */
    val languageTags: List<String> = emptyList(),
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
    /** Electron or a Chromium browser, started without zypak or cobalt ([Electron]). */
    val isElectron: Boolean,
    /** The app asks only for Wayland, so its session is Wayland (cage and wayvnc) instead of Xvnc. */
    val wayland: Boolean = false,
    /** What fdsec refuses for this app. */
    val filter: Fdsec.Filter,
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

        // Electron apps start through zypak, and some Chromium browsers through cobalt: both skipped.
        var command = listOf(commandPath)
        var isElectron = false
        val varApp = "${GuestPaths.HOME}/.var/app/$appId"
        val extraBinds = mutableListOf(
            dirs.fontDirs.path to GuestPaths.FONT_DIRS,
            dirs.desktopConfig.path to GuestPaths.XDG_CONFIG_DIR,
            // Where apps read it: XDG_CONFIG_HOME, and ~/.config for those that ignore it.
            dirs.userDirs.path to "$varApp/config/user-dirs.dirs",
            dirs.userDirs.path to "${GuestPaths.HOME}/.config/user-dirs.dirs",
        )
        // A writable scratch over each runtime cache a tool rewrites at launch, so the deployment
        // (what the file check baselines) isn't changed by the app's own normal operation.
        for (guest in RUNTIME_CACHE_DIRS) extraBinds += runtimeCacheScratch(dirs.runtimeCache, guest).path to guest
        dirs.hostFonts?.let { extraBinds += it.path to GuestPaths.HOST_FONTS }
        dirs.sdcard?.let { extraBinds += it.path to GuestPaths.SDCARD }
        dirs.sessionBus?.let { extraBinds += it.path to GuestPaths.SESSION_BUS_DIR }
        val runtimeDir = dirs.runtimeDir?.let { host -> GuestPaths.runtimeDir(dirs.userId).also { extraBinds += host.path to it } }
        val scripts = launchScripts(app, commandPath)
        val electronBinary = scripts.firstNotNullOfOrNull(Electron::zypakTarget)
        val cobaltBrowser = if (electronBinary != null) null
            else scripts.firstNotNullOfOrNull { Electron.cobaltTarget(it, appId) { path -> hostPath(app, path)?.isFile == true } }
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
        } else if (cobaltBrowser != null) {
            isElectron = true
            val binaryArch = (hostPath(app, cobaltBrowser)?.let(Executable::of) as? Executable.Elf)?.arch
            if (binaryArch != null && binaryArch != arch) return PlanResult.WrongArchitecture(cobaltBrowser, binaryArch)
            command = listOf(cobaltBrowser) + Electron.BASE_FLAGS +
                (if (options.newerElectronFlags) Electron.NEWER_FLAGS else emptyList())
        } else {
            val binaryArch = (hostPath(app, commandPath)?.let(Executable::of) as? Executable.Elf)?.arch
            if (binaryArch != null && binaryArch != arch) return PlanResult.WrongArchitecture(commandPath, binaryArch)
        }
        command = command + options.extraArgs

        // Only apps that can't use X11 get a Wayland session: for the rest, Xvnc costs less (measured).
        val wayland = app.metadata.context.waylandOnly
        if (wayland) command = command.map { if (it == Electron.OZONE_X11) Electron.OZONE_WAYLAND else it }
        val environment = LaunchEnvironment.build(
            appId = appId,
            triplet = LaunchEnvironment.triplet(arch),
            // As Flatpak: the runtime's (e.g. QT_PLUGIN_PATH in KDE's), then the app's over it.
            metadataEnvironment = runtime.metadata.environment + app.metadata.environment,
            glDefault = glDefault,
            extensionLdPaths = ldPaths,
            overrides = options.environment,
            darkTheme = options.darkTheme,
            kdePlatformTheme = DesktopConfig.KDE_PLATFORM_THEMES.any { File(runtime.files, it).exists() },
            sessionBus = dirs.sessionBus != null,
            lang = if (options.languageTags.isEmpty()) LaunchEnvironment.GENERIC_LOCALE
                else LaunchEnvironment.locale(options.languageTags, File(runtime.files, "lib/locale").list()?.toList().orEmpty()),
            runtimeDir = runtimeDir ?: "/tmp",
            flatpakContainer = options.flatpakInfo,
            wayland = wayland,
        )
        val devices = app.metadata.context.devices
        val binds = FlatpakBinds(
            root = dirs.root.path,
            runtimeFiles = runtime.files.path,
            appFiles = app.files.path,
            // Without the network, no DNS: the runtime's resolv.conf is a link that leads nowhere here.
            etc = dirs.etc.filterKeys { options.network || it != RESOLV_CONF }.mapValues { it.value.path },
            extensions = extensions.map { (base, ext) -> ext.deployment.files.path to "$base/${ext.guestPath}" },
            tmp = dirs.tmp.path,
            shm = dirs.shm.path,
            x11Sockets = dirs.x11Sockets.path,
            waylandSockets = dirs.waylandSockets?.path?.takeIf { wayland },
            pulseSockets = dirs.pulseSockets?.takeIf { options.audio }?.path,
            home = dirs.home.path,
            appDirs = dirs.appDirs.map { it.first.path to it.second },
            privateFiles = dirs.privateFiles.map { it.first.path to it.second },
            dev = DevNodes.forApp(devices, devExists, limited = options.limitDevices),
            wholeDev = options.wholeDev && DevNodes.ALL in devices,
            sys = if (options.limitDevices) DevNodes.LIMITED_SYS.filter(devExists) else null,
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
                wayland = wayland,
                filter = Fdsec.Filter(extraSyscalls = options.extraSyscalls, noInternet = !options.network),
            )
        )
    }

    /**
     * The text of the script at [commandPath] and of the scripts under /app it hands over to with
     * `exec` ([Electron.execTarget]), in that order, at most [MAX_SCRIPTS]: an IDE's command
     * script may set up its tools and only then call zypak, in a wrapper of its own.
     */
    private fun launchScripts(app: Deployment, commandPath: String): List<String> {
        val scripts = mutableListOf<String>()
        var path: String? = commandPath
        while (path != null && scripts.size < MAX_SCRIPTS) {
            val text = (hostPath(app, path)?.let { Executable.of(it) } as? Executable.Script)?.text ?: break
            scripts += text
            path = Electron.execTarget(text)?.takeIf { it != path }
        }
        return scripts
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
                L2s.inStore(target, l2sDir) -> return File(target)
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
        const val RESOLV_CONF = "resolv.conf"
        /** The `/etc` files every launch binds Flatdroid's own over (the runtime's are never read). */
        val ETC_OVERRIDES = listOf(RESOLV_CONF, "hosts", "machine-id", "passwd", "group", LOCALTIME, TIMEZONE)
        /** The time zone's files: Android's zone (or UTC when it isn't shared), written at every launch. */
        const val LOCALTIME = "localtime"
        const val TIMEZONE = "timezone"
        private const val MAX_LINKS = 8
        private const val MAX_SCRIPTS = 3

        /**
         * Runtime cache directories some tool regenerates into at launch (a read-only Flatpak
         * runtime would write these to the home instead). A writable scratch is bound over each so
         * the deployment stays unchanged for the file-integrity check. Add a directory here if the
         * check reports its files changing on an ordinary launch.
         */
        val RUNTIME_CACHE_DIRS = listOf("/usr/lib/fontconfig/cache")

        /** The scratch directory under [HostDirs.runtimeCache] that shadows [guest]. */
        fun runtimeCacheScratch(base: File, guest: String): File =
            File(base, guest.removePrefix("/").replace('/', '_'))

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
         * the host but the app may not ask Flatpak to: there is no host here, and KDE terminal
         * apps crash in that mode. Such an app runs without the file ([LaunchOptions.flatpakInfo]).
         */
        const val KDE_HOST_PROCESS_WARNING = "Process execution expects 'org.freedesktop.Flatpak=talk'"

        /** proot's line when the app's first process was killed by a signal (its exit code is then 0). */
        const val PROOT_KILLED_BY_SIGNAL = "terminated with signal"
    }
}
