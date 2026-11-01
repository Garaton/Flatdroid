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
            homeModeChoice = HomeMode.TEMPORARY, sdcard = true, camera = true, cameraResolution = 1080, cameraFrameRate = 15,
            network = false, allowExtraSyscalls = true, shareHiddenFiles = true,
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
        // A custom size is kept for later, but landscape still follows Flatdroid's default.
        AppSettingsStore(file).save(AppSettings(DisplaySize.LANDSCAPE, customWidth = 480, customHeight = 720, lastOpened = 5L))
        val loaded = AppSettingsStore(file).load()
        assertEquals("1600x1000", loaded.geometry)
        assertEquals("480x720", loaded.copy(displaySize = DisplaySize.CUSTOM).geometry)
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
        assertFalse("home.mode" in file.readText())
    }

    @Test
    fun unchangedSettingsFollowTheSecurityLevelsDefaults() {
        val hardened = AppDefaults(homeMode = HomeMode.PRIVATE, permissionsFromMetadata = false)
        val unset = AppSettings(defaults = hardened)
        assertEquals(HomeMode.PRIVATE, unset.homeMode)
        assertFalse(unset.audioAllowed(listOf("pulseaudio")))
        assertFalse(unset.networkAllowed(listOf("network", "ipc")))
        // What the user chose stays.
        val chosen = unset.copy(homeModeChoice = HomeMode.SHARED, audio = true, network = true)
        assertEquals(HomeMode.SHARED, chosen.homeMode)
        assertTrue(chosen.audioAllowed(emptyList()) && chosen.networkAllowed(emptyList()))
        // At the default level the metadata decides.
        assertTrue(AppSettings().networkAllowed(listOf("network")))
        assertFalse(AppSettings().networkAllowed(listOf("ipc")))
        assertEquals(HomeMode.SHARED, AppSettings().homeMode)
        // Android's fonts and the clipboard: shared unless the level or the user says otherwise.
        assertTrue(AppSettings().usesAndroidFonts && AppSettings().sharesClipboard)
        val private = AppSettings(defaults = AppDefaults(androidFonts = false, clipboard = false))
        assertFalse(private.usesAndroidFonts || private.sharesClipboard)
        assertTrue(private.copy(androidFonts = true, clipboard = true).let { it.usesAndroidFonts && it.sharesClipboard })
        // The time zone and the language likewise.
        assertTrue(AppSettings().sharesTimeZone && AppSettings().sharesLanguage)
        val generic = AppSettings(defaults = AppDefaults(timeZone = false, language = false))
        assertFalse(generic.sharesTimeZone || generic.sharesLanguage)
        assertTrue(generic.copy(timeZone = true, language = true).let { it.sharesTimeZone && it.sharesLanguage })
        // The store hands the defaults to what it loads, saved or not.
        val file = File(tmp.root, "d.properties")
        assertEquals(HomeMode.PRIVATE, AppSettingsStore(file, hardened).load().homeMode)
        AppSettingsStore(file, hardened).save(AppSettings(defaults = hardened, displaySize = DisplaySize.LANDSCAPE))
        assertEquals(hardened, AppSettingsStore(file, hardened).load().defaults)
        assertEquals(HomeMode.SHARED, AppSettingsStore(file).load().homeMode)
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
            "home.mode=shared\ndev.whole=true\nstorage.sdcard=true\npermission.microphone=true\npermission.camera=true\n" +
                "permission.microphone.always=true\nenv.LD_PRELOAD=/home/user/x.so\npermission.network=true\n" +
                "filter.allowExtraSyscalls=true\npermission.audio=false\nhome.shareHiddenFiles=true\n" +
                "privacy.androidFonts=true\npermission.clipboard=true\nprivacy.timeZone=true\nprivacy.language=true\n",
        ).readBytes()
        val local = file(
            "home.mode=private\ndev.whole=false\nenv.LANG=de_DE.UTF-8\npermission.network=false\n" +
                "privacy.androidFonts=false\npermission.clipboard=false\nprivacy.timeZone=false\n",
        ).readBytes()
        val loaded = AppSettingsStore(file(AppSettingsStore.imported(imported, local).decodeToString())).load()
        assertEquals(
            AppSettings(
                homeModeChoice = HomeMode.PRIVATE, microphone = true, network = false, audio = false,
                androidFonts = false, clipboard = false, timeZone = false, environment = mapOf("LANG" to "de_DE.UTF-8"),
            ),
            loaded,
        )
        val alone = AppSettingsStore(file(AppSettingsStore.imported(imported, null).decodeToString())).load()
        assertEquals(AppSettings(microphone = true, audio = false), alone)
    }
}
