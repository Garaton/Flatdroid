package com.garathon.flatdroid.session.helpers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XAuthorityTest {
    @Test
    fun oneCookieForAnyAddressAndDisplay() {
        val cookie = ByteArray(16) { it.toByte() }
        val name = "MIT-MAGIC-COOKIE-1".toByteArray()
        // As Xau writes an entry: family FamilyWild, empty address and number, then name and data.
        val expected = byteArrayOf(-1, -1, 0, 0, 0, 0, 0, name.size.toByte()) + name + byteArrayOf(0, 16) + cookie
        assertArrayEquals(expected, XAuthority.file(cookie))
    }

    @Test
    fun cookiesDiffer() {
        val a = XAuthority.cookie()
        assertEquals(XAuthority.COOKIE_BYTES, a.size)
        assertFalse(a.contentEquals(XAuthority.cookie()))
    }

    @Test
    fun xwaylandTakesTheCookie() {
        assertTrue("-auth /tmp/.X11-unix/Xauthority" in SessionScript.XWAYLAND_WRAPPER)
    }
}
