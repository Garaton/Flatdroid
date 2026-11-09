package com.garathon.flatdroid.settings

import com.garathon.flatdroid.flatpak.FlatpakContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(540 to 990, store.load().size(720, 1320))
        val saved = AppSettings(
            Resolution.CUSTOM, 960, 640, newerElectronFlags = true, lastOpened = 1234L, wholeDev = true,
            audio = false, microphone = true, alwaysAllowMicrophone = true, withoutFlatpakInfo = true,
            environment = mapOf("VLC_DATA_PATH" to "/app/share/vlc", "VLC_PLUGIN_PATH" to "/app/lib/vlc/plugins"),
            homeModeChoice = HomeMode.TEMPORARY, sdcard = true, camera = true, cameraResolutionOverride = 1080, cameraFrameRateOverride = 15,
            network = false, blockExtraSyscalls = false, shareHiddenFiles = true, launches = 3, usedMillis = 90_000L,
        )
        store.save(saved)
        assertEquals(saved, store.load())
        assertEquals(960 to 640, store.load().size(720, 1320))
        store.update { it.copy(lastOpened = 99L) }
        assertEquals(saved.copy(lastOpened = 99L), store.load())
        store.delete()
        assertEquals(AppSettings(), store.load())
    }

    @Test
    fun aCustomSizeIsKeptWhileAShareIsChosen() {
        val file = File(tmp.root, "b.properties")
        AppSettingsStore(file).save(AppSettings(Resolution.HALF, customWidth = 480, customHeight = 720, lastOpened = 5L))
        val loaded = AppSettingsStore(file).load()
        assertEquals(360 to 660, loaded.size(720, 1320))
        assertEquals(480 to 720, loaded.copy(resolution = Resolution.CUSTOM).size(720, 1320))
    }

    @Test
    fun sharesOfTheViewerAreEvenAndNotTiny() {
        assertEquals(720 to 1320, Resolution.FULL.scaled(720, 1320))
        assertEquals(540 to 990, Resolution.THREE_QUARTERS.scaled(720, 1320))
        // Landscape follows the viewer turned; odd sizes round down to even.
        assertEquals(1168 to 540, Resolution.HALF.scaled(2337, 1081))
        assertEquals(Resolution.MIN_FOLLOW to 330, Resolution.QUARTER.scaled(720, 1320))
    }

    @Test
    fun unsetSoundFollowsTheMetadataAndTheMicrophoneStartsOff() {
        val unset = AppSettings()
        assertTrue(unset.audioAllowed(listOf("x11", "pulseaudio")))
        assertFalse(unset.audioAllowed(listOf("x11")))
        assertFalse(unset.copy(audio = false).audioAllowed(listOf("pulseaudio")))
        assertTrue(unset.copy(audio = true).audioAllowed(emptyList()))
        assertNull(unset.microphone)
        assertNull(unset.camera)
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
        // The clipboard: shared unless the level or the user says otherwise.
        assertTrue(AppSettings().sharesClipboard)
        val private = AppSettings(defaults = AppDefaults(clipboard = false))
        assertFalse(private.sharesClipboard)
        assertTrue(private.copy(clipboard = true).sharesClipboard)
        // The system call filter and the camera follow the defaults until the app overrides them.
        assertTrue(AppSettings().blocksExtraSyscalls)
        assertFalse(AppSettings(defaults = AppDefaults(blockExtraSyscalls = false)).blocksExtraSyscalls)
        assertFalse(AppSettings(blockExtraSyscalls = false).blocksExtraSyscalls)
        val cameraDefaults = AppDefaults(cameraResolution = 480, cameraFrameRate = 15)
        assertEquals(480 to 15, AppSettings(defaults = cameraDefaults).let { it.cameraResolution to it.cameraFrameRate })
        assertEquals(1080 to 30, AppSettings(defaults = cameraDefaults, cameraResolutionOverride = 1080, cameraFrameRateOverride = 30).let { it.cameraResolution to it.cameraFrameRate })
        // The store hands the defaults to what it loads, saved or not.
        val file = File(tmp.root, "d.properties")
        assertEquals(HomeMode.PRIVATE, AppSettingsStore(file, hardened).load().homeMode)
        AppSettingsStore(file, hardened).save(AppSettings(defaults = hardened, resolution = Resolution.HALF))
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
            writeText("display.resolution=custom\ndisplay.width=99999\ndisplay.height=abc\nlastOpened=x\npermission.audio=maybe\npermission.microphone=always\n")
        }
        assertEquals(AppSettings(Resolution.CUSTOM), AppSettingsStore(broken).load())
    }

    @Test
    fun anImportLeavesWhatWidensReachAsItIsHere() {
        fun file(text: String) = File(tmp.root, "a.App.properties").apply { writeText(text) }
        val imported = file(
            "home.mode=shared\ndev.whole=true\nstorage.sdcard=true\npermission.microphone=true\npermission.camera=true\n" +
                "permission.microphone.always=true\nenv.LD_PRELOAD=/home/user/x.so\npermission.network=true\n" +
                "filter.blockExtraSyscalls=false\npermission.audio=false\nhome.shareHiddenFiles=true\n" +
                "permission.clipboard=true\n",
        ).readBytes()
        val local = file(
            "home.mode=private\ndev.whole=false\nenv.LANG=de_DE.UTF-8\npermission.network=false\n" +
                "permission.clipboard=false\n",
        ).readBytes()
        val loaded = AppSettingsStore(file(AppSettingsStore.imported(imported, local).decodeToString())).load()
        assertEquals(
            AppSettings(
                homeModeChoice = HomeMode.PRIVATE, microphone = true, network = false, audio = false,
                clipboard = false, environment = mapOf("LANG" to "de_DE.UTF-8"),
            ),
            loaded,
        )
        val alone = AppSettingsStore(file(AppSettingsStore.imported(imported, null).decodeToString())).load()
        assertEquals(AppSettings(microphone = true, audio = false), alone)
    }

    @Test
    fun microphoneAndCameraFollowTheMetadataUntilOverridden() {
        val sound = listOf("x11", "pulseaudio")
        val camera = FlatpakContext(devices = listOf("all"))
        val plain = FlatpakContext(sockets = sound, devices = listOf("dri"))
        // By default: the microphone for an app that asks for sound, the camera for a hint that it uses one.
        val unset = AppSettings()
        assertTrue(unset.microphoneAllowed(sound))
        assertFalse(unset.microphoneAllowed(listOf("x11")))
        assertTrue(unset.cameraAllowed(camera))
        assertFalse(unset.cameraAllowed(plain))
        // Sound off turns the microphone off with it.
        assertFalse(unset.copy(audio = false).microphoneAllowed(sound))
        // An override wins either way.
        assertFalse(unset.copy(microphone = false).microphoneAllowed(sound))
        assertTrue(unset.copy(microphone = true).microphoneAllowed(sound))
        assertTrue(unset.copy(camera = true).cameraAllowed(plain))
        assertFalse(unset.copy(camera = false).cameraAllowed(camera))
        // Hardened's level doesn't grant what apps ask for: both are off until turned on.
        val hardened = AppSettings(defaults = AppDefaults(permissionsFromMetadata = false))
        assertFalse(hardened.microphoneAllowed(sound))
        assertFalse(hardened.cameraAllowed(camera))
        assertTrue(hardened.copy(microphone = true, audio = true).microphoneAllowed(sound))
        // Not saved unless overridden; an override is saved either way.
        val file = File(tmp.root, "m.properties")
        AppSettingsStore(file).save(unset)
        assertFalse("permission.microphone" in file.readText() || "permission.camera" in file.readText())
        AppSettingsStore(file).save(unset.copy(microphone = false, camera = true))
        assertEquals(false to true, AppSettingsStore(file).load().let { it.microphone to it.camera })
    }

    @Test
    fun usageStaysLocalOnImport() {
        val file = File(tmp.root, "u.properties")
        val imported = "usage.launches=99\nusage.millis=5000000\nresolution=50\n".toByteArray()
        val local = "usage.launches=2\nusage.millis=60000\n".toByteArray()
        file.writeBytes(AppSettingsStore.imported(imported, local))
        assertEquals(2 to 60_000L, AppSettingsStore(file).load().let { it.launches to it.usedMillis })
        file.writeBytes(AppSettingsStore.imported(imported, null))
        assertEquals(0 to 0L, AppSettingsStore(file).load().let { it.launches to it.usedMillis })
    }

    @Test
    fun aCameraValueEqualToTheDefaultIsNoOverride() {
        val file = File(tmp.root, "c.properties")
        file.writeText("camera.resolution=720\ncamera.frameRate=30\n")
        val loaded = AppSettingsStore(file).load()
        assertNull(loaded.cameraResolutionOverride)
        assertNull(loaded.cameraFrameRateOverride)
        file.writeText("camera.resolution=1080\ncamera.frameRate=15\n")
        assertEquals(1080 to 15, AppSettingsStore(file).load().let { it.cameraResolutionOverride to it.cameraFrameRateOverride })
    }

    @Test
    fun launchArgumentsRoundTripAndAreKeptLocalOnImport() {
        assertEquals(listOf("--hold", "-e", "x"), LaunchArgs.parse("  --hold  -e\tx "))
        assertEquals(emptyList<String>(), LaunchArgs.parse(" "))
        val f = File(tmp.root, "appsettings/b.App.properties")
        val store = AppSettingsStore(f)
        store.save(AppSettings(launchArgs = listOf("--hold", "--x=1")))
        assertEquals(listOf("--hold", "--x=1"), store.load().launchArgs)
        val imported = File(tmp.root, "appsettings/c.App.properties").apply { writeBytes(AppSettingsStore.imported(f.readBytes(), null)) }
        assertEquals(emptyList<String>(), AppSettingsStore(imported).load().launchArgs)
    }
}
