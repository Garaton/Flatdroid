package com.garathon.flatdroid.proot.processes

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.garathon.flatdroid.util.debug.DebugLog

/**
 * Pauses a proot and everything it runs, and lets them go on. Two signals: SIGSTOP to the proot
 * first, then to every process it traces. A traced process that gets a signal waits for its
 * tracer, which, stopped, can't let it go (SIGSTOP to the processes alone does nothing: proot gets
 * the signal first and lets them run on). SIGCONT to proot lets every one of them go on; they get
 * one too, in case. A stopped process ignores the signals that end it, so a caller lets it go on
 * before stopping it.
 */
object ProotFreeze {
    /** How many processes [prootPid] traces, now stopped with it; null when proot couldn't be signalled (it ended). */
    fun stop(prootPid: Int): Int? {
        if (!signal(prootPid, OsConstants.SIGSTOP)) return null
        val traced = tracedBy(prootPid)
        traced.forEach { signal(it, OsConstants.SIGSTOP) }
        return traced.size
    }

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
