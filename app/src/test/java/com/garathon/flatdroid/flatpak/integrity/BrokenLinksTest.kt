package com.garathon.flatdroid.flatpak.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BrokenLinksTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fs = object : LinkFs {
        override fun isSymlink(file: File) = Files.isSymbolicLink(file.toPath())
        override fun readlink(file: File) = if (isSymlink(file)) Files.readSymbolicLink(file.toPath()).toString() else null
        override fun delete(file: File) {
            Files.deleteIfExists(file.toPath())
        }
        override fun list(dir: File): Array<String>? = dir.list()
        override fun isDirectory(file: File) = file.isDirectory
        override fun replaceLink(link: File, target: String) {
            Files.deleteIfExists(link.toPath())
            Files.createSymbolicLink(link.toPath(), File(target).toPath())
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

    @Test
    fun aCancelledRemovalDeletesNothingAfterItReturns() {
        val l2s = tmp.newFolder("rootfs", ".l2s")
        val repo = tmp.newFolder("rootfs", "root", "flatpak", "repo", "objects", "c6")
        repeat(50) { link(File(repo, "orphan$it.file"), File(l2s, ".l2s.orphan$it.file0001")) }
        val caller = Thread.currentThread()
        val returned = AtomicBoolean()
        val deleted = AtomicInteger()
        val late = AtomicInteger()
        // The first delete cancels the caller, then takes its time: the rest of the folder is still to come.
        val slow = object : LinkFs by fs {
            override fun delete(file: File) {
                if (deleted.getAndIncrement() == 0) {
                    caller.interrupt()
                    try {
                        Thread.sleep(300)
                    } catch (_: InterruptedException) {
                    }
                }
                if (returned.get()) late.incrementAndGet()
                fs.delete(file)
            }
        }
        try {
            BrokenLinks(l2s, slow).removeUnder(File(tmp.root, "rootfs/root/flatpak"), threads = 1)
            fail("expected an interrupt")
        } catch (_: InterruptedException) {
        }
        returned.set(true)
        Thread.sleep(300)
        assertEquals(0, late.get())
        assertTrue(deleted.get().toString(), deleted.get() < 50)
    }

    @Test
    fun countsOneMoreLinkOnlyWhenTheStoreEntryIsAsExpected() {
        val l2s = tmp.newFolder("rootfs", ".l2s")
        val content = File(l2s, ".l2s.ab.file0001.0002").apply { writeText("x") }
        val intermediate = File(l2s, ".l2s.ab.file0001").also { link(it, content) }
        val links = BrokenLinks(l2s, fs)

        assertTrue(links.countOneMore(".l2s.ab.file0001"))
        assertEquals(File(l2s, ".l2s.ab.file0001.0003").path, fs.readlink(intermediate))
        assertEquals("x", intermediate.readText())
        assertFalse(content.exists())

        // Its content gone, a content file already on the next count, no intermediate: unchanged.
        val gone = File(l2s, ".l2s.gone.file0001").also { link(it, File(l2s, ".l2s.gone.file0001.0002")) }
        assertFalse(links.countOneMore(".l2s.gone.file0001"))
        File(l2s, ".l2s.two.file0001.0002").writeText("y")
        File(l2s, ".l2s.two.file0001.0003").writeText("z")
        val two = File(l2s, ".l2s.two.file0001").also { link(it, File(l2s, ".l2s.two.file0001.0002")) }
        assertFalse(links.countOneMore(".l2s.two.file0001"))
        assertEquals("y", two.readText())
        assertFalse(links.countOneMore(".l2s.none.file0001"))
        // An intermediate pointing outside the store.
        val outside = tmp.newFolder("elsewhere")
        File(outside, ".l2s.out.file0001.0002").writeText("o")
        link(File(l2s, ".l2s.out.file0001"), File(outside, ".l2s.out.file0001.0002"))
        File(l2s, ".l2s.out.file0001.0002").writeText("o")
        assertFalse(links.countOneMore(".l2s.out.file0001"))
        assertTrue(File(l2s, ".l2s.out.file0001.0002").exists())
        assertEquals(File(l2s, ".l2s.gone.file0001.0002").path, fs.readlink(gone))
    }

    @Test
    fun relinksAnIntermediateLeftOnAnOldCount() {
        val l2s = tmp.newFolder("rootfs", ".l2s")
        // Renamed to its new count (5), but the intermediate still names the old one (4).
        val content = File(l2s, ".l2s.ab.file0001.0005").apply { writeText("x") }
        val stranded = File(l2s, ".l2s.ab.file0001").also { link(it, File(l2s, ".l2s.ab.file0001.0004")) }
        // Intact, and gone for good (no content at all): left alone here.
        val goodContent = File(l2s, ".l2s.good.file0001.0002").apply { writeText("y") }
        val good = File(l2s, ".l2s.good.file0001").also { link(it, goodContent) }
        val gone = File(l2s, ".l2s.gone.file0001").also { link(it, File(l2s, ".l2s.gone.file0001.0001")) }

        assertEquals(listOf(".l2s.ab.file0001"), BrokenLinks(l2s, fs).relinkStranded())
        assertEquals("x", stranded.readText())
        assertEquals(content.path, fs.readlink(stranded))
        assertEquals(goodContent.path, fs.readlink(good))
        assertEquals(File(l2s, ".l2s.gone.file0001.0001").path, fs.readlink(gone))
    }

    @Test
    fun onlyLinkErrorsLeadToTheCheck() {
        assertTrue(
            BrokenLinks.mayBeStranded(
                "error: Failed to install org.gnome.Platform: rename pending: renameat(ab02.file, 19/ab02.file): Operation not permitted",
            ),
        )
        assertTrue(BrokenLinks.mayBeStranded("error: unlinkat(objects/19/ab02.file): Operation not permitted"))
        assertFalse(BrokenLinks.mayBeStranded("error: Nothing matches org.example.NotReal in remote flathub"))
        assertFalse(BrokenLinks.mayBeStranded("error: Unable to open /dev/fuse: Operation not permitted"))
        assertFalse(BrokenLinks.mayBeStranded(null))
    }

    @Test
    fun relinksOnlyTheObjectAnErrorNames() {
        val l2s = tmp.newFolder("rootfs", ".l2s")
        val objects = tmp.newFolder("rootfs", "repo", "objects")
        val hash = "ab" + "0".repeat(62)
        val other = "cd" + "0".repeat(62)
        File(l2s, ".l2s.$hash.file0001.0003").writeText("x")
        link(File(l2s, ".l2s.$hash.file0001"), File(l2s, ".l2s.$hash.file0001.0002"))
        link(File(objects, "ab/${hash.drop(2)}.file"), File(l2s, ".l2s.$hash.file0001"))
        // Stranded as well, but not named: left for the full check.
        File(l2s, ".l2s.$other.file0001.0002").writeText("y")
        val unnamed = File(l2s, ".l2s.$other.file0001").also { link(it, File(l2s, ".l2s.$other.file0001.0001")) }
        link(File(objects, "cd/${other.drop(2)}.file"), unnamed)

        val error = "error: Failed to install org.gnome.Platform: rename pending: renameat(${hash.drop(2)}.file, ab/${hash.drop(2)}.file): Operation not permitted"
        assertEquals(listOf("ab/${hash.drop(2)}.file"), BrokenLinks.namedObjects(error))
        assertEquals(listOf(".l2s.$hash.file0001"), BrokenLinks(l2s, fs).relinkObjects(objects, BrokenLinks.namedObjects(error)))
        assertEquals("x", File(objects, "ab/${hash.drop(2)}.file").readText())
        assertEquals(File(l2s, ".l2s.$other.file0001.0001").path, fs.readlink(unnamed))
        // Intact now: nothing more to do.
        assertEquals(emptyList<String>(), BrokenLinks(l2s, fs).relinkObjects(objects, BrokenLinks.namedObjects(error)))
    }
}
