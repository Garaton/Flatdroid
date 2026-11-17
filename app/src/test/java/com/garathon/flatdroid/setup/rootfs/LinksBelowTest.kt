package com.garathon.flatdroid.setup.rootfs

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinksBelowTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun link(at: File, to: File) = Files.createSymbolicLink(at.toPath(), to.toPath())

    @Test fun plainFoldersHaveNoLink() {
        val base = tmp.newFolder("base")
        File(base, "a/b").mkdirs()
        assertFalse(JvmTarFs.hasLinkBelow(base, "a/b"))
        assertFalse(JvmTarFs.hasLinkBelow(base, "a/missing/deeper"))
    }

    @Test fun aLinkAnywhereInThePathIsFound() {
        val base = tmp.newFolder("base")
        val elsewhere = tmp.newFolder("elsewhere")
        File(elsewhere, "b").mkdirs()
        link(File(base, "a"), elsewhere)
        assertTrue(JvmTarFs.hasLinkBelow(base, "a/b"))
        assertTrue(JvmTarFs.hasLinkBelow(base, "a"))
    }

    @Test fun aDanglingLinkCounts() {
        val base = tmp.newFolder("base")
        link(File(base, "a"), File(tmp.root, "nowhere"))
        assertTrue(JvmTarFs.hasLinkBelow(base, "a/b"))
    }

    @Test fun makingFoldersReplacesALinkAndLeavesItsTargetAlone() {
        val base = tmp.newFolder("base")
        val elsewhere = tmp.newFolder("elsewhere")
        link(File(base, ".local"), elsewhere)
        assertTrue(JvmTarFs.makeDirsBelow(base, ".local/state"))
        assertEquals(0, elsewhere.list()!!.size)
        assertFalse(Files.isSymbolicLink(File(base, ".local").toPath()))
        assertTrue(File(base, ".local/state").isDirectory)
    }

    @Test fun makingFoldersReplacesADanglingLink() {
        val base = tmp.newFolder("base")
        link(File(base, "a"), File(tmp.root, "nowhere"))
        assertTrue(JvmTarFs.makeDirsBelow(base, "a/b"))
        assertFalse(File(tmp.root, "nowhere").exists())
        assertTrue(File(base, "a/b").isDirectory)
    }

    @Test fun makingFoldersMakesWhatIsMissing() {
        val base = tmp.newFolder("base")
        File(base, "a").mkdirs()
        assertTrue(JvmTarFs.makeDirsBelow(base, "a/b/c"))
        assertTrue(File(base, "a/b/c").isDirectory)
    }

    @Test fun makingFoldersKeepsWhatIsThere() {
        val base = tmp.newFolder("base")
        File(base, "a/b").mkdirs()
        File(base, "a/b/file").writeText("x")
        assertTrue(JvmTarFs.makeDirsBelow(base, "a/b"))
        assertEquals("x", File(base, "a/b/file").readText())
    }
}
