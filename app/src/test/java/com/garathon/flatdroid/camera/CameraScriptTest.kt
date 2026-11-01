package com.garathon.flatdroid.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraScriptTest {
    @Test
    fun aSourcePerNodeAtItsSize() {
        val text = CameraScript.text(
            listOf(
                CameraScript.Node("flatdroid-camera-0", "Back camera", 1920, 1080, 30, CameraScript.Location.BACK),
                CameraScript.Node("flatdroid-camera-1", "Front 'camera\"; rm -rf /", 640, 480, 15),
            )
        )
        assertTrue("exec 3<>/run/flatdroid/camera/flatdroid-camera-0; exec gst-launch-1.0 -q fdsrc fd=3 do-timestamp=true blocksize=1048576 " in text)
        assertTrue("! multipartdemux boundary=flatdroid-frame single-stream=true ! image/jpeg,width=1920,height=1080,framerate=30/1 " in text)
        assertTrue("image/jpeg,width=640,height=480,framerate=15/1 " in text)
        // Apps mirror their preview of any camera that doesn't say it faces back.
        assertTrue("api.libcamera.location=back,node.description=\\\"Back camera\\\"\"' client-name=flatdroid-camera-0" in text)
        assertTrue("! queue leaky=downstream max-size-buffers=1 max-size-bytes=0 max-size-time=0 ! pipewiresink mode=provide use-bufferpool=false " in text)
        // Quotes and the rest can't leave the description.
        assertTrue("node.description=\\\"Front camera rm rf\\\"\"' client-name=flatdroid-camera-1" in text)
        assertTrue("for node in flatdroid-camera-0 flatdroid-camera-1; do" in text)
        assertTrue("SetPermission devices true camera '' \"['yes']\"" in text)
    }

    @Test
    fun onlyFlatdroidsNodeNames() {
        assertThrows(IllegalArgumentException::class.java) {
            CameraScript.text(listOf(CameraScript.Node("x; reboot", "Back camera", 640, 480, 30)))
        }
        assertEquals("flatdroid-camera-2", CameraScript.nodeName(2))
    }

    @Test
    fun framesArePartsTheSourceSplitsByLength() {
        assertEquals(
            "--flatdroid-frame\r\nContent-Type: image/jpeg\r\nContent-Length: 1234\r\n\r\n",
            String(CameraScript.frameHeader(1234)),
        )
        assertEquals("\r\n", String(CameraScript.FRAME_END))
    }

    @Test
    fun usersLines() {
        assertEquals("flatdroid-camera-0" to 2, CameraScript.users("flatdroid: camera users flatdroid-camera-0 2"))
        assertNull(CameraScript.users("flatdroid: camera users flatdroid-camera-0"))
        assertNull(CameraScript.users("flatdroid: camera ready"))
    }

    @Test
    fun noHardwareMonitorsAndOnlyTheStandInAccessBackend() {
        val wireplumber = CameraScript.CONFIG_FILES.getValue("wireplumber/wireplumber.conf.d/flatdroid.conf")
        for (monitor in listOf("alsa", "bluez", "v4l2", "libcamera")) assertTrue("monitor.$monitor = disabled" in wireplumber)
        assertTrue("Interfaces=org.freedesktop.impl.portal.Access;" in CameraScript.CONFIG_FILES.getValue("portals/flatdroid.portal"))
        // With XDG_DESKTOP_PORTAL_DIR, the portal reads its configuration only beside its backends.
        assertTrue("default=flatdroid" in CameraScript.CONFIG_FILES.getValue("portals/flatdroid-portals.conf"))
    }

    @Test
    fun usersAreCountedByOneProcessStartedBeforeReady() {
        val lines = CameraScript.text(listOf(CameraScript.Node("flatdroid-camera-0", "Back camera", 640, 480, 30))).lines()
        val monitor = lines.indexOfFirst { it.startsWith("pw-link -m -l") }
        assertTrue(monitor >= 0 && "awk -v nodes=\"flatdroid-camera-0\" -v prefix=\"flatdroid: camera users \"" in lines[monitor])
        assertTrue(monitor < lines.indexOf("echo \"${CameraScript.READY}\""))
        // Nothing runs per change: no shell function that starts pw-link again.
        assertTrue(lines.none { it.startsWith("report") })
    }
}
