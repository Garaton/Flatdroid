package com.garathon.flatdroid.proot.processes

import java.io.File

/** One process as /proc shows it: its owner (-1 when not read), its parent and its tracer (0 when not traced). */
data class Proc(val uid: Int, val ppid: Int, val tracer: Int = 0)

/**
 * The processes /proc shows, as one snapshot: what [ProcessStopper], [HelperProcesses] and
 * [LeftoverProcesses] read from it. Android hides non-dumpable processes (proot) from their own
 * app, so they are missing here; the processes they trace name them as their tracer.
 */
object ProcTable {
    /**
     * Every readable process in [procDir], by pid. [uidOf] gives a process's owner from its /proc
     * directory (Android shows a non-dumpable process as root's); without it the owner isn't read.
     */
    fun read(procDir: File = File("/proc"), uidOf: ((File) -> Int)? = null): Map<Int, Proc> =
        procDir.listFiles()?.mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            readOne(dir, uidOf)?.let { pid to it }
        }?.toMap().orEmpty()

    /** The state letter of each of [pid]'s threads (field 3 of `task/<tid>/stat`), unreadable ones left out. */
    fun threadStates(pid: Int, procDir: File = File("/proc")): List<Char> =
        threads(pid, procDir).mapNotNull { tid ->
            try {
                stateOf(File(procDir, "$pid/task/$tid/stat").readText())
            } catch (e: java.io.IOException) {
                null
            }
        }

    /** The state in a `stat` line: the first letter after the command's closing parenthesis (a command may hold one). */
    fun stateOf(stat: String): Char? = stat.substringAfterLast(')', "").trim().firstOrNull()

    /** Stopped (`T`), stopped by its tracer (`t`), or ended (`Z`, `X`): not running any more. */
    fun isStopped(state: Char): Boolean = state in "tTZXx"

    /** The thread IDs of [pid] (its own included). */
    fun threads(pid: Int, procDir: File = File("/proc")): List<Int> =
        File(procDir, "$pid/task").list()?.mapNotNull { it.toIntOrNull() }.orEmpty()

    private fun readOne(dir: File, uidOf: ((File) -> Int)?): Proc? = try {
        var parent: Int? = null
        var tracer = 0
        File(dir, "status").useLines { lines ->
            for (line in lines) {
                when {
                    line.startsWith("PPid:") -> parent = line.substringAfter(':').trim().toIntOrNull()
                    line.startsWith("TracerPid:") -> {
                        tracer = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        break // after PPid in every kernel's status
                    }
                }
            }
        }
        parent?.let { Proc(uidOf?.invoke(dir) ?: -1, it, tracer) }
    } catch (_: Exception) {
        null // gone meanwhile, or not readable
    }
}
