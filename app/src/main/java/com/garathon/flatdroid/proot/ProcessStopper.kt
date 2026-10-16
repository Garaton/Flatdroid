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
 * does nothing to it. Instead, like Ctrl-C, the processes under proot get SIGINT first and
 * [interruptGraceMillis] to stop by themselves; then proot gets SIGQUIT, on which it kills every
 * process it traces and exits; SIGKILL is the last resort.
 *
 * The SIGQUIT step isn't clean: proot SIGKILLs processes wherever they are, including one that
 * stopped at a syscall after proot's link2symlink lowered a hard link's count but before the
 * unlink ran. The count then stays too low, and a later deletion removes content that a link
 * still points to. A process ending on SIGINT itself only dies between syscalls, so commands
 * that change Alpine or the Flatpak installation get a long [interruptGraceMillis].
 */
class ProcessStopper(
    private val signals: Signals,
    private val interruptGraceMillis: Long = SHORT_GRACE_MS,
    private val graceMillis: Long = SHORT_GRACE_MS,
    private val procDir: File = File("/proc"),
) {
    /** Stops [process] (whose pid is [pid]); returns once it has exited or after the last resort. */
    fun stop(process: Process, pid: Int) {
        interrupt(pid)
        if (waitForExit(process, interruptGraceMillis)) return
        kill(process, pid)
    }

    /** Ctrl-C: SIGINT to the processes under proot [pid]. Returns at once. */
    fun interrupt(pid: Int) {
        descendants(pid).forEach { signals.send(it, StopSignal.INTERRUPT) }
    }

    /**
     * The hard way, for when SIGINT wasn't enough: SIGQUIT to proot, then SIGKILL to everything
     * left. Returns once [process] has exited or after the last resort.
     */
    fun kill(process: Process, pid: Int) {
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
        /** For commands the user runs and may want gone at once. */
        const val SHORT_GRACE_MS = 3_000L
        /** For commands that change Alpine or the Flatpak installation (see the class comment). */
        const val LENIENT_GRACE_MS = 60_000L

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
