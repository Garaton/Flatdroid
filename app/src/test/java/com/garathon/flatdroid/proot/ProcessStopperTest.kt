package com.garathon.flatdroid.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Signals through the host's `kill`, standing in for android.system.Os on the JVM. */
object HostSignals : Signals {
    val sent = mutableListOf<Pair<Int, StopSignal>>()

    override fun send(pid: Int, signal: StopSignal) {
        synchronized(sent) { sent += pid to signal }
        val name = when (signal) {
            StopSignal.INTERRUPT -> "INT"
            StopSignal.QUIT -> "QUIT"
            StopSignal.KILL -> "KILL"
        }
        ProcessBuilder("kill", "-s", name, pid.toString()).start().waitFor()
    }
}

/**
 * A shell (standing in for proot) with one child process, like proot running a command. The
 * trailing "; true" keeps the shell from running the last command in place of itself.
 */
class ProcessStopperTest {
    private fun start(script: String): Pair<Process, Int> {
        val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        val pid = ProcessStopper.pidOf(process)
        assertNotNull(pid)
        Thread.sleep(300) // let the child start
        return process to pid!!
    }

    private fun alive(pid: Int) = File("/proc/$pid").exists()

    @Test
    fun interruptIsEnoughWhenTheCommandStopsOnCtrlC() {
        val (process, pid) = start("sleep 30; true")
        val child = ProcessStopper(HostSignals).descendants(pid).single()
        val started = System.nanoTime()
        assertFalse(ProcessStopper(HostSignals, interruptGraceMillis = 3_000, graceMillis = 3_000).stop(process, pid))
        assertTrue(ProcessStopper.waitForExit(process, 0))
        assertFalse(alive(child))
        assertTrue(System.nanoTime() - started < 2_000_000_000) // no need for the later stages
    }

    @Test
    fun quitStopsAParentThatIgnoresInterrupts() {
        // Like proot: the parent ignores SIGINT; its child (inheriting that) does too.
        val (process, pid) = start("trap '' INT; sleep 30; true")
        // Killed, which the caller is told: link2symlink's counts may be off.
        assertTrue(ProcessStopper(HostSignals, interruptGraceMillis = 500, graceMillis = 500).stop(process, pid))
        assertTrue(ProcessStopper.waitForExit(process, 0))
    }

    @Test
    fun killIsTheLastResortAndTakesTheChildrenToo() {
        val (process, pid) = start("trap '' INT QUIT; sleep 30; true")
        val child = ProcessStopper(HostSignals).descendants(pid).single()
        ProcessStopper(HostSignals, interruptGraceMillis = 500, graceMillis = 500).stop(process, pid)
        assertTrue(ProcessStopper.waitForExit(process, 0))
        Thread.sleep(200)
        assertFalse(alive(child))
    }

    @Test
    fun interruptAloneNeverEscalatesAndKillThenStopsIt() {
        // The terminal's ✕: the first press only interrupts, the second kills.
        val (process, pid) = start("trap '' INT; sleep 30; true")
        val stopper = ProcessStopper(HostSignals, graceMillis = 500)
        stopper.interrupt(pid)
        assertFalse(ProcessStopper.waitForExit(process, 1_500))
        stopper.kill(process, pid)
        assertTrue(ProcessStopper.waitForExit(process, 0))
    }

    @Test
    fun pidComesFromProcessToString() {
        val process = ProcessBuilder("/bin/true").start()
        assertEquals(true, ProcessStopper.pidOf(process)?.let { it > 0 })
        process.waitFor()
    }

    @Test
    fun descendantsIncludeWhatProotTracesWhateverTheirParent() {
        val proc = kotlin.io.path.createTempDirectory("proc").toFile()
        fun process(pid: Int, parent: Int, tracer: Int) =
            File(proc, "$pid").apply { mkdirs() }.resolve("status").writeText("Name:\tx\nPPid:\t$parent\nTracerPid:\t$tracer\n")
        process(50, 1, 0)      // proot
        process(101, 50, 50)   // the app
        process(102, 1, 50)    // gone into the background: init's child, still traced
        process(103, 102, 50)  // its child
        process(104, 1, 0)     // unrelated
        process(105, 1, 60)    // traced by another proot
        val found = ProcessStopper(HostSignals, procDir = proc).descendants(50)
        assertEquals(setOf(101, 102, 103), found.toSet())
        proc.deleteRecursively()
    }
}
