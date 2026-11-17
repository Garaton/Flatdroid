package com.garathon.flatdroid.storage.sizes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ListedSizesTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A store with fixed sizes: 100 per directory, a file's length times 10. Counts the stats. */
    private inner class Store(val root: File) {
        var stats = 0
        val stat = { file: File ->
            stats++
            if (!file.exists()) null else ListedSizes.Entry(file.isDirectory, if (file.isDirectory) 100L else file.length() * 10)
        }
        val list = { file: File -> file.list() }
        fun measure(sizes: ListedSizes, stop: () -> Boolean = { false }) = sizes.measure("store", root, stat, list, stop)
    }

    @Test
    fun onlyNewNamesAreStatted() {
        val root = tmp.newFolder("store")
        File(root, "ab").mkdir()
        File(root, "ab/one").writeText("1")
        File(root, "ab/two").writeText("22")
        File(root, "three").writeText("333")
        val store = Store(root)
        val sizes = ListedSizes(tmp.newFolder("sizes"))
        assertEquals(100 + 100 + 10 + 20 + 30L, store.measure(sizes))
        assertEquals(5, store.stats)

        // One file added, one removed: the directories and the new file are stat'ed, the rest is read.
        File(root, "ab/four").writeText("4444")
        File(root, "three").delete()
        store.stats = 0
        assertEquals(100 + 100 + 10 + 20 + 40L, store.measure(sizes))
        assertEquals(3, store.stats)

        // Nothing new: only the directories.
        store.stats = 0
        assertEquals(100 + 100 + 10 + 20 + 40L, store.measure(sizes))
        assertEquals(2, store.stats)
    }

    @Test
    fun aWalkThatStopsSavesNothing() {
        val root = tmp.newFolder("store")
        File(root, "a").mkdir()
        File(root, "a/x").writeText("x")
        val dir = tmp.newFolder("sizes")
        var calls = 0
        assertThrows(MeasureStopped::class.java) { Store(root).measure(ListedSizes(dir)) { ++calls > 1 } }
        assertFalse(File(dir, "store").exists())
        // A walk that finished while a change began isn't saved either: the two directories pass, the check before saving doesn't.
        var checks = 0
        Store(root).measure(ListedSizes(dir)) { ++checks > 2 }
        assertFalse(File(dir, "store").exists())
    }

    @Test
    fun aDamagedRecordMeansAFullWalk() {
        val root = tmp.newFolder("store")
        File(root, "x").writeText("x")
        val dir = tmp.newFolder("sizes")
        val store = Store(root)
        store.measure(ListedSizes(dir))
        val file = File(dir, "store")
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() - 1))
        store.stats = 0
        assertEquals(110L, store.measure(ListedSizes(dir)))
        assertEquals(2, store.stats)
    }
}
