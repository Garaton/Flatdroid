package com.garathon.flatdroid.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun defaultsUntilSavedThenRoundTrips() {
        val store = AppSettingsStore(File(tmp.root, "appsettings/a.App.properties"))
        assertEquals(AppSettings(), store.load())
        assertEquals("1000x1400", store.load().geometry)
        val saved = AppSettings(
            DisplaySize.CUSTOM, 960, 640, newerElectronFlags = true, lastOpened = 1234L, wholeDev = true,
            audio = false, microphone = true, alwaysAllowMicrophone = true,
        )
        store.save(saved)
        assertEquals(saved, store.load())
        assertEquals("960x640", store.load().geometry)
        store.update { it.copy(lastOpened = 99L) }
        assertEquals(saved.copy(lastOpened = 99L), store.load())
        store.delete()
        assertEquals(AppSettings(), store.load())
    }

    @Test
    fun defaultSizesAreNotPinned() {
        val file = File(tmp.root, "b.properties")
        AppSettingsStore(file).save(AppSettings(DisplaySize.LANDSCAPE, lastOpened = 5L))
        assertFalse("display.width" in file.readText())
        assertEquals("1600x1000", AppSettingsStore(file).load().geometry)
    }

    @Test
    fun unsetSoundFollowsTheMetadataAndTheMicrophoneStartsOff() {
        val unset = AppSettings()
        assertTrue(unset.audioAllowed(listOf("x11", "pulseaudio")))
        assertFalse(unset.audioAllowed(listOf("x11")))
        assertFalse(unset.copy(audio = false).audioAllowed(listOf("pulseaudio")))
        assertTrue(unset.copy(audio = true).audioAllowed(emptyList()))
        assertFalse(unset.microphone)
        // Unset stays unset in the file, so a later default applies.
        val file = File(tmp.root, "p.properties")
        AppSettingsStore(file).save(unset)
        assertFalse("permission." in file.readText())
    }

    @Test
    fun brokenValuesFallBackToDefaults() {
        val broken = File(tmp.root, "x.properties").apply {
            writeText("display.size=custom\ndisplay.width=99999\ndisplay.height=abc\nlastOpened=x\npermission.audio=maybe\npermission.microphone=always\n")
        }
        assertEquals(AppSettings(DisplaySize.CUSTOM), AppSettingsStore(broken).load())
    }
}
