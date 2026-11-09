package com.garathon.flatdroid.flatpak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlathubClientTest {
    // Shortened from Flathub's answers for org.xfce.mousepad and org.mozilla.firefox.
    private val mousepadSummary = """
        {"arches":["aarch64","x86_64"],"branch":"stable","download_size":1913353,"installed_size":5801472,
         "metadata":{"name":"org.xfce.mousepad","runtime":"org.freedesktop.Platform/x86_64/26.08","runtimeName":"Freedesktop Platform version 26.08",
          "runtimeInstalledSize":724151296,
          "permissions":{"shared":["ipc"],"sockets":["fallback-x11","wayland"],"filesystems":["host","xdg-run/gvfs"]}}}
    """
    private val firefoxSummary = """
        {"arches":["x86_64","aarch64"],"download_size":129656724,"installed_size":345679872,
         "metadata":{"permissions":{"sockets":["x11","wayland","pulseaudio"],"devices":["all"],
          "filesystems":["xdg-config/gtk-3.0:ro","xdg-download"],"shared":["network","ipc"]}}}
    """
    private val appstream = """{"name":"Mousepad","summary":"Mousepad is a simple text editor for the Xfce desktop environment"}"""

    @Test
    fun theConfirmationTakesWhatFlathubSays() {
        val app = FlathubClient.parse("org.xfce.mousepad", mousepadSummary, appstream)
        assertEquals("Mousepad", app.name)
        assertEquals("Mousepad is a simple text editor for the Xfce desktop environment", app.summary)
        assertEquals(listOf("aarch64", "x86_64"), app.arches)
        assertEquals(1913353L, app.downloadSize)
        assertEquals(5801472L, app.installedSize)
        assertEquals("Freedesktop Platform version 26.08", app.runtime)
        assertEquals(724151296L, app.runtimeSize)
        // Flathub names its own architecture; the app compares by name and branch on ours.
        assertEquals(FlatpakRef("org.freedesktop.Platform", "x86_64", "26.08"), app.runtimeRef)
        // Files: the whole host. No network, sound or devices.
        assertEquals(setOf(FlathubApp.Permission.FILES), app.permissions)
        assertTrue(app.builtFor("aarch64"))
        assertFalse(app.builtFor("riscv64"))
        assertFalse(app.verified)
    }

    @Test
    fun theVerificationComesFromTheAppstreamAnswer() {
        val verified = """{"name":"Mousepad","metadata":{"flathub::verification::verified":true,"flathub::verification::website":"xfce.org"}}"""
        assertTrue(FlathubClient.parse("org.xfce.mousepad", mousepadSummary, verified).verified)
        val asText = """{"name":"Mousepad","metadata":{"flathub::verification::verified":"true"}}"""
        assertTrue(FlathubClient.parse("org.xfce.mousepad", mousepadSummary, asText).verified)
        val no = """{"name":"Mousepad","metadata":{"flathub::verification::verified":false}}"""
        assertFalse(FlathubClient.parse("org.xfce.mousepad", mousepadSummary, no).verified)
        assertFalse(FlathubClient.parse("org.xfce.mousepad", mousepadSummary, null).verified)
    }

    @Test
    fun permissionsAreTheOnesFlatdroidShows() {
        val app = FlathubClient.parse("org.mozilla.firefox", firefoxSummary, null)
        assertEquals(setOf(FlathubApp.Permission.NETWORK, FlathubApp.Permission.SOUND, FlathubApp.Permission.ALL_DEVICES), app.permissions)
    }

    @Test
    fun withoutAnAppstreamAnswerTheIdIsTheName() {
        val app = FlathubClient.parse("org.xfce.mousepad", mousepadSummary, null)
        assertEquals("org.xfce.mousepad", app.name)
        assertNull(app.summary)
        // An unreadable answer counts as none.
        assertEquals("org.xfce.mousepad", FlathubClient.parse("org.xfce.mousepad", mousepadSummary, "not json").name)
    }

    @Test
    fun missingFieldsAreNullNotZero() {
        val app = FlathubClient.parse("a.b", """{"arches":["aarch64"]}""", null)
        assertNull(app.downloadSize)
        assertNull(app.installedSize)
        assertNull(app.runtime)
        assertNull(app.runtimeSize)
        assertNull(app.runtimeRef)
        assertTrue(app.permissions.isEmpty())
    }
}
