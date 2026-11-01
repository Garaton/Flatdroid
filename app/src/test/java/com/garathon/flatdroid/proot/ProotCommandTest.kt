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
        fdsec = "/lib/libfdsec.so",
    )
    private val binds = AlpineBinds(
        resolvConf = "/files/alpine/generated/resolv.conf",
        hosts = "/files/alpine/generated/hosts",
        tmp = "/cache/alpine/tmp",
        shm = "/cache/alpine/shm",
        emptyDir = "/files/alpine/generated/empty",
        dev = listOf("/dev/null", "/dev/urandom"),
    )

    @Test
    fun alpineArgvIsFlagsBindsThenCommand() {
        val inv = ProotCommand.alpine(paths, binds, listOf("/sbin/apk", "--version"))
        assertEquals(
            listOf(
                "/lib/libproot.so", "--kill-on-exit", "--link2symlink", "--sysvipc", "-L",
                "--change-id=0:0", "--rootfs=/files/alpine/rootfs", "--cwd=/root",
                "--bind=/dev/null", "--bind=/dev/urandom",
                "--bind=/proc", "--bind=/sys", "--bind=/dev/urandom:/dev/random",
                "--bind=/proc/self/fd:/dev/fd", "--bind=/proc/self/fd/0:/dev/stdin",
                "--bind=/proc/self/fd/1:/dev/stdout", "--bind=/proc/self/fd/2:/dev/stderr",
                "--bind=/cache/alpine/shm:/dev/shm",
                "--bind=/cache/alpine/tmp:/tmp",
                "--bind=/files/alpine/generated/resolv.conf:/etc/resolv.conf",
                "--bind=/files/alpine/generated/resolv.conf:/run/host/monitor/resolv.conf",
                "--bind=/files/alpine/generated/hosts:/etc/hosts",
                "--bind=/files/alpine/generated/empty:/sys/fs/selinux",
                "--bind=/lib/libfdsec.so:/run/flatdroid/fdsec",
                "/run/flatdroid/fdsec", "--", "/sbin/apk", "--version",
            ),
            inv.argv,
        )
    }

    @Test
    fun alpineWithoutAFilterStillHasFdsecBoundForItsScripts() {
        val argv = ProotCommand.alpine(paths, binds, listOf("/bin/sh", "-c", "x"), filter = null).argv
        assertEquals(listOf("--bind=/lib/libfdsec.so:/run/flatdroid/fdsec", "/bin/sh", "-c", "x"), argv.takeLast(4))
        val filtered = ProotCommand.alpine(paths, binds, listOf("/bin/sh"), filter = Fdsec.Filter(extraSyscalls = true, noInternet = true)).argv
        assertEquals(listOf("/run/flatdroid/fdsec", "--extra", "--no-inet", "--", "/bin/sh"), filtered.takeLast(5))
    }

    @Test
    fun fakeProcAndExtraBindsComeBeforeTheCommand() {
        val inv = ProotCommand.alpine(
            paths,
            binds.copy(
                fakeProc = mapOf("/proc/stat" to "/files/alpine/generated/proc/stat"),
                extra = listOf("/cache/apk-cache" to "/var/cache/apk"),
            ),
            listOf("/bin/true"),
        )
        val argv = inv.argv
        assertTrue("--bind=/files/alpine/generated/proc/stat:/proc/stat" in argv)
        assertEquals("--bind=/cache/apk-cache:/var/cache/apk", argv[argv.size - 5])
        assertEquals("/bin/true", argv.last())
    }

    @Test
    fun environmentIsCompleteAndAbsolute() {
        val env = ProotCommand.alpine(paths, binds, listOf("/bin/echo", "hi")).env
        assertEquals("/lib/libproot-loader.so", env["PROOT_LOADER"])
        assertEquals("/cache/proot-tmp", env["PROOT_TMP_DIR"])
        assertEquals("/files/alpine/rootfs/.l2s", env["PROOT_L2S_DIR"])
        assertEquals(ProotCommand.GUEST_PATH, env["PATH"])
        assertEquals(setOf("PROOT_LOADER", "PROOT_TMP_DIR", "PROOT_L2S_DIR", "PATH", "HOME", "LANG", "FLATPAK_BWRAP"), env.keys)
        assertEquals("/run/flatdroid/bwrap", env["FLATPAK_BWRAP"])
    }

    @Test
    fun applyExtraSeesOnlyTheRuntimeAndItsOwnFiles() {
        val inv = ProotCommand.applyExtra(paths, "/cache/extra-data/root", "/rt/files", "/deploy/files", "/cache/extra-data/tmp")
        assertTrue("--bind=/rt/files:/usr" in inv.argv && "--bind=/deploy/files:/app" in inv.argv && "--cwd=/app/extra" in inv.argv)
        assertTrue(inv.argv.none { it.contains("/home") || it.contains("sdcard") || it == "--change-id=0:0" || it == "-0" })
        // Behind the filter, without Internet sockets (Flatpak gives it no network).
        assertEquals(listOf("--bind=/lib/libfdsec.so:/.flatdroid/fdsec", "/.flatdroid/fdsec", "--extra", "--no-inet", "--", "/app/bin/apply_extra"), inv.argv.takeLast(6))
        assertEquals("/app/bin:/usr/bin", inv.env["PATH"])
        // No runtime ([Extra Data] NoRuntime=true): nothing on /usr.
        assertTrue(ProotCommand.applyExtra(paths, "/r", null, "/d", "/t").argv.none { it.endsWith(":/usr") })
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
    fun nonDevPathIsRejected() {
        binds.copy(dev = listOf("/etc/passwd"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun binderIsRejected() {
        flatpakBinds.copy(dev = listOf("/dev/binder"))
    }

    @Test
    fun wholeDevReplacesTheList() {
        val argv = ProotCommand.flatpak(paths, flatpakBinds.copy(wholeDev = true), emptyMap(), listOf("/app/bin/x"), Fdsec.Filter()).argv
        assertTrue("--bind=/dev" in argv)
        assertTrue("--bind=/dev/null" !in argv)
        assertTrue("--bind=/proc/self/fd:/dev/fd" in argv)
    }

    @Test
    fun noSoundMeansNoPulseAudioSocket() {
        val argv = ProotCommand.flatpak(paths, flatpakBinds.copy(pulseSockets = null), emptyMap(), listOf("/app/bin/x"), Fdsec.Filter()).argv
        assertTrue(argv.none { it.endsWith(":/run/pulse") })
        assertTrue("--bind=/cache/session/pulse:/run/pulse" in ProotCommand.flatpak(paths, flatpakBinds, emptyMap(), listOf("/app/bin/x"), Fdsec.Filter()).argv)
    }

    private val flatpakBinds = FlatpakBinds(
        root = "/cache/roots/org.xfce.mousepad",
        runtimeFiles = "/fp/runtime/org.freedesktop.Platform/aarch64/26.08/abc/files",
        appFiles = "/fp/app/org.xfce.mousepad/aarch64/stable/def/files",
        etc = linkedMapOf("resolv.conf" to "/files/launcher/etc/resolv.conf", "passwd" to "/files/launcher/etc/passwd"),
        extensions = listOf("/fp/runtime/GL.default/files" to "/usr/lib/aarch64-linux-gnu/GL/default"),
        tmp = "/cache/apptmp/org.xfce.mousepad/tmp",
        shm = "/cache/apptmp/org.xfce.mousepad/shm",
        x11Sockets = "/cache/session/x11",
        pulseSockets = "/cache/session/pulse",
        home = "/files/appdata/org.xfce.mousepad/home",
        appDirs = listOf("/files/appdata/org.xfce.mousepad/config" to "/home/user/.config"),
        dev = listOf("/dev/null", "/dev/dri"),
        extra = listOf("/files/electron/x" to "/app/x"),
    )

    /** The same binds, in the same order, as the tested shell launcher (fdrun). */
    @Test
    fun flatpakArgvMatchesTheTestedLauncher() {
        val inv = ProotCommand.flatpak(paths, flatpakBinds, linkedMapOf("PATH" to "/app/bin:/usr/bin", "A" to "x y"), listOf("/app/bin/mousepad", "--flag"), Fdsec.Filter())
        assertEquals(
            listOf(
                "/lib/libproot.so", "--sysvipc", "--link2symlink", "-L",
                "--rootfs=/cache/roots/org.xfce.mousepad", "--cwd=/home/user",
                "--bind=/files/alpine/rootfs/.l2s",
                "--bind=/fp/runtime/org.freedesktop.Platform/aarch64/26.08/abc/files:/usr",
                "--bind=/fp/app/org.xfce.mousepad/aarch64/stable/def/files:/app",
                "--bind=/fp/runtime/org.freedesktop.Platform/aarch64/26.08/abc/files/etc:/etc",
                "--bind=/files/launcher/etc/resolv.conf:/etc/resolv.conf",
                "--bind=/files/launcher/etc/passwd:/etc/passwd",
                "--bind=/fp/runtime/GL.default/files:/usr/lib/aarch64-linux-gnu/GL/default",
                "--bind=/dev/null", "--bind=/dev/dri", "--bind=/proc", "--bind=/sys",
                "--bind=/proc/self/fd:/dev/fd", "--bind=/proc/self/fd/0:/dev/stdin",
                "--bind=/proc/self/fd/1:/dev/stdout", "--bind=/proc/self/fd/2:/dev/stderr",
                "--bind=/cache/apptmp/org.xfce.mousepad/shm:/dev/shm",
                "--bind=/cache/apptmp/org.xfce.mousepad/tmp:/tmp",
                "--bind=/cache/session/x11:/tmp/.X11-unix",
                "--bind=/cache/session/pulse:/run/pulse",
                "--bind=/files/appdata/org.xfce.mousepad/home:/home/user",
                "--bind=/files/appdata/org.xfce.mousepad/config:/home/user/.config",
                "--bind=/files/electron/x:/app/x",
                "--bind=/lib/libfdsec.so:/.flatdroid/fdsec",
                "/.flatdroid/fdsec", "--",
                "/usr/bin/env", "PATH=/app/bin:/usr/bin", "A=x y",
                "/app/bin/mousepad", "--flag",
            ),
            inv.argv,
        )
        assertEquals(setOf("PROOT_LOADER", "PROOT_TMP_DIR", "PROOT_L2S_DIR"), inv.env.keys)
        assertTrue("--change-id=0:0" !in inv.argv)
    }

    @Test
    fun theFilterRunsBeforeEnv() {
        val argv = ProotCommand.flatpak(paths, flatpakBinds, emptyMap(), listOf("/app/bin/x"), Fdsec.Filter(extraSyscalls = true, noInternet = true)).argv
        assertEquals(listOf("/.flatdroid/fdsec", "--extra", "--no-inet", "--", "/usr/bin/env", "/app/bin/x"), argv.takeLast(6))
    }

    @Test
    fun hiddenHelperProcessesReducedSysAndPrivateFiles() {
        val binds = flatpakBinds.copy(
            hiddenProc = listOf("/proc/700", "/proc/701"), emptyDir = "/cache/session/empty",
            sys = listOf("/sys/devices/system/cpu"),
            privateFiles = listOf("/files/appdata/org.xfce.mousepad/dotfiles/.bashrc" to "/home/user/.bashrc"),
        )
        val argv = ProotCommand.flatpak(paths, binds, emptyMap(), listOf("/app/bin/x"), Fdsec.Filter()).argv
        val proc = argv.indexOf("--bind=/proc")
        assertEquals(listOf("--bind=/cache/session/empty:/proc/700", "--bind=/cache/session/empty:/proc/701", "--bind=/sys/devices/system/cpu"), argv.subList(proc + 1, proc + 4))
        assertTrue("--bind=/sys" !in argv)
        assertTrue("--bind=/files/appdata/org.xfce.mousepad/dotfiles/.bashrc:/home/user/.bashrc" in argv)
    }

    @Test(expected = IllegalArgumentException::class)
    fun onlyProcessEntriesCanBeHidden() {
        flatpakBinds.copy(hiddenProc = listOf("/proc/self"), emptyDir = "/cache/session/empty")
    }

    @Test(expected = IllegalArgumentException::class)
    fun reducedSysStaysInSys() {
        flatpakBinds.copy(sys = listOf("/sys/../data"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun flatpakCommandThatEnvWouldReadAsAVariableIsRejected() {
        ProotCommand.flatpak(paths, flatpakBinds, emptyMap(), listOf("/app/bin/a=b"), Fdsec.Filter())
    }

    @Test(expected = IllegalArgumentException::class)
    fun flatpakEnvironmentNameIsChecked() {
        ProotCommand.flatpak(paths, flatpakBinds, mapOf("--help" to "x"), listOf("/app/bin/x"), Fdsec.Filter())
    }

    @Test(expected = IllegalArgumentException::class)
    fun flatpakEtcNameCannotLeaveEtc() {
        flatpakBinds.copy(etc = mapOf("../usr/x" to "/files/x"))
    }
}
