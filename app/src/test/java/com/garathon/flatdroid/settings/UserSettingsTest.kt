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
            uncappedFrameRate = true, installGpuRuntimes = true, titleBars = false, customAudioLatencyMs = 30,
            lowLatencyAudio = true, highQualityAudio = true, sharedHome = "/storage/emulated/0/Download/Home",
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
    fun audioLatencyIs60UnlessTheDeveloperSettingIsOn() {
        assertEquals(60, UserSettings("en").audioLatencyMs)
        assertEquals(60, UserSettings("en", customAudioLatencyMs = 20).audioLatencyMs)
        assertEquals(20, UserSettings("en", developerOptions = true, customAudioLatencyMs = 20).audioLatencyMs)
    }

    @Test
    fun audioIsPowerSavingAndFastUnlessTurnedOn() {
        val base = UserSettings("en")
        assertEquals(AudioOutputMode.POWER_SAVING, base.audioOutputMode)
        assertEquals("speex-float-0", base.resampler.pulseName)
        assertEquals(AudioOutputMode.LOW_LATENCY, base.copy(lowLatencyAudio = true).audioOutputMode)
        assertEquals("speex-float-1", base.copy(highQualityAudio = true).resampler.pulseName)
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

    @Test
    fun aSecurityLevelIsSavedByNameAndCustomBySetting() {
        val file = File(tmp.root, "settings.properties")
        val store = UserSettingsStore(file)
        // Older files without a level, and the defaults, are Default.
        assertEquals(SecurityLevel.DEFAULT, UserSettings("en").securityLevel)
        file.writeText("flatpak.languages=en\n")
        assertEquals(SecurityLevel.DEFAULT.settings, store.load()!!.security)
        for (level in SecurityLevel.entries) {
            store.save(UserSettings("en", security = level.settings))
            assertEquals(level, store.load()!!.securityLevel)
            assertTrue("security.level=${level.name.lowercase()}" in file.readText())
            assertFalse("security.limitDevices" in file.readText())
        }
        val custom = SecurityLevel.HARDENED.settings.copy(defaultHomeMode = HomeMode.SHARED, exportWithPicker = false)
        store.save(UserSettings("en", security = custom))
        assertNull(store.load()!!.securityLevel)
        assertEquals(custom, store.load()!!.security)
        assertTrue("security.level=custom" in file.readText())
    }

    @Test
    fun theLevelsSortTheirSettings() {
        val performant = SecurityLevel.PERFORMANT.settings
        val default = SecurityLevel.DEFAULT.settings
        val hardened = SecurityLevel.HARDENED.settings
        assertFalse(performant.extraSyscallFilter || performant.hideHelperProcesses)
        assertTrue(default.extraSyscallFilter && hardened.extraSyscallFilter)
        assertTrue(default.hideHelperProcesses && hardened.hideHelperProcesses)
        assertEquals(HomeMode.PRIVATE, hardened.appDefaults.homeMode)
        assertFalse(hardened.appDefaults.permissionsFromMetadata)
        assertTrue(hardened.limitDevices && hardened.protectPassword && hardened.exportWithPicker && hardened.hideHelperProcesses)
        assertEquals(AppDefaults(), default.appDefaults)
        assertEquals(AppDefaults(), performant.appDefaults)
    }

    @Test
    fun theTerminalIsFilteredUnlessTheDeveloperSettingSaysOtherwise() {
        assertTrue(UserSettings("en").filterTerminal)
        assertTrue(UserSettings("en", terminalFilter = false).filterTerminal)
        assertFalse(UserSettings("en", developerOptions = true, terminalFilter = false).filterTerminal)
        val store = UserSettingsStore(File(tmp.root, "t.properties"))
        store.save(UserSettings("en", terminalFilter = false))
        assertFalse(store.load()!!.terminalFilter)
    }
}
