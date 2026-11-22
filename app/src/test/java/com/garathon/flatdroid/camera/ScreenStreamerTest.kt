package com.garathon.flatdroid.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenStreamerTest {
    @Test
    fun theScreenIsMadeSmallerInItsOwnShape() {
        // Half by default: a 720x1440 screen at 360x720, a 1080x2400 one at 536x1200 (sides multiples of 8).
        assertEquals(360 to 720, ScreenStreamer.fit(720, 1440, ScreenStreamer.DEFAULT_SCALE))
        assertEquals(536 to 1200, ScreenStreamer.fit(1080, 2400, 50))
        assertEquals(1200 to 536, ScreenStreamer.fit(2400, 1080, 50))
        assertEquals(1080 to 2400, ScreenStreamer.fit(1080, 2400, 100))
        assertEquals(176 to 360, ScreenStreamer.fit(720, 1440, 25))
        // A screen 1919x1036: 1912x1032, not 1918 wide (GStreamer's rows are 4-aligned).
        assertEquals(1912 to 1032, ScreenStreamer.fit(1919, 1036, 100))
    }
}
