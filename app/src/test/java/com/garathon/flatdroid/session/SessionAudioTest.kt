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
        assertTrue(on.any { it.startsWith("load-module module-simple-protocol-unix socket=/run/pulse/mic ") && "rate=48000" in it })
        assertTrue(on.any { it.startsWith("load-module module-remap-source source_name=mic master=mic_in.monitor") })
        val native = on.indexOfFirst { "module-native-protocol-unix" in it }
        assertTrue(native > on.indexOf("set-default-source mic") && native > on.indexOf("set-default-sink out"))

        // Without the microphone, the default source is silent, never the output's monitor.
        val off = PulseScript.text(outputRate = 48_000, microphone = false, micRate = 48_000)
        assertTrue("module-null-source source_name=mic " in off)
        assertTrue("/run/pulse/mic" !in off)
        assertTrue("set-default-source mic" in off)
    }

    @Test
    fun helpersGetSoundMicrophoneAndResampler() {
        assertEquals(
            listOf("/bin/sh", SessionScript.GUEST_SCRIPT, "1000x1400", "14400", "30", "on", "on", "speex-float-0"),
            SessionScript.command("1000x1400", 30, sound = true, microphone = true, Resampler.FAST),
        )
        // Without sound there is no PulseAudio, so no microphone either.
        assertEquals(
            listOf("off", "off", "speex-float-1"),
            SessionScript.command("1000x1400", 30, sound = false, microphone = true, Resampler.STANDARD).takeLast(3),
        )
    }

    @Test
    fun pulseAudioAndTheMicrophoneWatcherStartOnlyWithSound() {
        val lines = SessionScript.TEXT.lines().map { it.trim() }
        val soundCheck = lines.indexOf("if [ \"${'$'}sound\" = on ]; then")
        val pulse = lines.indexOfFirst { it.startsWith("pulseaudio ") }
        assertTrue(soundCheck in 0 until pulse && lines.subList(soundCheck, pulse).none { it == "fi" })
        assertTrue("sound=${'$'}4" in lines && "microphone=${'$'}5" in lines && "resampler=${'$'}6" in lines)
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
