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
        ProcessStopper(HostSignals, graceMillis = 3_000).stop(process, pid)
        assertTrue(ProcessStopper.waitForExit(process, 0))
        assertFalse(alive(child))
        assertTrue(System.nanoTime() - started < 2_000_000_000) // no need for the later stages
    }

    @Test
    fun quitStopsAParentThatIgnoresInterrupts() {
        // Like proot: the parent ignores SIGINT; its child (inheriting that) does too.
        val (process, pid) = start("trap '' INT; sleep 30; true")
        ProcessStopper(HostSignals, graceMillis = 500).stop(process, pid)
        assertTrue(ProcessStopper.waitForExit(process, 0))
    }

    @Test
    fun killIsTheLastResortAndTakesTheChildrenToo() {
        val (process, pid) = start("trap '' INT QUIT; sleep 30; true")
        val child = ProcessStopper(HostSignals).descendants(pid).single()
        ProcessStopper(HostSignals, graceMillis = 500).stop(process, pid)
        assertTrue(ProcessStopper.waitForExit(process, 0))
        Thread.sleep(200)
        assertFalse(alive(child))
    }

    @Test
    fun pidComesFromProcessToString() {
        val process = ProcessBuilder("/bin/true").start()
        assertEquals(true, ProcessStopper.pidOf(process)?.let { it > 0 })
        process.waitFor()
    }
}
