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
    /** Which of /dev/fd, /dev/stdin, /dev/stdout, /dev/stderr the host lacks. */
    val missingFdLinks: Set<String> = emptySet(),
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
        require(missingFdLinks.all { it in FD_LINKS }) { "Unknown fd link: $missingFdLinks" }
    }

    companion object {
        /** Guest /dev entries and the /proc/self/fd paths that stand in for them. */
        val FD_LINKS = linkedMapOf(
            "/dev/fd" to "/proc/self/fd",
            "/dev/stdin" to "/proc/self/fd/0",
            "/dev/stdout" to "/proc/self/fd/1",
            "/dev/stderr" to "/proc/self/fd/2",
        )
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

    /**
     * Runs [command] (absolute guest path first) in the Alpine rootfs as fake root. Only the
     * rootfs and [binds] are visible; nothing from shared storage.
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
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=/dev/urandom:/dev/random",
        )
        for ((guest, host) in AlpineBinds.FD_LINKS) {
            if (guest in binds.missingFdLinks) argv += "--bind=$host:$guest"
        }
        argv += "--bind=${binds.shm}:/dev/shm"
        argv += "--bind=${binds.tmp}:/tmp"
        argv += "--bind=${binds.resolvConf}:/etc/resolv.conf"
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
}
