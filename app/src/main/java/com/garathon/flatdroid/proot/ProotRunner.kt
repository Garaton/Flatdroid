package com.garathon.flatdroid.proot

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class ProcessResult(val exitCode: Int?, val output: String, val timedOut: Boolean)

/** Runs a [ProotInvocation] from a cleared environment and collects stdout and stderr together. */
object ProotRunner {
    /**
     * Blocks until the process exits. [stdin] is written and closed (empty when null). [onLine] is
     * called for each output line as it arrives, on a background thread. An interrupt of the
     * calling thread (cancelling) stops the process and is rethrown as [InterruptedException].
     * The timeout stops it too and returns a result with [ProcessResult.timedOut]. Stopping goes
     * through [ProcessStopper], since proot ignores the SIGTERM that Process.destroy() sends.
     */
    fun run(
        invocation: ProotInvocation,
        workDir: File,
        timeoutSeconds: Long = 60,
        stdin: ByteArray? = null,
        signals: Signals = AndroidSignals,
        onLine: (String) -> Unit = {},
    ): ProcessResult {
        val builder = ProcessBuilder(invocation.argv)
            .directory(workDir)
            .redirectErrorStream(true)
        builder.environment().apply {
            clear()
            putAll(invocation.env)
        }
        val process = builder.start()
        val stopper = ProcessStopper(signals)
        val stop = {
            val pid = ProcessStopper.pidOf(process)
            if (pid != null) stopper.stop(process, pid) else process.destroy()
        }
        val writer = thread(name = "proot-input") {
            try {
                process.outputStream.use { if (stdin != null) it.write(stdin) }
            } catch (_: java.io.IOException) {
                // The process exited without reading everything.
            }
        }
        val output = StringBuilder()
        val reader = thread(name = "proot-output") {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach {
                    synchronized(output) { output.appendLine(it) }
                    onLine(it)
                }
            }
        }
        // Process.waitFor(timeout) needs API 26; minSdk is 24.
        val timedOut = AtomicBoolean(false)
        val watchdog = thread(name = "proot-timeout", isDaemon = true) {
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(timeoutSeconds))
                timedOut.set(true)
                stop()
            } catch (_: InterruptedException) {
            }
        }
        val exitCode = try {
            process.waitFor()
        } catch (e: InterruptedException) {
            watchdog.interrupt()
            stop()
            throw e
        } finally {
            watchdog.interrupt()
        }
        reader.join(TimeUnit.SECONDS.toMillis(5))
        writer.join(TimeUnit.SECONDS.toMillis(1))
        return ProcessResult(
            exitCode = if (timedOut.get()) null else exitCode,
            output = synchronized(output) { output.toString() },
            timedOut = timedOut.get(),
        )
    }
}
