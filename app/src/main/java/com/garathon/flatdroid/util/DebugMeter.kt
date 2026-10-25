package com.garathon.flatdroid.util

import com.garathon.flatdroid.BuildConfig
import java.util.Locale

/**
 * Rates and step times for debug builds' logs: [start] and [step] time the steps of one round (a
 * camera frame: pack, turn, encode, write), [round] ends it, [count] counts something else per
 * period (frames the camera captured). Every [periodMs] one line goes to [DebugLog], such as
 * `Camera 0: 29 frames/s (captured 30), 15.2 ms each: pack 1.4, turn 7.0, JPEG 6.5, write 0.3`.
 * In release builds every call returns at once: [BuildConfig.DEBUG] is a constant, so the rest is
 * compiled out. Not thread-safe; use it from one thread.
 */
class DebugMeter(
    private val name: String,
    private val unit: String,
    private val steps: List<String>,
    private val counted: String? = null,
    private val periodMs: Long = 5_000,
) {
    private val nanos = LongArray(steps.size)
    private var rounds = 0
    private var others = 0
    private var since = 0L
    private var last = 0L

    fun start() {
        if (BuildConfig.DEBUG) last = System.nanoTime()
    }

    /** Step [index] of [steps] ended now. */
    fun step(index: Int) {
        if (!BuildConfig.DEBUG) return
        val now = System.nanoTime()
        nanos[index] += now - last
        last = now
    }

    fun count() {
        if (BuildConfig.DEBUG) others++
    }

    /** One round is done; logs once [periodMs] has passed since the period began. */
    fun round() {
        if (!BuildConfig.DEBUG) return
        rounds++
        val now = System.currentTimeMillis()
        if (since == 0L) since = now
        val elapsed = now - since
        if (elapsed < periodMs) return
        val ms = nanos.map { it / rounds / 1_000_000.0 }
        val extra = counted?.let { " ($it ${others * 1000 / elapsed})" }.orEmpty()
        val each = steps.indices.joinToString(", ") { "${steps[it]} %.1f".format(Locale.ROOT, ms[it]) }
        val total = "%.1f".format(Locale.ROOT, ms.sum())
        DebugLog.i("$name: ${rounds * 1000 / elapsed} $unit/s$extra, $total ms each: $each")
        reset()
        since = now
    }

    /** Starts a new period, forgetting what was counted. */
    fun reset() {
        if (!BuildConfig.DEBUG) return
        nanos.fill(0)
        rounds = 0
        others = 0
        since = 0L
    }
}
