package com.garathon.flatdroid.flatpak.catalog

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class CatalogIndexTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun aDamagedAppCountIsRebuiltNotAllocated() {
        val dir = File(tmp.root, "index")
        CatalogIndex.write(dir, "v") { }
        assertNotNull(CatalogIndex.read(dir))
        // The list starts with the format number (4 bytes) and the version as UTF (2 + 1 bytes), then the app count.
        for (count in listOf(Int.MAX_VALUE, -1, 1_000_000)) {
            RandomAccessFile(File(dir, "list"), "rw").use { it.seek(7); it.writeInt(count) }
            assertNull("count $count", CatalogIndex.read(dir))
        }
    }
}
