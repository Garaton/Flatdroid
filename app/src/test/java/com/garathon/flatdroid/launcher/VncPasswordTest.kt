package com.garathon.flatdroid.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VncPasswordTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** Expected values from TigerVNC's `vncpasswd -f`. */
    @Test
    fun matchesVncpasswd() {
        assertEquals("dbd83cfd727a1458", hex(VncPassword.file("password")))
        assertEquals("2eb990fcd5b16a53", hex(VncPassword.file("fdtest12")))
        assertEquals("9c0a172d3482e122", hex(VncPassword.file("abc")))
    }

    @Test
    fun randomPasswordsAreEightUnambiguousCharacters() {
        repeat(50) {
            val p = VncPassword.random()
            assertEquals(8, p.length)
            assertTrue(p, p.none { it in "0O1lI" })
        }
    }
}
