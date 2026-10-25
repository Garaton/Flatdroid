package com.garathon.flatdroid.storage

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
}
