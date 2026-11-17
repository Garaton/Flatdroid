package com.garathon.flatdroid.flatpak.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class FlathubListsTest {
    @Test
    fun theSavedLinesGiveTheVerifiedApps() {
        val lists = FlathubLists.parse(
            listOf(
                "org.example.A\t900\t300\t500\t1",
                "org.example.B\t50\t100\t700\t0",
                // An older file's line has no fifth column: it counts, as not verified.
                "org.example.C\t\t200\t",
                "org.example.A\t1\t1\t1\t0",
                "org.example.D\t1\t1\t1\t1\textra",
            ),
        )
        assertEquals(setOf("org.example.A"), lists.verified)
        assertEquals(listOf("org.example.A", "org.example.B", "org.example.C"), lists.popular.keys.toList())
    }
}
