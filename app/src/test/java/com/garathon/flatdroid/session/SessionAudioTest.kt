package com.garathon.flatdroid.session

import com.garathon.flatdroid.settings.Resampler
import org.junit.Assert.assertEquals
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
    fun helpersGetSoundAndResampler() {
        assertEquals(
            listOf("/bin/sh", SessionScript.GUEST_SCRIPT, "1000x1400", "14400", "30", "on", "speex-float-0", "on", "on", "on", "off"),
            SessionScript.command("1000x1400", 30, sound = true, Resampler.FAST, sessionBus = true, camera = true),
        )
        assertEquals(
            // PipeWire only with the bus, which the camera's portal needs.
            listOf("off", "speex-float-1", "off", "off"),
            SessionScript.command("1000x1400", 30, sound = false, Resampler.STANDARD, sessionBus = false, camera = true).subList(5, 9),
        )
    }

    @Test
    fun aWaylandSessionStartsCageAndWayvncInsteadOfXvnc() {
        assertEquals("on", SessionScript.command("1000x1400", 30, sound = false, Resampler.FAST, sessionBus = true, wayland = true).last())
        val text = SessionScript.TEXT
        assertTrue("wayland=${'$'}9" in text.lines().map { it.trim() })
        assertTrue("LD_PRELOAD=/run/flatdroid/fdshm.so XDG_RUNTIME_DIR=/run/flatdroid/wayland" in text)
        // wayvnc's control socket stays among the helpers, never in the app's Wayland directory.
        assertTrue("-S \"${'$'}3\"" in text && "/run/flatdroid/vnc/wayvncctl" in text)
        assertTrue("/run/flatdroid/vnc/wayvnc.conf" in text)
    }

    @Test
    fun wayvncsLoginHasAUsernameAndRefusesOddPasswords() {
        assertEquals(
            "enable_auth=true\nusername=flatdroid\npassword=abcDEF234567wxyz\nrelax_encryption=true\n",
            WaylandVnc.config("abcDEF234567wxyz"),
        )
        try {
            WaylandVnc.config("pass word\nenable_auth=false")
            throw AssertionError("expected a refusal")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun anUnsharedClipboardTurnsOffXvncsCutText() {
        val args = SessionScript.command("1000x1400", 30, sound = false, Resampler.FAST, sessionBus = true, clipboard = false)
        assertEquals("off", args[args.size - 2])
        val lines = SessionScript.TEXT.lines().map { it.trim() }
        assertTrue("clipboard=${'$'}8" in lines)
        assertTrue("[ \"${'$'}clipboard\" = on ] || clipboard_args=\"-AcceptCutText=0 -SendCutText=0\"" in lines)
        assertTrue(lines.single { it.startsWith("-nolisten local") }.endsWith("${'$'}clipboard_args &"))
    }

    @Test
    fun pulseAudioStartsOnlyWithSoundAndNothingWatchesItLater() {
        val lines = SessionScript.TEXT.lines().map { it.trim() }
        val soundCheck = lines.indexOf("if [ \"${'$'}sound\" = on ]; then")
        val pulse = lines.indexOfFirst { "/usr/bin/pulseaudio " in it }
        assertTrue(soundCheck in 0 until pulse && lines.subList(soundCheck, pulse).none { it == "fi" })
        assertTrue("sound=${'$'}4" in lines && "resampler=${'$'}5" in lines)
        // Whether the app records comes from the microphone's pipe, not from a process started later.
        assertTrue(lines.none { "pactl" in it })
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
    fun microphoneStreamsOnlyWhileAllowedAndRecording() {
        // Not recording: nothing, whatever the answer.
        assertEquals(MicAction.IDLE, MicAction.of(alwaysAllowed = true, answer = null, recording = false))
        assertEquals(MicAction.IDLE, MicAction.of(alwaysAllowed = false, answer = null, recording = false))
        // Recording, not answered yet this session: ask; allowed: record; denied: silence, no asking.
        assertEquals(MicAction.ASK, MicAction.of(alwaysAllowed = false, answer = null, recording = true))
        assertEquals(MicAction.STREAM, MicAction.of(alwaysAllowed = false, answer = true, recording = true))
        assertEquals(MicAction.IDLE, MicAction.of(alwaysAllowed = false, answer = false, recording = true))
        // Always allowed: records without asking.
        assertEquals(MicAction.STREAM, MicAction.of(alwaysAllowed = true, answer = null, recording = true))
    }

    @Test
    fun theAppCantLoadPulseAudioModules() {
        val line = SessionScript.TEXT.lines().map { it.trim() }.let { lines ->
            val start = lines.indexOfFirst { "/usr/bin/pulseaudio" in it }
            lines[start] + " " + lines[start + 1]
        }
        assertTrue(line, "--disallow-module-loading" in line && "--disallow-exit" in line)
    }
}
