package com.garathon.flatdroid.proot

import java.io.File

/**
 * The /proc entries of the [proots] and all under them, each of which an app's root can get an
 * empty directory over (FlatpakBinds.hiddenProc). The helpers run under proot, which keeps them
 * dumpable, so an app could otherwise open their memory through /proc. Covers what runs at the
 * time, threads included; processes the helpers start later aren't hidden, so the helpers' scripts
 * start none once the app runs (Xvnc still runs `xkbcomp` when a client asks for a keymap, which no
 * option turns off). Called once the scripts are past their start-up commands.
 *
 * The walk starts from the proots' own numbers ([ProotRunner.runningPids], so every helper is
 * covered without being listed): a proot is non-dumpable, and where Android hides other users'
 * processes it can't be found from Flatdroid's process down.
 */
object HelperProcesses {
    /** `/proc/<id>` for each of [proots], every process under them, and each of their threads. */
    fun procPaths(proots: List<Int>, procDir: File = File("/proc")): List<String> {
        val stopper = ProcessStopper(AndroidSignals, procDir = procDir)
        val pids = (proots + proots.flatMap { stopper.descendants(it) }).distinct()
        return pids.flatMap { pid -> listOf(pid) + ProcTable.threads(pid, procDir) }.toSet().sorted().map { "/proc/$it" }
    }
}
