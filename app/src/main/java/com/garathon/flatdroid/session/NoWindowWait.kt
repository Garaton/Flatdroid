package com.garathon.flatdroid.session

/**
 * How long an app may have no window before [HiddenPause] pauses it. The clock is the caller's
 * (milliseconds, any origin, as long as it is one). [startedAt] is when the app's process started.
 *
 * - After a window has existed, the wait counts from the moment the last one closed: [WAIT_MS].
 * - An app that has not opened one yet gets [FIRST_WINDOW_MS] from [startedAt]: a slow start has no
 *   window for a long time, and pausing it then would stall the very thing it waits for.
 * - Each [resumed] doubles the wait, up to [MAX_WAIT_MS], and counts from the resume. A window
 *   opening resets the doubling: the situation has changed, so the earlier answer no longer applies.
 */
class NoWindowWait(startedAt: Long) {
    private var count = 0
    /** A window has been open at some time: the app started. */
    var opened = false
        private set
    private var resumes = 0
    /** Where the wait counts from. */
    private var since = startedAt

    /** No window is open now. */
    val empty: Boolean get() = count == 0

    /** The wait now, in milliseconds. */
    val waitMs: Long
        get() = minOf((if (opened) WAIT_MS else FIRST_WINDOW_MS) shl minOf(resumes, MAX_DOUBLINGS), MAX_WAIT_MS)

    /** [count] windows are open at [now]. Returns when the app is to be paused if none opens before, or null while one is. */
    fun windows(count: Int, now: Long): Long? {
        val before = this.count
        this.count = count
        if (count > 0) {
            opened = true
            resumes = 0
            return null
        }
        if (before > 0) since = now
        return since + waitMs
    }

    /** The user let the app go on at [now]. Returns when it is to be paused next if still no window opens. */
    fun resumed(now: Long): Long {
        if (resumes < MAX_DOUBLINGS) resumes++
        since = now
        return since + waitMs
    }

    companion object {
        const val WAIT_MS = 5_000L
        const val FIRST_WINDOW_MS = 120_000L
        const val MAX_WAIT_MS = 300_000L
        private const val MAX_DOUBLINGS = 16
    }
}
