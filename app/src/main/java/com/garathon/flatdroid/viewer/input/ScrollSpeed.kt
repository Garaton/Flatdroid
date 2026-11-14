package com.garathon.flatdroid.viewer.input

import java.util.Locale
import kotlin.math.abs

/**
 * An app's touch scroll speed: how far its scroll unit (a wheel notch, or a step on Wayland)
 * moves its content, as a share of the typical unit (1 until measured). Learnt from
 * [ScrollMeter]'s measurements until [SETTLE_COUNT] in a row agree, then settled: measured no
 * more, in any later session either ([saved]), unless the frame's height or the typical unit (the
 * interface size) changed since ([setup]): some apps scroll by a share of their window, so the
 * speed is learnt again from where it was. A drag that scrolled nothing measurable (the end
 * of a page, nothing scrollable under the finger, content that changed as it scrolled) leaves the
 * speed as it is; after [MAX_FAILURES] of those a session stops measuring.
 * Not thread-safe: main thread.
 */
class ScrollSpeed(saved: String? = null) {
    var share = 1f
        private set
    var settled = false
        private set
    /** The measurements so far (the saved share first), of which [share] is the median. */
    private val shares = mutableListOf<Float>()
    private var failures = 0
    /** What the speed was learnt for: the frame's rows, and the typical unit's pixels. */
    private var rows = 0
    private var unit = 0f

    init {
        val parts = saved?.trim()?.split(' ').orEmpty()
        parts.firstOrNull()?.toFloatOrNull()?.takeIf(::plausible)?.let {
            share = it
            shares += it
            settled = parts.getOrNull(1) == SETTLED
            rows = parts.getOrNull(2)?.toIntOrNull() ?: 0
            unit = parts.getOrNull(3)?.toFloatOrNull() ?: 0f
        }
    }

    /** The session's frame has [rows] rows and a typical unit of [unit] pixels: a settled speed learnt for others is learnt again. */
    fun setup(rows: Int, unit: Float) {
        if (rows == this.rows && abs(unit - this.unit) < 0.01f) return
        if (settled) {
            settled = false
            shares.clear()
            shares += share
        }
        this.rows = rows
        this.unit = unit
    }

    /** Measured at least once (or saved). */
    val known: Boolean get() = shares.isNotEmpty()

    /** Drags are still worth measuring. */
    val measuring: Boolean get() = !settled && failures < MAX_FAILURES

    /**
     * A measurement: the unit moved the content [measured] times the typical unit, or null when it
     * gave no value. What came of it, for the log.
     */
    fun measured(measured: Float?): String {
        if (measured == null) {
            failures++
            return "no value"
        }
        if (!plausible(measured)) {
            failures++
            return "dropped (far from what apps move)"
        }
        shares += measured
        if (shares.size > ScrollMeter.HISTORY) shares.removeAt(0)
        share = ScrollMeter.median(shares)
        settled = shares.size >= SETTLE_COUNT && shares.takeLast(SETTLE_COUNT).all { abs(it - share) <= share * AGREE }
        return "using %.2f%s".format(Locale.ROOT, share, if (settled) ", settled" else "")
    }

    /** The speed as kept between sessions. */
    fun saved(): String = "%.3f %s %d %.2f".format(Locale.ROOT, share, if (settled) SETTLED else LEARNING, rows, unit)

    private fun plausible(share: Float) = share in 1 / PLAUSIBLE..PLAUSIBLE

    companion object {
        /** Measurements in a row that settle the speed when they agree within [AGREE] of it. */
        const val SETTLE_COUNT = 3
        const val AGREE = 0.15f
        /** Every app measured so far moved within twice either way of the typical unit; beyond this a measurement went wrong. */
        const val PLAUSIBLE = 3f
        /** Drags without a value after which a session stops measuring (sparse or repetitive content fails often; a try costs little). */
        const val MAX_FAILURES = 20
        private const val SETTLED = "settled"
        private const val LEARNING = "learning"
    }
}
