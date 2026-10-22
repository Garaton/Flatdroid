package com.garathon.flatdroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AtomicWriteTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun aFailedWriteLeavesTheOldFileAndNoTemporaryOne() {
        val dir = tmp.newFolder()
        val file = File(dir, "settings.properties").apply { writeText("old") }
        File(dir, "settings.properties.new").writeText("the user's")
        assertThrows(IOException::class.java) {
            file.writeAtomically { it.write("half".toByteArray()); throw IOException("disk full") }
        }
        assertEquals("old", file.readText())
        assertEquals("the user's", File(dir, "settings.properties.new").readText())
        assertEquals(setOf("settings.properties", "settings.properties.new"), dir.list()!!.toSet())
        file.writeTextAtomically("new")
        assertEquals("new", file.readText())
    }

    @Test
    fun replaceWithSwapsAndKeepsTheOldOneWhenTheMoveFails() {
        val dir = tmp.newFolder()
        val target = File(dir, "home").apply { mkdirs(); File(this, "old.txt").writeText("old") }
        val staged = File(dir, "staged").apply { mkdirs(); File(this, "new.txt").writeText("new") }
        target.replaceWith(staged) { it.deleteRecursively() }
        assertEquals(listOf("new.txt"), target.list()!!.toList())
        assertFalse(staged.exists())
        assertEquals(setOf("home"), dir.list()!!.toSet())

        assertThrows(IOException::class.java) { target.replaceWith(File(dir, "missing")) { it.deleteRecursively() } }
        assertEquals(listOf("new.txt"), target.list()!!.toList())

        val fresh = File(dir, "fresh")
        fresh.replaceWith(File(dir, "s").apply { mkdirs() }) { it.deleteRecursively() }
        assertTrue(fresh.isDirectory)
    }

    @Test
    fun leftoversOfAStoppedWriteAreRemovedAndNothingElse() {
        val dir = tmp.newFolder()
        val since = System.currentTimeMillis() - 1000
        val leftover = File(dir, ".photo.jpg.4821903315.part").apply { writeText("half") }
        val old = File(dir, ".old.txt.12.part").apply { writeText("x"); setLastModified(since - 60_000) }
        val users = listOf(File(dir, "notes.part"), File(dir, ".config"), File(dir, "a.1.part")).onEach { it.writeText("keep") }
        removeAtomicLeftovers(dir, since)
        assertFalse(leftover.exists())
        assertTrue(old.exists())
        assertTrue(users.all { it.exists() })
    }
}
