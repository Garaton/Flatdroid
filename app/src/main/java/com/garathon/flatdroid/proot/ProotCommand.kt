package com.garathon.flatdroid.proot

/** Host paths proot needs. All must be absolute. */
data class ProotPaths(
    /** nativeLibraryDir/libproot.so */
    val proot: String,
    /** nativeLibraryDir/libproot-loader.so */
    val loader: String,
    /** Private directory for proot's temporary files. */
    val tmpDir: String,
    /** The Alpine rootfs. */
    val rootfs: String,
) {
    /** link2symlink's backing files; Flatpak's hard links end up here. */
    val l2sDir: String get() = "$rootfs/.l2s"

    init {
        for (path in listOf(proot, loader, tmpDir, rootfs)) {
            require(path.startsWith("/")) { "Not an absolute path: $path" }
        }
    }
}

/**
 * Host files and directories bound into the Alpine root. All live outside the rootfs, so nothing
 * running in Alpine can swap one for a symlink that a later proot would then bind.
 */
data class AlpineBinds(
    /** Generated resolv.conf. */
    val resolvConf: String,
    /** Generated hosts. */
    val hosts: String,
    /** Private directory for the guest's /tmp. */
    val tmp: String,
    /** Private directory for the guest's /dev/shm (Android has none). */
    val shm: String,
    /** An empty directory, bound over /sys/fs/selinux so guest tools see SELinux as absent. */
    val emptyDir: String,
    /** Stand-ins for /proc entries the app can't read: guest path to host file. */
    val fakeProc: Map<String, String> = emptyMap(),
    /** Host /dev entries bound at the same path (see [DevNodes]); the rest of /dev isn't there. */
    val dev: List<String> = emptyList(),
    /** Further binds, host path to guest path. */
    val extra: List<Pair<String, String>> = emptyList(),
) {
    init {
        val hostPaths = listOf(resolvConf, hosts, tmp, shm, emptyDir) + fakeProc.values + extra.map { it.first }
        // proot splits --bind=host:guest at the colon.
        for (path in hostPaths) require(path.startsWith("/") && ':' !in path) { "Unusable host path: $path" }
        for (path in fakeProc.keys + extra.map { it.second }) {
            require(path.startsWith("/") && ':' !in path) { "Unusable guest path: $path" }
        }
        require(dev.all(DevNodes::isAllowed)) { "Unusable /dev path: $dev" }
    }

    companion object {
        /** Guest /dev entries and the /proc/self/fd paths that stand in for them; always bound. */
        val FD_LINKS = linkedMapOf(
            "/dev/fd" to "/proc/self/fd",
            "/dev/stdin" to "/proc/self/fd/0",
            "/dev/stdout" to "/proc/self/fd/1",
            "/dev/stderr" to "/proc/self/fd/2",
        )
    }
}

/** Host paths bound into a Flatpak's own root. All absolute, without colons. */
data class FlatpakBinds(
    /** The app's root directory (its layout and /.flatpak-info). */
    val root: String,
    /** Runtime `files`, bound to /usr; its `etc` is bound to /etc. */
    val runtimeFiles: String,
    /** App `files`, bound to /app. */
    val appFiles: String,
    /** Generated files over the runtime's: name in /etc to host file. */
    val etc: Map<String, String>,
    /** Installed extensions: host directory to guest path (under /usr or /app). */
    val extensions: List<Pair<String, String>> = emptyList(),
    /** Private /tmp and /dev/shm. */
    val tmp: String,
    val shm: String,
    /** Xvnc's socket directory ([GuestPaths.X11_DIR]) and PulseAudio's ([GuestPaths.PULSE_DIR]; null without sound). */
    val x11Sockets: String,
    val pulseSockets: String?,
    /** [GuestPaths.HOME], then private directories over parts of it: host to guest path. */
    val home: String,
    val homeOverlays: List<Pair<String, String>> = emptyList(),
    /** Host /dev entries bound at the same path (see [DevNodes]), unless [wholeDev]. */
    val dev: List<String> = emptyList(),
    /** The host's whole /dev instead, Binder included: only by the app's own setting. */
    val wholeDev: Boolean = false,
    /** Further binds, last: host to guest path (e.g. a swapped-in Electron over its app directory). */
    val extra: List<Pair<String, String>> = emptyList(),
) {
    init {
        val hosts = listOfNotNull(root, runtimeFiles, appFiles, tmp, shm, x11Sockets, pulseSockets, home) +
            etc.values + extensions.map { it.first } + homeOverlays.map { it.first } + extra.map { it.first }
        for (path in hosts) require(path.startsWith("/") && ':' !in path) { "Unusable host path: $path" }
        val guests = extensions.map { it.second } + homeOverlays.map { it.second } + extra.map { it.second }
        for (path in guests) require(path.startsWith("/") && ':' !in path) { "Unusable guest path: $path" }
        for (name in etc.keys) require(name.isNotEmpty() && '/' !in name && ':' !in name && name != "..") { "Unusable /etc name: $name" }
        require(dev.all(DevNodes::isAllowed)) { "Unusable /dev path: $dev" }
    }
}

/** A complete process description: argv and the whole environment (nothing is inherited). */
data class ProotInvocation(val argv: List<String>, val env: Map<String, String>)

/**
 * The one place proot invocations are built: always an argument list, never a shell string.
 * The environment is complete, since the process starts from a cleared one.
 */
