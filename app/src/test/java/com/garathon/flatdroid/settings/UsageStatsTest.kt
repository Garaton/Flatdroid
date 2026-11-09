package com.garathon.flatdroid.settings

import com.garathon.flatdroid.flatpak.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageStatsTest {
    private fun app(id: String, launches: Int, millis: Long) =
        InstalledApp(id, id, null, null, launches = launches, usedMillis = millis)

    @Test
    fun addsUpLaunchesAndTimeAndFindsTheMostUsed() {
        val stats = UsageStats.of(listOf(app("a", 2, 60_000), app("b", 5, 3_600_000), app("c", 0, 0)))
        assertEquals(7, stats.launches)
        assertEquals(3_660_000L, stats.usedMillis)
        assertEquals("b", stats.mostUsed?.id)
    }

    @Test
    fun noUseMeansNoMostUsed() {
        assertNull(UsageStats.of(listOf(app("a", 0, 0))).mostUsed)
        assertNull(UsageStats.of(emptyList()).mostUsed)
    }

    @Test
    fun splitsDurationsIntoTheUnitsShown() {
        assertEquals(Duration(0, 0, 0), Duration.of(-5))
        assertEquals(Duration.Kind.MINUTES, Duration.of(45 * 60_000L).largest)
        assertEquals(Duration(0, 3, 20), Duration.of((3 * 60 + 20) * 60_000L))
        assertEquals(Duration.Kind.HOURS_MINUTES, Duration.of((3 * 60 + 20) * 60_000L).largest)
        val long = Duration.of(((2 * 24 + 4) * 60 + 30) * 60_000L)
        assertEquals(Duration(2, 4, 30), long)
        assertEquals(Duration.Kind.DAYS_HOURS, long.largest)
    }
}
