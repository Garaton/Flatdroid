package com.garathon.flatdroid.proot

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class ProcessResult(val exitCode: Int?, val output: String, val timedOut: Boolean)

/** Runs a [ProotInvocation] from a cleared environment and collects stdout and stderr together. */
object ProotRunner {
    fun run(invocation: ProotInvocation, workDir: File, timeoutSeconds: Long = 60): ProcessResult {
        val builder = ProcessBuilder(invocation.argv)
            .directory(workDir)
            .redirectErrorStream(true)
        builder.environment().apply {
            clear()
            putAll(invocation.env)
        }
        val process = builder.start()
        process.outputStream.close()
        val output = StringBuilder()
        val reader = thread(name = "proot-output") {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { synchronized(output) { output.appendLine(it) } }
            }
        }
        // Process.waitFor(timeout) and destroyForcibly() need API 26; minSdk is 24.
        // destroy() signals proot, and --kill-on-exit takes its tracees down with it.
        val timedOut = AtomicBoolean(false)
        val watchdog = thread(name = "proot-timeout", isDaemon = true) {
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(timeoutSeconds))
                timedOut.set(true)
                process.destroy()
            } catch (_: InterruptedException) {
            }
        }
        val exitCode = process.waitFor()
        watchdog.interrupt()
        reader.join(TimeUnit.SECONDS.toMillis(5))
        return ProcessResult(
            exitCode = if (timedOut.get()) null else exitCode,
            output = synchronized(output) { output.toString() },
            timedOut = timedOut.get(),
        )
    }
}
