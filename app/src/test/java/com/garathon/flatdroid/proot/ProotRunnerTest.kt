package com.garathon.flatdroid.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs host programs (Linux) in place of proot: the runner itself doesn't care what argv[0] is. */
class ProotRunnerTest {
    private val dir = File(System.getProperty("java.io.tmpdir")!!)

    @Test
    fun collectsOutputAndExitCode() {
        val result = ProotRunner.run(ProotInvocation(listOf("/bin/sh", "-c", "echo hi; echo err >&2; exit 3"), emptyMap()), dir)
        assertEquals("hi\nerr\n", result.output)
        assertEquals(3, result.exitCode)
    }

    @Test
    fun onlyTheTailOfLongOutputIsKept() {
        // 200 000 lines of "line N" is well over the limit.
        val lines = mutableListOf<String>()
        val result = ProotRunner.run(
            ProotInvocation(listOf("/bin/sh", "-c", "i=0; while [ \$i -lt 200000 ]; do echo line \$i; i=\$((i+1)); done"), emptyMap()), dir,
            timeoutSeconds = 120,
        ) { synchronized(lines) { lines += it } }
        assertEquals(200_000, lines.size)
        assertTrue(result.output.length <= ProotRunner.MAX_OUTPUT_CHARS)
        assertTrue(result.output.startsWith("line "))
        assertTrue(result.output.endsWith("line 199999\n"))
    }

    @Test
    fun environmentIsOnlyWhatTheInvocationGives() {
        val result = ProotRunner.run(ProotInvocation(listOf("/usr/bin/env"), mapOf("ONLY" to "this")), dir)
        assertEquals("ONLY=this\n", result.output)
    }

    @Test
    fun timeoutStopsTheProcess() {
        val start = System.nanoTime()
        val result = ProotRunner.run(ProotInvocation(listOf("/bin/sleep", "30"), emptyMap()), dir, timeoutSeconds = 1, signals = HostSignals)
        assertTrue(result.timedOut)
        assertNull(result.exitCode)
        assertTrue(System.nanoTime() - start < 10_000_000_000)
    }

    @Test
    fun stdinIsWrittenAndLinesArriveAsTheyComeOut() {
        val lines = mutableListOf<String>()
        val result = ProotRunner.run(
            ProotInvocation(listOf("/bin/sh", "-c", "cat; echo done"), emptyMap()), dir,
            stdin = "a\nb\n".toByteArray(),
        ) { synchronized(lines) { lines += it } }
        assertEquals("a\nb\ndone\n", result.output)
        assertEquals(listOf("a", "b", "done"), synchronized(lines) { lines.toList() })
        assertEquals(0, result.exitCode)
    }

    @Test
    fun noStdinMeansEndOfInput() {
        val result = ProotRunner.run(ProotInvocation(listOf("/bin/cat"), emptyMap()), dir, timeoutSeconds = 5)
        assertEquals(0, result.exitCode)
        assertEquals("", result.output)
    }

    @Test
    fun interruptStopsTheProcess() {
        var thrown: Throwable? = null
        val start = System.nanoTime()
        val worker = Thread {
            try {
                ProotRunner.run(ProotInvocation(listOf("/bin/sleep", "30"), emptyMap()), dir, signals = HostSignals)
            } catch (e: Throwable) {
                thrown = e
            }
        }
        worker.start()
        Thread.sleep(300)
        worker.interrupt()
        worker.join(8_000)
        assertTrue(thrown is InterruptedException)
        // sleep has no children and ignores nothing: it ends on the SIGQUIT after the grace period.
        assertTrue(System.nanoTime() - start < 8_000_000_000)
    }
}
