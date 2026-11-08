package com.garathon.flatdroid.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollMeterTest {
    private val width = 64
    private val stride = width * 4

    /** A screen of [rows] rows: a fixed bar of [bar] rows on top, then lines of text from [first] on. */
    private fun screen(rows: Int, bar: Int, first: Int): ByteArray {
        val pixels = ByteArray(rows * stride)
        for (y in 0 until rows) {
            val content = if (y < bar) 1000 + y else first + y - bar
            // Every fourth row blank, as between lines of text: alike everywhere.
            val value = if (y >= bar && content % 4 == 0) 0 else content
            for (x in 0 until width) {
                pixels[y * stride + x * 4] = (value shr 8).toByte()
                pixels[y * stride + x * 4 + 1] = value.toByte() // green: what is compared
            }
        }
        return pixels
    }

    private fun hashes(pixels: ByteArray, rows: Int) = IntArray(rows * ScrollMeter.BANDS).also { ScrollMeter.hashRows(pixels, stride, width, rows, it) }

    private fun shift(old: IntArray, new: IntArray) = ScrollMeter().run {
        start(old, 100)
        shift(new)
    }

    @Test
    fun aFrameAfterTheFirstIsComparedWithTheOneBefore() {
        val meter = ScrollMeter()
        meter.start(hashes(screen(100, bar = 10, first = 0), 100), 100)
        assertEquals(-10, meter.shift(hashes(screen(100, bar = 10, first = 10), 100)))
        assertEquals(-5, meter.shift(hashes(screen(100, bar = 10, first = 15), 100)))
    }

    @Test
    fun contentMovingUpUnderAStillBarIsMeasured() {
        val before = hashes(screen(100, bar = 10, first = 0), 100)
        val after = hashes(screen(100, bar = 10, first = 23), 100) // scrolled down: content moved up 23 rows
        assertEquals(-23, shift(before, after))
        assertEquals(23, shift(after, before))
    }

    @Test
    fun aStillSidebarBesideTheContentDoesntHideItsMove() {
        val before = screen(100, bar = 0, first = 0)
        val after = screen(100, bar = 0, first = 17)
        // The left quarter, a sidebar with lines of its own, stays where it is.
        val sidebar = screen(100, bar = 0, first = 500)
        for (y in 0 until 100) {
            sidebar.copyInto(before, y * stride, y * stride, y * stride + stride / 4)
            sidebar.copyInto(after, y * stride, y * stride, y * stride + stride / 4)
        }
        assertEquals(-17, shift(hashes(before, 100), hashes(after, 100)))
    }

    @Test
    fun nothingMovedIsZero() {
        val same = hashes(screen(100, bar = 10, first = 5), 100)
        assertEquals(0, shift(same, same.copyOf()))
    }

    @Test
    fun aNotchIsTheDistanceOverTheNotchesSent() {
        val meter = ScrollMeter()
        meter.sent(2)
        meter.movedBy(-50)
        meter.sent(1)
        meter.movedBy(-40)
        meter.movedBy(-30) // a smooth scroll still finishing
        assertEquals(40f, meter.finish()!!, 0.01f)
    }

    @Test
    fun contentThatDidntFollowMeasuresNothing() {
        val meter = ScrollMeter()
        meter.sent(3) // at the end of the page
        assertNull(meter.finish())
        meter.sent(-3)
        meter.movedBy(-60) // the wrong way
        assertNull(meter.finish())
        meter.sent(1) // too few notches
        meter.movedBy(-40)
        assertNull(meter.finish())
    }

    @Test
    fun rowsVoteWhateverTheirHashesTopBit() {
        val rows = 40
        // Hashes on both sides of the sign bit, in each band, so the two halves of the sort both vote.
        fun frame(shift: Int) = IntArray(ScrollMeter.BANDS * rows) { i ->
            val band = i / rows
            val row = i % rows + shift
            (row * 0x9e3779b1.toInt()) xor band
        }
        val meter = ScrollMeter()
        meter.start(frame(0), rows)
        assertEquals(-5, meter.shift(frame(5)))
    }
}
