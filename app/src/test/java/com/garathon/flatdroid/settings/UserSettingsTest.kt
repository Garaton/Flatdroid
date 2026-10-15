package com.garathon.flatdroid.settings

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
    }

    @Test
    fun defaultsUseTheDeviceLanguages() {
        val defaults = UserSettings.defaults("fr;en")
        assertTrue(defaults.onlyMyLanguages)
        assertFalse(UserSettings(GuestFiles.ALL_LANGUAGES).onlyMyLanguages)
    }

    @Test
    fun commandsSetFlatpakLanguages() {
        assertEquals(
            listOf(listOf("/usr/bin/flatpak", "--user", "config", "--set", "languages", "fr;en")),
            UserSettings("fr;en").commands(),
        )
    }
}
