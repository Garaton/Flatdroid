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

/** A complete process description: argv and the whole environment (nothing is inherited). */
data class ProotInvocation(val argv: List<String>, val env: Map<String, String>)

/**
 * The one place proot invocations are built: always an argument list, never a shell string.
 * The environment is complete, since the process starts from a cleared one.
 */
object ProotCommand {
    val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** Runs [command] (absolute guest path first) in the Alpine rootfs as fake root, as setup does. */
    fun alpine(paths: ProotPaths, command: List<String>, cwd: String = "/root"): ProotInvocation {
        require(command.isNotEmpty() && command[0].startsWith("/")) {
            "Guest command must start with an absolute path: $command"
        }
        val argv = listOf(
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
        ) + command
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
