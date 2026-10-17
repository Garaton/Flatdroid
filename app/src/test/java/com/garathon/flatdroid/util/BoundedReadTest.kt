package com.garathon.flatdroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoundedReadTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun smallFilesAreReadAndLargerOnesRefused() {
        val file = tmp.newFile("a.desktop")
        file.writeText("[Desktop Entry]\nName=Å\n")
        assertEquals("[Desktop Entry]\nName=Å\n", file.readTextUpTo())
        file.writeBytes(ByteArray(11))
        assertEquals(11, file.readTextUpTo(11)!!.length)
        assertNull(file.readTextUpTo(10))
    }
}
