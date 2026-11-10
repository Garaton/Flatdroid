package com.garathon.flatdroid.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProotExitTest {
    @Test
    fun readsTheAppsSignal() {
        assertEquals(7, ProotExit.signalOf("proot info: vpid 1: terminated with signal 7"))
        assertEquals(11, ProotExit.signalOf("11-09 15:11:58.222 I proot info: vpid 1: terminated with signal 11"))
        // Another process of the app, or any other line, isn't the app's end.
        assertNull(ProotExit.signalOf("proot info: vpid 12: terminated with signal 9"))
        assertNull(ProotExit.signalOf("[flatdroid] the app exited (0)"))
    }

    @Test
    fun namesSignals() {
        assertEquals("SIGBUS", ProotExit.signalName(7))
        assertEquals("SIGSEGV", ProotExit.signalName(11))
        assertEquals("signal 40", ProotExit.signalName(40))
    }

    @Test
    fun tellsCrashesFromOtherEnds() {
        assertEquals(AppEnding.Crashed(11), ProotExit.ending(signal = 11, code = 0))
        assertEquals(AppEnding.Crashed(6), ProotExit.ending(signal = 6, code = 0))
        // Sent to end it: not a crash.
        assertEquals(AppEnding.Ended(9), ProotExit.ending(signal = 9, code = 0))
        assertEquals(AppEnding.Ended(15), ProotExit.ending(signal = 15, code = 0))
        assertEquals(AppEnding.Failed(1), ProotExit.ending(signal = 0, code = 1))
        assertNull(ProotExit.ending(signal = 0, code = 0))
    }

    @Test
    fun readsASignalThroughAShell() {
        // A launcher script that doesn't exec the app: the shell exits 128 + the signal.
        assertEquals(AppEnding.Crashed(11), ProotExit.ending(signal = 0, code = 139))
        assertEquals(AppEnding.Ended(15), ProotExit.ending(signal = 0, code = 143))
        // Beyond the standard signals, an exit code as any other.
        assertEquals(AppEnding.Failed(200), ProotExit.ending(signal = 0, code = 200))
        assertEquals(AppEnding.Failed(128), ProotExit.ending(signal = 0, code = 128))
    }
}
