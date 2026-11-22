package com.garathon.flatdroid.proot

import com.garathon.flatdroid.proot.Fdv4l2.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Fdv4l2Test {
    private val back = Fdv4l2.Device(0, 720, 1600, 30, "Back camera")
    private val front = Fdv4l2.Device(1, 480, 640, 15, "Front camera", front = true)

    @Test
    fun camerasListIsOneLinePerDeviceWithPlainNames() {
        val list = Fdv4l2.camerasList(
            listOf(
                back,
                Fdv4l2.Device(1, 480, 640, 15, "Front \"camera\"\n/dev/video9 1 1 1 x and a very long name after it"),
                Fdv4l2.Device(2, 640, 480, 30, "!!!"),
                Fdv4l2.Device(3, 640, 480, 30, "Caméra arrière très très grande"),
            ),
        )
        assertEquals(
            "/dev/video0 /dev/media0 /.flatdroid/video0.format 720 1600 30 nv12,mjpeg Back camera\n" +
                "/dev/video1 /dev/media1 /.flatdroid/video1.format 480 640 15 nv12,mjpeg Front cameradevvideo9 1 1 1 x a\n" +
                "/dev/video2 /dev/media2 /.flatdroid/video2.format 640 480 30 nv12,mjpeg Camera\n" +
                // 31 bytes, not characters: "é" and "è" are two each.
                "/dev/video3 /dev/media3 /.flatdroid/video3.format 640 480 30 nv12,mjpeg Caméra arrière très très gr\n",
            list,
        )
        assertEquals(
            "/dev/video0 /dev/media0 /.flatdroid/video0.format 720 1600 30 nv12 Back camera\n",
            Fdv4l2.camerasList(listOf(back), listOf(Fdv4l2.Format.NV12, Fdv4l2.Format.NV12)),
        )
    }

    @Test
    fun onlyCamerasWithASizeAndAFormat() {
        assertThrows(IllegalArgumentException::class.java) { Fdv4l2.camerasList(listOf(back.copy(width = 0))) }
        assertThrows(IllegalArgumentException::class.java) { Fdv4l2.camerasList(listOf(back.copy(index = -1))) }
        assertThrows(IllegalArgumentException::class.java) { Fdv4l2.camerasList(listOf(back), emptyList()) }
    }

    @Test
    fun theLastFormatTheLibrarySaidCounts() {
        assertEquals(Fdv4l2.Format.NV12, Fdv4l2.chosenFormat("mjpeg\nnv12\n"))
        assertEquals(Fdv4l2.Format.MJPEG, Fdv4l2.chosenFormat("nv12\nmjpeg"))
        assertEquals(Fdv4l2.Format.NV12, Fdv4l2.chosenFormat("nv12\nyuyv\njunk\n"))
        assertNull(Fdv4l2.chosenFormat(""))
        // Streaming as the library said last.
        assertEquals(true, Fdv4l2.streaming("nv12\non\n"))
        assertEquals(false, Fdv4l2.streaming("nv12\non\noff\n"))
        assertEquals(true, Fdv4l2.streaming("off\nmjpeg\non"))
        assertNull(Fdv4l2.streaming("nv12\n"))
    }

    @Test
    fun theLibraryComesBeforeTheAppsOwnPreload() {
        assertEquals("/.flatdroid/fdv4l2.so", Fdv4l2.preload(null))
        assertEquals("/.flatdroid/fdv4l2.so", Fdv4l2.preload("  "))
        assertEquals("/.flatdroid/fdv4l2.so:/app/lib/libx.so", Fdv4l2.preload("/app/lib/libx.so"))
    }

    @Test
    fun eachCameraIsAUsbDeviceWithAVideoNodeAndAMediaDevice() {
        val tree = Fdv4l2.tree(listOf(back, front))
        val iface = "devices/usb1/1-2/1-2:1.0"
        // What libcamera reads: the media devices, the video node's name by its numbers, a firmware node above.
        assertEquals(Entry.Link("/sys/devices/flatdroid/usb1/1-2/1-2:1.0/media1"), tree["bus-media/devices/media1"])
        assertEquals(Entry.Text("MAJOR=81\nMINOR=1\nDEVNAME=video1\n"), tree["$iface/video4linux/video1/uevent"])
        assertEquals(Entry.Link("/sys/devices/flatdroid/usb1/1-2/1-2:1.0"), tree["$iface/video4linux/video1/device"])
        assertTrue(tree["devices/firmware_node/path"] is Entry.Text)
        assertEquals(Entry.Text("0002\n"), tree["devices/usb1/1-2/idProduct"])
        // Which way each faces, beside the interface (libcamera reads <interface>/../removable): built in means front.
        assertEquals(Entry.Text("fixed\n"), tree["devices/usb1/1-2/removable"])
        assertEquals(Entry.Text("removable\n"), tree["devices/usb1/1-1/removable"])
        // What udev reads: the class link, the subsystem, and the database entry that marks it initialized.
        assertEquals(Entry.Link("/sys/devices/flatdroid/usb1/1-2/1-2:1.0/video4linux/video1"), tree["class/video4linux/video1"])
        assertEquals(Entry.Link("/sys/class/video4linux"), tree["$iface/video4linux/video1/subsystem"])
        assertTrue((tree["udev/data/c81:1"] as Entry.Text).text.contains("E:ID_V4L_PRODUCT=Front camera\n"))
        assertEquals(Entry.Text("MAJOR=238\nMINOR=0\nDEVNAME=media0\n"), tree["devices/usb1/1-1/1-1:1.0/media0/uevent"])
        // Every link points into the app's root, at what the binds put there.
        for ((_, entry) in tree) if (entry is Entry.Link) assertTrue(entry.target.startsWith("/sys/"))
    }

    @Test
    fun androidsClassesStayReadableBesideTheCameras() {
        val tree = Fdv4l2.tree(listOf(back), listOf("net", "video4linux", "../etc", "power_supply"))
        // Relative, so a class's own links (../../devices/...) still land in /sys/devices.
        assertEquals(Entry.Link("../class-android/net"), tree["class/net"])
        assertEquals(Entry.Link("../class-android/power_supply"), tree["class/power_supply"])
        // Not the cameras' own class, nor a name that could leave the folder.
        assertEquals(Entry.Dir, tree["class/video4linux"])
        assertTrue(tree.keys.none { ".." in it })
        assertTrue(Fdv4l2.KNOWN_CLASSES.all { Regex("[a-z0-9_-]+").matches(it) })
    }

    @Test
    fun eachDevicesFolderIsAlsoBoundAtItsNumbers() {
        assertEquals(listOf("char/video0" to "/sys/dev/char/81:0", "char/media0" to "/sys/dev/char/238:0"), Fdv4l2.charBinds(listOf(back)))
        // The bound folders are in the tree, with what the device's own folder has; no host path has a colon (proot's separator).
        val tree = Fdv4l2.tree(listOf(back))
        assertEquals(tree["devices/usb1/1-1/1-1:1.0/video4linux/video0/uevent"], tree["char/video0/uevent"])
        assertEquals(Entry.Link("/sys/devices/flatdroid/usb1/1-1/1-1:1.0"), tree["char/video0/device"])
        for ((relative, _) in Fdv4l2.TREE_BINDS + Fdv4l2.charBinds(listOf(back))) assertTrue(':' !in relative)
        for ((relative, _) in Fdv4l2.charBinds(listOf(back))) assertTrue(tree.keys.any { it.startsWith("$relative/") })
        for ((relative, _) in Fdv4l2.TREE_BINDS) assertTrue(relative, tree.keys.any { it == relative || it.startsWith("$relative/") })
    }
}
