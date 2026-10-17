package com.garathon.flatdroid.settings

import com.garathon.flatdroid.flatpak.FlatpakCommands
import com.garathon.flatdroid.alpine.GuestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UserSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun nothingSavedMeansNotChosenYet() {
        assertNull(UserSettingsStore(File(tmp.root, "settings.properties")).load())
    }

    @Test
    fun roundTrips() {
        val store = UserSettingsStore(File(tmp.root, "settings.properties"))
        store.save(UserSettings("de;en"))
        assertEquals(UserSettings("de;en"), store.load())
        store.save(UserSettings(GuestFiles.ALL_LANGUAGES))
        assertEquals(UserSettings(GuestFiles.ALL_LANGUAGES), store.load())
        val all = UserSettings(
            "de;en", developerOptions = true, vncViewer = "com.gaurav.avnc", frameRate60 = true, customFrameRate = 45,
            uncappedFrameRate = true, installGpuRuntimes = true, titleBars = false,
        )
        store.save(all)
        assertEquals(all, store.load())
    }

    @Test
    fun defaultsUseTheDeviceLanguages() {
        val defaults = UserSettings.defaults("fr;en")
        assertTrue(defaults.onlyMyLanguages)
        assertFalse(UserSettings(GuestFiles.ALL_LANGUAGES).onlyMyLanguages)
    }

    @Test
    fun commandsSetFlatpakLanguagesAndMaskGpuRuntimes() {
        val f = listOf("/usr/bin/flatpak", "--user")
        val masks = FlatpakCommands.GPU_RUNTIME_PATTERNS.map { f + listOf("mask", it) }
        val remove = FlatpakCommands.uninstallMatching(FlatpakCommands.GPU_RUNTIME_PATTERNS)
        assertEquals(listOf(f + listOf("config", "--set", "languages", "fr;en")) + masks + listOf(remove), UserSettings("fr;en").commands())
        // Allowed only with developer options on: each mask is added (harmless if present), then removed.
        assertEquals(masks + listOf(remove), UserSettings("fr;en", installGpuRuntimes = true).commands().drop(1))
        val allowed = UserSettings("fr;en", developerOptions = true, installGpuRuntimes = true).commands().drop(1)
        assertEquals(FlatpakCommands.GPU_RUNTIME_PATTERNS.flatMap { listOf(f + listOf("mask", it), f + listOf("mask", "--remove", it)) }, allowed)
        assertTrue(FlatpakCommands.GPU_RUNTIME_PATTERNS.none { it.startsWith("org.freedesktop.Platform.GL.default") })
    }

    @Test
    fun titleBarsCanBeTurnedOffOnlyWithDeveloperOptions() {
        assertTrue(UserSettings("en").windowTitleBars)
        assertTrue(UserSettings("en", titleBars = false).windowTitleBars)
        assertFalse(UserSettings("en", developerOptions = true, titleBars = false).windowTitleBars)
    }

    @Test
    fun frameRateIs30Unless60OrADeveloperSettingIsChosen() {
        val base = UserSettings("en")
        assertEquals(30, base.frameRate)
        assertEquals(60, base.copy(frameRate60 = true).frameRate)
        // Developer settings count only while developer options are on.
        assertEquals(60, base.copy(frameRate60 = true, customFrameRate = 45).frameRate)
        assertEquals(45, base.copy(frameRate60 = true, customFrameRate = 45, developerOptions = true).frameRate)
        assertEquals(UserSettings.UNCAPPED_FRAME_RATE, base.copy(customFrameRate = 45, uncappedFrameRate = true, developerOptions = true).frameRate)
        assertEquals(30, base.copy(uncappedFrameRate = true).frameRate)
    }
}
