package com.garathon.flatdroid.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotCommandTest {
    private val paths = ProotPaths(
        proot = "/lib/libproot.so",
        loader = "/lib/libproot-loader.so",
        tmpDir = "/cache/proot-tmp",
        rootfs = "/files/alpine/rootfs",
        fdsec = "/lib/libfdsec.so",
        fdshm = "/lib/libfdshm.so",
        fdv4l2 = "/lib/libfdv4l2.so",
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
                "--bind=/lib/libfdshm.so:/run/flatdroid/fdshm.so",
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
    fun alpineTakesATerminalThroughFdsecAndAddsTheEnvironment() {
        val inv = ProotCommand.alpine(paths, binds, listOf("/bin/sh", "-l"), tty = "/dev/pts/4", environment = mapOf("TERM" to "foot"))
        assertEquals(listOf("/run/flatdroid/fdsec", "--tty", "/dev/pts/4", "--", "/bin/sh", "-l"), inv.argv.takeLast(6))
        assertEquals("foot", inv.env["TERM"])
        assertEquals("/root", inv.env["HOME"])
        // Without a filter, fdsec only hands over the terminal.
        assertEquals(
            listOf("/run/flatdroid/fdsec", "--no-filter", "--tty", "/dev/pts/4", "--", "/bin/sh"),
            ProotCommand.alpine(paths, binds, listOf("/bin/sh"), filter = null, tty = "/dev/pts/4").argv.takeLast(6),
        )
        assertThrows(IllegalArgumentException::class.java) { ProotCommand.alpine(paths, binds, listOf("/bin/sh"), environment = mapOf("A B" to "x")) }
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
        assertEquals("--bind=/cache/apk-cache:/var/cache/apk", argv[argv.size - 6])
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
        // The whole /dev has no links of the root's own: the host's are bound over it.
        assertTrue("--bind=/proc/self/fd:/dev/fd" in argv)
        assertTrue(ProotCommand.flatpak(paths, flatpakBinds, emptyMap(), listOf("/app/bin/x"), Fdsec.Filter()).argv.none { it.startsWith("--bind=/proc/self/fd") })
    }

    @Test
    fun noX11SocketsInAWaylandSession() {
        val argv = ProotCommand.flatpak(paths, flatpakBinds.copy(x11Sockets = null, waylandSockets = "/cache/session/wayland"), emptyMap(), listOf("/app/bin/x"), Fdsec.Filter()).argv
        assertTrue(argv.none { it.endsWith(":/tmp/.X11-unix") })
        assertTrue("--bind=/cache/session/wayland:/run/flatdroid/wayland" in argv)
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

    @Test
    fun hostPathsBehindTheHomeAndItsBinds() {
        val binds = flatpakBinds.copy(extra = flatpakBinds.extra + ("/storage/emulated/0" to "/sdcard") + ("/files/other" to "/home/user/.config"))
        assertEquals("/files/appdata/org.xfce.mousepad/home", binds.hostOf("/home/user"))
        assertEquals("/files/appdata/org.xfce.mousepad/home/Music", binds.hostOf("/home/user/Music/"))
        // The last bind at the longest path, as proot.
        assertEquals("/files/other/a", binds.hostOf("/home/user/.config/a"))
        assertEquals("/files/appdata/org.xfce.mousepad/home/.configx", binds.hostOf("/home/user/.configx"))
        assertEquals("/storage/emulated/0/Download", binds.hostOf("/sdcard/Download"))
        for (refused in listOf("/usr/share", "/tmp/a", "/home/user/../etc", "/home/user/./a", "home/user", "/home/username")) {
            assertNull(refused, binds.hostOf(refused))
        }
    }

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
    fun cameraBindsComeAfterDevWithTheLibrary() {
        val binds = flatpakBinds.copy(
            wholeDev = true,
            cameraBinds = listOf(
                "/cache/session/video/video0" to "/dev/video0", "/cache/session/video/media0" to "/dev/media0",
                "/cache/session/video/control0" to "/.flatdroid/video0.format", "/cache/session/cameras" to "/.flatdroid/cameras",
                "/cache/session/camera-sys/devices" to "/sys/devices/flatdroid", "/cache/session/camera-sys/udev" to "/run/udev",
                "/cache/session/camera-sys/char/media0" to "/sys/dev/char/238:0",
            ),
        )
        val argv = ProotCommand.flatpak(paths, binds, emptyMap(), listOf("/app/bin/x"), Fdsec.Filter()).argv
        val video = argv.indexOf("--bind=/cache/session/video/video0:/dev/video0")
        assertTrue(video > argv.indexOf("--bind=/dev"))
        assertEquals(
            listOf("--bind=/lib/libfdv4l2.so:/.flatdroid/fdv4l2.so", "--bind=/lib/libfdsec.so:/.flatdroid/fdsec"),
            argv.subList(video + 7, video + 9),
        )
        // None without cameras in device mode.
        assertTrue(ProotCommand.flatpak(paths, flatpakBinds, emptyMap(), listOf("/app/bin/x"), Fdsec.Filter()).argv.none { "fdv4l2" in it || "/dev/video" in it })
        // An Alpine program's root has the library without cameras.
        assertTrue("--bind=/lib/libfdv4l2.so:/.flatdroid/fdv4l2.so" in ProotCommand.flatpak(paths, flatpakBinds.copy(fdv4l2 = true), emptyMap(), listOf("/app/bin/x"), Fdsec.Filter()).argv)
    }

    @Test
    fun cameraBindsGoOnlyWhereDeviceModeBindsAndComeWithTheirList() {
        val list = "/cache/cameras" to "/.flatdroid/cameras"
        for (guest in listOf("/dev/binder", "/sys/class/net", "/sys/dev/char", "/sys/subsystem", "/.flatdroid/fdsec", "/run/user", "/dev/video0/../binder")) {
            assertThrows(guest, IllegalArgumentException::class.java) { flatpakBinds.copy(cameraBinds = listOf("/cache/v" to guest, list)) }
        }
        assertThrows(IllegalArgumentException::class.java) { flatpakBinds.copy(cameraBinds = listOf("/cache/v" to "/dev/video0")) }
        flatpakBinds.copy(cameraBinds = listOf("/cache/v" to "/dev/video0", "/cache/m" to "/sys/dev/char/81:0", list))
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

    @Test
    fun procStandInsComeAfterProc() {
        val binds = flatpakBinds.copy(fakeProc = mapOf("/proc/version" to "/files/alpine/generated/proc/version"))
        val argv = ProotCommand.flatpak(paths, binds, emptyMap(), listOf("/app/bin/x"), Fdsec.Filter()).argv
        assertTrue(argv.indexOf("--bind=/files/alpine/generated/proc/version:/proc/version") > argv.indexOf("--bind=/proc"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun procStandInsStayInProc() {
        flatpakBinds.copy(fakeProc = mapOf("/etc/passwd" to "/files/alpine/generated/proc/version"))
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
