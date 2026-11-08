package com.garathon.flatdroid.settings

import com.garathon.flatdroid.alpine.GuestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftsTest {
    @Test
    fun untouchedUserDraftSavesTheSameSettings() {
        val settings = UserSettings("de;en", customFrameRate = 45, customAudioLatencyMs = 80)
        assertEquals(settings, UserSettingsDraft.of(settings).toSettings("de;en"))
    }

    @Test
    fun languagesFollowTheSwitch() {
        val draft = UserSettingsDraft.of(UserSettings("de;en"))
        assertTrue(draft.onlyMyLanguages)
        assertEquals(GuestFiles.ALL_LANGUAGES, draft.copy(onlyMyLanguages = false).toSettings("de;en")?.flatpakLanguages)
        // The device's languages may differ from the ones saved.
        assertEquals("fr;en", draft.toSettings("fr;en")?.flatpakLanguages)
    }

    @Test
    fun typedRatesAreCheckedAndEmptyMeansNotSet() {
        val draft = UserSettingsDraft.of(UserSettings("de;en"))
        assertNull(draft.toSettings("de;en")?.customFrameRate)
        assertEquals(60, draft.copy(customFrameRate = "60").toSettings("de;en")?.customFrameRate)
        val bad = draft.copy(customFrameRate = "5000", audioLatency = "0")
        assertFalse(bad.customFrameRateValid)
        assertFalse(bad.audioLatencyValid)
        assertNull(bad.toSettings("de;en"))
    }

    @Test
    fun untouchedAppDraftSavesTheSameSettings() {
        val settings = AppSettings(
            resolution = Resolution.CUSTOM, customWidth = 800, customHeight = 600,
            environment = mapOf("A" to "1"), launchArgs = listOf("--x"),
        )
        assertEquals(settings, AppSettingsDraft.of(settings).toSettings(developerOptions = true))
    }

    @Test
    fun customSizeMustBeValidOnlyWhileChosen() {
        val draft = AppSettingsDraft.of(AppSettings()).copy(width = "10")
        assertFalse(draft.widthValid)
        // Not chosen: the bad text is ignored and the saved size stays.
        assertEquals(AppSettings.CUSTOM_WIDTH, draft.toSettings(developerOptions = false)?.customWidth)
        val chosen = draft.copy(settings = draft.settings.copy(resolution = Resolution.CUSTOM))
        assertNull(chosen.toSettings(developerOptions = false))
        assertEquals(1200, chosen.copy(width = "1200").toSettings(developerOptions = false)?.customWidth)
    }

    @Test
    fun developerFieldsCountOnlyWithDeveloperOptions() {
        val saved = AppSettings(environment = mapOf("A" to "1"), launchArgs = listOf("--x"))
        val draft = AppSettingsDraft.of(saved).copy(environment = "not a variable", launchArgs = "--y")
        assertFalse(draft.environmentValid)
        assertNull(draft.toSettings(developerOptions = true))
        assertEquals(saved, draft.toSettings(developerOptions = false))
        val edited = draft.copy(environment = "B=2").toSettings(developerOptions = true)
        assertEquals(mapOf("B" to "2"), edited?.environment)
        assertEquals(listOf("--y"), edited?.launchArgs)
    }
}
