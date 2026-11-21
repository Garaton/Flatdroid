package com.garathon.flatdroid.launcher.electron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Runs the stand-in with the build machine's `/bin/sh`, under each of zypak's names. */
class ZypakStandInTest {
    @get:Rule val tmp = TemporaryFolder()

    private val script by lazy {
        File(tmp.root, "zypak").apply { writeText(Electron.zypakStandIn(listOf("--no-sandbox", "--ozone-platform=x11"), nodeFlags = listOf("--no-sandbox", "--in-process-gpu"))); setExecutable(true) }
    }
    /** Prints its arguments, one a line, standing in for the Electron binary. */
    private val binary by lazy { File(tmp.root, "electron").apply { writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\"\n"); setExecutable(true) } }

    private fun run(name: String, vararg args: String, env: Map<String, String> = emptyMap()): Pair<Int, String> {
        val link = File(tmp.root, name)
        if (!link.exists()) Files.createSymbolicLink(link.toPath(), script.toPath())
        val process = ProcessBuilder(listOf(link.path) + args).redirectErrorStream(true).apply { environment().putAll(env) }.start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        return process.exitValue() to process.inputStream.bufferedReader().readText()
    }

    @Test
    fun theWrapperStartsTheBinaryWithTheFlagsFirst() {
        for (name in listOf("zypak-wrapper.sh", "zypak-wrapper")) {
            assertEquals(0 to "--no-sandbox\n--ozone-platform=x11\n/app/x\n--a b\n", run(name, binary.path, "/app/x", "--a b"))
        }
    }

    @Test
    fun electronAsNodeGetsTheFlagsAfterItsScript() {
        val node = mapOf("ELECTRON_RUN_AS_NODE" to "1")
        assertEquals(
            0 to "--inspect\n/app/cli.js\n--no-sandbox\n--in-process-gpu\n--extensions-dir\n/x\nfile\n",
            run("zypak-helper", "host", "-", binary.path, "--inspect", "/app/cli.js", "--extensions-dir", "/x", "file", env = node),
        )
        // Node's prompt: no script, no flags.
        assertEquals(0 to "--version\n", run("zypak-wrapper.sh", binary.path, "--version", env = node))
        assertEquals(0 to "--no-sandbox\n--ozone-platform=x11\n/app/x\n", run("zypak-wrapper.sh", binary.path, "/app/x", env = mapOf("ELECTRON_RUN_AS_NODE" to "")))
    }

    @Test
    fun theHelpersModes() {
        assertEquals(0 to "--no-sandbox\n--ozone-platform=x11\n--y\n", run("zypak-helper", "host", "3=4", "-", binary.path, "--y"))
        assertEquals(0 to "--no-sandbox\n--ozone-platform=x11\n", run("zypak-helper", "host-latest", binary.path))
        // A child goes as it is.
        assertEquals(0 to "--type=renderer\n", run("zypak-helper", "child", "-", binary.path, "--type=renderer"))
        assertEquals(1, run("zypak-helper", "host", binary.path).first)
        assertEquals(1, run("zypak-helper", "spawn-strategy-test").first)
        assertEquals(1, run("zypak-helper", "nonsense").first)
        assertTrue(run("zypak-helper", "version").second.startsWith("Zypak"))
        assertEquals(1, run("zypak-wrapper").first)
    }

    @Test(expected = IllegalArgumentException::class)
    fun flagsAreCheckedBeforeQuoting() {
        Electron.zypakStandIn(listOf("--x='; rm -rf /"))
    }
}
