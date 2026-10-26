package com.garathon.flatdroid.session

import com.garathon.flatdroid.settings.Resampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAudioTest {
    @Test
    fun pulseScriptSetsDefaultsBeforeTheAppsSocket() {
        val on = PulseScript.text(outputRate = 44_100, microphone = true, micRate = 48_000).lines()
        assertTrue(on.first().startsWith("load-module module-null-sink sink_name=out rate=44100"))
        // The microphone straight from Flatdroid's pipe, with no sink and monitor to hold it back.
        assertTrue(on.any { it.startsWith("load-module module-pipe-source source_name=mic file=/run/pulse/mic format=s16le rate=48000 channels=1 ") })
        assertTrue(on.none { "mic_in" in it || "module-simple-protocol-unix socket=/run/pulse/mic" in it })
        val native = on.indexOfFirst { "module-native-protocol-unix" in it }
        assertTrue(native > on.indexOf("set-default-source mic") && native > on.indexOf("set-default-sink out"))

        // Without the microphone, the default source is silent, never the output's monitor.
        val off = PulseScript.text(outputRate = 48_000, microphone = false, micRate = 48_000)
        // module-null-source takes description=, not source_properties= (which fails to load).
        assertTrue("load-module module-null-source source_name=mic description=Microphone\n" in off)
        assertTrue("/run/pulse/mic" !in off)
        assertTrue("set-default-source mic" in off)
    }

    @Test
    fun helpersGetSoundMicrophoneAndResampler() {
        assertEquals(
            listOf("/bin/sh", SessionScript.GUEST_SCRIPT, "1000x1400", "14400", "30", "on", "on", "speex-float-0", "on", "on"),
            SessionScript.command("1000x1400", 30, sound = true, microphone = true, Resampler.FAST, sessionBus = true, camera = true),
        )
        // Without sound there is no PulseAudio, so no microphone either.
        assertEquals(
            // PipeWire only with the bus, which the camera's portal needs.
            listOf("off", "off", "speex-float-1", "off", "off"),
            SessionScript.command("1000x1400", 30, sound = false, microphone = true, Resampler.STANDARD, sessionBus = false, camera = true).takeLast(5),
        )
    }

    @Test
    fun pulseAudioAndTheMicrophoneWatcherStartOnlyWithSound() {
        val lines = SessionScript.TEXT.lines().map { it.trim() }
        val soundCheck = lines.indexOf("if [ \"${'$'}sound\" = on ]; then")
        val pulse = lines.indexOfFirst { "/usr/bin/pulseaudio " in it }
        assertTrue(soundCheck in 0 until pulse && lines.subList(soundCheck, pulse).none { it == "fi" })
        assertTrue("sound=${'$'}4" in lines && "microphone=${'$'}5" in lines && "resampler=${'$'}6" in lines)
    }

    @Test
    fun everyHelperButXvncRunsWithoutInternetSockets() {
        val lines = SessionScript.TEXT.lines().map { it.trim() }
        for (helper in listOf("openbox", "dbus-daemon", "xdg-dbus-proxy", "pipewire", "pulseaudio")) {
            val line = lines.single { "/usr/bin/$helper" in it }
            assertTrue(line, "/run/flatdroid/fdsec --no-inet -- /usr/bin/$helper" in line)
        }
        assertTrue(lines.single { it.startsWith("Xvnc ") }.let { "fdsec" !in it })
    }

    @Test
    fun micUsersAreReadFromTheHelpersOutput() {
        assertEquals(2, SessionScript.micUsers("flatdroid: microphone users 2"))
        assertEquals(0, SessionScript.micUsers("flatdroid: microphone users 0"))
        assertNull(SessionScript.micUsers("flatdroid: display ready"))
        assertNull(SessionScript.micUsers("flatdroid: microphone users x"))
    }

    @Test
    fun microphoneStreamsOnlyWhileAllowedAndRecording() {
        // Not recording: nothing, whatever the answer.
        assertEquals(MicAction.IDLE, MicAction.of(alwaysAllowed = true, answer = null, users = 0))
        assertEquals(MicAction.IDLE, MicAction.of(alwaysAllowed = false, answer = null, users = 0))
        // Recording, not answered yet this session: ask; allowed: record; denied: silence, no asking.
        assertEquals(MicAction.ASK, MicAction.of(alwaysAllowed = false, answer = null, users = 2))
        assertEquals(MicAction.STREAM, MicAction.of(alwaysAllowed = false, answer = true, users = 1))
        assertEquals(MicAction.IDLE, MicAction.of(alwaysAllowed = false, answer = false, users = 1))
        // Always allowed: records without asking.
        assertEquals(MicAction.STREAM, MicAction.of(alwaysAllowed = true, answer = null, users = 1))
    }
}
