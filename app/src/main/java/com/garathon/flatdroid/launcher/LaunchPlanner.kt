package com.garathon.flatdroid.launcher

import com.garathon.flatdroid.alpine.programs.AlpineRoot
import com.garathon.flatdroid.flatpak.Deployment
import com.garathon.flatdroid.flatpak.FlatpakInstallation
import com.garathon.flatdroid.flatpak.FlatpakRef
import com.garathon.flatdroid.launcher.electron.Electron
import com.garathon.flatdroid.proot.L2s
import com.garathon.flatdroid.flatpak.MountedExtension
import com.garathon.flatdroid.proot.DevNodes
import com.garathon.flatdroid.proot.Fdsec
import com.garathon.flatdroid.proot.FlatpakBinds
import com.garathon.flatdroid.proot.GuestPaths
import com.garathon.flatdroid.session.helpers.SessionBus
import java.io.File
import java.security.MessageDigest
import com.garathon.flatdroid.util.io.readTextUpTo

/** Host directories for one launch of one app; see AppPaths for where they live. */
data class HostDirs(
    val root: File,
    val tmp: File,
    val shm: File,
    val home: File,
    /** The app's own private directories, over parts of the home: host to guest path. */
    val appDirs: List<Pair<File, String>>,
    /** The app's own single files over the home's ([FlatpakBinds.privateFiles]): host to guest path. */
    val privateFiles: List<Pair<File, String>> = emptyList(),
    /** Generated /etc files: name to host file. */
    val etc: Map<String, File>,
    /** Xvnc's socket directory, bound only for apps in an X11 session. */
    val x11Sockets: File,
    /** The Wayland compositor's socket directory, bound only for apps in a Wayland session. */
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
    /**
     * Holds the "run on the host" stand-ins ([HostCommands]), bound over the runtime's
     * `flatpak-spawn` and the app's `host-spawn` where those exist; null binds neither.
     */
    val hostCommands: File? = null,
    /**
     * Where Flatdroid's zypak goes ([Electron.zypakStandIn], written from [LaunchPlan.zypakStandIn]);
     * null starts Electron apps by their binary, as before the stand-in.
     */
    val zypakStandIn: File? = null,
    /**
     * Writable per-app base for the app's `/etc/ld.so.cache` ([LdCache]), one directory per set of
     * deployments; null for none.
     */
    val ldCache: File? = null,
    /** Per-launch folder for the extensions' merged folders ([MergedDir]), one subfolder each; null merges none. */
    val mergedDirs: File? = null,
)

/**
 * A folder that collects the entries of several extensions' folders, as Flatpak's `merge-dirs`
 * does: [dir] (bound at [guest]) holds a relative link per entry, to the extension that has it.
 * The first extension (in mount order) with an entry wins.
 */
data class MergedDir(val dir: File, val guest: String, val links: Map<String, String>)

/**
 * The app's `/etc/ld.so.cache`, as Flatpak builds one: the runtime's `ldconfig` run in the app's
 * root over [conf] (the app's and the extensions' library directories), so lookups that read the
 * cache rather than LD_LIBRARY_PATH (Python's `ctypes.util.find_library`, `ldconfig -p`) find the
 * app's libraries. [dir] is named after the deployments' commits; when it has no cache yet
 * ([generate]), the launch runs `ldconfig` before the app.
 */
data class LdCache(val dir: File, val conf: String, val generate: Boolean) {
    val cache: File get() = File(dir, LaunchPlanner.LD_CACHE)
    val confFile: File get() = File(dir, LaunchPlanner.LD_CONF)
}

