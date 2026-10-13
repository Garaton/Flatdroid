package com.garathon.flatdroid.proot

import org.junit.Assert.assertEquals
import org.junit.Test

class ProotCommandTest {
    private val paths = ProotPaths(
        proot = "/lib/libproot.so",
        loader = "/lib/libproot-loader.so",
        tmpDir = "/files/proot-tmp",
        rootfs = "/files/alpine/rootfs",
    )

    @Test
    fun alpineArgvIsFlagsThenCommand() {
        val inv = ProotCommand.alpine(paths, listOf("/sbin/apk", "--version"))
        assertEquals(
            listOf(
                "/lib/libproot.so", "--kill-on-exit", "--link2symlink", "--sysvipc", "-L",
                "--change-id=0:0", "--rootfs=/files/alpine/rootfs", "--cwd=/root",
                "--bind=/dev", "--bind=/proc", "--bind=/sys", "--bind=/dev/urandom:/dev/random",
                "/sbin/apk", "--version",
            ),
            inv.argv,
        )
    }

    @Test
    fun environmentIsCompleteAndAbsolute() {
        val env = ProotCommand.alpine(paths, listOf("/bin/echo", "hi")).env
        assertEquals("/lib/libproot-loader.so", env["PROOT_LOADER"])
        assertEquals("/files/proot-tmp", env["PROOT_TMP_DIR"])
        assertEquals("/files/alpine/rootfs/.l2s", env["PROOT_L2S_DIR"])
        assertEquals(ProotCommand.GUEST_PATH, env["PATH"])
        assertEquals(setOf("PROOT_LOADER", "PROOT_TMP_DIR", "PROOT_L2S_DIR", "PATH", "HOME", "LANG"), env.keys)
    }

    @Test
    fun argumentsWithSpacesStayOneArgument() {
        val inv = ProotCommand.alpine(paths, listOf("/bin/echo", "a b; rm -rf /"))
        assertEquals("a b; rm -rf /", inv.argv.last())
    }

    @Test(expected = IllegalArgumentException::class)
    fun relativeGuestCommandIsRejected() {
        ProotCommand.alpine(paths, listOf("echo", "hi"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun relativeHostPathIsRejected() {
        paths.copy(rootfs = "alpine/rootfs")
    }
}
