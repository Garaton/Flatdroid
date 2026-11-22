package com.garathon.flatdroid.storage.backup

import com.garathon.flatdroid.session.AlpineShellUser
import com.garathon.flatdroid.settings.MobileData
import com.garathon.flatdroid.settings.SecuritySettings
import com.garathon.flatdroid.settings.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SettingsBackupTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun roundTripsFlatdroidsSettingsOnly() {
        val settings = tmp.newFile("settings.properties").apply { writeText("flatpak.languages=en\n") }
        val appDir = tmp.newFolder("appsettings")
        File(appDir, "org.kde.kate.properties").writeText("home.mode=private\n")
        File(appDir, "notes.txt").writeText("x")
        val out = ByteArrayOutputStream()
        SettingsBackup.write(out, settings, appDir)
        val files = SettingsBackup.read(out.toByteArray().inputStream())
        assertEquals(setOf("settings.properties", "appsettings/org.kde.kate.properties"), files.keys)
        assertEquals("home.mode=private\n", files.getValue("appsettings/org.kde.kate.properties").decodeToString())
    }

    @Test
    fun othersAreLeftOutAndSettingsAreRequired() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in listOf("appsettings/../x.properties", "appsettings/bad.properties", "other.txt")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(1); zip.closeEntry()
            }
        }
        assertThrows(IOException::class.java) { SettingsBackup.read(out.toByteArray().inputStream()) }
        assertNull(SettingsBackup.appId("bad.properties"))
        assertEquals("org.kde.kate", SettingsBackup.appId("org.kde.kate.properties"))
    }

    private fun props(bytes: ByteArray) = java.util.Properties().apply { load(bytes.inputStream()) }

    @Test
    fun anAppTheZipLacksKeepsWhatWidensItsReach() {
        val local = mapOf(
            "org.kde.kate.properties" to "permission.network=false\nhome.mode=private\ndisplay.x11=true\n".toByteArray(),
            "org.kde.kmines.properties" to "permission.camera.always=true\nusage.launches=7\n".toByteArray(),
        )
        val files = mapOf(
            "settings.properties" to ByteArray(0),
            "appsettings/org.kde.kate.properties" to "permission.network=true\ndisplay.x11=false\n".toByteArray(),
        )
        val out = SettingsBackup.appSettingsToWrite(files, local)
        assertEquals(setOf("org.kde.kate.properties", "org.kde.kmines.properties"), out.keys)
        // In the zip: its own non-widening keys, this device's widening ones.
        val kate = props(out.getValue("org.kde.kate.properties"))
        assertEquals("false", kate.getProperty("permission.network"))
        assertEquals("private", kate.getProperty("home.mode"))
        assertEquals("false", kate.getProperty("display.x11"))
        // Not in the zip: only the protected keys stay.
        val kmines = props(out.getValue("org.kde.kmines.properties"))
        assertEquals("true", kmines.getProperty("permission.camera.always"))
        assertEquals("7", kmines.getProperty("usage.launches"))
    }

    @Test
    fun anAppOnlyTheZipHasIsWrittenWithoutWideningKeys() {
        val files = mapOf(
            "settings.properties" to ByteArray(0),
            "appsettings/org.kde.kate.properties" to "permission.network=true\ndisplay.x11=true\n".toByteArray(),
        )
        val kate = props(SettingsBackup.appSettingsToWrite(files, emptyMap()).getValue("org.kde.kate.properties"))
        assertNull(kate.getProperty("permission.network"))
        assertEquals("true", kate.getProperty("display.x11"))
    }

    @Test
    fun aBadFileFailsThePlanBeforeAnythingIsWritten() {
        val files = mapOf("settings.properties" to ByteArray(0), "appsettings/org.kde.kate.properties" to "x=\\u12\n".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { SettingsBackup.appSettingsToWrite(files, emptyMap()) }
    }

    @Test
    fun anImportKeepsThisDevicesChoices() {
        val device = UserSettings(
            flatpakLanguages = "de;en", vncViewer = "com.example.viewer", externalViewer = true,
            security = SecuritySettings(extraSyscallFilter = false), terminalFilter = false, terminalDuringSession = true,
            alpineShellUser = AlpineShellUser.APP_USER,
        )
        val zip = UserSettings(
            flatpakLanguages = "--install-everything", vncViewer = "com.other.viewer", externalViewer = false,
            security = SecuritySettings(), terminalFilter = true, terminalDuringSession = false, mobileData = MobileData.NEVER,
        )
        val kept = SettingsBackup.keepingDevice(zip, device)
        assertEquals("de;en", kept.flatpakLanguages)
        assertEquals("com.example.viewer", kept.vncViewer)
        assertEquals(true, kept.externalViewer)
        assertEquals(device.security, kept.security)
        assertEquals(false, kept.terminalFilter)
        assertEquals(true, kept.terminalDuringSession)
        // What isn't a device choice comes from the zip.
        assertEquals(zip.copy(
            flatpakLanguages = device.flatpakLanguages, vncViewer = device.vncViewer, externalViewer = true,
            security = device.security, terminalFilter = false, terminalDuringSession = true,
            alpineShellUser = AlpineShellUser.APP_USER,
        ), kept)
    }
}
