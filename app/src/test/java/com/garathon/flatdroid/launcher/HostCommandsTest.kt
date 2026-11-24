package com.garathon.flatdroid.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs the stand-ins with the build machine's `/bin/sh` and `/usr/bin/env`, as POSIX sh would in a runtime. */
class HostCommandsTest {
    @get:Rule val tmp = TemporaryFolder()

    private data class Run(val code: Int, val out: String, val err: String)

    private fun run(script: String, name: String, vararg args: String, env: Map<String, String> = emptyMap(), stdinFd: String? = null): Run {
        val file = File(tmp.root, name).apply { writeText(script); setExecutable(true) }
        val command = if (stdinFd == null) listOf(file.path) + args else listOf("/bin/sh", "-c", "exec 7<\"$stdinFd\"; exec \"\$0\" \"\$@\"", file.path) + args
        val process = ProcessBuilder(command).apply { environment().putAll(env) }.start()
        process.outputStream.close()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        return Run(process.exitValue(), process.inputStream.bufferedReader().readText(), process.errorStream.bufferedReader().readText())
    }

    private fun spawn(vararg args: String, allowed: Boolean = true, env: Map<String, String> = emptyMap(), stdinFd: String? = null) =
        run(HostCommands.flatpakSpawn(allowed), "flatpak-spawn", *args, env = env, stdinFd = stdinFd)

    @Test
    fun runsTheCommandHereWithItsOptions() {
        val dir = tmp.newFolder("work")
        val r = spawn("--host", "--watch-bus", "--directory=${dir.path}", "--env=A=1 2", "--env=B=x=y", "--forward-fd=3",
            "sh", "-c", "pwd; echo \"\$A|\$B|\$KEEP\"; exit 7", env = mapOf("KEEP" to "kept"))
        assertEquals(7, r.code)
        assertEquals("${dir.canonicalPath}\n1 2|x=y|kept\n", r.out)
    }

    @Test
    fun clearAndUnset() {
        val cleared = spawn("--clear-env", "--env=A=a", "/bin/sh", "-c", "echo \"\$A|\$KEEP\"", env = mapOf("KEEP" to "kept"))
        assertEquals("a|\n", cleared.out)
        // The last of --env and --unset-env for a name wins.
        val r = spawn("--env=A=a", "--unset-env=A", "--unset-env=KEEP", "--env=KEEP=again", "sh", "-c", "echo \"\${A-unset}|\$KEEP\"", env = mapOf("KEEP" to "kept", "A" to "outer"))
        assertEquals("unset|again\n", r.out)
    }

    @Test
    fun environmentFromADescriptor() {
        val vars = File(tmp.root, "vars").apply { writeBytes("C=from fd\u0000D=d\u0000".toByteArray()) }
        val r = spawn("--env-fd=7", "sh", "-c", "echo \"\$C|\$D\"", stdinFd = vars.path)
        assertEquals(r.err, "from fd|d\n", r.out)
    }

    @Test
    fun hostOnlyWithTheMetadata() {
        val refused = spawn("--host", "true", allowed = false)
        assertEquals(1, refused.code)
        assertTrue("org.freedesktop.Flatpak was not provided" in refused.err)
        // A new sandbox of the same app is every app's.
        assertEquals(0, spawn("true", allowed = false).code)
    }

    @Test
    fun sandboxOptionsAreRefused() {
        for (option in listOf("--sandbox", "--no-network", "--sandbox-expose=x", "--sandbox-flag=share-gpu", "--app-path=", "--usr-path=/x")) {
            val r = spawn(option, "true")
            assertEquals(option, 1, r.code)
            assertTrue(option, "can't start a Flatpak sandbox" in r.err)
        }
    }

    @Test
    fun valuesFromADescriptorMayHoldNewlines() {
        val vars = File(tmp.root, "vars").apply { writeBytes("M=one\ntwo\u0000T=end\n\u0000P=plain\u0000".toByteArray()) }
        val r = spawn("--env-fd=7", "sh", "-c", "printf '%s|%s|%s' \"\$M\" \"\$T\" \"\$P\"", stdinFd = vars.path)
        assertEquals(r.err, "one\ntwo|end\n|plain", r.out)
    }

