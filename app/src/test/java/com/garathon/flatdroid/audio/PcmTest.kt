package com.garathon.flatdroid.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmTest {
    @Test
    fun bytesAreWholeFrames() {
        assertEquals(1920, Pcm.bytesFor(48_000, 1, 20))
        assertEquals(11_520, Pcm.bytesFor(48_000, 2, 60))
        assertEquals(1764, Pcm.bytesFor(44_100, 2, 10))
        assertEquals(4, Pcm.bytesFor(48_000, 2, 0))
    }

    @Test
    fun silenceIsAllZeroWithinTheLength() {
        assertTrue(Pcm.isSilent(ByteArray(8), 8))
        val buffer = ByteArray(8).also { it[6] = 1 }
        assertFalse(Pcm.isSilent(buffer, 8))
        assertTrue(Pcm.isSilent(buffer, 6))
    }
}
