package com.garathon.flatdroid.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotCommandTest {
    private val paths = ProotPaths(
        proot = "/lib/libproot.so",
        loader = "/lib/libproot-loader.so",
        tmpDir = "/cache/proot-tmp",
        rootfs = "/files/alpine/rootfs",
    )
    private val binds = AlpineBinds(
        resolvConf = "/files/alpine/generated/resolv.conf",
        hosts = "/files/alpine/generated/hosts",
        tmp = "/cache/alpine/tmp",
        shm = "/cache/alpine/shm",
        emptyDir = "/files/alpine/generated/empty",
    )

    @Test
    fun alpineArgvIsFlagsBindsThenCommand() {
        val inv = ProotCommand.alpine(paths, binds, listOf("/sbin/apk", "--version"))
        assertEquals(
            listOf(
                "/lib/libproot.so", "--kill-on-exit", "--link2symlink", "--sysvipc", "-L",
                "--change-id=0:0", "--rootfs=/files/alpine/rootfs", "--cwd=/root",
                "--bind=/dev", "--bind=/proc", "--bind=/sys", "--bind=/dev/urandom:/dev/random",
                "--bind=/cache/alpine/shm:/dev/shm",
                "--bind=/cache/alpine/tmp:/tmp",
                "--bind=/files/alpine/generated/resolv.conf:/etc/resolv.conf",
                "--bind=/files/alpine/generated/hosts:/etc/hosts",
                "--bind=/files/alpine/generated/empty:/sys/fs/selinux",
                "/sbin/apk", "--version",
            ),
            inv.argv,
        )
    }

    @Test
    fun fakeProcFdLinksAndExtraBindsComeBeforeTheCommand() {
        val inv = ProotCommand.alpine(
            paths,
            binds.copy(
                fakeProc = mapOf("/proc/stat" to "/files/alpine/generated/proc/stat"),
                missingFdLinks = setOf("/dev/stdin", "/dev/fd"),
                extra = listOf("/cache/apk-cache" to "/var/cache/apk"),
            ),
            listOf("/bin/true"),
        )
        val argv = inv.argv
        assertTrue("--bind=/proc/self/fd:/dev/fd" in argv)
        assertTrue("--bind=/proc/self/fd/0:/dev/stdin" in argv)
        assertTrue("--bind=/proc/self/fd/1:/dev/stdout" !in argv)
        assertTrue("--bind=/files/alpine/generated/proc/stat:/proc/stat" in argv)
        assertEquals("--bind=/cache/apk-cache:/var/cache/apk", argv[argv.size - 2])
        assertEquals("/bin/true", argv.last())
    }

    @Test
    fun environmentIsCompleteAndAbsolute() {
        val env = ProotCommand.alpine(paths, binds, listOf("/bin/echo", "hi")).env
        assertEquals("/lib/libproot-loader.so", env["PROOT_LOADER"])
        assertEquals("/cache/proot-tmp", env["PROOT_TMP_DIR"])
        assertEquals("/files/alpine/rootfs/.l2s", env["PROOT_L2S_DIR"])
        assertEquals(ProotCommand.GUEST_PATH, env["PATH"])
        assertEquals(setOf("PROOT_LOADER", "PROOT_TMP_DIR", "PROOT_L2S_DIR", "PATH", "HOME", "LANG"), env.keys)
    }

    @Test
    fun argumentsWithSpacesStayOneArgument() {
        val inv = ProotCommand.alpine(paths, binds, listOf("/bin/echo", "a b; rm -rf /"))
        assertEquals("a b; rm -rf /", inv.argv.last())
    }

    @Test(expected = IllegalArgumentException::class)
    fun relativeGuestCommandIsRejected() {
        ProotCommand.alpine(paths, binds, listOf("echo", "hi"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun relativeHostPathIsRejected() {
        paths.copy(rootfs = "alpine/rootfs")
    }

    @Test(expected = IllegalArgumentException::class)
    fun colonInBindSourceIsRejected() {
        binds.copy(tmp = "/cache/a:b")
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownFdLinkIsRejected() {
        binds.copy(missingFdLinks = setOf("/etc/passwd"))
    }
}
