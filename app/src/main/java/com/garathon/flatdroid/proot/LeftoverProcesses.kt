package com.garathon.flatdroid.proot

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Processes an earlier run of Flatdroid left behind. Android normally ends an app's child
 * processes with it, but on Waydroid a force-stop left a whole session running (its proots
 * reparented to init), holding the VNC port so the next launch failed. Ended when Flatdroid's
 * process starts, before it starts anything itself.
 */
object LeftoverProcesses {
    /** One process: its owner and its parent. */
    data class Proc(val uid: Int, val ppid: Int)

    /**
     * PIDs of [uid]'s orphaned trees: processes whose parents, up to init, are all [uid]'s. A
     * process of the running Flatdroid leads to zygote instead, and a `run-as` shell to adbd.
     */
    fun select(processes: Map<Int, Proc>, uid: Int): List<Int> = processes.keys.filter { isOrphan(it, processes, uid) }.sorted()

    private fun isOrphan(pid: Int, processes: Map<Int, Proc>, uid: Int): Boolean {
        var current = pid
        val seen = mutableSetOf<Int>()
        while (seen.add(current)) {
            val proc = processes[current] ?: return false
            if (proc.uid != uid) return false
            if (proc.ppid == INIT) return true
            current = proc.ppid
        }
        return false
    }

    /** Ends them (SIGKILL) and returns how many there were. */
    fun kill(): Int {
        val processes = File("/proc").listFiles()?.mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            try {
                // The fourth field of /proc/<pid>/stat, after the parenthesised name.
                val ppid = File(dir, "stat").readText().substringAfterLast(')').trim().split(' ')[1].toInt()
                pid to Proc(Os.stat(dir.path).st_uid, ppid)
            } catch (_: Exception) {
                null
            }
        }?.toMap() ?: return 0
        val pids = select(processes, Os.getuid())
        for (pid in pids) {
            try {
                Os.kill(pid, OsConstants.SIGKILL)
            } catch (_: ErrnoException) {
            }
        }
        return pids.size
    }

    private const val INIT = 1
}
