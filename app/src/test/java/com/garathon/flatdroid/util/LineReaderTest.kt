package com.garathon.flatdroid.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader

class LineReaderTest {
    private fun lines(text: String, max: Int = 100): List<String> =
        mutableListOf<String>().also { out -> StringReader(text).forEachLineLimited(max) { out += it } }

    @Test
    fun splitsLikeReadLine() {
        assertEquals(listOf("a", "b", "c", "", "d"), lines("a\nb\r\nc\r\rd"))
        assertEquals(listOf("a"), lines("a\n"))
        assertEquals(emptyList<String>(), lines(""))
    }

    @Test
    fun longLinesComeInPieces() {
        assertEquals(listOf("abcd", "efgh", "ij", "k"), lines("abcdefghij\nk", max = 4))
    }

    @Test
    fun crLfAcrossReadsIsOneBreak() {
        // 8192 is the read size: the \r ends one read, the \n starts the next.
        val text = "x".repeat(8191) + "\r\n" + "y"
        assertEquals(listOf("x".repeat(8191), "y"), lines(text, max = 10_000))
    }
}
