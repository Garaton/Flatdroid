package com.garathon.flatdroid.camera

import com.garathon.flatdroid.camera.AndroidCameras.FULL_RESOLUTION
import com.garathon.flatdroid.camera.AndroidCameras.Resolution
import com.garathon.flatdroid.camera.AndroidCameras.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidCamerasTest {
    private val back = listOf(Size(4000, 3000), Size(1920, 1080), Size(1440, 1080), Size(1280, 720), Size(640, 480), Size(176, 144))
    private val front = listOf(Size(1920, 1080), Size(1280, 720), Size(640, 480))

    @Test
    fun theLargestSizeUpToTheShortSideElseTheSmallest() {
        assertEquals(Size(1920, 1080), AndroidCameras.pick(back, 1080))
        assertEquals(Size(1280, 720), AndroidCameras.pick(back, 720))
        // No 360: the largest below it.
        assertEquals(Size(176, 144), AndroidCameras.pick(back, 360))
        assertEquals(Size(4000, 3000), AndroidCameras.pick(back, FULL_RESOLUTION))
        assertEquals(Size(640, 480), AndroidCameras.pick(front, 144))
        assertNull(AndroidCameras.pick(emptyList(), 720))
    }

    @Test
    fun ratesAreTheCamerasOwnRangeTops() {
        // A phone's ranges: [10,10] [10,15] [15,15] [7,20] [15,20] [20,20] [5,30] [24,30] [30,30] [60,60].
        val tops = listOf(10, 15, 15, 20, 20, 20, 30, 30, 30, 60)
        assertEquals(listOf(10, 15, 20, 30, 60), AndroidCameras.rates(tops) { true })
        // A rate no YUV size is fast enough for isn't offered; nonsense tops are ignored.
        assertEquals(listOf(10, 15, 20, 30), AndroidCameras.rates(tops + 0 + 1000) { it <= 30 })
    }

    @Test
    fun choicesAreSidesSomeCameraGivesAfterFull() {
        assertEquals(
            listOf(Resolution(FULL_RESOLUTION, listOf(Size(4000, 3000), Size(1920, 1080))), Resolution(1080), Resolution(720), Resolution(480), Resolution(144)),
            AndroidCameras.resolutions(listOf(back, front)),
        )
        // Full is the 1080p choice already.
        assertEquals(listOf(Resolution(1080), Resolution(720), Resolution(480)), AndroidCameras.resolutions(listOf(front)))
        assertEquals(emptyList<Resolution>(), AndroidCameras.resolutions(emptyList()))
    }
}
