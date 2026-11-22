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
 * The cameras (and the screen) of one session: the [streamers] (one per node, by node name), and
 * the [events] pipe the camera's helpers write links and readiness to ([CameraScript]). The pipe
 * sits among the nodes' pipes, in a folder only the camera's helpers have, never in the app's
 * root. In device mode ([events] null) there are no helpers: each streamer tells from its device's
 * pipe whether the app reads it. An app linked to a node (reading a device) uses its [Sensor]:
 * the user is asked ([UseQuestion]) about the cameras unless [alwaysAllowed], and about the screen
 * every time; one answer holds for every camera until the app ends, the screen's until its share
 * ends ([screenEnded]). [initialAnswer] is the cameras' answer when the user was asked before the
 * launch (notifications off). [onFirstUse] is called the first time the app uses either, whatever
 * the answer.
 */
class CameraSession(
    private val streamers: Map<String, FrameStreamer>,
    private val events: File?,
    alwaysAllowed: Boolean,
    initialAnswer: Boolean? = null,
    private val onFirstUse: (() -> Unit)? = null,
) {
    private val questions = streamers.values.map { it.sensor }.distinct().associateWith { sensor ->
        UseQuestion(sensor, alwaysAllowed && sensor == Sensor.CAMERA, initialAnswer.takeIf { sensor == Sensor.CAMERA }) { action ->
            streamers.values.filter { it.sensor == sensor }.forEach { it.setAllowed(action == UseAction.USE) }
        }
    }
    private val links = HashMap<String, Int>()
    private var used = false
    private val ready = CountDownLatch(1)
    private val reader = events?.let { Thread({ read(it) }, "camera-events").apply { isDaemon = true } }
    @Volatile private var closed = false

    init {
        for ((node, streamer) in streamers) if (events == null) streamer.onUsers = { count -> linked(node, count) }
    }

    /** Before the camera's helpers start: the reader waits for them to open the pipe. In device mode the cameras are ready at once. */
    fun start() {
        reader?.start() ?: ready.countDown()
        streamers.values.forEach { it.open() }
    }

    /** Whether the helpers reported the cameras ready within [timeoutMs]. */
    fun awaitReady(timeoutMs: Long): Boolean = ready.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** After the camera's helpers have stopped. */
    fun close() {
        closed = true
        questions.values.forEach { it.stop() }
        // A reader still waiting for the helpers to open the pipe (they never got there) stops waiting.
        events?.let { pipe -> runCatching { Os.close(Os.open(pipe.path, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)) } }
        reader?.join(CLOSE_WAIT_MS)
        streamers.values.forEach { it.close() }
    }

    private fun read(events: File) {
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
        linked(node, count)
    }

    /** [count] apps are linked to [node] (in device mode: read it, 1 or 0). From the reader, or each streamer's thread. */
    @Synchronized
    private fun linked(node: String, count: Int) {
        val streamer = streamers[node] ?: return
        links[node] = count
        if (count > 0 && !used) {
            used = true
            onFirstUse?.invoke()
        }
        // The answer first, so a camera allowed already starts as the app links, without a black frame between.
        questions[streamer.sensor]?.inUse(links.any { (name, links) -> links > 0 && streamers[name]?.sensor == streamer.sensor })
        streamer.setUsers(count)
    }

    /** The screen's share ended: the next use asks again. */
    fun screenEnded() {
        questions[Sensor.SCREEN]?.forget()
    }

    private companion object {
        const val READ_BYTES = 512
        /** Longer lines are cut: the script's lines are a node's name and a number. */
        const val MAX_LINE = 256
        const val CLOSE_WAIT_MS = 1000L
    }
}
