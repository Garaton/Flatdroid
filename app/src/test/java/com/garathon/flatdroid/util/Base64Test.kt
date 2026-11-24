package com.garathon.flatdroid.util

import org.junit.Assert.assertEquals
import org.junit.Test

class Base64Test {
    @Test
    fun encodesWithPadding() {
        assertEquals("", base64(ByteArray(0)))
        assertEquals("YQ==", base64("a".toByteArray()))
        assertEquals("YWI=", base64("ab".toByteArray()))
        assertEquals("YWJj", base64("abc".toByteArray()))
    }

}
