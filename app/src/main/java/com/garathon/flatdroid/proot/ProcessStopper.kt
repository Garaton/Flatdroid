package com.garathon.flatdroid.proot

import android.system.Os
import android.system.OsConstants
import java.io.File

/** The signals used to stop a proot and the processes it runs. */
enum class StopSignal { INTERRUPT, QUIT, KILL }

/** Sends signals; Android's implementation is [AndroidSignals]. */
fun interface Signals {
    fun send(pid: Int, signal: StopSignal)
}

object AndroidSignals : Signals {
    override fun send(pid: Int, signal: StopSignal) {
        val sig = when (signal) {
            StopSignal.INTERRUPT -> OsConstants.SIGINT
            StopSignal.QUIT -> OsConstants.SIGQUIT
            StopSignal.KILL -> OsConstants.SIGKILL
        }
        try {
            Os.kill(pid, sig)
        } catch (_: Exception) {
            // Already gone.
        }
    }
}

/**
 * Stops a proot and what runs under it. proot ignores SIGTERM and SIGINT, so Process.destroy()
 * does nothing to it. Instead, like Ctrl-C, the processes under proot get SIGINT first, so they
 * can stop cleanly; then proot gets SIGQUIT, on which it kills every process it traces and exits;
 * SIGKILL is the last resort.
 */
class ProcessStopper(
    private val signals: Signals,
    private val graceMillis: Long = 3_000,
    private val procDir: File = File("/proc"),
) {
    /** Stops [process] (whose pid is [pid]); returns once it has exited or after the last resort. */
    fun stop(process: Process, pid: Int) {
        descendants(pid).forEach { signals.send(it, StopSignal.INTERRUPT) }
        if (waitForExit(process, graceMillis)) return
        signals.send(pid, StopSignal.QUIT)
        if (waitForExit(process, graceMillis)) return
        (descendants(pid) + pid).forEach { signals.send(it, StopSignal.KILL) }
        waitForExit(process, graceMillis)
    }

    /** Processes below [pid], found through /proc (the app sees its own processes there). */
    fun descendants(pid: Int): List<Int> {
        val parents = procDir.listFiles()
            ?.mapNotNull { dir -> dir.name.toIntOrNull()?.let { it to parentOf(dir) } }
            ?.filter { it.second != null }
            ?.associate { it.first to it.second!! }
            ?: return emptyList()
        val found = mutableListOf<Int>()
        var frontier = listOf(pid)
        while (frontier.isNotEmpty()) {
            val children = parents.filter { it.value in frontier }.keys.filter { it !in found }
            found += children
            frontier = children
        }
        return found
    }

    private fun parentOf(dir: File): Int? = try {
        File(dir, "status").useLines { lines ->
            lines.firstOrNull { it.startsWith("PPid:") }?.substringAfter(':')?.trim()?.toIntOrNull()
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        /**
         * The pid of [process]. java.lang.Process has no pid() on Android, but its toString() is
         * "Process[pid=…, …]" (on Android and on the desktop JDK alike).
         */
        fun pidOf(process: Process): Int? =
            Regex("""pid=(\d+)""").find(process.toString())?.groupValues?.get(1)?.toIntOrNull()

        /** Waits up to [millis] for [process] to exit (Process.waitFor(timeout) needs API 26). */
        fun waitForExit(process: Process, millis: Long): Boolean {
            val deadline = System.currentTimeMillis() + millis
            while (true) {
                try {
                    process.exitValue()
                    return true
                } catch (_: IllegalThreadStateException) {
                    if (System.currentTimeMillis() >= deadline) return false
                    Thread.sleep(50)
                }
            }
        }
    }
}
