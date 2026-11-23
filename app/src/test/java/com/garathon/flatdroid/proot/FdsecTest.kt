package com.garathon.flatdroid.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FdsecTest {
    @Test
    fun theCommandComesAfterTheFlagsAndADoubleDash() {
        assertEquals(listOf("/f", "--", "/bin/x", "-a"), Fdsec.wrap("/f", Fdsec.Filter(), listOf("/bin/x", "-a")))
        assertEquals(listOf("/f", "--extra", "--no-inet", "--", "/bin/x"), Fdsec.wrap("/f", Fdsec.Filter(true, true), listOf("/bin/x")))
        assertEquals(listOf("--no-inet"), Fdsec.flags(Fdsec.Filter(noInternet = true)))
    }

    @Test
    fun aTerminalGoesBeforeTheDoubleDash() {
        assertEquals(listOf("/f", "--no-inet", "--tty", "/dev/pts/3", "--", "/bin/x"), Fdsec.wrap("/f", Fdsec.Filter(noInternet = true), listOf("/bin/x"), tty = "/dev/pts/3"))
        assertEquals(listOf(Fdsec.ALPINE_PATH, "--no-inet", "--hold-tty", "/run/t"), Fdsec.holdTerminal(Fdsec.Filter(noInternet = true), "/run/t"))
    }

    @Test
    fun onlyPseudoTerminalsAreTerminals() {
        assertTrue(Fdsec.isTerminal("/dev/pts/0"))
        assertTrue(Fdsec.isTerminal("/dev/pts/12"))
        for (path in listOf("/dev/pts/", "/dev/pts/1/../../null", "/dev/null", "/dev/pts/1 ", "/dev/pts/x", "/dev/tty")) assertFalse(path, Fdsec.isTerminal(path))
        assertThrows(IllegalArgumentException::class.java) { Fdsec.wrap("/f", Fdsec.Filter(), listOf("/bin/x"), tty = "/dev/null") }
        assertEquals(listOf("/f", "--no-filter", "--tty", "/dev/pts/3", "--", "/bin/x"), Fdsec.terminalOnly("/f", listOf("/bin/x"), "/dev/pts/3"))
        assertThrows(IllegalArgumentException::class.java) { Fdsec.terminalOnly("/f", listOf("/bin/x"), "/dev/null") }
    }

    @Test
    fun aRefusalIsRecognisedOnlyAtTheStartOfALine() {
        assertTrue(Fdsec.refused("fdsec: refused: the filter is not in effect"))
        assertFalse(Fdsec.refused("app says fdsec: refused"))
        assertFalse(Fdsec.refused("fdsec: cannot execute /x"))
    }
}
