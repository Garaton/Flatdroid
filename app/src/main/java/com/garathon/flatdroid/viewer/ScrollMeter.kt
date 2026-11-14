package com.garathon.flatdroid.viewer

import kotlin.math.abs

/**
 * How far a drag moved an app's content, for learning its scroll speed ([ScrollSpeed]): the frame
 * before the drag's first notch and the frame once the content settled are each reduced to a
 * profile ([profile]: per row, the summed green of each of [BANDS] bands of columns), and the move
 * is the shift that lines the two up best ([shift]). Lined up by difference rather than equality,
 * so content the compositor resampled (at a fractional interface size, text moved by part of a
 * pixel comes out slightly different) still matches, and searched only among the moves the
 * notches sent make plausible. Two frames a drag, while an app's speed is still being learnt.
 */
object ScrollMeter {
    /** The width is cut into this many bands of columns, compared each on its own (a still sidebar is left out). */
    const val BANDS = 8
    /** Fewer notches than this measure too coarsely. */
    const val MIN_NOTCHES = 2
    /** At most this share of the scrolling rows may move: the rest must still be on screen to line up. */
    const val MAX_MOVE = 0.75f
    /** Measurements a speed is the median of. */
    const val HISTORY = 5
    /** The best move's difference must be at most this share of the best other match's (or, without one, of the unmoved one's). */
    private const val MATCH = 0.75f
    /** The best move's difference must be at most this share of how much the content varies ([Area.contrast]). */
    private const val FIT = 0.25f
    /** The first search takes every this many rows. */
    private const val COARSE_ROWS = 4
    /** A profile varying less than this share of its level is blank ([blank]). */
    private const val BLANK = 0.008f
    /** Frames differing less than this share of their level are the same frame (nothing scrolled, a cursor blinked). */
    private const val UNCHANGED = 0.002f

    /**
     * The profile of [pixels] ([width] pixels of 4 bytes a row, rows [stride] bytes apart,
     * [height] rows) into [out]: for each row, each band of columns' summed green, row after row;
     * or, for [columns] (a sideways scroll), for each column, each band of rows', column after
     * column. The rest of this object takes either as "lines".
     */
    fun profile(pixels: ByteArray, stride: Int, width: Int, height: Int, out: IntArray, columns: Boolean = false) {
        if (columns) {
            out.fill(0, 0, width * BANDS)
            for (y in 0 until height) {
                val band = (y.toLong() * BANDS / height).toInt()
                var i = y * stride + 1
                for (x in 0 until width) {
                    out[x * BANDS + band] += pixels[i].toInt() and 0xff
                    i += 4
                }
            }
            return
        }
        for (y in 0 until height) {
            for (band in 0 until BANDS) {
                var i = y * stride + width * band / BANDS * 4 + 1
                val end = y * stride + width * (band + 1) / BANDS * 4
                var sum = 0
                while (i < end) {
                    sum += pixels[i].toInt() and 0xff
                    i += 4
                }
                out[y * BANDS + band] = sum
            }
        }
    }

    /** The two profiles of [rows] rows differ: something on screen changed. */
    fun changed(before: IntArray, after: IntArray, rows: Int): Boolean {
        var level = 0L
        var diff = 0L
        for (i in 0 until rows * BANDS) {
            level += before[i]
            diff += abs(after[i] - before[i])
        }
        return diff > UNCHANGED * (level + rows * BANDS)
    }

    /**
     * Either frame is nearly the same all over where the content scrolls ([Area]): a blank page,
     * nothing to follow (as after scrolling past the last lines).
     */
    fun blank(before: IntArray, after: IntArray, rows: Int): Boolean {
        val area = Area.of(before, after, rows)
        return area.flat(before) || area.flat(after)
    }

