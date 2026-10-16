package com.garathon.flatdroid.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val saved = AppSettings(DisplaySize.CUSTOM, 960, 640, newerElectronFlags = true, lastOpened = 1234L)
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
    fun brokenValuesFallBackToDefaults() {
        val broken = File(tmp.root, "x.properties").apply { writeText("display.size=custom\ndisplay.width=99999\ndisplay.height=abc\nlastOpened=x\n") }
        assertEquals(AppSettings(DisplaySize.CUSTOM), AppSettingsStore(broken).load())
    }
}
