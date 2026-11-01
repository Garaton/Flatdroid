package com.garathon.flatdroid.flatpak

import com.garathon.flatdroid.flatpak.FileIntegrity.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileIntegrityTest {
    @get:Rule val tmp = TemporaryFolder()

    /** An Fs that treats a file's text as content: sha is the text, stat uses a map, links a map. */
    private class FakeFs(
        val links: MutableMap<String, String> = mutableMapOf(),
        val stats: MutableMap<String, Triple<Long, Long, Long>> = mutableMapOf(),
        val content: MutableMap<String, String> = mutableMapOf(),
    ) {
        val fs = FileIntegrity.Fs(
            isSymlink = { it.path in links },
            readlink = { links[it.path] },
            stat = { stats[it.path] },
            sha256 = { content[it.path] },
        )
    }

    @Test
    fun walkRecursesRealDirsAndTreatsSymlinksAsLeaves() {
        val root = tmp.newFolder("files")
        File(root, "bin").mkdirs(); File(root, "bin/app").writeText("x")
        File(root, "lib").mkdirs(); File(root, "lib/libc.so").writeText("y")
        val fs = FakeFs(links = mutableMapOf(File(root, "bin/link").path to "/usr/x")).fs
        File(root, "bin/link").writeText("") // a symlink, per FakeFs.links
        assertEquals(listOf("bin/app", "bin/link", "lib/libc.so"), FileIntegrity.walk(root, fs))
    }

    @Test
    fun unchangedFilesTakeTheFastPathAndAreNotHashed() {
        val base = mapOf(
            "a" to Entry(null, 1, 100, 10, "sha-a"),
            "b" to Entry("/t", 2, 100, 0, "sha-b"),
        )
        // Same shape (link + ino + ctime + size): the hash must not be consulted.
        val current = mapOf(
            "a" to Entry(null, 1, 100, 10, null),
            "b" to Entry("/t", 2, 100, 0, null),
        )
        val diff = FileIntegrity.diff(base, current) { error("hash should not be needed for $it") }
        assertTrue(diff.clean)
    }

    @Test
    fun aContentChangeIsTamperingAndABareCtimeBumpIsBenign() {
        val base = mapOf("a" to Entry(null, 1, 100, 10, "sha-a"), "c" to Entry(null, 3, 100, 5, "sha-c"))
        // a: ctime bumped, content the same -> benign. c: ctime bumped and content changed -> tampered.
        val current = mapOf("a" to Entry(null, 1, 200, 10, null), "c" to Entry(null, 3, 200, 5, null))
        val rehash = mapOf("a" to Entry(null, 1, 200, 10, "sha-a"), "c" to Entry(null, 3, 200, 5, "sha-c-NEW"))
        val diff = FileIntegrity.diff(base, current) { rehash[it] }
        assertEquals(listOf("c"), diff.tampered)
        assertTrue(diff.removed.isEmpty())
    }

    @Test
    fun repointedSymlinkAddedAndRemovedFilesAreCaught() {
        val base = mapOf("link" to Entry("/old", 1, 100, 0, null), "gone" to Entry(null, 2, 100, 1, "s"))
        val current = mapOf("link" to Entry("/new", 1, 200, 0, null), "added" to Entry(null, 9, 100, 1, null))
        val diff = FileIntegrity.diff(base, current) { p ->
            // The repointed link re-reads with its new target; the added file has no baseline.
            if (p == "link") Entry("/new", 1, 200, 0, null) else Entry(null, 9, 100, 1, "new")
        }
        assertEquals(listOf("added", "link"), diff.tampered)
        assertEquals(listOf("gone"), diff.removed)
    }

    @Test
    fun aTreesOwnSymlinkIsCheckedByItsTargetAndLink2symlinksIsFollowed() {
        val own = File("/d/lib/libx.so")
        val hardLink = File("/d/bin/app")
        val fake = FakeFs(
            links = mutableMapOf(own.path to "libx.so.1", hardLink.path to "/data/rootfs/.l2s/.l2s.app0001"),
            stats = mutableMapOf(own.path to Triple(5L, 6L, 7L), hardLink.path to Triple(42L, 1700L, 99L)),
            content = mutableMapOf(own.path to "host-file", hardLink.path to "deadbeef"),
        )
        // Followed from the host, a guest path would read the host's file: only the target counts.
        assertEquals(Entry("libx.so.1", 0, 0, 0, null), FileIntegrity.entry(own, fake.fs))
        assertTrue(FileIntegrity.entry(own, fake.fs).linkOnly)
        assertEquals(Entry("/data/rootfs/.l2s/.l2s.app0001", 42, 1700, 99, "deadbeef"), FileIntegrity.entry(hardLink, fake.fs))
    }

    @Test
    fun benignChangesComeBackToBeWrittenAndLinkOnlyEntriesCompareTheirTarget() {
        val base = mapOf(
            "a" to Entry(null, 1, 100, 10, "sha-a"),
            "own" to Entry("libx.so.1", 5, 6, 7, "followed-before"), // an older, followed entry
            "moved" to Entry("libx.so.1", 0, 0, 0, null),
        )
        val current = mapOf(
            "a" to Entry(null, 1, 200, 10, null),                   // link2symlink renamed it: ctime only
            "own" to Entry("libx.so.1", 0, 0, 0, null),
            "moved" to Entry("evil.so", 0, 0, 0, null),
        )
        val diff = FileIntegrity.diff(base, current) { if (it == "a") Entry(null, 1, 200, 10, "sha-a") else error("no hash for $it") }
        assertEquals(listOf("moved"), diff.tampered)
        assertEquals(setOf("a", "own"), diff.refreshed.keys)
        assertEquals(Entry(null, 1, 200, 10, "sha-a"), diff.refreshed["a"])
    }

    @Test
    fun manifestRoundTrips() {
        val m = mapOf(
            "bin/a file" to Entry(null, 10, 1700000000, 1234, "abcdef"),
            "lib/x.so" to Entry("x.so.1", 11, 1700000001, 0, null),
        )
        assertEquals(m, FileIntegrity.read(FileIntegrity.write(m)))
    }

    @Test
    fun entryReadsLinkStatAndHash() {
        val f = File("/d/bin/app")
        val fake = FakeFs(
            links = mutableMapOf(),
            stats = mutableMapOf(f.path to Triple(42L, 1700L, 99L)),
            content = mutableMapOf(f.path to "deadbeef"),
        )
        assertEquals(Entry(null, 42, 1700, 99, "deadbeef"), FileIntegrity.entry(f, fake.fs))
    }
}
