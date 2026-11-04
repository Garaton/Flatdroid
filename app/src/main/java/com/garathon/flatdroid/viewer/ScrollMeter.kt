package com.garathon.flatdroid.viewer

import kotlin.math.abs

/**
 * How far an app scrolls for one wheel notch, measured from the screen: each app picks its own
 * distance (three lines of its font, a share of the page…), and a drag only follows the finger
 * when a notch is sent every that many pixels of finger travel.
 *
 * While a drag scrolls, each new frame is compared with the one before it ([hashRows]); the rows
 * that moved vote for how far ([shift]). Once the content has settled after a drag, the distance
 * moved over the notches sent is the app's notch.
 * Not thread-safe: UI thread.
 */
class ScrollMeter {
    private var notches = 0
    private var movedRows = 0

    /** Wheel notches sent: positive scrolls the content down (it moves up on the screen). */
    fun sent(down: Int) {
        notches += down
    }

    /** The content moved [rows] down on the screen between two frames (negative: up). */
    fun movedBy(rows: Int) {
        movedRows += rows
    }

    /**
     * Ends a measurement (the drag is over and the content has settled): the pixels one notch
     * scrolled, or null when the content didn't follow (at the end of a page, or nothing
     * recognisable moved), so the last good value stays.
     */
    fun finish(): Float? = estimate(MIN_NOTCHES).also {
        notches = 0
        movedRows = 0
    }

    /** The pixels a notch scrolled so far, from at least [minNotches] notches, or null; the measurement goes on. */
    fun estimate(minNotches: Int = MIN_NOTCHES): Float? {
        if (notches == 0 || movedRows == 0 || (movedRows > 0) == (notches > 0) || abs(notches) < minNotches) return null
        return (abs(movedRows).toFloat() / abs(notches)).takeIf { it in MIN_NOTCH..MAX_NOTCH }
    }

    companion object {
        /** Until measured: about three lines of text, or a browser's notch. */
        const val DEFAULT_NOTCH = 48f
        /** Fewer notches than this measure too coarsely (a smooth-scrolling app may still be moving). */
        const val MIN_NOTCHES = 2
        const val MIN_NOTCH = 4f
        const val MAX_NOTCH = 1000f
        /** Rows that moved by the same amount, at least, for a frame to count as moved. */
        private const val MIN_VOTES = 3

        /** The width is cut into this many bands of columns, each compared on its own (a still sidebar spoils none but its own). */
        const val BANDS = 8

        /**
         * One hash per row in each of [BANDS] bands of columns of [pixels] ([width] pixels of 4
         * bytes a row, rows [stride] bytes apart), the green of every fourth pixel, into [out]
         * (band after band, [height] each).
         */
        fun hashRows(pixels: ByteArray, stride: Int, width: Int, height: Int, out: IntArray) {
            for (band in 0 until BANDS) {
                val from = width * band / BANDS * 4
                val to = width * (band + 1) / BANDS * 4
                for (y in 0 until height) {
                    var h = -0x7ee3623b // FNV-1a, 32 bits
                    // Green (most of the brightness) of every fourth pixel: enough to tell rows apart.
                    var i = y * stride + from + 1
                    val end = y * stride + to
                    while (i < end) {
                        h = (h xor (pixels[i].toInt() and 0xff)) * 0x01000193
                        i += 16
                    }
                    out[band * height + y] = h
                }
            }
        }
    }

    // The last frame's rows and the new one's, band after band, each row's hash and number
    // packed into a Long and sorted by hash; and the votes.
    private var lastBands = LongArray(0)
    private var newBands = LongArray(0)
    private var votes = IntArray(0)
    private var rows = 0

    /** The frame a measurement starts from ([hashRows] of [rows] rows). */
    fun start(hashes: IntArray, rows: Int) {
        this.rows = rows
        if (lastBands.size != BANDS * rows) {
            lastBands = LongArray(BANDS * rows)
            newBands = LongArray(BANDS * rows)
            votes = IntArray(2 * rows + 1)
        }
        sortBands(hashes, lastBands)
    }

    /**
     * How many rows the content moved down from the last frame to [hashes] (the next frame's
     * [hashRows], as many rows as [start] had; negative: up), 0 when nothing recognisable moved.
     * Only rows whose hash is found once in their band in each frame count (blank rows look alike
     * everywhere); the largest group moving by the same amount wins, still parts (bars, a header)
     * aside. [hashes] is then the last frame.
     */
    fun shift(hashes: IntArray): Int {
        sortBands(hashes, newBands)
        votes.fill(0)
        for (band in 0 until BANDS) {
            val from = band * rows
            val to = from + rows
            // Both sorted by hash: walk them together, matching hashes found once in each.
            var i = from
            var j = from
            while (i < to && j < to) {
                val a = lastBands[i] ushr 32
                val b = newBands[j] ushr 32
                when {
                    a < b -> i = next(lastBands, i, to)
                    b < a -> j = next(newBands, j, to)
                    else -> {
                        val ni = next(lastBands, i, to)
                        val nj = next(newBands, j, to)
                        if (ni == i + 1 && nj == j + 1) {
                            val d = newBands[j].toInt() - lastBands[i].toInt()
                            if (d != 0) votes[d + rows]++
                        }
                        i = ni
                        j = nj
                    }
                }
            }
        }
        lastBands = newBands.also { newBands = lastBands }
        var best = 0
        for (k in 1 until votes.size) if (votes[k] > votes[best]) best = k
        return if (votes[best] >= MIN_VOTES) best - rows else 0
    }

    private fun sortBands(hashes: IntArray, out: LongArray) {
        for (band in 0 until BANDS) {
            val from = band * rows
            for (y in 0 until rows) out[from + y] = (hashes[from + y].toLong() and 0xffffffffL shl 32) or y.toLong()
            out.sort(from, from + rows)
        }
    }

    /** The index after the run of equal hashes starting at [i]. */
    private fun next(bands: LongArray, i: Int, to: Int): Int {
        val hash = bands[i] ushr 32
        var k = i + 1
        while (k < to && bands[k] ushr 32 == hash) k++
        return k
    }
}
