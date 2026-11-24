package com.garathon.flatdroid.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.garathon.flatdroid.proot.Fdv4l2
import com.garathon.flatdroid.session.Sensor
import com.garathon.flatdroid.util.debug.DebugLog
import com.garathon.flatdroid.util.debug.DebugMeter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException

/**
 * One source of frames (an Android camera, [CameraStreamer], or the screen, [ScreenStreamer]) into
 * its node's pipe ([CameraScript]): JPEG frames, each in one write. [open] writes a black frame,
 * which makes the node appear; the source then runs only while an app is linked to the node
 * ([setUsers]) and the user allowed it ([setAllowed]). Linked but not allowed (not answered yet, or
 * denied), the app gets black frames at the node's rate, so its stream goes on and real frames
 * follow once it is allowed. Everything runs on the source's own thread; writing to the pipe blocks
 * it while the node's source is behind, and the source then drops frames.
 *
 * In device mode ([deviceMode]) the pipe is the app's `/dev/video*` ([Fdv4l2]), read by the app
 * itself. This end is opened to read and write, so neither side waits for the other; a frame is
 * written only into an empty pipe (the app gets the newest, and none is encoded for nothing), and
 * one always waits there ([refill]). The library says on the control pipe when the app starts
 * and stops streaming: in between the app uses the device ([onUsers] 1, 0 after), however slowly
 * it reads (an app encoding a recording can pause for seconds), unless it read nothing for
 * [STALE_MS] (paused or killed). Before the library said either, the pipe tells: emptied means the
 * app reads it, a frame left in it for [NOT_READ_MS] means it stopped. Opening the device to list
 * it isn't use. The size stays as it started: a V4L2 app fixes it when it starts streaming.
 */
