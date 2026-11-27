package com.garathon.flatdroid.proot.processes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The thread states the hidden pause counts ([ProotFreeze]): from `task/<tid>/stat`. */
class ProcTableThreadsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun stateAfterTheCommand() {
        assertEquals('S', ProcTable.stateOf("1234 (snapshot) S 1230 1234 1230 0 -1 4194560"))
        assertEquals('t', ProcTable.stateOf("1235 (queue0:src) t 1230 1234"))
    }

    @Test
    fun commandWithParenthesesAndSpaces() {
        // A thread may name itself anything, a closing parenthesis and a state-like letter included.
        assertEquals('R', ProcTable.stateOf("1236 (a) T (b) R 1230 1234"))
    }

    @Test
    fun processGroupAfterTheParent() {
        assertEquals(1234, ProcTable.groupOf("1234 (sh) S 1230 1234 1230 0 -1 4194560"))
        assertEquals(1230, ProcTable.groupOf("1236 (a) T (b) R 1231 1230 1230"))
        assertNull(ProcTable.groupOf("1237 (cut"))
    }

    @Test
    fun noStateInABrokenLine() {
        assertNull(ProcTable.stateOf("1237 (cut"))
        assertNull(ProcTable.stateOf(""))
    }

    @Test
    fun stoppedStates() {
        for (state in "tTZX") assertTrue("$state", ProcTable.isStopped(state))
        for (state in "RSD") assertFalse("$state", ProcTable.isStopped(state))
    }

    @Test
    fun statesOfEveryThread() {
        val proc = tmp.newFolder("proc")
        fun thread(tid: Int, line: String) = File(proc, "100/task/$tid").apply { mkdirs() }.resolve("stat").writeText(line)
        thread(100, "100 (app) t 1 100")
        thread(101, "101 (gmain) S 1 100")
        thread(102, "102 (worker 2) R 1 100")
        File(proc, "100/task/103").mkdirs() // a thread that ended between the listing and the read
        assertEquals(listOf('R', 'S', 't'), ProcTable.threadStates(100, proc).sorted())
        assertEquals(emptyList<Char>(), ProcTable.threadStates(200, proc))
    }
}
