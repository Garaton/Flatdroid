package com.garathon.flatdroid.session

/**
 * How long an app may have no window before [HiddenPause] pauses it. The clock is the caller's
 * (milliseconds, any origin, as long as it is one). [startedAt] is when the app's process started.
 *
 * - After a window has existed, the wait counts from the moment the last one closed: [WAIT_MS].
 * - An app that has not opened one yet gets [FIRST_WINDOW_MS] from [startedAt]: a slow start has no
 *   window for a long time, and pausing it then would stall the very thing it waits for. A window
 *   open for less than [BRIEF_MS] doesn't count as one: a splash, or a first start that restarts
 *   itself, is followed by a while with none. The wait then still ends no sooner than [WAIT_MS]
 *   after that window closed.
 * - Time the app spends stopped for another reason doesn't count ([suspended]).
 * - Each [resumed] doubles the wait, up to [MAX_WAIT_MS], and counts from the resume. A window
 *   opening resets the doubling: the situation has changed, so the earlier answer no longer applies.
 */
class NoWindowWait(private val startedAt: Long) {
    private var count = 0
    /** A window has been open for [BRIEF_MS] or more at some time: the app started. */
    var opened = false
        private set
    /** When the windows went from none to some; null while none is open. */
    private var shownAt: Long? = null
    private var resumes = 0
    /** Where the wait counts from. */
    private var since = startedAt

    /** [windows] has been told at least once: before that the count is a guess. */
    var known = false
        private set

    /** No window is open now. */
    val empty: Boolean get() = count == 0

    /** When the app is to be paused if no window opens before, or null while one is open or none is known yet. */
    val deadline: Long? get() = if (!known || count > 0) null else since + waitMs

    /** The wait now, in milliseconds. */
    val waitMs: Long
        get() = minOf((if (opened) WAIT_MS else FIRST_WINDOW_MS) shl minOf(resumes, MAX_DOUBLINGS), MAX_WAIT_MS)

    /** [count] windows are open at [now]. Returns when the app is to be paused if none opens before, or null while one is. */
    fun windows(count: Int, now: Long): Long? {
        val before = this.count
        this.count = count
        known = true
        if (count > 0) {
            if (before == 0) shownAt = now
            resumes = 0
            return null
        }
        if (before > 0) {
            if (now - (shownAt ?: now) >= BRIEF_MS) opened = true
            // A brief window keeps the first-window time, but leaves at least the short wait after it
            // closes: a splash closing as the first-window time ends isn't paused on the spot.
            since = if (opened) now else maxOf(since, now + WAIT_MS - waitMs)
        }
        shownAt = null
        return since + waitMs
    }

    /** The user let the app go on at [now]. Returns when it is to be paused next if still no window opens. */
    fun resumed(now: Long): Long {
        if (resumes < MAX_DOUBLINGS) resumes++
        since = now
        return since + waitMs
    }

    /** How long until the first-window time is over, for an app whose windows nobody counts ([known] false) and that isn't known to have opened one; 0 otherwise. */
    fun firstWindowLeft(now: Long): Long = if (known || opened) 0 else maxOf(0, startedAt + FIRST_WINDOW_MS - now)

    /**
     * The app was stopped for another reason from [from] to [until] (the viewer hidden): the wait
     * doesn't count that time. A resume inside it counts from [until].
     */
    fun suspended(from: Long, until: Long) {
        since = if (since >= from) until else since + (until - from)
    }

    companion object {
        const val WAIT_MS = 10_000L
        const val FIRST_WINDOW_MS = 120_000L
        const val MAX_WAIT_MS = 300_000L
        const val BRIEF_MS = 5_000L
        private const val MAX_DOUBLINGS = 16
    }
}
