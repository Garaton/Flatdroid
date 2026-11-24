package com.garathon.flatdroid.proot.processes

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.garathon.flatdroid.util.debug.DebugLog

/**
 * Pauses a proot and everything it runs, and lets them go on. SIGSTOP to the proot first, then to
 * every process it traces. A traced thread that takes a signal waits for its tracer, which,
 * stopped, can't let it go (SIGSTOP to the processes alone does nothing: proot gets the signal
 * first and lets them run on). But a SIGSTOP sent to a process stops only the one thread that
 * takes it, as the stopped tracer can't spread it to the others, so the rest of an app's threads
 * would run on. A stopped thread doesn't take the next one, so each process gets one SIGSTOP per
 * thread still running ([stopEveryThread]). SIGCONT to proot lets every one of them go on; they
 * get one too, in case. A stopped process ignores the signals that end it, so a caller lets it go
 * on before stopping it.
 */
object ProotFreeze {
    /** Rounds of [stopEveryThread]: a thread busy in the kernel may take its signal late. */
    private const val PASSES = 3

    /** How many processes [prootPid] traces, now stopped with it; null when proot couldn't be signalled (it ended). */
    fun stop(prootPid: Int): Int? {
        if (!signal(prootPid, OsConstants.SIGSTOP)) return null
        val traced = tracedBy(prootPid)
        stopEveryThread(traced)
        return traced.size
    }

    /**
     * Sends each of [pids] a SIGSTOP per thread that still runs, a millisecond apart (a signal sent
     * before the last one was taken would merge with it), every process at once, then counts again.
     * A few hundred milliseconds for a browser's processes. Threads still running after [PASSES]
     * rounds are logged.
     */
    private fun stopEveryThread(pids: List<Int>) {
        var running = pids.associateWith { runningThreads(it) }.filterValues { it > 0 }
        repeat(PASSES) {
            if (running.isEmpty()) return
            for (round in 0 until running.values.max()) {
                for ((pid, count) in running) if (round < count) signal(pid, OsConstants.SIGSTOP)
                Thread.sleep(1)
            }
            running = running.keys.associateWith { runningThreads(it) }.filterValues { it > 0 }
        }
        if (running.isNotEmpty()) DebugLog.w("Pause: threads still running in ${running.map { (pid, n) -> "$pid ($n)" }}")
    }

    /** How many of [pid]'s threads aren't stopped ([ProcTable.threadStates]); 0 when it ended. */
    private fun runningThreads(pid: Int): Int = ProcTable.threadStates(pid).count { !ProcTable.isStopped(it) }

    fun go(prootPid: Int) {
        signal(prootPid, OsConstants.SIGCONT)
        tracedBy(prootPid).forEach { signal(it, OsConstants.SIGCONT) }
    }

    private fun signal(pid: Int, signal: Int): Boolean = try {
        Os.kill(pid, signal)
        true
    } catch (e: ErrnoException) {
        DebugLog.w("Signal $signal to $pid: $e")
        false
    }

    /** The processes [tracer] traces (`TracerPid:` in `/proc/<pid>/status`), however they were started. */
    private fun tracedBy(tracer: Int): List<Int> = ProcTable.read().filterValues { it.tracer == tracer }.keys.toList()
}
