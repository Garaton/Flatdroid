package com.garathon.flatdroid.flatpak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class BrokenLinksTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fs = object : LinkFs {
        override fun isSymlink(file: File) = Files.isSymbolicLink(file.toPath())
        override fun readlink(file: File) = if (isSymlink(file)) Files.readSymbolicLink(file.toPath()).toString() else null
        override fun delete(file: File) {
            Files.deleteIfExists(file.toPath())
        }
    }

    private fun link(link: File, target: File) {
        link.parentFile?.mkdirs()
        Files.createSymbolicLink(link.toPath(), target.toPath())
    }

    @Test
    fun removesOnlyLinksWhoseContentIsGone() {
        val l2s = tmp.newFolder("rootfs", ".l2s")
        val repo = tmp.newFolder("rootfs", "root", "flatpak", "repo", "objects", "c6")

        // Intact: link -> intermediate -> content.
        val goodContent = File(l2s, ".l2s.good.file0001.0002").apply { writeText("x") }
        val goodIntermediate = File(l2s, ".l2s.good.file0001").also { link(it, goodContent) }
        val good = File(repo, "good.file").also { link(it, goodIntermediate) }
        // Content deleted while a link remained.
        val badIntermediate = File(l2s, ".l2s.bad.file0001").also { link(it, File(l2s, ".l2s.bad.file0001.0001")) }
        val bad = File(repo, "bad.file").also { link(it, badIntermediate) }
        // Intermediate gone as well.
        val orphan = File(repo, "orphan.file").also { link(it, File(l2s, ".l2s.orphan.file0001")) }
        // Ordinary links are left alone, dangling or not.
        val relative = File(repo, "relative").also { link(it, File("../elsewhere")) }

        val removed = BrokenLinks(l2s, fs).removeUnder(File(tmp.root, "rootfs/root/flatpak"))

        assertEquals(2, removed)
        assertEquals("x", good.readText())
        assertTrue(fs.isSymlink(goodIntermediate))
        assertFalse(fs.isSymlink(bad) || bad.exists())
        assertFalse(fs.isSymlink(badIntermediate))
        assertFalse(fs.isSymlink(orphan))
        assertTrue(fs.isSymlink(relative))
    }
}