    @Test
    fun theScriptsOwnNamesDontReachTheCommand() {
        // Names a script might use for itself: the command sees the caller's values.
        val outer = mapOf("dir" to "d", "host" to "h", "clear" to "c", "names" to "n", "a" to "x", "m" to "y", "name" to "z")
        val r = spawn("--host", "--env=A=1", "sh", "-c", "echo \"\$dir|\$host|\$clear|\$names|\$a|\$m|\$name|\$A\"", env = outer)
        assertEquals(r.err, "d|h|c|n|x|y|z|1\n", r.out)
        // Unsetting such a name unsets the caller's variable, not the script's state.
        val cleared = spawn("--clear-env", "--unset-env=clear", "--unset-env=_fs_clear", "--env=A=a", "/bin/sh", "-c", "echo \"\$A|\${KEEP-gone}\"", env = mapOf("KEEP" to "kept"))
        assertEquals(cleared.err, "a|gone\n", cleared.out)
        assertEquals("c\n", hostSpawn("sh", "-c", "echo \"\$cwd\"", env = mapOf("cwd" to "c", "a" to "x")).out)
    }

    @Test
    fun malformedCalls() {
        assertTrue("No command specified" in spawn("--host").err)
        assertEquals(1, spawn("--env=1A=b", "true").code)
        assertEquals(1, spawn("--env=NOVALUE", "true").code)
        assertTrue("Missing argument" in spawn("--directory", "/tmp", "true").err)
        assertTrue("Unknown option" in spawn("--bogus", "true").err)
        // Arguments after the command are its own, and -- ends the options.
        assertEquals("--host\n", spawn("--", "echo", "--host").out)
        assertEquals("-n x\n", spawn("printf", "%s\\n", "-n x").out)
    }

    private fun hostSpawn(vararg args: String, allowed: Boolean = true, name: String = "host-spawn", env: Map<String, String> = emptyMap()) =
        run(HostCommands.hostSpawn(allowed), name, *args, env = env)

    @Test
    fun hostSpawnFlags() {
        val dir = tmp.newFolder("cwd")
        assertEquals("${dir.canonicalPath}\n", hostSpawn("-no-pty", "--env", "TERM", "-cwd", dir.path, "pwd").out)
        assertEquals("${dir.canonicalPath}\n", hostSpawn("-cwd=${dir.path}", "--", "pwd").out)
        assertEquals("x -pty\n", hostSpawn("echo", "x", "-pty").out)
        // No command: the user's shell.
        assertEquals("shell\n", hostSpawn(env = mapOf("SHELL" to File(tmp.root, "sh").apply { writeText("#!/bin/sh\necho shell\n"); setExecutable(true) }.path)).out)
        assertEquals(0, hostSpawn("-bogus").code)
    }

    @Test
    fun hostSpawnRefusesWithoutTheMetadataAndUnderOtherNames() {
        assertEquals(127, hostSpawn("true", allowed = false).code)
        val shim = hostSpawn("ps", name = "podman")
        assertEquals(127, shim.code)
        assertTrue("aren't available" in shim.err)
    }

    @Test
    fun whoMayAskForTheHost() {
        assertTrue(HostCommands.hostAllowed(mapOf("org.freedesktop.Flatpak" to "talk"), emptyList()))
        assertTrue(HostCommands.hostAllowed(emptyMap(), listOf("wayland", "session-bus")))
        assertFalse(HostCommands.hostAllowed(mapOf("org.freedesktop.Flatpak" to "see"), emptyList()))
        assertFalse(HostCommands.hostAllowed(emptyMap(), listOf("wayland")))
        assertTrue("[Session Bus Policy]\norg.freedesktop.Flatpak=talk\n" in LaunchPlanner.flatpakInfo("a.B", com.garathon.flatdroid.flatpak.FlatpakRef.parse("org.kde.Platform/aarch64/6.10")!!, network = false, hostAllowed = true))
    }
}
