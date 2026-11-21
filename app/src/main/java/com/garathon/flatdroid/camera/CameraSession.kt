package com.garathon.flatdroid.camera

import android.system.Os
import android.system.OsConstants
import com.garathon.flatdroid.session.Sensor
import com.garathon.flatdroid.session.UseAction
import com.garathon.flatdroid.session.UseQuestion
import com.garathon.flatdroid.util.debug.DebugLog
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The cameras of one session whose camera setting is on: the [streamers] (one per node, by node
 * name), and the [events] pipe the camera's helpers write links and readiness to ([CameraScript]).
 * The pipe sits among the nodes' pipes, in a folder only the camera's helpers have, never in the
 * app's root. An app linked to any node uses the camera: the user is asked ([UseQuestion]) unless
 * [alwaysAllowed]; one answer holds for every camera until the app ends. [initialAnswer] is the
 * answer when the user was asked before the launch (notifications off).
 */
class CameraSession(
    private val streamers: Map<String, CameraStreamer>,
    private val events: File,
    alwaysAllowed: Boolean,
    initialAnswer: Boolean? = null,
) {
    private val question = UseQuestion(Sensor.CAMERA, alwaysAllowed, initialAnswer) { action ->
        streamers.values.forEach { it.setAllowed(action == UseAction.USE) }
    }
    private val links = HashMap<String, Int>()
    private val ready = CountDownLatch(1)
    private val reader = Thread(::read, "camera-events").apply { isDaemon = true }
    @Volatile private var closed = false

    /** Before the camera's helpers start: the reader waits for them to open the pipe. */
    fun start() {
        reader.start()
        streamers.values.forEach { it.open() }
    }

    /** Whether the helpers reported the cameras ready within [timeoutMs]. */
    fun awaitReady(timeoutMs: Long): Boolean = ready.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** After the camera's helpers have stopped. */
    fun close() {
        closed = true
        question.stop()
        // A reader still waiting for the helpers to open the pipe (they never got there) stops waiting.
        runCatching { Os.close(Os.open(events.path, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)) }
        reader.join(CLOSE_WAIT_MS)
        streamers.values.forEach { it.close() }
    }

    private fun read() {
        try {
            FileInputStream(events).use { input ->
                val line = StringBuilder()
                val buffer = ByteArray(READ_BYTES)
                while (!closed) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    for (i in 0 until count) {
                        val char = buffer[i].toInt().toChar()
                        if (char == '\n') {
                            onLine(line.toString())
                            line.setLength(0)
                        } else if (line.length < MAX_LINE) {
                            line.append(char)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            if (!closed) DebugLog.w("Camera events: ${e.message}")
        }
    }

    private fun onLine(line: String) {
        if (line == CameraScript.READY) return ready.countDown()
        val (node, count) = CameraScript.users(line) ?: return
        val streamer = streamers[node] ?: return
        links[node] = count
        // The answer first, so a camera allowed already starts as the app links, without a black frame between.
        question.inUse(links.values.any { it > 0 })
        streamer.setUsers(count)
    }

    private companion object {
        const val READ_BYTES = 512
        /** Longer lines are cut: the script's lines are a node's name and a number. */
        const val MAX_LINE = 256
        const val CLOSE_WAIT_MS = 1000L
    }
}
