package com.garathon.flatdroid.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun aRefusalIsRecognisedOnlyAtTheStartOfALine() {
        assertTrue(Fdsec.refused("fdsec: refused: the filter is not in effect"))
        assertFalse(Fdsec.refused("app says fdsec: refused"))
        assertFalse(Fdsec.refused("fdsec: cannot execute /x"))
    }
}