/** Per-app choices that change a launch. */
data class LaunchOptions(
    /** Add Electron's flags for newer versions (set after a "GPU process isn't usable" exit). */
    val newerElectronFlags: Boolean = false,
    /** A prepared arm64/x64 Electron for an app that ships the other architecture's, if any. */
    val electronSwap: File? = null,
    val extraArgs: List<String> = emptyList(),
    /** An X11 session for an app that asks for both Wayland and X11 (its Force X11 setting); ignored for others. */
    val forceX11: Boolean = false,
    val environment: Map<String, String> = emptyMap(),
    /** The host's whole /dev, Binder included, whatever the app asks for (the app's own setting). */
    val wholeDev: Boolean = false,
    /** The app gets PulseAudio's socket (see AppSettings.audioAllowed). */
    val audio: Boolean = true,
    /** The app gets the Internet (see AppSettings.networkAllowed): resolv.conf, and Internet sockets. */
    val network: Boolean = true,
    /** fdsec also refuses the security level's extra system calls ([Fdsec.Filter.extraSyscalls]). */
    val extraSyscalls: Boolean = true,
    /** Only CPU and memory information from /sys, and `devices=all` gives no device group ([DevNodes.forApp]). */
    val limitDevices: Boolean = false,
    /** The root gets /.flatpak-info ([FlatpakInfo]). */
    val flatpakInfo: Boolean = true,
    /** Alpine's Flatpak version for /.flatpak-info ([FlatpakInfo.Instance.flatpakVersion]); null leaves it out. */
    val flatpakVersion: String? = null,
    /** The device's languages (BCP 47) for the app's locale; empty gives the generic C.UTF-8. */
    val languageTags: List<String> = emptyList(),
    /** GTK 4 and Qt Quick draw on the CPU instead of through GL ([com.garathon.flatdroid.settings.Rendering]). */
    val softwareRendering: Boolean = true,
    /** Electron and Chromium apps are told to reduce motion ([Electron.REDUCED_MOTION]). */
    val reduceMotion: Boolean = false,
    /** The app's desktop file says `Terminal=true`: it runs in a terminal ([LaunchPlan.terminal]). */
    val terminal: Boolean = false,
    /** An Alpine program's root instead of the installation's deployments: no extensions, ld cache, run-on-the-host stand-ins or /.flatpak-info. */
    val alpine: AlpineRoot? = null,
)