    /**
     * How many rows (or columns) the content moved from [before] to [after] (profiles of [rows]
     * lines): up (or left) when [up] (scrolled down or right), by [min] to [max] lines. Null when no move in that range lines the two
     * up clearly better than any other (the content changed otherwise, or repeats itself, as lines
     * that look alike do), so a doubtful match never counts.
     *
     * Only the part that scrolls is compared ([Area]). First every move on every fourth row; then, on
     * every row, around the best and around the best other match.
     */
    fun shift(before: IntArray, after: IntArray, rows: Int, up: Boolean, min: Int, max: Int): Int? {
        val area = Area.of(before, after, rows)
        val top = minOf(max, ((area.bottom - area.top) * MAX_MOVE).toInt())
        if (min > top) return null
        val sign = if (up) -1 else 1
        val errors = FloatArray(top - min + 1) { area.error(sign * (min + it), COARSE_ROWS) }
        var best = 0
        for (k in errors.indices) if (errors[k] < errors[best]) best = k
        // The best's own dip (the difference rising either way from it) is the same match, a line
        // or a column off; another match lies beyond it.
        var left = best
        while (left > 0 && errors[left - 1] >= errors[left]) left--
        var right = best
        while (right < errors.size - 1 && errors[right + 1] >= errors[right]) right++
        var other = -1
        for (k in errors.indices) if ((k < left || k > right) && (other < 0 || errors[k] < errors[other])) other = k
        val (move, error) = area.refine(sign, min + best, min, top)
        // Clearly the best: another move lining up nearly as well (a line further on) leaves it in doubt.
        val against = if (other < 0) area.error(0) else area.refine(sign, min + other, min, top).second
        // And close in itself: a wrong move lines up about as badly as the content varies.
        return if (error <= MATCH * against && against > 0f && error <= FIT * area.cap) sign * move else null
    }

    /**
     * The part of two frames' profiles that scrolls: the rows between those that stayed the same
     * at the top and at the bottom (a toolbar, a prompt), in the bands that changed (not a still
     * sidebar). Counted in, what stayed differs alike for every move and hides the small
     * differences that tell lines which look alike apart.
     */
    private class Area(
        private val before: IntArray,
        private val after: IntArray,
        val top: Int,
        val bottom: Int,
        private val bands: BooleanArray,
    ) {
        /**
         * How much the content varies ([contrast]), and the most one part's difference counts:
         * something thin that stays put while the content moves (a scrollbar, a text cursor: a
         * whole column in a sideways profile) otherwise outweighs everything that lines up.
         */
        val cap: Float by lazy { contrast().first }

        /** How much the frame after differs from the one before moved by [move] rows (down: positive), within the area, on every [step]th row. */
        fun error(move: Int, step: Int = 1): Float {
            val capped = maxOf(1, cap.toInt())
            val from = maxOf(top, top - move)
            val to = minOf(bottom, bottom - move)
            var sum = 0L
            var n = 0
            for (y in from until to step step) {
                val b = y * BANDS
                val a = (y + move) * BANDS
                for (band in 0 until BANDS) if (bands[band]) {
                    sum += minOf(abs(after[a + band] - before[b + band]), capped)
                    n++
                }
            }
            return if (n == 0) Float.MAX_VALUE else sum.toFloat() / n
        }

        /** How much the content varies: how far the area's parts of [profile] lie from their average, on average; and that average. */
        fun contrast(profile: IntArray = before): Pair<Float, Float> {
            var sum = 0L
            var n = 0
            for (y in top until bottom) for (band in 0 until BANDS) if (bands[band]) {
                sum += profile[y * BANDS + band]
                n++
            }
            if (n == 0) return 0f to 0f
            val mean = sum.toFloat() / n
            var dev = 0f
            for (y in top until bottom) for (band in 0 until BANDS) if (bands[band]) dev += abs(profile[y * BANDS + band] - mean)
            return dev / n to mean
        }

        /** [profile] varies hardly at all within the area ([BLANK]). */
        fun flat(profile: IntArray): Boolean {
            val (dev, mean) = contrast(profile)
            return dev <= BLANK * (mean + 1)
        }

        /** Of the moves (each [sign]ed) [around] and a row either side, within [min] to [max], the one that lines up best, and its difference. */
        fun refine(sign: Int, around: Int, min: Int, max: Int): Pair<Int, Float> {
            var move = around
            var least = Float.MAX_VALUE
            for (m in maxOf(min, around - 1)..minOf(max, around + 1)) {
                val e = error(sign * m)
                if (e < least) {
                    least = e
                    move = m
                }
            }
            return move to least
        }

        companion object {
            fun of(before: IntArray, after: IntArray, rows: Int): Area {
                fun same(y: Int) = (0 until BANDS).all { before[y * BANDS + it] == after[y * BANDS + it] }
                var top = 0
                while (top < rows && same(top)) top++
                var bottom = rows
                while (bottom > top && same(bottom - 1)) bottom--
                val bands = BooleanArray(BANDS) { band -> (top until bottom).any { before[it * BANDS + band] != after[it * BANDS + band] } }
                return Area(before, after, top, bottom, bands)
            }
        }
    }

    /**
     * The middle of [values] (oldest first). For an even count the newest is left out, so a
     * second measurement alone never moves the value halfway towards one that went wrong.
     */
    fun median(values: List<Float>): Float {
        val sorted = (if (values.size % 2 == 0) values.dropLast(1) else values).sorted()
        return sorted[sorted.size / 2]
    }
}
