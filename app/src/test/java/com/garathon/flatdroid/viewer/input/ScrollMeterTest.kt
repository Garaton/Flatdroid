package com.garathon.flatdroid.viewer.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.math.roundToInt

class ScrollMeterTest {
    private val width = 64
    private val stride = width * 4
    private val rows = 200

    /**
     * Lines of text, 20 rows each (a few rows of strokes, then blank rows), each line's strokes
     * starting at a column of its own and running a length of its own (as its words do). [y] may fall between rows: blended as a
     * compositor resampling content does.
     */
    private fun text(y: Double, x: Int): Int {
        fun at(row: Int): Int {
            val line = Math.floorMod(row, 1_000_000) / 20
            val inLine = Math.floorMod(row, 20)
            // Where each line's words start and how long they run vary as real lines do, without a pattern.
            val hash = (line * 0x9e3779b1.toInt()) ushr 8
            val start = hash % (width - 16)
            return if (inLine in 3..12 && x in start until start + 6 + (hash shr 8) % 10) 220 else 30
        }
        val r = floor(y).toInt()
        val f = y - r
        return (at(r) * (1 - f) + at(r + 1) * f).roundToInt()
    }

    /** A screen: a still bar of [bar] rows on top, then the text from content row [first] on. */
    private fun screen(first: Double, bar: Int = 20): ByteArray {
        val pixels = ByteArray(rows * stride)
        for (y in 0 until rows) for (x in 0 until width) {
            val green = if (y < bar) 90 + y else text(first + y - bar, x)
            pixels[y * stride + x * 4 + 1] = green.toByte()
        }
        return pixels
    }

    private fun profile(pixels: ByteArray) = IntArray(rows * ScrollMeter.BANDS).also { ScrollMeter.profile(pixels, stride, width, rows, it) }

    @Test
    fun aProfileSumsEachBandsGreen() {
        val pixels = ByteArray(2 * stride)
        for (x in 0 until width) pixels[stride + x * 4 + 1] = x.toByte()
        val out = IntArray(2 * ScrollMeter.BANDS)
        ScrollMeter.profile(pixels, stride, width, 2, out)
        assertEquals(0, out[0])
        assertEquals((0 until 8).sum(), out[ScrollMeter.BANDS])
        assertEquals((56 until 64).sum(), out[2 * ScrollMeter.BANDS - 1])
    }

    @Test
    fun aColumnProfileSumsEachBandOfRows() {
        val height = 16
        val pixels = ByteArray(height * stride)
        for (y in 0 until height) pixels[y * stride + 3 * 4 + 1] = y.toByte() // column 3
        val out = IntArray(width * ScrollMeter.BANDS)
        ScrollMeter.profile(pixels, stride, width, height, out, columns = true)
        assertEquals(0 + 1, out[3 * ScrollMeter.BANDS]) // rows 0 and 1
        assertEquals(14 + 15, out[3 * ScrollMeter.BANDS + ScrollMeter.BANDS - 1])
        assertEquals(0, out[4 * ScrollMeter.BANDS])
    }

    @Test
    fun contentMovingSidewaysIsFoundFromColumns() {
        // The text screen turned on its side: columns of it as rows were.
        fun sideways(first: Double): ByteArray {
            val p = ByteArray(width * rows * 4)
            val w = rows // pixels a row: as many as the text screen's rows
            for (y in 0 until width) for (x in 0 until w) p[(y * w + x) * 4 + 1] = (if (x < 20) 90 + x else text(first + x - 20, y)).toByte()
            return p
        }
        fun columns(p: ByteArray) = IntArray(rows * ScrollMeter.BANDS).also { ScrollMeter.profile(p, rows * 4, rows, width, it, columns = true) }
        assertEquals(-37, ScrollMeter.shift(columns(sideways(0.0)), columns(sideways(37.0)), rows, up = true, min = 10, max = 120))
    }

    @Test
    fun contentMovingUnderAStillBarIsFound() {
        val before = profile(screen(0.0))
        val after = profile(screen(37.0))
        assertEquals(-37, ScrollMeter.shift(before, after, rows, up = true, min = 10, max = 120))
        assertEquals(37, ScrollMeter.shift(after, before, rows, up = false, min = 10, max = 120))
    }

    @Test
    fun resampledContentIsFoundToTheNearestRow() {
        // At a fractional interface size, a move by part of a pixel blends every row anew.
        val before = profile(screen(0.0))
        val after = profile(screen(52.5))
        val moved = ScrollMeter.shift(before, after, rows, up = true, min = 10, max = 120)!!
        assertTrue(moved in -53..-52)
    }