object ProotCommand {
    val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    const val FLATPAK_HOST_RESOLV_CONF = "/run/host/monitor/resolv.conf"

    /**
     * Runs [command] (absolute guest path first) in the Alpine rootfs as fake root. Only the
     * rootfs and [binds] are visible; nothing from shared storage. The display helpers need fake
     * root too: Xvnc runs xkbcomp only after setgid()/setuid(), which Android refuses to an app
     * unless proot fakes them.
     */
    fun alpine(paths: ProotPaths, binds: AlpineBinds, command: List<String>, cwd: String = "/root"): ProotInvocation {
        require(command.isNotEmpty() && command[0].startsWith("/")) {
            "Guest command must start with an absolute path: $command"
        }
        val argv = mutableListOf(
            paths.proot,
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "-L",
            "--change-id=0:0",
            "--rootfs=${paths.rootfs}",
            "--cwd=$cwd",
        )
        for (path in binds.dev) argv += "--bind=$path"
        argv += listOf("--bind=/proc", "--bind=/sys", "--bind=/dev/urandom:/dev/random")
        for ((guest, host) in AlpineBinds.FD_LINKS) argv += "--bind=$host:$guest"
        argv += "--bind=${binds.shm}:/dev/shm"
        argv += "--bind=${binds.tmp}:/tmp"
        argv += "--bind=${binds.resolvConf}:/etc/resolv.conf"
        // Where a Flatpak host has it. Runtimes ship etc/resolv.conf as a link to this path, and
        // Flatpak replaces that link while deploying only if it sees it: proot answers a
        // faccessat2(AT_SYMLINK_NOFOLLOW) about a link through its target, so a dangling link
        // looks missing, and the deploy fails with "File exists" (KDE runtimes).
        argv += "--bind=${binds.resolvConf}:$FLATPAK_HOST_RESOLV_CONF"
        argv += "--bind=${binds.hosts}:/etc/hosts"
        argv += "--bind=${binds.emptyDir}:/sys/fs/selinux"
        for ((guest, host) in binds.fakeProc) argv += "--bind=$host:$guest"
        for ((host, guest) in binds.extra) argv += "--bind=$host:$guest"
        argv += command

        val env = linkedMapOf(
            "PROOT_LOADER" to paths.loader,
            "PROOT_TMP_DIR" to paths.tmpDir,
            "PROOT_L2S_DIR" to paths.l2sDir,
            "PATH" to GUEST_PATH,
            "HOME" to "/root",
            "LANG" to "C.UTF-8",
        )
        return ProotInvocation(argv, env)
    }

    /**
     * Runs a Flatpak in its own root: runtime on /usr, app on /app, as the Android user (no
     * --change-id). The guest starts `[prefix…] /usr/bin/env <environment> <command>`; [prefix]
     * is for a helper that must run before the app (empty for now). The environment goes through
     * `env` rather than proot's own, which carries only proot's settings.
     */
    fun flatpak(
        paths: ProotPaths,
        binds: FlatpakBinds,
        environment: Map<String, String>,
        command: List<String>,
        prefix: List<String> = emptyList(),
    ): ProotInvocation {
        require(command.isNotEmpty() && command[0].startsWith("/")) {
            "Guest command must start with an absolute path: $command"
        }
        require(command[0].none { it == '=' }) { "The command would be read as a variable: ${command[0]}" }
        require(environment.keys.all { Regex("[A-Za-z_][A-Za-z0-9_]*").matches(it) }) { "Bad variable name in $environment" }
        val argv = mutableListOf(
            paths.proot,
            "--kill-on-exit",
            "--sysvipc",
            "--link2symlink",
            "-L",
            "--rootfs=${binds.root}",
            "--cwd=/",
            // Flatpak's hard links are symlinks into .l2s, at its host path.
            "--bind=${paths.l2sDir}",
            "--bind=${binds.runtimeFiles}:/usr",
            "--bind=${binds.appFiles}:/app",
            "--bind=${binds.runtimeFiles}/etc:/etc",
        )
        for ((name, host) in binds.etc) argv += "--bind=$host:/etc/$name"
        for ((host, guest) in binds.extensions) argv += "--bind=$host:$guest"
        if (binds.wholeDev) argv += "--bind=/dev" else for (path in binds.dev) argv += "--bind=$path"
        argv += listOf("--bind=/proc", "--bind=/sys")
        for ((guest, host) in AlpineBinds.FD_LINKS) argv += "--bind=$host:$guest"
        argv += "--bind=${binds.shm}:/dev/shm"
        argv += "--bind=${binds.tmp}:/tmp"
        argv += "--bind=${binds.x11Sockets}:${GuestPaths.X11_DIR}"
        binds.pulseSockets?.let { argv += "--bind=$it:${GuestPaths.PULSE_DIR}" }
        argv += "--bind=${binds.home}:${GuestPaths.HOME}"
        for ((host, guest) in binds.homeOverlays) argv += "--bind=$host:$guest"
        for ((host, guest) in binds.extra) argv += "--bind=$host:$guest"
        argv += prefix
        argv += "/usr/bin/env"
        for ((key, value) in environment) argv += "$key=$value"
        argv += command

        val env = linkedMapOf(
            "PROOT_LOADER" to paths.loader,
            "PROOT_TMP_DIR" to paths.tmpDir,
            "PROOT_L2S_DIR" to paths.l2sDir,
        )
        return ProotInvocation(argv, env)
    }
}
