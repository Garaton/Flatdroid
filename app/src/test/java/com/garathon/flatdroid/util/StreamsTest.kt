package com.garathon.flatdroid.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException

class StreamsTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Zero blocks at the start, middle and end, a zero run shorter than a block, and reads in odd sizes. */
    private fun sparseData(): ByteArray = ByteArray(300_000).also { data ->
        for (i in 4096 * 3 until 4096 * 10) data[i] = 7
        for (i in 4096 * 20 + 100 until 4096 * 20 + 200) data[i] = 9
        for (i in 4096 * 40 until 4096 * 60) data[i] = (i % 251).toByte()
        data[4096 * 70 - 1] = 1
    }

    @Test
    fun aSparseCopyReadsTheSameAndHasTheSameLength() {
        val data = sparseData()
        val file = tmp.newFile()
        var last = 0L
        val input = object : FilterInputStream(data.inputStream()) {
            // Short reads, as a zip stream gives.
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 1000))
        }
        FileOutputStream(file).use { assertEquals(data.size.toLong(), input.copySparse(it) { copied -> last = copied }) }
        assertEquals(data.size.toLong(), last)
        assertEquals(data.size.toLong(), file.length())
        assertArrayEquals(data, file.readBytes())
    }

    @Test
    fun aFileOfOnlyZerosOrNothingKeepsItsLength() {
        for (size in listOf(0, 1, 4096, 100_000)) {
            val file = tmp.newFile()
            FileOutputStream(file).use { ByteArray(size).inputStream().copySparse(it) }
            assertEquals(size.toLong(), file.length())
            assertArrayEquals(ByteArray(size), file.readBytes())
        }
    }

    @Test
    fun zeroBlocksTakeNoSpaceWhereTheFileSystemHasHoles() {
        val file = tmp.newFile()
        FileOutputStream(file).use { ByteArray(4 shl 20).also { it[0] = 1 }.inputStream().copySparse(it) }
        // st_blocks, where the host has stat (Linux).
        val blocks = runCatching { ProcessBuilder("stat", "-c", "%b", file.path).start().inputStream.bufferedReader().readText().trim().toLong() }.getOrNull()
        assumeTrue(blocks != null)
        assertTrue("allocated ${blocks!! * 512} bytes", blocks * 512 < (1 shl 20))
    }

    @Test
    fun copiesAndReportsTheTotalSoFar() {
        val data = ByteArray(200_000) { it.toByte() }
        val out = ByteArrayOutputStream()
        var last = 0L
        assertEquals(200_000L, data.inputStream().copyWithProgress(out) { last = it })
        assertEquals(200_000L, last)
        assertEquals(data.toList(), out.toByteArray().toList())
    }

    @Test
    fun theCallbackCanStopTheCopy() {
        assertThrows(IOException::class.java) {
            ByteArray(200_000).inputStream().copyWithProgress(ByteArrayOutputStream()) { if (it > 100_000) throw IOException("too large") }
        }
    }

    @Test
    fun countsWhatAnotherReaderConsumes() {
        var read = 0L
        CountingInputStream(ByteArray(5000).inputStream()) { read = it }.use { it.readBytes() }
        assertEquals(5000L, read)
    }
}
