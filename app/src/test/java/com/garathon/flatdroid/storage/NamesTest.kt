package com.garathon.flatdroid.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun longNamesAreCutByBytesNotCharacters() {
        // 100 Japanese characters are 300 bytes: more than a file system takes once a temporary name is added.
        val long = "あ".repeat(100) + ".txt"
        val cut = Names.safe(long, "x")
        assertTrue(cut.endsWith(".txt"))
        assertTrue(cut.toByteArray(Charsets.UTF_8).size <= 200)
        assertEquals("あ".repeat(65) + ".txt", cut)
        // A code point of two UTF-16 units isn't split.
        val emoji = Names.safe("😀".repeat(80), "x")
        assertEquals("😀".repeat(50), emoji)
        // Short names and plain long ASCII names keep working.
        assertEquals("a.txt", Names.safe("a.txt", "x"))
        assertEquals(200, Names.safe("a".repeat(300), "x").length)
        // A long extension is cut with the rest, not kept.
        assertTrue(Names.safe("a." + "b".repeat(300), "x").toByteArray(Charsets.UTF_8).size <= 200)
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

    @Test
    fun takenNamesInAZipGetANumber() {
        val taken = mutableSetOf("a.txt", "a (1).txt")
        assertEquals("a (2).txt", Names.unique("a.txt", taken::contains))
        assertEquals("b.txt", Names.unique("b.txt", taken::contains))
    }
}
