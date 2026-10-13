package com.garathon.flatdroid.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs host programs (Linux) in place of proot: the runner itself doesn't care what argv[0] is. */
class ProotRunnerTest {
    private val dir = File(System.getProperty("java.io.tmpdir"))

    @Test
    fun collectsOutputAndExitCode() {
        val result = ProotRunner.run(ProotInvocation(listOf("/bin/sh", "-c", "echo hi; echo err >&2; exit 3"), emptyMap()), dir)
        assertEquals("hi\nerr\n", result.output)
        assertEquals(3, result.exitCode)
    }

    @Test
    fun environmentIsOnlyWhatTheInvocationGives() {
        val result = ProotRunner.run(ProotInvocation(listOf("/usr/bin/env"), mapOf("ONLY" to "this")), dir)
        assertEquals("ONLY=this\n", result.output)
    }

    @Test
    fun timeoutStopsTheProcess() {
        val start = System.nanoTime()
        val result = ProotRunner.run(ProotInvocation(listOf("/bin/sleep", "30"), emptyMap()), dir, timeoutSeconds = 1)
        assertTrue(result.timedOut)
        assertNull(result.exitCode)
        assertTrue(System.nanoTime() - start < 10_000_000_000)
    }
}
