package com.garathon.flatdroid.session.helpers

import com.garathon.flatdroid.audio.MicAction
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
            listOf("/bin/sh", SessionScript.GUEST_SCRIPT, "1000x1400", "14400", "30", "on", "speex-float-0", "on", "on", "on", "off", "off", "1.00", "on"),
            SessionScript.command("1000x1400", 30, sound = true, Resampler.FAST, sessionBus = true, camera = true),
        )
        assertEquals(
            // PipeWire only with the bus, which the camera's portal needs.
            listOf("off", "speex-float-1", "off", "off"),
            SessionScript.command("1000x1400", 30, sound = false, Resampler.STANDARD, sessionBus = false, camera = true).subList(5, 9),
        )
    }

    @Test
    fun pulseAudioSharesMemoryOnlyWhereTheKernelHasMemfd() {
        assertTrue(SessionScript.kernelHasMemfd("4.19.191-g1234"))
        assertTrue(SessionScript.kernelHasMemfd("3.18.71"))
        assertTrue(SessionScript.kernelHasMemfd("3.17.0"))
        assertTrue(SessionScript.kernelHasMemfd("6.1.0-android14"))
        assertTrue(!SessionScript.kernelHasMemfd("3.10.0+"))
        assertTrue(!SessionScript.kernelHasMemfd("2.6.32"))
        assertTrue(SessionScript.kernelHasMemfd("unreadable"))
        assertEquals("off", SessionScript.command("1000x1400", 30, sound = true, Resampler.FAST, sessionBus = true, memfd = false)[13])
        assertTrue("memfd=${'$'}{12}" in SessionScript.TEXT.lines().map { it.trim() })
    }

    @Test
    fun aWaylandSessionStartsLabwcAndWayvncInsteadOfXvnc() {
        assertEquals("on", SessionScript.command("1000x1400", 30, sound = false, Resampler.FAST, sessionBus = true, wayland = true)[10])
        val text = SessionScript.TEXT
        assertTrue("wayland=${'$'}9" in text.lines().map { it.trim() })
        assertTrue("LD_PRELOAD=/run/flatdroid/fdshm.so XDG_RUNTIME_DIR=/run/flatdroid/wayland" in text)
        // wayvnc's control socket stays among the helpers, never in the app's Wayland directory.
        assertTrue("exec /usr/bin/wayvnc -S /run/flatdroid/vnc/wayvncctl -f" in text)
        // labwc ends with wayvnc (-S), its configuration among the helpers.
        assertTrue("/usr/bin/labwc -C /run/flatdroid/vnc/labwc -S" in text)
        // The output refreshes at the frame rate sent, unless uncapped.
        assertTrue("[ \"${'$'}frame_rate\" -le 240 ] && mode=\"${'$'}geometry@${'$'}{frame_rate}Hz\"" in text)
        // labwc always runs behind a filter without Internet sockets, whatever the viewer: it parses what the app sends.
        val lines = text.lines().map { it.trim() }
        val labwc = lines.filter { "/usr/bin/labwc" in it }
        assertEquals(2, labwc.size)
        assertTrue(labwc.all { " --no-inet -- /usr/bin/labwc" in it })
        // Flatdroid's viewer: wayvnc starts under labwc, on a Unix socket, with no login.
        assertTrue("-f ${'$'}frame_rate -u /run/flatdroid/vnc/vnc.sock" in text)
        // An external viewer: wayvnc listens on the port with the login, so the script starts it (after the output's mode is set), unfiltered.
        assertTrue(lines.any { it.startsWith("/usr/bin/wayvnc -S") && "-C /run/flatdroid/vnc/wayvnc.conf 127.0.0.1 ${'$'}port" in it })
        assertTrue(lines.any { it.startsWith("/usr/bin/wlr-randr ") })
    }

    @Test
    fun xvncListensOnAUnixSocketUnlessTheViewerIsExternal() {
        assertEquals("on", SessionScript.command("1000x1400", 30, sound = false, Resampler.FAST, sessionBus = true, externalViewer = true)[11])
        val lines = SessionScript.TEXT.lines().map { it.trim() }
        assertTrue("external=${'$'}{10}" in lines)
        assertTrue("listen=\"-rfbport -1 -rfbunixpath /run/flatdroid/vnc/vnc.sock -rfbunixmode 0600 -SecurityTypes None\"" in lines)
        assertTrue("listen=\"-rfbport ${'$'}port -localhost -SecurityTypes VncAuth -PasswordFile /run/flatdroid/vnc/passwd\"" in lines)
        // An external viewer needs the network, so Xvnc may not connect anywhere (the VNC extension's reverse connections).
        assertTrue(lines.any { it.startsWith("guard=\"") && it.endsWith(" --no-connect --\"") })
        assertTrue(lines.any { it.startsWith("guard=\"") && it.endsWith(" --no-inet --\"") })
        // No parameter overrides through the VNC extension.
        assertTrue(lines.single { it.startsWith("-AllowOverride= -nolisten local") }.isNotEmpty())
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
        assertEquals("off", args[9])
        val lines = SessionScript.TEXT.lines().map { it.trim() }
        assertTrue("clipboard=${'$'}8" in lines)
        assertTrue("[ \"${'$'}clipboard\" = on ] || clipboard_args=\"-AcceptCutText=0 -SendCutText=0\"" in lines)
        assertTrue(lines.single { it.startsWith("-AllowOverride= -nolisten local") }.endsWith("${'$'}clipboard_args &"))
        // Selecting text isn't copying: X's selection (PRIMARY) never goes to the viewer.
        assertTrue(lines.single { it.startsWith("-AllowOverride= -nolisten local") }.endsWith("-SendPrimary=0 ${'$'}clipboard_args &"))
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
    fun theBusAndPulseAudioStartBeforeTheDisplayAndTheirSocketsAreWaitedForAfterIt() {
        val lines = SessionScript.TEXT.lines().map { it.trim() }
        val bus = lines.indexOfFirst { "/usr/bin/dbus-daemon" in it }
        val pulse = lines.indexOfFirst { "/usr/bin/pulseaudio " in it }
        val labwc = lines.indexOfFirst { "/usr/bin/labwc" in it }
        val xvnc = lines.indexOfFirst { "Xvnc :1" in it }
        val proxy = lines.indexOfFirst { "/usr/bin/xdg-dbus-proxy" in it }
        val ready = lines.indexOfFirst { it.startsWith("echo \"flatdroid: display ready") }
        assertTrue(bus in 0 until labwc && bus < xvnc && pulse in 0 until labwc && pulse < xvnc)
        // The proxy needs the daemon's socket: started after the display, not in front of it.
        assertTrue(proxy > xvnc && proxy > labwc && proxy < ready)
        // Nothing in front of the display waits: the only sleep is in wait_socket's own definition.
        assertEquals(1, lines.subList(0, minOf(labwc, xvnc)).count { it.startsWith("sleep ") })
    }

    @Test
    fun everyHelperRunsWithoutInternetSockets() {
        val lines = SessionScript.TEXT.lines().map { it.trim() }
        for (helper in listOf("openbox", "dbus-daemon", "xdg-dbus-proxy", "pipewire", "pulseaudio")) {
            val line = lines.single { "/usr/bin/$helper" in it }
            assertTrue(line, "/run/flatdroid/fdsec --no-inet -- /usr/bin/$helper" in line)
        }
        // Xvnc too, behind the filter chosen for the viewer (no internet sockets, or no connecting for an external viewer).
        assertTrue(lines.single { "Xvnc :1" in it }.startsWith("${'$'}guard /usr/bin/Xvnc "))
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
