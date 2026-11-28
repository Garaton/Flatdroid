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
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The commands an app runs "on the host" over D-Bus ([HostCommandBus]): each in a proot of its own
 * over the app's root, with the app's binds, environment and filter ([plan], set once the app's
 * own launch is known), and its terminal taken by fdsec as a terminal app's is (other descriptors
 * reopened as its standard input, output and error). Like the
 * `flatpak-spawn` stand-in it goes without the app's `XDG_*_HOME`. Each command's thread starts it,
 * then waits for it, so the bus doesn't wait for a start. Not paused with the app while hidden
 * (verify what a running build costs then); all stopped at the session's end ([stopAll]), and one
 * still starting then is stopped as soon as it runs.
 */
class HostRuns(private val paths: ProotPaths, private val log: (String) -> Unit) : HostCommandBus.Commands {
    @Volatile var plan: LaunchPlan? = null

    /** By the command's pid (the process fdsec became), its proot. Guarded by itself, with [stopped]. */
    private val procs = HashMap<Int, ProotProcess>()
    private var stopped = false

    override fun start(command: HostCommandBus.Command, started: (Int?) -> Unit, exited: (pid: Int, status: Int) -> Unit): Boolean {
        val plan = plan ?: return false
        // The call's descriptors are closed once the bus's handler returns: duplicated here, on its thread.
        val held = HashMap<Int, ParcelFileDescriptor>()
        try {
            for ((n, fd) in command.stdio) held[n] = ParcelFileDescriptor.dup(fd)
        } catch (e: java.io.IOException) {
            for (fd in held.values) runCatching { fd.close() }
            log("[flatdroid] a host command could not start: $e")
            return false
        }
        thread(name = "host-command", isDaemon = true) {
            val signal = AtomicInteger(0)
            val proc = launch(command, plan, held, signal)
            val pid = proc?.pid?.let(::commandOf)
            if (proc == null || pid == null) {
                proc?.stop()
                if (proc != null) log("[flatdroid] a host command did not start")
                started(null)
                return@thread
            }
            // The session ended while it started: it doesn't outlive it.
            val kept = synchronized(procs) { if (stopped) false else { procs[pid] = proc; true } }
            if (!kept) {
                proc.kill()
                started(null)
                return@thread
            }
            started(pid)
            log("[flatdroid] host command $pid: ${command.argv.joinToString(" ").replace('\n', ' ').take(LOGGED_CHARS)}")
            val code = try { proc.waitFor() } catch (_: InterruptedException) { -1 }
            synchronized(procs) { procs.remove(pid) }
            // A wait status as Flatpak reports it: the signal that ended the command, or its exit code shifted.
            exited(pid, signal.get().takeIf { it != 0 } ?: (code and 0xff shl 8))
        }
        return true
    }

    /** [command]'s proot started, null when it couldn't; [held] is closed either way. */
    private fun launch(command: HostCommandBus.Command, plan: LaunchPlan, held: Map<Int, ParcelFileDescriptor>, signal: AtomicInteger): ProotProcess? {
        val env = if (command.clearEnv) command.env else plan.environment - HostCommands.HOST_UNSET_NAMES.toSet() + command.env
        // A name without a path is found as the shell would, on the command's PATH.
        val argv = if (command.argv[0].startsWith("/")) command.argv else listOf(ENV, "--") + command.argv
        val partners = pipePartners(held)
        return try {
            // Without a terminal, the caller's descriptors reopened through Flatdroid's own (pipes and
            // files alike); proot's notes then go to the command's standard error, not to the log.
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
            null
        } finally {
            for (fd in partners) runCatching { Os.close(fd) }
            for (fd in held.values) runCatching { fd.close() }
        }
    }

    private fun reopened(fd: ParcelFileDescriptor) = File("/proc/self/fd/${fd.fd}")

    /**
     * Each pipe among [held] opened here from the side the command doesn't take, without waiting,
     * until the command has its own. The redirect opens `/proc/self/fd/N` by path, and a pipe
     * opened so waits for an end of the other kind: forever, on this thread, when the app passed
     * one end and closed the other. Input gets a writer here, output and error a reader. A writer
     * can't open only where no end reads; then the app's end writes, and the redirect doesn't wait.
     */
    private fun pipePartners(held: Map<Int, ParcelFileDescriptor>): List<FileDescriptor> = held.mapNotNull { (n, fd) ->
        val pipe = runCatching { OsConstants.S_ISFIFO(Os.fstat(fd.fileDescriptor).st_mode) }.getOrDefault(false)
        if (!pipe) return@mapNotNull null
        val side = if (n == 0) OsConstants.O_WRONLY else OsConstants.O_RDONLY
        runCatching { Os.open(reopened(fd).path, side or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0) }.getOrNull()
    }

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
        val proc = synchronized(procs) { procs[pid] } ?: return false
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
        val all = synchronized(procs) {
            stopped = true
            procs.values.toList().also { procs.clear() }
        }
        for (proc in all) runCatching { proc.kill() }
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