abstract class FrameStreamer(
    private val pipe: File,
    /** For the logs: "Camera 0", "Screen". */
    protected val label: String,
    protected val deviceMode: Boolean,
    /** Device mode: the pipe through which the V4L2 library says which format the app chose. */
    private val control: File?,
    /**
     * Device mode: the media device's pipe. The library answers its calls, but an app opening it
     * read-only waits until a writer has it open (GStreamer's plugin scanner did, forever).
     */
    private val media: File?,
    threadName: String,
) {
    /** Who is asked when an app uses this source. */
    abstract val sensor: Sensor
    /** Device mode: whether the app reads its device (1) or not (0), on this streamer's thread, each time it changes. */
    @Volatile var onUsers: ((Int) -> Unit)? = null
    /** The frames' size now, and how many a second the node announces. */
    protected abstract val outWidth: Int
    protected abstract val outHeight: Int
    protected abstract val frameRate: Int
    private val thread = HandlerThread(threadName).apply { start() }
    protected val handler = Handler(thread.looper)
    private val jpeg = ByteArrayOutputStream()
    private val part = Part()
    private var output: FileOutputStream? = null
    /** Device mode: this end of the device's pipe, of the control pipe and of the media device's. */
    private var deviceEnd: FileDescriptor? = null
    private var controlEnd: FileDescriptor? = null
    private var mediaEnd: FileDescriptor? = null
    /**
     * Device mode: what the app takes, as the library tells at stream-on. Motion-JPEG before: the
     * black frame waiting for the first read must fit the pipe, and a raw one doesn't.
     */
    protected var format = Fdv4l2.Format.MJPEG
        private set
    private val controlBytes = ByteArray(CONTROL_BYTES)
    /** Device mode: whether the app uses the device, and when the pipe was last seen empty. When a frame was last written. */
    private var reading = false
    private var lastEmpty = 0L
    /** Device mode: whether the app streams, as the library last said (null: it hasn't), and since when. */
    private var streaming: Boolean? = null
    private var streamingSince = 0L
    private var lastWrite = 0L
    /** What [repeatLast] writes again: the last part encoded ([part]), or the last raw frame (unchanged until the source's next frame). */
    private var lastIsPart = false
    private var lastRaw: ByteArray? = null
    private val watch = object : Runnable {
        override fun run() {
            watchDevice()
            handler.postDelayed(this, WATCH_MS)
        }
    }
    protected var users = 0
        private set
    protected var allowed = false
        private set
    protected var paused = false
        private set
    /** A black frame with its part header, at the size and in the format it was made for (the size changes when the display turns). */
    private var black: Pair<Triple<Int, Int, Fdv4l2.Format>, ByteArray>? = null
    private val blackFrames = object : Runnable {
        override fun run() {
            if (output == null && deviceEnd == null) return
            try {
                // In device mode not while the app hasn't taken the last one; the next turn tries again.
                sendBlack()
            } catch (e: IOException) {
                if (!closed) DebugLog.w("$label: ${e.message}")
                return
            }
            handler.postDelayed(this, 1000L / frameRate)
        }
    }
    /**
     * Debug builds only: frames written, frames the source gave (more means each frame's work is
     * the limit), and each step's time per frame.
     */
    protected val meter = DebugMeter(label, "frames", listOf("pack", "turn", "JPEG", "write"), counted = "captured")
    @Volatile protected var closed = false
        private set

    /** Starts the source, while an app is linked and allowed; it may already run. */
    protected abstract fun startSource()

    /** Stops the source, if it runs. */
    protected abstract fun stopSource()

    /** On the thread, once the pipe is open. */
    protected open fun opened() {}

    /** On the thread, before the source stops for good. */
    protected open fun closing() {}

    /** On the thread, when the number of links changed ([setUsers]), before the source follows. */
    protected open fun usersChanged() {}

    /** Device mode: the frame put into an empty pipe when none came for [REFILL_MS], so one waits for the app. */
    protected open fun refill() {
        sendBlack()
    }

    /** Opens the pipe (once the source reads it; in device mode at once) and writes the black frame. */
    fun open() = handler.post {
        if (deviceMode) return@post openDevice()
        try {
            val stream = FileOutputStream(pipe)
            output = stream
            // A frame then fits the pipe whole, so the source reads it at once (fewer reads through
            // proot). Os.fcntlInt is public from Android 11; before, the pipe keeps its size.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { Os.fcntlInt(stream.fd, F_SETPIPE_SZ, CameraScript.PIPE_BYTES) }
                    .onFailure { DebugLog.w("$label: pipe size: ${it.message}") }
            }
            sendBlack()
            meter.reset()
            opened()
        } catch (e: IOException) {
            DebugLog.w("$label: ${e.message}")
        }
    }

    /** How many links lead from the node: the source runs while there are any, once allowed. */
    fun setUsers(count: Int) = handler.post {
        if (closed || count == users) return@post
        users = count
        usersChanged()
        update()
    }

    /** Whether the user allowed the app this source, for now. */
    fun setAllowed(on: Boolean) = handler.post {
        if (closed || on == allowed) return@post
        allowed = on
        update()
    }

    /** The app is paused: it takes no frames, so the source stops, linked or not, until it goes on. */
    fun setPaused(on: Boolean) = handler.post {
        if (closed || on == paused) return@post
        paused = on
        update()
    }

    /** The source while linked and allowed, black frames while linked and not, nothing while not linked or paused. */
    private fun update() {
        handler.removeCallbacks(blackFrames)
        when {
            paused -> stopSource()
            users > 0 && allowed -> startSource()
            users > 0 -> {
                stopSource()
                blackFrames.run()
            }
            else -> stopSource()
        }
    }

    /** After the camera's helpers have stopped, so a write still waiting for the source fails. */
    fun close() {
        closed = true
        // An open() still waiting for a reader (the source never started) goes on, then fails to write.
        if (!deviceMode) runCatching { Os.close(Os.open(pipe.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)) }
        handler.post {
            handler.removeCallbacks(blackFrames)
            handler.removeCallbacks(watch)
            closing()
            stopSource()
            runCatching { output?.close() }
            output = null
            deviceEnd?.let { runCatching { Os.close(it) } }
            deviceEnd = null
            controlEnd?.let { runCatching { Os.close(it) } }
            controlEnd = null
            mediaEnd?.let { runCatching { Os.close(it) } }
            mediaEnd = null
            thread.quitSafely()
        }
    }

    private fun openDevice() {
        try {
            val end = Os.open(pipe.path, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
            deviceEnd = end
            control?.let { controlEnd = Os.open(it.path, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0) }
            media?.let { mediaEnd = Os.open(it.path, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { Os.fcntlInt(end, F_SETPIPE_SZ, CameraScript.PIPE_BYTES) }
                    .onFailure { DebugLog.w("$label: pipe size: ${it.message}") }
            }
            meter.reset()
            opened()
            handler.postDelayed(watch, WATCH_MS)
            // The frame the app's first read takes, which shows it reads.
            sendBlack()
        } catch (e: Exception) {
            DebugLog.w("$label: ${e.message}")
        }
    }

    /** Device mode, every [WATCH_MS]: whether the app uses the device, and a [refill] when no frame came for a while, so one waits. */
    private fun watchDevice() {
        readControl()
        val empty = pipeEmpty()
        val now = SystemClock.uptimeMillis()
        val reads = when (streaming) {
            true -> now - maxOf(lastEmpty, streamingSince) < STALE_MS
            false -> false
            null -> lastEmpty > 0 && now - lastEmpty < NOT_READ_MS
        }
        if (reads != reading) {
            reading = reads
            onUsers?.invoke(if (reads) 1 else 0)
        }
        if (empty && now - lastWrite > REFILL_MS) runCatching { refill() }
    }

    /**
     * Device mode: whether the app started or stopped streaming, and the format it chose, since the
     * last look, if the library said; a frame waiting in another format is then dropped for one in it.
     */
    private fun readControl() {
        val end = controlEnd ?: return
        var text = ""
        while (true) {
            val count = try {
                Os.read(end, controlBytes, 0, controlBytes.size)
            } catch (_: ErrnoException) {
                0
            }
            if (count <= 0) break
            // Only the last lines count; an app writing much here only spoils its own camera.
            text = (text + String(controlBytes, 0, count, Charsets.US_ASCII)).takeLast(CONTROL_BYTES)
        }
        Fdv4l2.streaming(text)?.let { now ->
            if (now && streaming != true) streamingSince = SystemClock.uptimeMillis()
            if (now != streaming) DebugLog.i("$label: the app ${if (now) "streams" else "stopped streaming"}")
            streaming = now
        }
        val chosen = Fdv4l2.chosenFormat(text) ?: return
        if (chosen == format) return
        format = chosen
        // The last frame is in the other format.
        lastIsPart = false
        lastRaw = null
        DebugLog.i("$label: the app takes ${chosen.id}")
    }

    /** Device mode: nothing waits in the pipe (the app took the last frame, once one was written). */
    protected fun pipeEmpty(): Boolean {
        val end = deviceEnd ?: return false
        val poll = StructPollfd().apply {
            fd = end
            events = OsConstants.POLLIN.toShort()
        }
        val empty = try {
            Os.poll(arrayOf(poll), 0) == 0
        } catch (_: ErrnoException) {
            false
        }
        // Empty before the first frame went in says nothing about the app.
        if (empty && lastWrite > 0) lastEmpty = SystemClock.uptimeMillis()
        return empty
    }

    /** Whether a frame written now would go out: the pipe is open and, in device mode, empty. */
    protected fun wantsFrame(): Boolean = if (deviceEnd != null) pipeEmpty() else output != null

    /** Milliseconds since a frame was last written. */
    protected fun sinceLastWrite(): Long = SystemClock.uptimeMillis() - lastWrite

    /**
     * Writes a frame ([bytes], with its part header). In device mode only into an empty pipe (false
     * when not), whole: the pipe holds at most 1 MB (on Android 10 and earlier 64 KB), less than a raw
     * frame, and the rest goes as the app reads, for at most [PARTIAL_WAIT_MS].
     */
    private fun send(bytes: ByteArray, count: Int = bytes.size): Boolean {
        val end = deviceEnd
        if (end == null) {
            val stream = output ?: return false
            stream.write(bytes, 0, count)
            lastWrite = SystemClock.uptimeMillis()
            return true
        }
        if (!pipeEmpty()) return false
        lastWrite = SystemClock.uptimeMillis()
        writeAll(end, bytes, count, lastWrite + PARTIAL_WAIT_MS)
        return true
    }

    /** Device mode: a raw frame, [header], the first [count] bytes of [body] and the part's end, without copying them together. */
    private fun send(header: ByteArray, body: ByteArray, count: Int): Boolean {
        val end = deviceEnd ?: return false
        if (!pipeEmpty()) return false
        lastWrite = SystemClock.uptimeMillis()
        val until = lastWrite + PARTIAL_WAIT_MS
        writeAll(end, header, header.size, until)
        writeAll(end, body, count, until)
        writeAll(end, CameraScript.FRAME_END, CameraScript.FRAME_END.size, until)
        return true
    }

    /** The first [count] bytes of [bytes], waiting for room as the app reads, until [until]. */
    private fun writeAll(end: FileDescriptor, bytes: ByteArray, count: Int, until: Long) {
        var at = 0
        while (at < count) {
            try {
                at += Os.write(end, bytes, at, count - at)
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EAGAIN) throw IOException(e)
                val left = until - SystemClock.uptimeMillis()
                if (left <= 0) throw IOException("the app stopped reading in the middle of a frame")
                val poll = StructPollfd().apply {
                    fd = end
                    events = OsConstants.POLLOUT.toShort()
                }
                runCatching { Os.poll(arrayOf(poll), left.toInt()) }
            }
        }
    }

    /**
     * [nv21] ([outWidth]×[outHeight]; NV12 when the app takes that) as JPEG, or as it is, with its
     * part header. Meter steps 2 and 3. The source keeps a raw frame unchanged until its next.
     */
    protected fun writeFrame(nv21: ByteArray) {
        if (format == Fdv4l2.Format.NV12) {
            meter.step(2)
            val size = Nv21.bytes(outWidth, outHeight)
            if (send(CameraScript.frameHeader(size, format.contentType), nv21, size)) {
                lastRaw = nv21
                lastIsPart = false
            }
        } else {
            encode(nv21)
            meter.step(2)
            if (send(part.bytes(), part.size())) {
                lastIsPart = true
                lastRaw = null
            }
        }
        meter.step(3)
    }

    /** The frame written last again, as it was; false when there is none (or the pipe is full). */
    protected fun repeatLast(): Boolean {
        val raw = lastRaw
        return when {
            lastIsPart -> send(part.bytes(), part.size())
            raw != null -> send(CameraScript.frameHeader(Nv21.bytes(outWidth, outHeight), format.contentType), raw, Nv21.bytes(outWidth, outHeight))
            else -> false
        }
    }

    /** [nv21] ([outWidth]×[outHeight]) as JPEG with its part header, into [part]. */
    private fun encode(nv21: ByteArray) {
        jpeg.reset()
        YuvImage(nv21, ImageFormat.NV21, outWidth, outHeight, null).compressToJpeg(Rect(0, 0, outWidth, outHeight), JPEG_QUALITY, jpeg)
        wrap()
    }

    /** [jpeg] with its part header, into [part]. */
    private fun wrap() {
        part.reset()
        part.write(CameraScript.frameHeader(jpeg.size()))
        jpeg.writeTo(part)
        part.write(CameraScript.FRAME_END)
    }

    /** A black frame at the current size and format, made once for each; after it there is no last frame to repeat. */
    private fun sendBlack() {
        val key = Triple(outWidth, outHeight, format)
        val bytes = black?.takeIf { it.first == key }?.second ?: run {
            val nv21 = Nv21.black(outWidth, outHeight)
            if (format == Fdv4l2.Format.NV12) {
                // Black is the same in either chroma order.
                CameraScript.frameHeader(nv21.size, format.contentType) + nv21 + CameraScript.FRAME_END
            } else {
                encode(nv21)
                lastIsPart = false
                part.toByteArray()
            }.also { black = key to it }
        }
        if (send(bytes)) {
            lastIsPart = false
            lastRaw = null
        }
    }

    /** A ByteArrayOutputStream whose bytes are written without a copy. */
    private class Part : ByteArrayOutputStream() {
        fun bytes(): ByteArray = buf
    }

    protected companion object {
        /** gst's jpegenc default. */
        const val JPEG_QUALITY = 85
        /**
         * Device mode: how often the pipe is looked at, how long a frame left in it means the app
         * stopped reading (before the library said it streams; while it streams, [STALE_MS]), and
         * after how long without a frame one is put in.
         */
        const val WATCH_MS = 100L
        const val NOT_READ_MS = 1000L
        const val STALE_MS = 30_000L
        const val REFILL_MS = 500L
        const val PARTIAL_WAIT_MS = 500L
        const val CONTROL_BYTES = 256
        /** Linux's fcntl to resize a pipe; not in OsConstants. */
        const val F_SETPIPE_SZ = 1031
    }
}
