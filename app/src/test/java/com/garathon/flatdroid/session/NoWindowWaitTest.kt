package com.garathon.flatdroid.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoWindowWaitTest {
    @Test
    fun anAppThatHadAWindowIsPausedFiveSecondsAfterTheLastOneClosed() {
        val wait = NoWindowWait(startedAt = 0)
        assertNull(wait.windows(1, now = 20_000))
        assertEquals(30_000L, wait.windows(0, now = 25_000))
        // The same answer again changes nothing: the wait counts from the close.
        assertEquals(30_000L, wait.windows(0, now = 27_000))
        assertTrue(wait.empty)
        assertTrue(wait.opened)
    }

    @Test
    fun aWindowOpeningCancelsTheWaitAndEndsTheDoubling() {
        val wait = NoWindowWait(startedAt = 0)
        wait.windows(1, 1_000)
        wait.windows(0, 2_000)
        assertEquals(17_000L, wait.resumed(now = 7_000)) // 5 s doubled
        assertEquals(10_000L, wait.waitMs)
        assertNull(wait.windows(2, 8_000))
        assertFalse(wait.empty)
        assertEquals(5_000L, wait.waitMs)
        // One of two closing isn't none.
        assertNull(wait.windows(1, 9_000))
        assertEquals(15_000L, wait.windows(0, 10_000))
    }

    @Test
    fun eachResumeDoublesTheWaitUpToFiveMinutes() {
        val wait = NoWindowWait(startedAt = 0)
        wait.windows(1, 0)
        wait.windows(0, 0)
        val waits = (1..10).map { wait.resumed(now = 0); wait.waitMs }
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 80_000L, 160_000L), waits.take(5))
        assertTrue(waits.drop(5).all { it == 300_000L })
    }

    @Test
    fun anAppThatNeverOpenedAWindowGetsTwoMinutesFromItsStart() {
        val wait = NoWindowWait(startedAt = 1_000)
        assertFalse(wait.opened)
        // Told late (the viewer opened a minute in): the limit still counts from the start.
        assertEquals(121_000L, wait.windows(0, now = 60_000))
        assertEquals(121_000L, wait.windows(0, now = 61_000))
        // After a Resume it doubles, counted from there, and is capped like the other.
        assertEquals(370_000L, wait.resumed(now = 130_000))
        assertEquals(670_000L, wait.resumed(now = 370_000))
        assertEquals(300_000L, wait.waitMs)
    }
}
