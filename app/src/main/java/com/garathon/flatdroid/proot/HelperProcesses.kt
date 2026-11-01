package com.garathon.flatdroid.proot

import java.io.File

/**
 * The /proc entries of the processes under the helpers' proots, each of which an app's root can
 * get an empty directory over (FlatpakBinds.hiddenProc). The helpers run under proot, which keeps
 * them dumpable, so an app could otherwise open their memory through /proc. Covers what runs at
 * the time, threads included; processes the helpers start later aren't hidden.
 *
 * Only what is still there after [SETTLE_MS] counts: the scripts' short-lived commands (`sleep`,
 * the microphone watcher's `pactl`, `awk` and `wc`) hold nothing worth reading, and their numbers
 * would soon be reused (process and thread numbers share one space), giving one of the app's own
 * processes or threads an empty `/proc/<id>`.
 */
object HelperProcesses {
    const val SETTLE_MS = 250L

    /** `/proc/<id>` for every process under [proots], and each of their threads, that lasts [settleMs]. */
    fun procPaths(proots: List<Int>, procDir: File = File("/proc"), settleMs: Long = SETTLE_MS): List<String> {
        val stopper = ProcessStopper(AndroidSignals, procDir = procDir)
        fun ids(): Set<Int> {
            val pids = proots.flatMap { stopper.descendants(it) }.distinct()
            return pids.flatMap { pid -> listOf(pid) + threads(procDir, pid) }.toSet()
        }
        val first = ids()
        if (first.isEmpty()) return emptyList()
        if (settleMs > 0) Thread.sleep(settleMs)
        return (first intersect ids()).sorted().map { "/proc/$it" }
    }

    private fun threads(procDir: File, pid: Int): List<Int> =
        File(procDir, "$pid/task").list()?.mapNotNull { it.toIntOrNull() }.orEmpty()
}