/** Everything needed to start an app: its proot binds, environment and command, and its root's layout. */
data class LaunchPlan(
    val appId: String,
    val app: Deployment,
    val runtime: Deployment,
    val binds: FlatpakBinds,
    val environment: Map<String, String>,
    val command: List<String>,
    /** Content of the root's /.flatpak-info; null for none ([FlatpakInfo]). */
    val flatpakInfo: String?,
    /** Top-level names that are links into /usr (bin, lib, …), as Flatpak makes them. */
    val usrLinks: List<String>,
    /** Electron or a Chromium browser, started without zypak or cobalt ([Electron]). */
    val isElectron: Boolean,
    /** Flatdroid's zypak for [HostDirs.zypakStandIn] ([Electron.zypakStandIn]); null when the app starts by its binary. */
    val zypakStandIn: String? = null,
    /** The session is Wayland (labwc and wayvnc) instead of Xvnc: the app takes Wayland, or runs in a terminal. */
    val wayland: Boolean = false,
    /**
     * Xvnc offers GLX: an X11 session drawn through GL ([LaunchOptions.softwareRendering] off). Apps
     * that make their GL context only through GLX (LWJGL 2, GLFW by default) need it; the rest use EGL.
     */
    val glx: Boolean = false,
    /**
     * The app runs in a terminal, a Wayland window of the session's: it gets the Wayland socket only
     * if it takes Wayland itself, and never X11.
     */
    val terminal: Boolean = false,
    /** What fdsec refuses for this app. */
    val filter: Fdsec.Filter,
    /** The app's ld.so.cache; null when there is none (no [HostDirs.ldCache], or no `ldconfig` in the runtime). */
    val ldCache: LdCache? = null,
    /** The extensions' merged folders, written before the launch. */
    val mergedDirs: List<MergedDir> = emptyList(),
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
        val alpine = options.alpine
        val app = alpine?.app ?: installation.app(appId) ?: return PlanResult.NotInstalled(appId)
        val runtimeRef = app.metadata.runtime ?: return PlanResult.RuntimeMissing("(none in metadata)")
        val runtime = alpine?.runtime ?: installation.runtime(runtimeRef) ?: return PlanResult.RuntimeMissing(runtimeRef.toString())
        val commandName = app.metadata.command?.takeIf { it.isNotBlank() } ?: return PlanResult.NoCommand(appId)
        val commandPath = if (commandName.startsWith("/")) commandName else "/app/bin/$commandName"

        // Runtime extension points mount under /usr, the app's under /app.
        val extensions = if (alpine != null) emptyList() else installation.extensions(runtime.metadata.extensionPoints, runtimeRef.branch).map { "/usr" to it } +
            installation.extensions(app.metadata.extensionPoints, app.ref.branch).map { "/app" to it }
        val glDefault = extensions.firstOrNull { (base, ext) ->
            base == "/usr" && ext.deployment.ref.name == GL_DEFAULT
        }?.second?.guestPath
        val ldPaths = extensions.mapNotNull { (base, ext) -> ext.addLdPath?.let { "$base/${ext.guestPath}/$it" } }

        // Electron apps start through zypak, and some Chromium browsers through cobalt: both skipped.
        // Wayland for every app that takes it, unless one that also takes X11 is set to Force X11.
        // A terminal app's session is Wayland whatever it takes: the terminal is a Wayland window.
        val context = app.metadata.context
        val wayland = context.wayland && !(options.forceX11 && context.waylandAndX11 && !options.terminal)
        val waylandSession = wayland || options.terminal
        var command = listOf(commandPath)
        var isElectron = false
        var zypakStandIn: String? = null
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
        // Alpine's fontconfig keeps its cache in /var, which isn't in an Alpine program's root.
        if (alpine == null) for (guest in RUNTIME_CACHE_DIRS) extraBinds += runtimeCacheScratch(dirs.runtimeCache, guest).path to guest
        if (alpine != null) {
            extraBinds += alpine.binds
            extraBinds += alpine.l2sBinds
            alpine.bwrap?.let { extraBinds += it to AlpineRoot.BWRAP }
        }
        dirs.hostFonts?.let { extraBinds += it.path to GuestPaths.HOST_FONTS }
        dirs.sdcard?.let { extraBinds += it.path to GuestPaths.SDCARD }
        dirs.sessionBus?.let { extraBinds += it.path to GuestPaths.SESSION_BUS_DIR }
        dirs.hostCommands?.takeIf { alpine == null }?.let { dir ->
            if (File(runtime.files, "bin/${HostCommands.FLATPAK_SPAWN}").isFile) {
                extraBinds += File(dir, HostCommands.FLATPAK_SPAWN).path to HostCommands.FLATPAK_SPAWN_GUEST
            }
            if (File(app.files, "bin/${HostCommands.HOST_SPAWN}").isFile) {
                extraBinds += File(dir, HostCommands.HOST_SPAWN).path to HostCommands.HOST_SPAWN_GUEST
            }
        }
        val runtimeDir = dirs.runtimeDir?.let { host -> GuestPaths.runtimeDir(dirs.userId).also { extraBinds += host.path to it } }
        val scripts = launchScripts(app, commandPath)
        val electronBinary = scripts.firstNotNullOfOrNull(Electron::zypakTarget)
        val cobaltBrowser = if (electronBinary != null) null
            else scripts.firstNotNullOfOrNull { script ->
                Electron.cobaltTarget(script, appId, config = { hostPath(app, Electron.COBALT_CONFIG)?.takeIf { it.isFile }?.readTextUpTo() }) { path ->
                    hostPath(app, path)?.isFile == true
                }
            }
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
            val flags = Electron.flags(options.newerElectronFlags, wayland, options.reduceMotion)
            // The app's own command, its zypak replaced by Flatdroid's: its launch script still runs.
            val standIn = dirs.zypakStandIn
            val zypak = if (standIn == null) emptyList() else Electron.ZYPAK_FILES.filter { zypakFileHere(app, it) }
            if (standIn != null && zypak.isNotEmpty()) {
                for (name in zypak) extraBinds += standIn.path to "/app/bin/$name"
                zypakStandIn = Electron.zypakStandIn(flags, nodeFlags = Electron.flags(newer = true, wayland, options.reduceMotion))
            } else {
                command = listOf(electronBinary) + flags
            }
        } else if (cobaltBrowser != null) {
            isElectron = true
            val binaryArch = (hostPath(app, cobaltBrowser)?.let(Executable::of) as? Executable.Elf)?.arch
            if (binaryArch != null && binaryArch != arch) return PlanResult.WrongArchitecture(cobaltBrowser, binaryArch)
            command = listOf(cobaltBrowser) + Electron.flags(options.newerElectronFlags, wayland, options.reduceMotion)
        } else {
            val binaryArch = (hostPath(app, commandPath)?.let(Executable::of) as? Executable.Elf)?.arch
            if (binaryArch != null && binaryArch != arch) return PlanResult.WrongArchitecture(commandPath, binaryArch)
        }
        command = command + alpine?.args.orEmpty() + options.extraArgs

        val mergedDirs = mergedDirs(dirs.mergedDirs, extensions)
        for (merged in mergedDirs) extraBinds += merged.dir.path to merged.guest
        val ldCache = ldCache(dirs.ldCache.takeIf { alpine == null }, runtime, listOf(app, runtime) + extensions.map { it.second.deployment }, extensions)
        if (ldCache != null) {
            extraBinds += ldCache.cache.path to "/etc/$LD_CACHE"
            if (ldCache.generate) {
                extraBinds += ldCache.dir.path to GuestPaths.LD_CACHE_DIR
                command = LD_CACHE_COMMAND + command
            }
        }

        val environment = LaunchEnvironment.build(
            appId = appId,
            triplet = LaunchEnvironment.triplet(arch),
            // As Flatpak: the runtime's (e.g. QT_PLUGIN_PATH in KDE's), then the app's over it.
            metadataEnvironment = runtime.metadata.environment + app.metadata.environment,
            glDefault = glDefault,
            extensionLdPaths = ldPaths,
            overrides = options.environment,
            kdePlatformTheme = DesktopConfig.KDE_PLATFORM_THEMES.any { File(runtime.files, it).exists() },
            sessionBus = dirs.sessionBus != null,
            lang = if (options.languageTags.isEmpty()) LaunchEnvironment.GENERIC_LOCALE
                else LaunchEnvironment.locale(options.languageTags, File(runtime.files, "lib/locale").list()?.toList().orEmpty()),
            runtimeDir = runtimeDir ?: "/tmp",
            flatpakContainer = options.flatpakInfo,
            wayland = wayland,
            softwareRendering = options.softwareRendering,
            terminal = if (!options.terminal) null else if (File(runtime.files, FOOT_TERMINFO).isFile) FOOT_TERM else FALLBACK_TERM,
            usrHas = { path -> path.startsWith("/usr/") && File(runtime.files, path.removePrefix("/usr/")).isDirectory },
            alpine = alpine != null,
        )
        // Nothing is bound where Alpine has nothing: proot would make a stand-in in Alpine's own folder.
        // The time zone goes beside the root's other files instead, named by TZ.
        val etc = dirs.etc.filterKeys { options.network || it != RESOLV_CONF }.filterKeys { alpine == null || !alpine.bindWouldChangeAlpine("/etc/$it") }
        if (alpine != null) {
            dirs.etc[LOCALTIME]?.takeIf { LOCALTIME !in etc }?.let { file ->
                extraBinds += file.path to GuestPaths.ALPINE_LOCALTIME
                environment["TZ"] = GuestPaths.ALPINE_LOCALTIME
            }
            extraBinds.removeAll { (_, guest) -> alpine.bindWouldChangeAlpine(guest) }
        }
        val devices = app.metadata.context.devices
        val binds = FlatpakBinds(
            root = dirs.root.path,
            runtimeFiles = runtime.files.path,
            appFiles = app.files.path,
            etcFiles = alpine?.etc,
            // Without the network, no DNS: the runtime's resolv.conf is a link that leads nowhere here.
            etc = etc.mapValues { it.value.path },
            extensions = extensions.map { (base, ext) -> ext.deployment.files.path to "$base/${ext.guestPath}" },
            tmp = dirs.tmp.path,
            shm = dirs.shm.path,
            x11Sockets = dirs.x11Sockets.path.takeIf { !waylandSession },
            waylandSockets = dirs.waylandSockets?.path?.takeIf { wayland },
            pulseSockets = dirs.pulseSockets?.takeIf { options.audio }?.path,
            home = dirs.home.path,
            appDirs = dirs.appDirs.map { it.first.path to it.second },
            privateFiles = dirs.privateFiles.map { it.first.path to it.second },
            dev = DevNodes.forApp(devices, devExists, limited = options.limitDevices),
            wholeDev = options.wholeDev,
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
                flatpakInfo = if (!options.flatpakInfo || alpine != null) null
                    else flatpakInfo(appId, runtimeRef, app, runtime, extensions, binds, options, dirs, wayland).text(),
                // Alpine's /bin, /lib and /sbin are folders of their own, bound beside /usr.
                usrLinks = if (alpine != null) emptyList() else USR_LINKS.filter { File(runtime.files, it).exists() },
                isElectron = isElectron,
                zypakStandIn = zypakStandIn,
                wayland = waylandSession,
                glx = !waylandSession && !options.softwareRendering,
                terminal = options.terminal,
                filter = Fdsec.Filter(extraSyscalls = options.extraSyscalls, noInternet = !options.network),
                ldCache = ldCache,
                mergedDirs = mergedDirs,
            )
        )
    }

    /**
     * The app's [LdCache] under [base], or null without one or when the runtime has no `ldconfig`
     * or `sh` to build it. Flatpak's order: the app's extensions, the app's own `/etc/ld.so.conf`,
     * `/app/lib`, then the runtime's extensions; the runtime's own directories are `ldconfig`'s
     * built-in ones.
     */
    private fun ldCache(base: File?, runtime: Deployment, deployments: List<Deployment>, extensions: List<Pair<String, MountedExtension>>): LdCache? {
        if (base == null) return null
        if (!File(runtime.files, "bin/ldconfig").exists() || !File(runtime.files, "bin/sh").exists()) return null
        fun paths(under: String) = extensions.filter { it.first == under }.mapNotNull { (b, ext) -> ext.addLdPath?.let { "$b/${ext.guestPath}/$it" } }
        val conf = (paths("/app") + listOf("include /app/etc/ld.so.conf", "/app/lib") + paths("/usr")).joinToString("\n", postfix = "\n")
        val key = sha256((deployments.map { it.dir.name } + conf).joinToString("\n")).take(16)
        val dir = File(base, key)
        val cache = File(dir, LD_CACHE)
        val ready = cache.isFile && readlink(cache) == null && cache.length() > 0
        return LdCache(dir, conf, generate = !ready)
    }

    /**
     * The text of the script at [commandPath] and of the scripts under /app it hands over to with
     * `exec` ([Electron.execTarget]), in that order, at most [MAX_SCRIPTS]: an IDE's command
     * script may set up its tools and only then call zypak, in a wrapper of its own.
     */
    /**
     * The root's /.flatpak-info: what the app's metadata asks for and this launch gives it (an
     * X11 socket only in an X11 session, the device groups bound, the bus names the proxy lets
     * through), the deployments as Alpine's Flatpak has them.
     */
    private fun flatpakInfo(
        appId: String, runtimeRef: FlatpakRef, app: Deployment, runtime: Deployment, extensions: List<Pair<String, MountedExtension>>,
        binds: FlatpakBinds, options: LaunchOptions, dirs: HostDirs, wayland: Boolean,
    ): FlatpakInfo {
        val context = app.metadata.context
        val x11 = binds.x11Sockets != null && context.sockets.any { it == "x11" || it == "fallback-x11" }
        val sockets = buildList {
            if (x11 && "x11" in context.sockets) add("x11")
            if (wayland) add("wayland")
            if (binds.pulseSockets != null && "pulseaudio" in context.sockets) add("pulseaudio")
            if ((x11 || wayland) && "fallback-x11" in context.sockets) add("fallback-x11")
        }
        val groups = DevNodes.GROUPS.filterValues { paths -> paths.any { it in binds.dev } }.keys
        val devices = when {
            options.wholeDev -> listOf(DevNodes.ALL)
            else -> groups.filter { it in context.devices || DevNodes.ALL in context.devices } + listOfNotNull("shm".takeIf { it in context.devices })
        }
        val policy = LinkedHashMap<String, String>()
        if (dirs.sessionBus != null) policy += SessionBus.granted(app.metadata.sessionBusPolicy, SessionBus.WHOLE_BUS in context.sockets)
        if (HostCommands.hostAllowed(app.metadata.sessionBusPolicy, context.sockets) && policy[HostCommands.FLATPAK_NAME] != "own") {
            policy[HostCommands.FLATPAK_NAME] = "talk"
        }
        fun ids(base: String) = extensions.filter { it.first == base }.associate { (_, ext) -> ext.deployment.ref.name to commit(ext.deployment) }
        return FlatpakInfo(
            appId = appId,
            runtime = runtimeRef,
            shared = listOfNotNull("network".takeIf { options.network }, "ipc".takeIf { "ipc" in context.shared }),
            sockets = sockets,
            devices = devices,
            features = context.features.filter { it == PER_APP_DEV_SHM },
            filesystems = FlatpakInfo.filesystems(context.filesystems, sdcard = dirs.sdcard != null),
            persistent = context.persistent,
            sessionBusPolicy = policy,
            environment = runtime.metadata.environment + app.metadata.environment + options.environment,
            instance = FlatpakInfo.Instance(
                appPath = installation.alpinePath(app.files),
                appCommit = commit(app),
                runtimePath = installation.alpinePath(runtime.files),
                runtimeCommit = commit(runtime),
                appExtensions = ids("/app"),
                runtimeExtensions = ids("/usr"),
                branch = app.ref.branch,
                arch = app.ref.arch,
                flatpakVersion = options.flatpakVersion,
                sessionBusProxy = dirs.sessionBus != null,
            ),
        )
    }

    /**
     * Flatpak's `merge-dirs`: for each extension and each of its point's merged folders, the
     * entries of that folder linked into the folder of the same name beside the extension
     * (`<point>/<ext>/lv2/x.lv2` as `<point>/lv2/x.lv2`). Only folders with an entry are made.
     */
    private fun mergedDirs(base: File?, extensions: List<Pair<String, MountedExtension>>): List<MergedDir> {
        if (base == null) return emptyList()
        val merged = LinkedHashMap<String, LinkedHashMap<String, String>>()
        for ((root, ext) in extensions) {
            val parent = ext.guestPath.substringBeforeLast('/', "")
            val name = ext.guestPath.substringAfterLast('/')
            for (dir in ext.mergeDirs) {
                val entries = File(ext.deployment.files, dir).list()?.sorted() ?: continue
                val guest = listOf(root, parent, dir).filter { it.isNotEmpty() }.joinToString("/")
                val links = merged.getOrPut(guest) { LinkedHashMap() }
                // Relative, so proot resolves it inside the root: up out of the merged folder, into the extension's.
                val up = "../".repeat(dir.count { it == '/' } + 1)
                for (entry in entries) links.putIfAbsent(entry, "$up$name/$dir/$entry")
            }
        }
        return merged.filterValues { it.isNotEmpty() }.entries.mapIndexed { i, (guest, links) -> MergedDir(File(base, "$i"), guest, links) }
    }

    /** The commit a deployment was checked out from: its folder's name, without the `-<languages>` a partial (locale) checkout adds. */
    private fun commit(deployment: Deployment): String = deployment.dir.name.let { COMMIT.find(it)?.value ?: it }

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
     * Whether the app's `/app/bin/<name>` is a file of its own (a link into link2symlink's store
     * counts as one); a link elsewhere (`zypak-wrapper` to the script) reaches a bound one anyway.
     */
    private fun zypakFileHere(app: Deployment, name: String): Boolean {
        val file = File(app.files, "bin/$name")
        val target = readlink(file) ?: return file.isFile
        return L2s.inStore(target, l2sDir) && File(target).isFile
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
        const val LD_CACHE = "ld.so.cache"
        const val LD_CONF = "ld.so.conf"

        /**
         * Runs before the app when its [LdCache] has to be built: the runtime's `ldconfig` writes the
         * cache in [GuestPaths.LD_CACHE_DIR], which is already bound at /etc/ld.so.cache (it renames
         * its new file into place, and proot's binds follow the path), then the app replaces the shell.
         * `-X` leaves the libraries' links alone and `-i` skips the auxiliary cache.
         */
        val LD_CACHE_COMMAND = listOf(
            "/usr/bin/sh", "-c",
            "/usr/bin/ldconfig -X -i -f ${GuestPaths.LD_CACHE_DIR}/$LD_CONF -C ${GuestPaths.LD_CACHE_DIR}/$LD_CACHE; exec \"\$@\"",
            "sh",
        )

        const val GL_DEFAULT = "org.freedesktop.Platform.GL.default"
        /** A terminal app's TERM: the terminal's own name where the runtime describes it, else what it is compatible with. */
        const val FOOT_TERM = "foot"
        const val FALLBACK_TERM = "xterm-256color"
        /** The runtime's description of [FOOT_TERM] (relative to its files). */
        const val FOOT_TERMINFO = "share/terminfo/f/foot"
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

        /** `features=` entry for a private /dev/shm, which every app has here. */
        const val PER_APP_DEV_SHM = "per-app-dev-shm"

        /** An OSTree commit (SHA-256) at the start of a deployment's folder name. */
        private val COMMIT = Regex("^[0-9a-f]{64}")

        /** Flatpak links these to /usr when the runtime has them. */
        val USR_LINKS = listOf("bin", "lib", "lib32", "lib64", "sbin")

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

private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
