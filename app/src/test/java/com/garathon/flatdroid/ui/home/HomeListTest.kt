package com.garathon.flatdroid.ui.home

import com.garathon.flatdroid.flatpak.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeListTest {
    private val apps = listOf(
        InstalledApp("b.B", "beta", null, null, usedMillis = 5_000),
        InstalledApp("a.A", "Alpha", null, 100L, usedMillis = 90_000),
        InstalledApp("c.C", "Gamma", null, 200L),
    )

    private fun names(sort: String, text: String = ""): List<String> {
        val query = homeListSpec.sortedBy(homeListSpec.newQuery().copy(text = text), sort)
        return homeListSpec.apply(apps, query).map { it.name }
    }

    @Test
    fun startsWithTheLastOpenedFirst() {
        assertEquals(listOf("Gamma", "Alpha", "beta"), homeListSpec.apply(apps, homeListSpec.newQuery()).map { it.name })
    }

    @Test
    fun nameIsAToZ() {
        assertEquals(listOf("Alpha", "beta", "Gamma"), names("name"))
    }

    @Test
    fun mostUsedIsByTheTimeRecorded() {
        assertEquals(listOf("Alpha", "beta", "Gamma"), names("mostUsed"))
    }

    @Test
    fun searchMatchesNamesAndIdsIgnoringCase() {
        assertEquals(listOf("Alpha"), names("name", " ALP"))
        assertEquals(listOf("Gamma"), names("name", "c.c"))
        assertEquals(emptyList<String>(), names("name", "zzz"))
    }

    @Test
    fun aNewlyInstalledAppStartsAtTheTop() {
        val installed = apps + InstalledApp("d.D", "Delta", null, null, installed = 300L)
        assertEquals(listOf("Delta", "Gamma", "Alpha", "beta"), homeListSpec.apply(installed, homeListSpec.newQuery()).map { it.name })
    }

    @Test
    fun iconCacheKeysFollowThePathTimeAndSize() {
        val key = IconCache.key("/a/b.svg", 10, 20)
        assertEquals(key, IconCache.key("/a/b.svg", 10, 20))
        assertNotEquals(key, IconCache.key("/a/b.svg", 11, 20))
        assertNotEquals(key, IconCache.key("/a/c.svg", 10, 20))
        assertEquals(40, key.length)
    }

    @Test
    fun onlySvgsAndBigPicturesAreCached() {
        assertTrue(IconCache.shouldCache("/x/icon.svg", 10))
        assertTrue(IconCache.shouldCache("/x/icon.png", IconCache.MIN_RASTER_BYTES))
        assertFalse(IconCache.shouldCache("/x/icon.png", 4_000))
    }
}
