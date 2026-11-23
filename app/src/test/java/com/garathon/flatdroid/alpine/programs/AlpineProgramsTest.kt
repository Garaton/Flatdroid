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

class AlpineProgramsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun rootfs(): File {
        val root = tmp.newFolder("rootfs")
        fun write(path: String, text: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }
        write("etc/apk/world", "flatpak\nfoot\nmousepad\nxeyes\nhidden-app\n")
        write(
            "lib/apk/db/installed",
            listOf(
                "P:foot\no:foot\nF:usr/share/applications\nR:foot.desktop\n",
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
