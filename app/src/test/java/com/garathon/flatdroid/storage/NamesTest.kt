package com.garathon.flatdroid.storage

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NamesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun namesStayInTheFolder() {
        assertEquals("a_b.txt", Names.safe("a/b.txt", "x"))
        assertEquals("x", Names.safe("..", "x"))
        assertEquals("x", Names.safe(null, "x"))
        assertEquals("x", Names.safe("  ", "x"))
    }

    @Test
    fun takenNamesGetANumber() {
        val dir = tmp.newFolder()
        assertEquals(File(dir, "a.txt"), Names.unique(dir, "a.txt"))
        File(dir, "a.txt").writeText("")
        assertEquals(File(dir, "a (1).txt"), Names.unique(dir, "a.txt"))
        File(dir, "notes").mkdir()
        assertEquals(File(dir, "notes (1)"), Names.unique(dir, "notes"))
        File(dir, ".hidden").writeText("")
        assertEquals(File(dir, ".hidden (1)"), Names.unique(dir, ".hidden"))
    }
}
