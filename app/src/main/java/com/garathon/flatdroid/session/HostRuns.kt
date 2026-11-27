package com.garathon.flatdroid.session

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.garathon.flatdroid.launcher.HostCommands
import com.garathon.flatdroid.launcher.LaunchPlan
import com.garathon.flatdroid.launcher.ProotExit
import com.garathon.flatdroid.proot.GuestPaths
import com.garathon.flatdroid.proot.ProotCommand
import com.garathon.flatdroid.proot.ProotPaths
import com.garathon.flatdroid.proot.ProotProcess
import com.garathon.flatdroid.proot.ProotRunner
import com.garathon.flatdroid.proot.processes.ProcTable
import com.garathon.flatdroid.session.bus.HostCommandBus
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The commands an app runs "on the host" over D-Bus ([HostCommandBus]): each in a proot of its own
 * over the app's root, with the app's binds, environment and filter ([plan], set once the app's
 * own launch is known), and its terminal taken by fdsec as a terminal app's is (other descriptors
 * reopened as its standard input, output and error). Like the
 * `flatpak-spawn` stand-in it goes without the app's `XDG_*_HOME`. Not paused with the app while
 * hidden (verify what a running build costs then); all stopped at the session's end ([stopAll]).
 */
class HostRuns(private val paths: ProotPaths, private val log: (String) -> Unit) : HostCommandBus.Commands {
    @Volatile var plan: LaunchPlan? = null

    /** By the command's pid (the process fdsec became), its proot. */
    private val procs = ConcurrentHashMap<Int, ProotProcess>()

    override fun start(command: HostCommandBus.Command, exited: (Int) -> Unit): Int? {
        val plan = plan ?: return null
        val env = if (command.clearEnv) command.env else plan.environment - HostCommands.HOST_UNSET_NAMES.toSet() + command.env
        // A name without a path is found as the shell would, on the command's PATH.
        val argv = if (command.argv[0].startsWith("/")) command.argv else listOf(ENV, "--") + command.argv
        val signal = AtomicInteger(0)
        // Without a terminal, the caller's descriptors reopened through Flatdroid's own (pipes and
        // files alike); proot's notes then go to the command's standard error, not to the log.
        val held = command.stdio.mapValues { ParcelFileDescriptor.dup(it.value) }
        val proc = try {
            ProotRunner.start(
                ProotCommand.flatpak(paths, plan.binds, env, argv, plan.filter, command.tty, cwd = command.cwd.ifEmpty { GuestPaths.HOME }),
                File(plan.binds.root),
                stdio = if (command.tty != null) null else ProotRunner.Stdio(
                    input = held[0]?.let(::reopened) ?: DEV_NULL,
                    output = held[1]?.let(::reopened) ?: DEV_NULL,
                    error = held[2]?.let(::reopened) ?: DEV_NULL,
                ),
            ) { line ->
                ProotExit.signalOf(line)?.let { signal.compareAndSet(0, it) }
                log("[host] $line")
            }
        } catch (e: Exception) {
            log("[flatdroid] a host command could not start: $e")
            return null
        } finally {
            for (fd in held.values) runCatching { fd.close() }
        }
        val proot = proc.pid
        val pid = proot?.let(::commandOf)
        if (pid == null) {
            proc.stop()
            log("[flatdroid] a host command did not start")
            return null
        }
        procs[pid] = proc
        log("[flatdroid] host command $pid: ${command.argv.joinToString(" ").replace('\n', ' ').take(LOGGED_CHARS)}")
        thread(name = "host-command", isDaemon = true) {
            val code = try { proc.waitFor() } catch (_: InterruptedException) { -1 }
            procs.remove(pid)
            // A wait status as Flatpak reports it: the signal that ended the command, or its exit code shifted.
            exited(signal.get().takeIf { it != 0 } ?: (code and 0xff shl 8))
        }
        return pid
    }

    private fun reopened(fd: ParcelFileDescriptor) = File("/proc/self/fd/${fd.fd}")

    /** proot's first process (fdsec, then the command in its place), once proot has started it. */
    private fun commandOf(proot: Int): Int? {
        val until = SystemClock.elapsedRealtime() + START_WAIT_MS
        while (SystemClock.elapsedRealtime() < until) {
            ProcTable.read().entries.firstOrNull { it.value.tracer == proot && it.value.ppid == proot }?.let { return it.key }
            Thread.sleep(POLL_MS)
        }
        return null
    }

    /**
     * A command on a terminal leads its own process group (fdsec starts a session for it), which a
     * group's signal reaches. Another stays in Flatdroid's group: a group's kill then ends its
     * proot, and with it everything the command started; other signals reach the command alone.
     */
    override fun signal(pid: Int, signal: Int, group: Boolean): Boolean {
        val proc = procs[pid] ?: return false
        val leads = ProcTable.groupOf(pid) == pid
        return runCatching {
            when {
                group && leads -> Os.kill(-pid, signal)
                group && signal == OsConstants.SIGKILL -> proc.kill()
                else -> Os.kill(pid, signal)
            }
        }.isSuccess
    }

    /** Ends every command still running, at once as Flatpak ends a leaving caller's (a shell ignores SIGINT). */
    fun stopAll() {
        for (proc in procs.values) runCatching { proc.kill() }
        procs.clear()
    }

    private companion object {
        const val ENV = "/usr/bin/env"
        val DEV_NULL = File("/dev/null")
        const val START_WAIT_MS = 5_000L
        const val POLL_MS = 20L
        /** Of a command line in the log: a script given with -c can be long. */
        const val LOGGED_CHARS = 160
    }
}
