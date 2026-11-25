package com.garathon.flatdroid.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoWindowWaitTest {
    @Test
    fun anAppThatHadAWindowIsPausedTenSecondsAfterTheLastOneClosed() {
        val wait = NoWindowWait(startedAt = 0)
        assertNull(wait.windows(1, now = 20_000))
        assertEquals(35_000L, wait.windows(0, now = 25_000))
        // The same answer again changes nothing: the wait counts from the close.
        assertEquals(35_000L, wait.windows(0, now = 27_000))
        assertTrue(wait.empty)
        assertTrue(wait.opened)
    }

    @Test
    fun aWindowOpeningCancelsTheWaitAndEndsTheDoubling() {
        val wait = NoWindowWait(startedAt = 0)
        wait.windows(1, 1_000)
        wait.windows(0, 7_000)
        assertEquals(32_000L, wait.resumed(now = 12_000)) // 10 s doubled
        assertEquals(20_000L, wait.waitMs)
        assertNull(wait.windows(2, 13_000))
        assertFalse(wait.empty)
        assertEquals(10_000L, wait.waitMs)
        // One of two closing isn't none.
        assertNull(wait.windows(1, 14_000))
        assertEquals(30_000L, wait.windows(0, 20_000))
    }

    @Test
    fun eachResumeDoublesTheWaitUpToFiveMinutes() {
        val wait = NoWindowWait(startedAt = 0)
        wait.windows(1, 0)
        wait.windows(0, 5_000)
        val waits = (1..10).map { wait.resumed(now = 0); wait.waitMs }
        assertEquals(listOf(20_000L, 40_000L, 80_000L, 160_000L, 300_000L), waits.take(5))
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

    @Test
    fun timeStoppedForAnotherReasonDoesntCount() {
        val wait = NoWindowWait(startedAt = 0)
        assertNull(wait.deadline) // nothing is known before the first count
        assertEquals(120_000L, wait.windows(0, now = 1_000))
        assertEquals(120_000L, wait.deadline)
        // Stopped from 30 s to 200 s: the two minutes run on from where they were.
        wait.suspended(from = 30_000, until = 200_000)
        assertEquals(290_000L, wait.deadline)
        wait.windows(1, 210_000)
        assertNull(wait.deadline)
    }

    @Test
    fun aResumeInsideTheStopCountsFromTheEndOfIt() {
        val wait = NoWindowWait(startedAt = 0)
        wait.windows(1, 0)
        wait.windows(0, 6_000)
        wait.resumed(now = 60_000) // doubled: 20 s from the resume
        wait.suspended(from = 40_000, until = 100_000)
        assertEquals(120_000L, wait.deadline)
    }

    @Test
    fun anAppWhoseWindowsNobodyCountsGetsItsFirstWindowTimeBeforeAHiddenPause() {
        val wait = NoWindowWait(startedAt = 1_000)
        assertEquals(120_000L, wait.firstWindowLeft(now = 1_000))
        assertEquals(31_000L, wait.firstWindowLeft(now = 90_000))
        assertEquals(0L, wait.firstWindowLeft(now = 200_000))
        // Counted: the wait for a window decides, not this.
        wait.windows(0, now = 2_000)
        assertEquals(0L, wait.firstWindowLeft(now = 3_000))
        val opened = NoWindowWait(startedAt = 0).apply { windows(1, 0); windows(0, 6_000) }
        assertEquals(0L, opened.firstWindowLeft(now = 7_000))
    }

    @Test
    fun aBriefWindowIsNotTheAppStarting() {
        // A splash for under a second, then a restart with no window: the first-window time goes on.
        val wait = NoWindowWait(startedAt = 0)
        assertNull(wait.windows(1, 10_000))
        assertEquals(120_000L, wait.windows(0, 10_600))
        assertFalse(wait.opened)
        // The real window, open for long: from then on its closing starts the short wait.
        assertNull(wait.windows(1, 70_000))
        assertEquals(90_000L, wait.windows(0, 80_000))
        assertTrue(wait.opened)
    }
}
