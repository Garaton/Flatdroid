package com.garathon.flatdroid.alpine.programs

import com.garathon.flatdroid.flatpak.FlatpakMetadata
import com.garathon.flatdroid.flatpak.Keyfile
import com.garathon.flatdroid.settings.AppSettings
import com.garathon.flatdroid.settings.VideoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class AlpineProgramsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun rootfs(): File {
        val root = tmp.newFolder("rootfs")
        fun write(path: String, text: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }
        // foot came with Flatdroid's terminal group, under its virtual package.
        write("etc/apk/world", "flatpak\n.flatdroid-terminal\nmousepad\nxeyes\nhidden-app\n")
        write(
            "lib/apk/db/installed",
            listOf(
                "P:.flatdroid-terminal\nD:foot\n",
                "P:foot\no:foot\nF:usr/share/applications\nR:foot.desktop\nR:foot-server.desktop\n",
                "P:mousepad\no:mousepad\nD:so:libgtk-3.so.0\nF:usr/bin\nR:mousepad\nF:usr/share/applications\nR:org.xfce.mousepad.desktop\n",
                "P:xeyes\no:xeyes\nD:so:libX11.so.6\nF:usr/share/applications\nR:xeyes.desktop\n",
                "P:hidden-app\no:hidden-app\nF:usr/share/applications\nR:hidden.desktop\n",
                "P:dep-only\no:dep-only\nF:usr/share/applications\nR:dep.desktop\n",
            ).joinToString("\n"),
        )
        write("usr/bin/mousepad", "")
        write("usr/bin/xeyes", "")
        write("usr/bin/foot", "")
        write("usr/share/icons/hicolor/128x128/apps/org.xfce.mousepad.png", "png")
        val entry = "[Desktop Entry]\nType=Application\n"
        write("usr/share/applications/org.xfce.mousepad.desktop", entry + "Name=Mousepad\nExec=mousepad %F\nIcon=org.xfce.mousepad\n")
        write("usr/share/applications/xeyes.desktop", entry + "Name=XEyes\nExec=/usr/bin/xeyes -geometry 100x100\nTerminal=false\n")
        write("usr/share/applications/foot.desktop", entry + "Name=Foot\nExec=foot\n")
        write("usr/share/applications/foot-server.desktop", entry + "Name=Foot Server\nExec=foot --server\n")
        write("usr/share/applications/hidden.desktop", entry + "Name=Hidden\nExec=mousepad\nNoDisplay=true\n")
        write("usr/share/applications/dep.desktop", entry + "Name=Dep\nExec=mousepad\n")
        return root
    }

    @Test
    fun theUsersProgramsWithTheirCommandsIconsAndDisplays() {
        val root = rootfs()
        val programs = AlpinePrograms(root).list()
        // foot is Flatdroid's, hidden.desktop is NoDisplay, dep-only isn't in the world file.
        assertEquals(listOf("alpine+org.xfce.mousepad", "alpine+xeyes"), programs.map { it.id })
        val mousepad = programs[0]
        assertEquals("Mousepad", mousepad.name)
        assertEquals("mousepad", mousepad.packageName)
        assertEquals(listOf("/usr/bin/mousepad"), mousepad.command)
        assertEquals(File(root, "usr/share/icons/hicolor/128x128/apps/org.xfce.mousepad.png"), mousepad.icon)
        assertEquals(AlpinePackages.Display.WAYLAND_AND_X11, mousepad.display)
        val xeyes = programs[1]
        assertEquals(listOf("/usr/bin/xeyes", "-geometry", "100x100"), xeyes.command)
        assertEquals(AlpinePackages.Display.X11, xeyes.display)
        assertNull(xeyes.icon)
        assertEquals("xeyes", AlpinePrograms(root).program("alpine+xeyes")?.packageName)
    }

    @Test
    fun aMetaPackagesPartsAreListedThroughTheirLinks() {
        val root = rootfs()
        fun write(path: String, text: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }
        File(root, "etc/apk/world").appendText("office\n")
        File(root, "lib/apk/db/installed").appendText(
            "\n" + listOf(
                "P:office\no:office\nD:office-writer office-common\n",
                "P:office-common\no:office\nF:usr/bin\nR:office\nF:usr/share/applications\nR:office-start.desktop\n",
                "P:office-writer\no:office\nF:usr/share/applications\nR:office-writer.desktop\n",
            ).joinToString("\n"),
        )
        write("usr/bin/office", "")
        val entry = "[Desktop Entry]\nType=Application\n"
        write("usr/lib/office/xdg/start.desktop", entry + "Name=Office\nExec=office %U\n")
        write("usr/lib/office/xdg/writer.desktop", entry + "Name=Writer\nExec=office --writer %U\nIcon=office-writer\n")
        write("usr/lib/office/icons/writer.png", "png")
        val apps = File(root, "usr/share/applications").toPath()
        // As Alpine has them: guest paths, which lead nowhere on the host.
        Files.createSymbolicLink(apps.resolve("office-start.desktop"), Paths.get("/usr/lib/office/xdg/start.desktop"))
        Files.createSymbolicLink(apps.resolve("office-writer.desktop"), Paths.get("../../lib/office/xdg/writer.desktop"))
        File(root, "usr/share/icons/hicolor/128x128/apps").mkdirs()
        Files.createSymbolicLink(File(root, "usr/share/icons/hicolor/128x128/apps/office-writer.png").toPath(), Paths.get("/usr/lib/office/icons/writer.png"))
        val readLink: (File) -> String? = { f -> if (Files.isSymbolicLink(f.toPath())) Files.readSymbolicLink(f.toPath()).toString() else null }

        val programs = AlpinePrograms(root, readLink = readLink).list().filter { it.id.startsWith("alpine+office") }
        // Writer adds an argument to the start center's command, but comes from another part: both stay.
        assertEquals(listOf("alpine+office-start", "alpine+office-writer"), programs.map { it.id })
        assertEquals(listOf("office", "office"), programs.map { it.packageName })
        assertEquals(listOf("office-common", "office-writer"), programs.map { it.owner })
        assertEquals(File(root, "usr/lib/office/icons/writer.png"), programs[1].icon)
    }

    @Test
    fun aGroupsPackageTheUserAddedIsTheirs() {
        val root = rootfs()
        File(root, "etc/apk/world").appendText("foot\n")
        val ids = AlpinePrograms(root).list().map { it.id }
        assertTrue("alpine+foot" in ids)
        assertFalse("foot" in AlpinePrograms.FLATDROID_PACKAGES)
        // Its server mode is the same command with an argument added: not listed.
        assertFalse("alpine+foot-server" in ids)
        assertEquals(listOf("mousepad", "xeyes", "foot"), AlpinePrograms.userPackages(root).filter { it != "hidden-app" })
    }

    @Test
    fun aProgramWasInstalledWhenItsDesktopFileWasWritten() {
        val root = rootfs()
        val programs = AlpinePrograms(root, changedAt = { if (it.name == "xeyes.desktop") 1_000L else null }).list()
        assertEquals(listOf(null, 1_000L), programs.map { it.installed })
    }

    @Test
    fun aCommandThatIsntInstalledIsNotAProgram() {
        val root = rootfs()
        File(root, "usr/bin/xeyes").delete()
        assertEquals(listOf("alpine+org.xfce.mousepad"), AlpinePrograms(root).list().map { it.id })
    }

    @Test
    fun theMetadataAsksAsATypicalApp() {
        val program = AlpinePrograms(rootfs()).list()[0]
        val metadata = program.metadata("aarch64", "v3.24")
        assertEquals("/usr/bin/mousepad", metadata.command)
        assertEquals(listOf("network"), metadata.context.shared)
        assertEquals(listOf("wayland", "fallback-x11", "pulseaudio", "session-bus"), metadata.context.sockets)
        assertTrue(metadata.context.waylandAndX11)
        // It opens /dev/video* itself: Automatic gives it the device, as an app asking for all devices.
        assertEquals(VideoMode.DEVICE, AppSettings().videoFor(metadata.context))
        // Never from a metadata file.
        assertFalse(FlatpakMetadata(Keyfile.parse("[Context]\ndevices=dri;\nreadsVideoDevices=true\n")).context.readsVideoDevices)
    }

    @Test
    fun programsOfOnePackageEachHaveWhatUninstallFrees() {
        val packages = AlpinePackages.parseInstalled("P:office\nI:900\nD:libs\n\nP:libs\nI:100\n\nP:other\nI:50\n")
        fun program(id: String, packageName: String) =
            AlpineProgram(id, packageName, id, listOf("/usr/bin/$id"), terminal = false, icon = null, display = AlpinePackages.Display.WAYLAND_AND_X11)
        val programs = AlpinePrograms(tmp.root).withFreedBytes(
            listOf(program("alpine+writer", "office"), program("alpine+calc", "office"), program("alpine+other", "other")),
            packages, setOf("office", "other"),
        )
        assertEquals(listOf(1000L, 1000L, 50L), programs.map { it.freedBytes })
    }

    @Test
    fun idsNeverLookLikeFlatpaks() {
        assertEquals("alpine+htop", AlpineProgramIds.of("htop"))
        assertTrue(AlpineProgramIds.isId("alpine+org.gnome.Calculator"))
        for (bad in listOf("htop", "alpine+", "alpine+../x", "alpine+a:b", "alpine+a/b", "alpine+-x")) assertFalse(bad, AlpineProgramIds.isId(bad))
        assertNull(AlpineProgramIds.of("a..b"))
    }
}
