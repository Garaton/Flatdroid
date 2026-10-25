package com.garathon.flatdroid.proot

import com.garathon.flatdroid.util.forEachLineLimited
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * [output] holds the last [ProotRunner.MAX_OUTPUT_CHARS] of the output, from a line start.
 * [killed]: a timeout had to kill it (see [ProcessStopper.stop]).
 */
data class ProcessResult(val exitCode: Int?, val output: String, val timedOut: Boolean, val killed: Boolean = false)

/** [ProotRunner.run] was interrupted; [killed]: stopping it took more than SIGINT (see [ProcessStopper.stop]). */
class RunInterruptedException(val killed: Boolean) : InterruptedException("Interrupted")

/**
 * A started proot. Its output (stdout and stderr together) goes to the onLine callback given to
 * [ProotRunner.start], on a background thread. [stop] goes through [ProcessStopper], since proot
 * ignores the SIGTERM that Process.destroy() sends.
 */
class ProotProcess internal constructor(
    private val process: Process,
    private val signals: Signals,
    private val reader: Thread,
    private val writer: Thread,
) {
    // Process.isAlive needs API 26; minSdk is 24.
    val isAlive: Boolean get() = !ProcessStopper.waitForExit(process, 0)

    /** Waits for the exit and for the last output lines. Interruptible. */
    fun waitFor(): Int {
        val code = process.waitFor()
        reader.join(TimeUnit.SECONDS.toMillis(5))
        writer.join(TimeUnit.SECONDS.toMillis(1))
        return code
    }

    /**
     * Stops the process and everything under it; returns once it has exited (or after the last
     * resort), with whether it had to be killed. [interruptGraceMillis] is how long it gets to
     * end on SIGINT (see ProcessStopper).
     */
    fun stop(interruptGraceMillis: Long = ProcessStopper.SHORT_GRACE_MS): Boolean {
        if (!isAlive) return false
        val pid = ProcessStopper.pidOf(process) ?: run { process.destroy(); return true }
        return ProcessStopper(signals, interruptGraceMillis).stop(process, pid)
    }

    /** Ctrl-C only: SIGINT to the processes under proot; whether they stop is up to them. */
    fun interrupt() {
        if (!isAlive) return
        ProcessStopper.pidOf(process)?.let { ProcessStopper(signals).interrupt(it) }
    }

    /** Stops it the hard way at once (SIGQUIT to proot, then SIGKILL; see ProcessStopper). */
    fun kill() {
        if (!isAlive) return
        val pid = ProcessStopper.pidOf(process)
        if (pid != null) ProcessStopper(signals).kill(process, pid) else process.destroy()
    }
}

/** Runs a [ProotInvocation] from a cleared environment and collects stdout and stderr together. */
object ProotRunner {
    /** How much of a command's output [run] keeps; callers read its last lines. */
    const val MAX_OUTPUT_CHARS = 256 * 1024

    /** Longer output lines are handed on in pieces of this size. */
    const val MAX_LINE_CHARS = 8192

    /** Starts [invocation]; [stdin] is written and closed (empty when null). */
    fun start(
        invocation: ProotInvocation,
        workDir: File,
        stdin: ByteArray? = null,
        signals: Signals = AndroidSignals,
        onLine: (String) -> Unit = {},
    ): ProotProcess {
        val builder = ProcessBuilder(invocation.argv)
            .directory(workDir)
            .redirectErrorStream(true)
        builder.environment().apply {
            clear()
            putAll(invocation.env)
        }
        val process = builder.start()
        val writer = thread(name = "proot-input") {
            try {
                process.outputStream.use { if (stdin != null) it.write(stdin) }
            } catch (_: java.io.IOException) {
                // The process exited without reading everything.
            }
        }
        val reader = thread(name = "proot-output") {
            try {
                process.inputStream.reader().use { it.forEachLineLimited(MAX_LINE_CHARS, onLine) }
            } catch (_: java.io.IOException) {
                // The stream closed while the process was being stopped.
            }
        }
        return ProotProcess(process, signals, reader, writer)
    }

    /**
     * Blocks until the process exits. [onLine] is called for each output line as it arrives, on a
     * background thread. An interrupt of the calling thread (cancelling) stops the process and is
     * rethrown as [RunInterruptedException]. The timeout stops it too and returns a result with
     * [ProcessResult.timedOut].
     */
    fun run(
        invocation: ProotInvocation,
        workDir: File,
        timeoutSeconds: Long = 60,
        stdin: ByteArray? = null,
        signals: Signals = AndroidSignals,
        interruptGraceMillis: Long = ProcessStopper.SHORT_GRACE_MS,
        onLine: (String) -> Unit = {},
    ): ProcessResult {
        val output = StringBuilder()
        val proc = start(invocation, workDir, stdin, signals) {
            synchronized(output) {
                output.appendLine(it)
                // Trimmed only once it is twice the limit, so the copy is paid once per
                // MAX_OUTPUT_CHARS of output, not once per line.
                if (output.length > 2 * MAX_OUTPUT_CHARS) trimToTail(output)
            }
            onLine(it)
        }
        // Process.waitFor(timeout) needs API 26; minSdk is 24.
        val timedOut = AtomicBoolean(false)
        val killed = AtomicBoolean(false)
        val watchdog = thread(name = "proot-timeout", isDaemon = true) {
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(timeoutSeconds))
                timedOut.set(true)
                killed.set(proc.stop(interruptGraceMillis))
            } catch (_: InterruptedException) {
            }
        }
        val exitCode = try {
            proc.waitFor()
        } catch (e: InterruptedException) {
            watchdog.interrupt()
            throw RunInterruptedException(proc.stop(interruptGraceMillis))
        } finally {
            watchdog.interrupt()
        }
        return ProcessResult(
            exitCode = if (timedOut.get()) null else exitCode,
            output = synchronized(output) { trimToTail(output); output.toString() },
            timedOut = timedOut.get(),
            killed = killed.get(),
        )
    }

    /** Keeps the last [MAX_OUTPUT_CHARS] of [output], from the start of a line. */
    private fun trimToTail(output: StringBuilder) {
        if (output.length <= MAX_OUTPUT_CHARS) return
        val cut = output.indexOf("\n", output.length - MAX_OUTPUT_CHARS - 1)
        output.delete(0, if (cut < 0) output.length - MAX_OUTPUT_CHARS else cut + 1)
    }
}
