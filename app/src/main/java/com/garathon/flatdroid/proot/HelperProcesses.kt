package com.garathon.flatdroid.proot

import java.io.File

/**
 * The /proc entries of the processes under the helpers' proots, each of which an app's root can
 * get an empty directory over (FlatpakBinds.hiddenProc). The helpers run under proot, which keeps
 * them dumpable, so an app could otherwise open their memory through /proc. Covers what runs at
 * the time, threads included; processes the helpers start later aren't hidden.
 */
object HelperProcesses {
    /** `/proc/<id>` for every process under [proots] and each of their threads. */
    fun procPaths(proots: List<Int>, procDir: File = File("/proc")): List<String> {
        val stopper = ProcessStopper(AndroidSignals, procDir = procDir)
        val pids = proots.flatMap { stopper.descendants(it) }.distinct()
        val ids = pids.flatMap { pid -> listOf(pid) + threads(procDir, pid) }
        return ids.distinct().sorted().map { "/proc/$it" }
    }

    private fun threads(procDir: File, pid: Int): List<Int> =
        File(procDir, "$pid/task").list()?.mapNotNull { it.toIntOrNull() }.orEmpty()
}