    @Test
    fun linesThatLookAlikeArentMistakenForEachOther() {
        // A line's height (20) off would line up the blank rows, but not the strokes.
        val before = profile(screen(0.0))
        val after = profile(screen(66.0))
        assertEquals(-66, ScrollMeter.shift(before, after, rows, up = true, min = 30, max = 140))
    }

    @Test
    fun linesDifferingOnlyInANumberUnderAStillToolbarAreFollowed() {
        // A terminal: a toolbar and a prompt that stay, and lines whose only difference is a few pixels at the left.
        fun line(row: Int, x: Int): Int {
            val n = Math.floorMod(row, 1_000_000) / 15
            val inLine = Math.floorMod(row, 15)
            return if (inLine in 3..10 && x < 6 && ((n shr (x % 6)) and 1) == 1) 200 else 20
        }
        fun screen(first: Int) = ByteArray(rows * stride).also { p ->
            for (y in 0 until rows) for (x in 0 until width) {
                val green = when {
                    y < 30 -> 120 + (x * 7 + y) % 50 // toolbar
                    y >= rows - 15 -> 150 // prompt
                    else -> line(first + y, x)
                }
                p[y * stride + x * 4 + 1] = green.toByte()
            }
        }
        assertEquals(45, ScrollMeter.shift(profile(screen(300)), profile(screen(255)), rows, up = false, min = 15, max = 140))
    }

    @Test
    fun linesThatAreAllTheSameGiveNoMove() {
        // Every line alike: a line further on lines up as well as the true move, so neither counts.
        val alike = { y: Int -> if (Math.floorMod(y - 20, 20) in 3..12) 220 else 30 }
        fun screen(first: Int) = ByteArray(rows * stride).also { p ->
            for (y in 0 until rows) for (x in 0 until width) p[y * stride + x * 4 + 1] = (if (y < 20) 90 + y else alike(first + y)).toByte()
        }
        assertNull(ScrollMeter.shift(profile(screen(0)), profile(screen(46)), rows, up = true, min = 10, max = 140))
    }

    @Test
    fun aMoveOutsideTheRangeIsNotFound() {
        val before = profile(screen(0.0))
        val after = profile(screen(37.0))
        assertNull(ScrollMeter.shift(before, after, rows, up = true, min = 80, max = 140))
        assertNull(ScrollMeter.shift(before, after, rows, up = false, min = 10, max = 120))
    }

    @Test
    fun aFrameThatChangedOtherwiseIsNotAMove() {
        val before = profile(screen(0.0))
        val other = profile(ByteArray(rows * stride) { i -> if (i % 4 == 1) ((i * 2654435761L) shr 24).toByte() else 0 })
        assertNull(ScrollMeter.shift(before, other, rows, up = true, min = 10, max = 120))
    }

    @Test
    fun aBlankPageIsTold() {
        // Scrolled past the last lines: under the still bar, nothing but the background.
        val past = ByteArray(rows * stride).also { p ->
            for (y in 0 until rows) for (x in 0 until width) p[y * stride + x * 4 + 1] = (if (y < 20) 90 + y else 30).toByte()
        }
        assertTrue(ScrollMeter.blank(profile(screen(0.0)), profile(past), rows))
        assertFalse(ScrollMeter.blank(profile(screen(0.0)), profile(screen(37.0)), rows))
    }

    @Test
    fun anUnchangedFrameIsTold() {
        val before = profile(screen(0.0))
        assertFalse(ScrollMeter.changed(before, profile(screen(0.0)), rows))
        assertTrue(ScrollMeter.changed(before, profile(screen(5.0)), rows))
    }

    @Test
    fun aMoveTooFarToCompareIsNotSearched() {
        val before = profile(screen(0.0))
        assertNull(ScrollMeter.shift(before, before, rows, up = true, min = (rows * ScrollMeter.MAX_MOVE).toInt() + 1, max = 1000))
    }

    @Test
    fun theMedianLeavesTheNewestOutOfAnEvenCount() {
        assertEquals(48f, ScrollMeter.median(listOf(48f)), 0f)
        assertEquals(48f, ScrollMeter.median(listOf(48f, 50f, 12f, 47f, 49f)), 1f)
        // A second measurement alone doesn't move the first.
        assertEquals(14f, ScrollMeter.median(listOf(14f, 1.2f)), 0f)
    }
}
