package com.garathon.flatdroid.viewer.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollSpeedTest {
    @Test
    fun startsAtTheTypicalUnitAndMeasures() {
        val speed = ScrollSpeed()
        assertEquals(1f, speed.share, 0f)
        assertTrue(speed.measuring)
    }

    @Test
    fun threeAgreeingMeasurementsSettleIt() {
        val speed = ScrollSpeed()
        speed.measured(1.8f)
        speed.measured(1.9f)
        assertTrue(speed.measuring)
        speed.measured(1.85f)
        assertFalse(speed.measuring)
        assertEquals(1.85f, speed.share, 0.01f)
    }

    @Test
    fun oneOutlierMovesNothing() {
        val speed = ScrollSpeed()
        speed.measured(0.6f)
        speed.measured(2.9f) // the newest of an even count is left out
        assertEquals(0.6f, speed.share, 0f)
        speed.measured(0.62f)
        assertEquals(0.62f, speed.share, 0f)
    }

    @Test
    fun dragsThatScrollNothingLeaveTheSpeedAndEndMeasuringForTheSession() {
        val speed = ScrollSpeed("1.400")
        repeat(ScrollSpeed.MAX_FAILURES - 1) { speed.measured(null) }
        speed.measured(10f) // implausible: counts as one more
        assertEquals(1.4f, speed.share, 0f)
        assertFalse(speed.measuring)
        // The next session tries again from where this one was.
        assertTrue(ScrollSpeed(speed.saved()).measuring)
    }

    @Test
    fun aSettledSpeedIsKeptAndNotMeasuredAgain() {
        val speed = ScrollSpeed()
        repeat(3) { speed.measured(0.45f) }
        val next = ScrollSpeed(speed.saved()).apply { setup(0, 0f) }
        assertEquals(0.45f, next.share, 0.001f)
        assertFalse(next.measuring)
    }

    @Test
    fun aSettledSpeedIsLearntAgainForAnotherFrameOrInterfaceSize() {
        val speed = ScrollSpeed()
        speed.setup(1020, 12f)
        repeat(3) { speed.measured(1.8f) }
        val same = ScrollSpeed(speed.saved()).apply { setup(1020, 12f) }
        assertFalse(same.measuring)
        val taller = ScrollSpeed(speed.saved()).apply { setup(1400, 12f) }
        assertTrue(taller.measuring)
        assertEquals(1.8f, taller.share, 0.001f) // learnt again from where it was
        val larger = ScrollSpeed(speed.saved()).apply { setup(1020, 16f) }
        assertTrue(larger.measuring)
    }

    @Test
    fun aSavedValueThatMakesNoSenseIsIgnored() {
        assertEquals(1f, ScrollSpeed("junk").share, 0f)
        assertEquals(1f, ScrollSpeed("40 settled").share, 0f)
        assertTrue(ScrollSpeed("40 settled").measuring)
    }
}
