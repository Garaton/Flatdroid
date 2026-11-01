package com.garathon.flatdroid.proot

import org.junit.Assert.assertEquals
import org.junit.Test

class LeftoverProcessesTest {
    private val app = 10127

    @Test
    fun onlyTreesOrphanedToInitAreSelected() {
        val processes = mapOf(
            1 to Proc(0, 0),
            89 to Proc(0, 1), // zygote
            500 to Proc(app, 89), // Flatdroid itself
            501 to Proc(app, 500), // its current proot
            502 to Proc(app, 501), // and that proot's tracee
            700 to Proc(app, 1), // an earlier run's proot, reparented to init
            701 to Proc(app, 700),
            702 to Proc(app, 701),
            800 to Proc(2000, 1), // adbd's shell
            801 to Proc(app, 800), // a run-as shell
            900 to Proc(10200, 1), // another app's orphan
        )
        assertEquals(listOf(700, 701, 702), LeftoverProcesses.select(processes, app))
    }

    @Test
    fun processesTracedByAHiddenProotAreSelectedWithIt() {
        // proot isn't dumpable, so /proc doesn't show it (here 700 and 900); its tracees name it.
        val processes = mapOf(
            1 to Proc(0, 0),
            89 to Proc(0, 1),
            500 to Proc(app, 89), // Flatdroid itself
            701 to Proc(app, 700, tracer = 700),
            702 to Proc(app, 701, tracer = 700),
            801 to Proc(app, 800), // a run-as shell (adbd hidden too), not traced
            802 to Proc(app, 801, tracer = 801), // something it traces itself, visible tracer
            901 to Proc(10200, 900, tracer = 900), // another app's
        )
        assertEquals(listOf(700, 701, 702), LeftoverProcesses.select(processes, app))
    }

    @Test
    fun unknownParentsAndLoopsAreLeftAlone() {
        val processes = mapOf(10 to Proc(app, 11), 20 to Proc(app, 21), 21 to Proc(app, 20))
        assertEquals(emptyList<Int>(), LeftoverProcesses.select(processes, app))
    }

    @Test
    fun escapedAreUntracedOrphansOnly() {
        val processes = mapOf(
            1 to Proc(0, 0),
            89 to Proc(0, 1),
            500 to Proc(app, 89), // Flatdroid itself
            600 to Proc(app, 1), // left proot: its proot was killed
            601 to Proc(app, 600), // its child, also untraced
            700 to Proc(app, 1, tracer = 650), // a daemon of a running task, still traced
            701 to Proc(app, 500, tracer = 650), // a running session's app
            801 to Proc(app, 800), // a run-as shell (adbd hidden)
            900 to Proc(10200, 1), // another app's
        )
        assertEquals(listOf(600, 601), LeftoverProcesses.escaped(processes, app))
    }
}
