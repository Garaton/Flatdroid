package com.garathon.flatdroid.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Test

class PagerWindowTest {
    private val order = (0 until 100).map { "app$it" }

    @Test
    fun holdsOnlyTheAppsAroundTheOneShown() {
        val w = pagerWindow(order, "app50", 3)
        assertEquals(47, w.first)
        assertEquals((47..53).map { "app$it" }, w.pages)
        assertEquals("app50", w.pages[w.currentAt])
    }

    @Test
    fun isShorterNearTheEnds() {
        val start = pagerWindow(order, "app1", 3)
        assertEquals(0, start.first)
        assertEquals(5, start.pages.size)
        assertEquals(1, start.currentAt)
        val end = pagerWindow(order, "app99", 3)
        assertEquals(96, end.first)
        assertEquals(listOf("app96", "app97", "app98", "app99"), end.pages)
        assertEquals(3, end.currentAt)
    }

    @Test
    fun anAppOutsideTheListHasNoNeighbors() {
        val w = pagerWindow(order, "org.example.Other", 3)
        assertEquals(listOf("org.example.Other"), w.pages)
        assertEquals(0, w.currentAt)
        assertEquals(listOf("only"), pagerWindow(emptyList(), "only", 3).pages)
    }

    @Test
    fun aHugeListStaysBounded() {
        val huge = (0 until 50_000).map { "a$it" }
        assertEquals(7, pagerWindow(huge, "a25000", 3).pages.size)
    }
}
