package com.garathon.flatdroid.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Base64Test {
    @Test
    fun encodesWithPadding() {
        assertEquals("", base64(ByteArray(0)))
        assertEquals("YQ==", base64("a".toByteArray()))
        assertEquals("YWI=", base64("ab".toByteArray()))
        assertEquals("YWJj", base64("abc".toByteArray()))
    }

    @Test
    fun decodesWhatItEncodes() {
        for (n in 0..70) {
            val bytes = ByteArray(n) { (it * 37 + 11).toByte() }
            assertArrayEquals(bytes, fromBase64(base64(bytes)))
        }
        assertArrayEquals("a".toByteArray(), fromBase64("YQ=="))
    }

    @Test
    fun refusesWhatIsntBase64() {
        assertNull(fromBase64("YQ="))
        assertNull(fromBase64("Y=Q="))
        assertNull(fromBase64("YQ-_"))
        assertNull(fromBase64("Y==="))
    }
}
