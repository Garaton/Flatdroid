package com.garathon.flatdroid.viewer

import com.garathon.flatdroid.util.DebugLog
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Debug builds' benchmark ([BenchSettings]): drives the app from inside the viewer (keys or
 * pointer moves sent on the viewer's own connection, so nothing else runs meanwhile) and logs the
 * updates, pixels and latency (input to the next update) of [BenchSettings.seconds].
 *
 * Workloads: `type` (typing, a line break every 36 characters), `pages` (Page Down and Up in a
 * 128-line document it makes first), `pointer` (the pointer circling, no buttons), `watch` (no
 * input: an app that animates by itself).
 */
class ViewerBench(private val settings: BenchSettings, private val client: RfbClient) {
    private val pending = ConcurrentLinkedQueue<Long>()
    private val latencies = mutableListOf<Long>()
    @Volatile private var measuring = false
    @Volatile private var updates = 0
    @Volatile private var bytes = 0L

    /** Reading thread: Raw pixels arrived. */
    fun pixels(count: Int) {
        if (measuring) bytes += count
    }

    /** Reading thread: an update is complete; it answers every input sent before it. */
    fun updateDone() {
        if (!measuring) return
        updates++
        val now = System.nanoTime()
        while (true) {
            val sent = pending.poll() ?: break
            synchronized(latencies) { latencies += now - sent }
        }
    }

    fun start() {
        thread(name = "viewer-bench", isDaemon = true) { runCatching(::run).onFailure { DebugLog.i("Bench: stopped: $it") } }
    }

    private fun run() {
        Thread.sleep(settings.settleMs)
        val mode = settings.workload
        prepare(mode)
        pending.clear()
        updates = 0
        bytes = 0
        measuring = true
        DebugLog.i("Bench START")
        val start = System.nanoTime()
        val end = start + settings.seconds * 1_000_000_000L
        val interval = (1000.0 / settings.rate).toLong()
        var inputs = 0
        val text = "typing test typing test typing test\n"
        val fb = client.framebuffer
        while (System.nanoTime() < end) {
            when (mode) {
                "type" -> { input(); tap(Keysyms.ofChar(text[inputs % text.length].code) ?: Keysyms.RETURN) }
                "pages" -> { input(); tap(if (inputs % 2 == 0) Keysyms.PAGE_DOWN else Keysyms.PAGE_UP) }
                "pointer" -> {
                    input()
                    val angle = inputs * 2 * PI / 30
                    client.pointer(0, (fb.width / 2 + fb.width / 4 * cos(angle)).toInt(), (fb.height / 2 + fb.height / 4 * sin(angle)).toInt())
                }
                "watch" -> Unit
                else -> { DebugLog.i("Bench: unknown workload $mode"); return }
            }
            inputs++
            Thread.sleep(if (mode == "watch") 200 else interval)
        }
        measuring = false
        val seconds = (System.nanoTime() - start) / 1e9
        val sorted = synchronized(latencies) { latencies.sorted() }
        val latency = if (sorted.isEmpty()) "" else ", latency median %d ms, p90 %d ms (%d answered)".format(
            Locale.ROOT, sorted[sorted.size / 2] / 1_000_000, sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.size - 1)] / 1_000_000, sorted.size,
        )
        DebugLog.i(
            "Bench %s: %dx%d, inputs %d, updates %d (%.1f/s), %.1f MB%s".format(
                Locale.ROOT, mode, fb.width, fb.height, if (mode == "watch") 0 else inputs, updates, updates / seconds, bytes / 1e6, latency,
            ),
        )
    }

    private fun input() {
        pending += System.nanoTime()
    }

    private fun tap(vararg keysyms: Int) {
        keysyms.forEach { client.key(true, it) }
        keysyms.reversed().forEach { client.key(false, it) }
    }

    private fun typeText(text: String) {
        for (c in text) {
            tap(Keysyms.ofChar(c.code) ?: continue)
            Thread.sleep(20)
        }
    }

    /** Typing and pages start from an empty document; pages makes 128 lines (one doubled 7 times). */
    private fun prepare(mode: String) {
        if (mode != "type" && mode != "pages") return
        tap(Keysyms.ESCAPE); Thread.sleep(300)
        tap(Keysyms.CONTROL_L, 'a'.code); tap(Keysyms.BACKSPACE); Thread.sleep(500)
        if (mode == "pages") {
            typeText("typing test typing test typing test\n")
            repeat(7) {
                tap(Keysyms.CONTROL_L, 'a'.code); tap(Keysyms.CONTROL_L, 'c'.code)
                tap(Keysyms.CONTROL_L, Keysyms.END); tap(Keysyms.CONTROL_L, 'v'.code)
                Thread.sleep(400)
            }
            tap(Keysyms.CONTROL_L, Keysyms.HOME)
            Thread.sleep(2000)
        }
    }
}
