package com.garathon.flatdroid.proot

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Processes of Flatdroid's that nothing of the running Flatdroid should have.
 *
 * At start ([kill]): what an earlier run left behind. Android normally ends an app's child
 * processes with it, but a force-stop can leave a whole session running on some systems (its
 * proots reparented to init), holding the VNC port so the next launch failed.
 *
 * At any time ([killEscaped]): processes that left proot. A process can kill its own proot (same
 * user), and proot doesn't take its tracees with it: they keep running untraced, with the files
 * they had open, after Flatdroid has ended the session. Every process Flatdroid runs in a root is
 * traced, so an untraced one of Flatdroid's whose parents up to init are all Flatdroid's escaped.
 */
object LeftoverProcesses {
    /**
     * PIDs to end at start, of the visible [processes]: [uid]'s orphaned trees (processes whose
     * parents, up to init, are all [uid]'s), and [uid]'s processes traced by a process that isn't
     * visible, with that tracer. proot isn't dumpable, so Android's /proc hides it from its own
     * app: the processes it traces lead to a parent Flatdroid can't see, but their tracer names it,
     * and when Flatdroid's process starts nothing of its own is traced yet. A process of the
     * running Flatdroid leads to zygote instead, and a `run-as` shell to adbd.
     */
    fun select(processes: Map<Int, Proc>, uid: Int): List<Int> {
        val orphans = processes.keys.filter { isOrphan(it, processes, uid) }
        val tracedByHidden = processes.filter { (_, proc) -> proc.uid == uid && proc.tracer != 0 && proc.tracer !in processes }
        return (orphans + tracedByHidden.keys + tracedByHidden.values.map { it.tracer }).distinct().sorted()
    }

    /**
     * PIDs of [uid]'s processes that escaped proot: untraced orphans (see the class comment).
     * Traced ones stay, so a running task's or session's daemons (an agent gone into the
     * background) are left alone; a `run-as` shell leads to adbd and stays too.
     */
    fun escaped(processes: Map<Int, Proc>, uid: Int): List<Int> =
        processes.filter { (pid, proc) -> proc.tracer == 0 && isOrphan(pid, processes, uid) }.keys.sorted()

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

    /** At start: ends what an earlier run left ([select]; SIGKILL) and returns how many there were. */
    fun kill(): Int = killAll(select(ProcTable.read(uidOf = ::owner), Os.getuid()))

    /**
     * Ends processes that escaped proot ([escaped]; SIGKILL) and returns how many there were. Run
     * when a session or task ends and before a file check. proot's own filter leaves an escaped
     * process without clone, but not every way to start a process (vfork on some architectures),
     * so it looks again until none is left.
     */
    fun killEscaped(): Int {
        var total = 0
        repeat(MAX_PASSES) {
            val pids = escaped(ProcTable.read(uidOf = ::owner), Os.getuid()) - Process.myPid()
            if (pids.isEmpty()) return total
            total += killAll(pids)
        }
        return total
    }

    private fun killAll(pids: List<Int>): Int {
        for (pid in pids) {
            try {
                Os.kill(pid, OsConstants.SIGKILL)
            } catch (_: ErrnoException) {
            }
        }
        return pids.size
    }

    private fun owner(dir: File): Int = Os.stat(dir.path).st_uid

    private const val INIT = 1
    private const val MAX_PASSES = 5
}
