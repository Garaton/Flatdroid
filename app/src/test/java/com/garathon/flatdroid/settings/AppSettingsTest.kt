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
            audio = false, microphone = true, alwaysAllowMicrophone = true, withoutFlatpakInfo = true,
            environment = mapOf("VLC_DATA_PATH" to "/app/share/vlc", "VLC_PLUGIN_PATH" to "/app/lib/vlc/plugins"),
            homeMode = HomeMode.TEMPORARY, sdcard = true,
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
    fun environmentTextIsOneNameValuePerLine() {
        assertEquals(
            mapOf("VLC_PLUGIN_PATH" to "/app/lib/vlc/plugins", "A" to "x=y z", "EMPTY" to ""),
            EnvironmentText.parse("VLC_PLUGIN_PATH=/app/lib/vlc/plugins\n\n# comment\n  A=x=y z  \nEMPTY="),
        )
        assertEquals(emptyMap<String, String>(), EnvironmentText.parse(""))
        assertEquals(null, EnvironmentText.parse("no equals sign"))
        assertEquals(null, EnvironmentText.parse("1BAD=x"))
        assertEquals(null, EnvironmentText.parse("-u=PATH"))
        assertEquals("A=1\nB=2", EnvironmentText.format(linkedMapOf("A" to "1", "B" to "2")))
    }

    @Test
    fun brokenValuesFallBackToDefaults() {
        val broken = File(tmp.root, "x.properties").apply {
            writeText("display.size=custom\ndisplay.width=99999\ndisplay.height=abc\nlastOpened=x\npermission.audio=maybe\npermission.microphone=always\n")
        }
        assertEquals(AppSettings(DisplaySize.CUSTOM), AppSettingsStore(broken).load())
    }

    @Test
    fun anImportLeavesWhatWidensReachAsItIsHere() {
        fun file(text: String) = File(tmp.root, "a.App.properties").apply { writeText(text) }
        val imported = file(
            "home.mode=private\ndev.whole=true\nstorage.sdcard=true\npermission.microphone=true\n" +
                "permission.microphone.always=true\nenv.LD_PRELOAD=/home/user/x.so\n",
        ).readBytes()
        val local = file("home.mode=shared\ndev.whole=false\nenv.LANG=de_DE.UTF-8\n").readBytes()
        val loaded = AppSettingsStore(file(AppSettingsStore.imported(imported, local).decodeToString())).load()
        assertEquals(
            AppSettings(homeMode = HomeMode.PRIVATE, microphone = true, environment = mapOf("LANG" to "de_DE.UTF-8")),
            loaded,
        )
        val alone = AppSettingsStore(file(AppSettingsStore.imported(imported, null).decodeToString())).load()
        assertEquals(AppSettings(homeMode = HomeMode.PRIVATE, microphone = true), alone)
    }
}
