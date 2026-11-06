package com.garathon.flatdroid.proot

import java.io.File

/**
 * The /proc entries of every process Flatdroid has started, each of which an app's root can get
 * an empty directory over (FlatpakBinds.hiddenProc): the proots and all under them, so any helper
 * is covered without being listed. The helpers run under proot, which keeps them dumpable, so an
 * app could otherwise open their memory through /proc. Covers what runs at the time, threads
 * included; processes the helpers start later aren't hidden, so the helpers' scripts start none
 * once the app runs (Xvnc still runs `xkbcomp` when a client asks for a keymap, which no option
 * turns off).
 *
 * Only what is still there after [SETTLE_MS] counts: the scripts' start-up commands (`sleep` in
 * their wait loops) end before the app starts, and their numbers would soon be reused (process
 * and thread numbers share one space), giving one of the app's own processes or threads an empty
 * `/proc/<id>`.
 */
object HelperProcesses {
    const val SETTLE_MS = 250L

    /** `/proc/<id>` for every process under [parent] (not itself), and each of their threads, that lasts [settleMs]. */
    fun procPaths(parent: Int, procDir: File = File("/proc"), settleMs: Long = SETTLE_MS): List<String> {
        val stopper = ProcessStopper(AndroidSignals, procDir = procDir)
        fun ids(): Set<Int> {
            return stopper.descendants(parent).flatMap { pid -> listOf(pid) + ProcTable.threads(pid, procDir) }.toSet()
        }
        val first = ids()
        if (first.isEmpty()) return emptyList()
        if (settleMs > 0) Thread.sleep(settleMs)
        return (first intersect ids()).sorted().map { "/proc/$it" }
    }
}
