package com.garathon.flatdroid.launcher

import com.garathon.flatdroid.flatpak.FlatpakInstallation
import com.garathon.flatdroid.flatpak.TestInstallation
import com.garathon.flatdroid.proot.DevNodes
import com.garathon.flatdroid.proot.Fdsec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LaunchPlannerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val inst by lazy { TestInstallation(File(tmp.root, "flatpak")) }
    private val l2s by lazy { File(tmp.root, "l2s").apply { mkdirs() } }
    private val planner by lazy { LaunchPlanner(FlatpakInstallation(inst.root, "aarch64"), "aarch64", l2s, TestInstallation::readlink) }
    private val dirs by lazy {
        val d = File(tmp.root, "host")
        HostDirs(
            root = File(d, "root"), tmp = File(d, "tmp"), shm = File(d, "shm"), home = File(d, "home"),
            appDirs = listOf(File(d, "config") to "/home/user/.config"),
            etc = mapOf("passwd" to File(d, "passwd")),
            x11Sockets = File(d, "x11"), pulseSockets = File(d, "pulse"), fontDirs = File(d, "font-dirs.xml"),
            desktopConfig = File(d, "xdg"), userDirs = File(d, "user-dirs.dirs"), runtimeCache = File(d, "runtime-cache"),
        )
    }

    /** The further binds every app gets. */
    private fun baseExtra(id: String) = listOf(
        dirs.fontDirs.path to "/run/host/font-dirs.xml",
        dirs.desktopConfig.path to "/run/flatdroid/xdg",
        dirs.userDirs.path to "/home/user/.var/app/$id/config/user-dirs.dirs",
        dirs.userDirs.path to "/home/user/.config/user-dirs.dirs",
        LaunchPlanner.runtimeCacheScratch(dirs.runtimeCache, "/usr/lib/fontconfig/cache").path to "/usr/lib/fontconfig/cache",
    )

    private fun appMetadata(id: String, command: String, runtime: String = "org.freedesktop.Platform/aarch64/26.08", extra: String = "") =
        "[Application]\nname=$id\nruntime=$runtime\ncommand=$command\n$extra"

    private fun runtimeWithGl() = inst.runtime(
        "org.freedesktop.Platform", "26.08",
        "[Runtime]\nname=org.freedesktop.Platform\n\n[Extension org.freedesktop.Platform.GL]\n" +
            "versions=26.08;1.4\ndirectory=lib/aarch64-linux-gnu/GL\nsubdirectories=true\nadd-ld-path=lib\n",
    ).also { File(it, "bin").mkdirs(); File(it, "lib").mkdirs() }

    private fun Ready(result: PlanResult) = (result as PlanResult.Ready).plan

    @Test
    fun nativeAppWithGlAndMetadataEnvironment() {
        runtimeWithGl()
        inst.runtime("org.freedesktop.Platform.GL.default", "26.08")
        val files = inst.app(
            "org.supertuxproject.SuperTux", metadata = appMetadata("org.supertuxproject.SuperTux", "supertux2", extra = "[Environment]\nLD_LIBRARY_PATH=/app/lib/x\n"),
        )
        File(files, "bin").mkdirs()
        File(files, "bin/supertux2").writeBytes(ExecutableTest.elf(0xb7))

        val plan = Ready(planner.plan("org.supertuxproject.SuperTux", dirs))
        assertEquals(listOf("/app/bin/supertux2"), plan.command)
        assertEquals(baseExtra("org.supertuxproject.SuperTux"), plan.binds.extra)
        assertFalse(plan.isElectron)
        assertEquals(listOf("bin", "lib"), plan.usrLinks)
        assertEquals(
            "[Application]\nname=org.supertuxproject.SuperTux.Devel\nruntime=runtime/org.freedesktop.Platform/aarch64/26.08\n\n[Instance]\nbuild=true\n",
            plan.flatpakInfo,
        )
        assertEquals(listOf("/usr/lib/aarch64-linux-gnu/GL/default"), plan.binds.extensions.map { it.second })
        assertEquals(
            "/app/lib/x:/app/lib:/usr/lib/aarch64-linux-gnu:/usr/lib/aarch64-linux-gnu/GL/default/lib",
            plan.environment["LD_LIBRARY_PATH"],
        )
        assertEquals("1", plan.environment["LIBGL_ALWAYS_SOFTWARE"])
        assertEquals(dirs.home.path, plan.binds.home)
    }

    @Test
    fun networkOffDropsResolvConfAndInternetSocketsAndDevicesCanBeLimited() {
        runtimeWithGl()
        val files = inst.app("org.example.Tool", metadata = appMetadata("org.example.Tool", "tool", extra = "[Context]\ndevices=all;\n"))
        File(files, "bin").mkdirs()
        File(files, "bin/tool").writeBytes(ExecutableTest.elf(0xb7))
        val withEtc = dirs.copy(etc = mapOf("resolv.conf" to File(tmp.root, "resolv.conf"), "passwd" to File(tmp.root, "passwd")))
        val everything: (String) -> Boolean = { true }
        val limitedPlanner = LaunchPlanner(FlatpakInstallation(inst.root, "aarch64"), "aarch64", l2s, TestInstallation::readlink, everything)

        val open = Ready(limitedPlanner.plan("org.example.Tool", withEtc))
        assertEquals(setOf("resolv.conf", "passwd"), open.binds.etc.keys)
        assertEquals(Fdsec.Filter(extraSyscalls = true, noInternet = false), open.filter)
        assertEquals(null, open.binds.sys)
        assertTrue("/dev/input" in open.binds.dev)

        val closed = Ready(limitedPlanner.plan("org.example.Tool", withEtc, LaunchOptions(network = false, extraSyscalls = false, limitDevices = true)))
        assertEquals(setOf("passwd"), closed.binds.etc.keys)
        assertEquals(Fdsec.Filter(extraSyscalls = false, noInternet = true), closed.filter)
        assertEquals(DevNodes.LIMITED_SYS, closed.binds.sys)
        // `devices=all` with devices limited: only the basic nodes, not even the GPU.
        assertEquals(DevNodes.BASE, closed.binds.dev)
    }

    @Test
    fun runtimeEnvironmentComesFirstAndTheAppsOverridesIt() {
        inst.runtime("org.kde.Platform", "6.10", "[Runtime]\nname=org.kde.Platform\n\n[Environment]\nQT_PLUGIN_PATH=/app/lib/plugins\nA=runtime\n")
        val files = inst.app("org.kde.okular", metadata = appMetadata("org.kde.okular", "okular", "org.kde.Platform/aarch64/6.10", "[Environment]\nA=app\n"))
        File(files, "bin").mkdirs()
        File(files, "bin/okular").writeBytes(ExecutableTest.elf(0xb7))

        val plan = Ready(planner.plan("org.kde.okular", dirs))
        assertEquals("/app/lib/plugins", plan.environment["QT_PLUGIN_PATH"])
        assertEquals("app", plan.environment["A"])
    }

    @Test
    fun sdcardDarkModeAndKdesThemeWhenAsked() {
        val runtime = inst.runtime("org.kde.Platform", "6.10", "[Runtime]\nname=org.kde.Platform\n")
        File(runtime, "lib/plugins/platformthemes").mkdirs()
        File(runtime, "lib/plugins/platformthemes/KDEPlasmaPlatformTheme6.so").writeText("")
        val files = inst.app("org.kde.kate", metadata = appMetadata("org.kde.kate", "kate", "org.kde.Platform/aarch64/6.10"))
        File(files, "bin").mkdirs()
        File(files, "bin/kate").writeBytes(ExecutableTest.elf(0xb7))
        val sdcard = tmp.newFolder("sdcard")

        val plain = Ready(planner.plan("org.kde.kate", dirs))
        assertEquals("kde", plain.environment["QT_QPA_PLATFORMTHEME"])
        assertFalse("ADW_DEBUG_COLOR_SCHEME" in plain.environment)
        val plan = Ready(planner.plan("org.kde.kate", dirs.copy(sdcard = sdcard), LaunchOptions(darkTheme = true)))
        assertEquals(baseExtra("org.kde.kate") + (sdcard.path to "/sdcard"), plan.binds.extra)
        assertEquals("prefer-dark", plan.environment["ADW_DEBUG_COLOR_SCHEME"])
    }

    @Test
    fun androidsFontsAreBoundWhereFlatpakPutsTheHosts() {
        runtimeWithGl()
        File(inst.app("a.App", metadata = appMetadata("a.App", "a")), "bin").mkdirs()
        val fonts = tmp.newFolder("fonts")
        assertEquals(baseExtra("a.App") + (fonts.path to "/run/host/fonts"), Ready(planner.plan("a.App", dirs.copy(hostFonts = fonts))).binds.extra)
    }

    @Test
    fun onlyAppsWithoutX11GetAWaylandSession() {
        runtimeWithGl()
        val wayland = tmp.newFolder("wayland")
        fun plan(id: String, sockets: String): LaunchPlan {
            val files = inst.app(id, metadata = appMetadata(id, "app", extra = "[Context]\nsockets=$sockets\n"))
            File(files, "bin").mkdirs()
            File(files, "bin/app").writeBytes(ExecutableTest.elf(0xb7))
            return Ready(planner.plan(id, dirs.copy(waylandSockets = wayland)))
        }
        val only = plan("page.codeberg.dnkl.foot", "wayland;")
        assertTrue(only.wayland)
        assertEquals(wayland.path, only.binds.waylandSockets)
        assertEquals("/run/flatdroid/wayland/wayland-0", only.environment["WAYLAND_DISPLAY"])
        for (sockets in listOf("wayland;fallback-x11;", "x11;wayland;", "x11;")) {
            val both = plan("org.example.App" + sockets.length, sockets)
            assertFalse(sockets, both.wayland)
            assertEquals(null, both.binds.waylandSockets)
            assertEquals(":1", both.environment["DISPLAY"])
        }
    }

    @Test
    fun theSessionBusIsBoundWithItsAddress() {
        inst.runtime("org.kde.Platform", "6.10", "[Runtime]\nname=org.kde.Platform\n")
        val files = inst.app("org.kde.kmines", metadata = appMetadata("org.kde.kmines", "kmines", "org.kde.Platform/aarch64/6.10"))
        File(files, "bin").mkdirs()
        File(files, "bin/kmines").writeBytes(ExecutableTest.elf(0xb7))
        val bus = tmp.newFolder("bus")

        assertFalse("DBUS_SESSION_BUS_ADDRESS" in Ready(planner.plan("org.kde.kmines", dirs)).environment)
        val plan = Ready(planner.plan("org.kde.kmines", dirs.copy(sessionBus = bus)))
        assertEquals(baseExtra("org.kde.kmines") + (bus.path to "/run/flatpak"), plan.binds.extra)
        assertEquals("unix:path=/run/flatpak/bus", plan.environment["DBUS_SESSION_BUS_ADDRESS"])
    }

    @Test
    fun missingRuntimeAndCommandAreReported() {
        inst.app("a.App", metadata = appMetadata("a.App", "a"))
        assertEquals(PlanResult.RuntimeMissing("org.freedesktop.Platform/aarch64/26.08"), planner.plan("a.App", dirs))
        assertEquals(PlanResult.NotInstalled("b.App"), planner.plan("b.App", dirs))
        runtimeWithGl()
        inst.app("c.App", metadata = "[Application]\nname=c.App\nruntime=org.freedesktop.Platform/aarch64/26.08\n")
        assertEquals(PlanResult.NoCommand("c.App"), planner.plan("c.App", dirs))
    }

    @Test
    fun linksToGuestPathsOutsideAppAreNotReadOnTheHost() {
        runtimeWithGl()
        val files = inst.app("y.App", metadata = appMetadata("y.App", "y"))
        File(files, "bin").mkdirs()
        // Means the runtime's /usr inside the root, not the host's /usr: not followed.
        TestInstallation.link(File(files, "bin/y"), "/usr/bin/env")
        assertEquals(listOf("/app/bin/y"), Ready(planner.plan("y.App", dirs)).command)
        // A binary reached through link2symlink's directory (a host path) is checked.
        val content = File(l2s, ".l2s.z0001.0002").apply { writeBytes(ExecutableTest.elf(0x3e)) }
        val files2 = inst.app("z.App", metadata = appMetadata("z.App", "z"))
        File(files2, "bin").mkdirs()
        TestInstallation.link(File(files2, "bin/z"), content.path)
        assertEquals(PlanResult.WrongArchitecture("/app/bin/z", "x86_64"), planner.plan("z.App", dirs))
    }

    @Test
    fun foreignNativeBinaryIsRefused() {
        runtimeWithGl()
        val files = inst.app("x.App", metadata = appMetadata("x.App", "x"))
        File(files, "bin").mkdirs()
        File(files, "bin/x").writeBytes(ExecutableTest.elf(0x3e))
        assertEquals(PlanResult.WrongArchitecture("/app/bin/x", "x86_64"), planner.plan("x.App", dirs))
    }

    @Test
    fun electronAppSkipsZypakAndFollowsGuestLinks() {
        runtimeWithGl()
        val files = inst.app("md.obsidian.Obsidian", metadata = appMetadata("md.obsidian.Obsidian", "obsidian"))
        File(files, "bin").mkdirs()
        File(files, "obsidian.sh").writeText("#!/bin/sh\nexec zypak-wrapper /app/obsidian \"\$@\"\n")
        // A link to a guest path: only resolvable inside /app.
        TestInstallation.link(File(files, "bin/obsidian"), "/app/obsidian.sh")
        File(files, "obsidian").writeBytes(ExecutableTest.elf(0xb7))

        val plan = Ready(planner.plan("md.obsidian.Obsidian", dirs, LaunchOptions(extraArgs = listOf("--x"))))
        assertTrue(plan.isElectron)
        assertEquals(listOf("/app/obsidian") + Electron.BASE_FLAGS + "--x", plan.command)

        val newer = Ready(planner.plan("md.obsidian.Obsidian", dirs, LaunchOptions(newerElectronFlags = true)))
        assertEquals(listOf("/app/obsidian") + Electron.BASE_FLAGS + Electron.NEWER_FLAGS, newer.command)
    }

    @Test
    fun cobaltBrowserRunsWithoutCobalt() {
        runtimeWithGl()
        val files = inst.app("com.google.Chrome", metadata = appMetadata("com.google.Chrome", "chrome"))
        File(files, "bin").mkdirs()
        File(files, "bin/chrome").writeText("#!/usr/bin/bash\nexec cobalt \"\$@\"\n")
        File(files, "extra").mkdirs()
        File(files, "extra/chrome").writeBytes(ExecutableTest.elf(0xb7))

        val plan = Ready(planner.plan("com.google.Chrome", dirs))
        assertTrue(plan.isElectron)
        assertEquals(listOf("/app/extra/chrome") + Electron.BASE_FLAGS, plan.command)
        // Its extra data downloaded for another architecture.
        File(files, "extra/chrome").writeBytes(ExecutableTest.elf(0x3e))
        assertEquals(PlanResult.WrongArchitecture("/app/extra/chrome", "x86_64"), planner.plan("com.google.Chrome", dirs))
    }

    @Test
    fun foreignElectronNeedsASwapUntilOneIsPrepared() {
        runtimeWithGl()
        val id = "dev.bambosh.UnofficialHomestuckCollection"
        val files = inst.app(id, metadata = appMetadata(id, "uhc"))
        File(files, "bin").mkdirs()
        File(files, "bin/uhc").writeText("#!/bin/sh\nexec zypak-wrapper.sh /app/uhc/uhc \"\$@\"\n")
        File(files, "uhc/resources").mkdirs()
        File(files, "uhc/uhc").writeBytes(ExecutableTest.elf(0x3e) + "..Electron/9.4.4 ".toByteArray())

        val needed = planner.plan(id, dirs) as PlanResult.ElectronSwapNeeded
        assertEquals("9.4.4", needed.version)
        assertEquals("x86_64", needed.binaryArch)
        assertEquals("/app/uhc", needed.appDir)
        assertEquals("uhc", needed.binaryName)
        assertEquals(File(files, "uhc"), needed.hostAppDir)
        assertFalse(needed.hasNativeModules)

        val swap = tmp.newFolder("swap")
        File(swap, "uhc").writeBytes(ExecutableTest.elf(0xb7))
        val plan = Ready(planner.plan(id, dirs, LaunchOptions(electronSwap = swap)))
        assertEquals(baseExtra(id) + (swap.path to "/app/uhc"), plan.binds.extra)
        assertEquals(listOf("/app/uhc/uhc") + Electron.BASE_FLAGS, plan.command)
    }

    @Test
    fun devFollowsTheMetadatasDevices() {
        val devPlanner = LaunchPlanner(FlatpakInstallation(inst.root, "aarch64"), "aarch64", l2s, TestInstallation::readlink) { it != "/dev/kvm" }
        runtimeWithGl()
        fun plan(id: String, devices: String?, options: LaunchOptions = LaunchOptions()): LaunchPlan {
            val context = devices?.let { "\n[Context]\ndevices=$it\n" } ?: ""
            File(inst.app(id, metadata = appMetadata(id, "x", extra = context)), "bin").mkdirs()
            return Ready(devPlanner.plan(id, dirs, options))
        }
        assertEquals(DevNodes.BASE, plan("a.Plain", null).binds.dev)
        assertEquals(DevNodes.BASE + "/dev/dri", plan("a.Dri", "dri;").binds.dev)
        val all = plan("a.All", "all;")
        assertEquals(DevNodes.BASE + listOf("/dev/dri", "/dev/input", "/dev/bus/usb"), all.binds.dev)
        assertFalse(all.binds.wholeDev)
        assertTrue(plan("a.All2", "all;", LaunchOptions(wholeDev = true)).binds.wholeDev)
        // The setting only applies to apps that ask for all devices.
        assertFalse(plan("a.Dri2", "dri;", LaunchOptions(wholeDev = true)).binds.wholeDev)
    }

    @Test
    fun flatpakInfoUnlessTheAppRunsWithoutIt() {
        runtimeWithGl()
        File(inst.app("org.kde.konsole", metadata = appMetadata("org.kde.konsole", "x")), "bin").mkdirs()
        assertTrue(Ready(planner.plan("org.kde.konsole", dirs)).flatpakInfo!!.contains("build=true"))
        assertEquals(null, Ready(planner.plan("org.kde.konsole", dirs, LaunchOptions(flatpakInfo = false))).flatpakInfo)
    }

    @Test
    fun noSoundLeavesOutPulseAudio() {
        runtimeWithGl()
        File(inst.app("a.Quiet", metadata = appMetadata("a.Quiet", "x")), "bin").mkdirs()
        assertEquals(File(dirs.pulseSockets, "").path, Ready(planner.plan("a.Quiet", dirs)).binds.pulseSockets)
        assertEquals(null, Ready(planner.plan("a.Quiet", dirs, LaunchOptions(audio = false))).binds.pulseSockets)
    }

    @Test
    fun electronBehindAnIdeWrapperIsFound() {
        runtimeWithGl()
        val id = "com.vscodium.codium"
        val files = inst.app(id, metadata = appMetadata(id, id))
        File(files, "bin").mkdirs()
        // The command script sets things up and hands over to a wrapper, which calls zypak's helper.
        File(files, "bin/$id").writeText("#!/usr/bin/bash\nexec_editor() {\n  exec \"/app/bin/$id-wrapper\" \"/app/share/codium/resources/app/out/cli.js\" \"\$@\"\n}\nexec_editor \"\$@\"\n")
        File(files, "bin/$id-wrapper").writeText("#!/usr/bin/bash\nexec \"/app/bin/zypak-helper\" host - /app/share/codium/$id \"\$@\"\n")
        File(files, "share/codium").mkdirs()
        File(files, "share/codium/$id").writeBytes(ExecutableTest.elf(0xb7))

        val plan = Ready(planner.plan(id, dirs))
        assertTrue(plan.isElectron)
        assertEquals(listOf("/app/share/codium/$id") + Electron.BASE_FLAGS, plan.command)
    }

    @Test
    fun runtimeDirLocaleAndContainerAsFlatpaksOrGeneric() {
        val runtime = runtimeWithGl()
        File(runtime, "lib/locale/de_DE.utf8").mkdirs()
        val files = inst.app("org.xfce.mousepad", metadata = appMetadata("org.xfce.mousepad", "mousepad"))
        File(files, "bin").mkdirs()
        File(files, "bin/mousepad").writeBytes(ExecutableTest.elf(0xb7))
        val withRuntimeDir = dirs.copy(runtimeDir = File(tmp.root, "run"), userId = 10235)

        val shared = Ready(planner.plan("org.xfce.mousepad", withRuntimeDir, LaunchOptions(languageTags = listOf("de-DE"))))
        assertEquals("/run/user/10235", shared.environment["XDG_RUNTIME_DIR"])
        assertTrue(File(tmp.root, "run").path to "/run/user/10235" in shared.binds.extra)
        assertEquals("de_DE.UTF-8", shared.environment["LANG"])
        assertEquals("flatpak", shared.environment["container"])

        // Not shared: the generic locale; without /.flatpak-info, no `container` either.
        val generic = Ready(planner.plan("org.xfce.mousepad", withRuntimeDir, LaunchOptions(flatpakInfo = false)))
        assertEquals(LaunchEnvironment.GENERIC_LOCALE, generic.environment["LANG"])
        assertFalse("container" in generic.environment)
        assertTrue(LaunchPlanner.LOCALTIME in LaunchPlanner.ETC_OVERRIDES && LaunchPlanner.TIMEZONE in LaunchPlanner.ETC_OVERRIDES)
    }
}
