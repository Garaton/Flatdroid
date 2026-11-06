package com.garathon.flatdroid.proot

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HelperProcessesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun proc(dir: File, pid: Int, ppid: Int, tracer: Int, threads: List<Int> = emptyList()) {
        File(dir, "$pid").mkdirs()
        File(dir, "$pid/status").writeText("Name:\tx\nPPid:\t$ppid\nTracerPid:\t$tracer\n")
        for (tid in listOf(pid) + threads) File(dir, "$pid/task/$tid").mkdirs()
    }

    @Test
    fun everyProcessUnderFlatdroidWithItsThreads() {
        val dir = tmp.newFolder("proc")
        proc(dir, 50, 10, 0) // the helpers' proot, started by Flatdroid (10)
        proc(dir, 60, 10, 0) // the camera's proot
        proc(dir, 100, 50, 50, threads = listOf(103, 104)) // Xvnc and two threads
        proc(dir, 101, 1, 50) // went into the background, still traced
        proc(dir, 102, 100, 50) // a child
        proc(dir, 300, 60, 60) // a camera helper
        proc(dir, 200, 1, 0) // something else
        assertEquals(
            listOf("/proc/50", "/proc/60", "/proc/100", "/proc/101", "/proc/102", "/proc/103", "/proc/104", "/proc/300"),
            HelperProcesses.procPaths(10, dir, settleMs = 0),
        )
        assertEquals(emptyList<String>(), HelperProcesses.procPaths(999, dir, settleMs = 0))
    }
}
