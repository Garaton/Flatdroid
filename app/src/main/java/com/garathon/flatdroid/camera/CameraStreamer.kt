package com.garathon.flatdroid.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Range
import com.garathon.flatdroid.BuildConfig
import com.garathon.flatdroid.proot.Fdv4l2
import com.garathon.flatdroid.util.debug.DebugLog
import com.garathon.flatdroid.util.debug.DebugMeter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException

/**
 * One Android camera into its node's pipe ([CameraScript]): JPEG frames at the camera's size and
 * rate, each in one write. [open] writes a black frame, which makes the node appear; Android's
 * camera then runs only while an app is linked to the node ([setUsers]) and the user allowed it
 * ([setAllowed]), so Android's camera indicator shows only meanwhile. Linked but not allowed (not
 * answered yet, or denied), the app gets black frames at the camera's rate, so its stream goes on
 * and real frames follow once it is allowed. When Android takes the camera away (another app, a call) or
 * can't open it, it is tried again whenever Android reports a camera available or a change of
 * who may use cameras, while an app is still linked.
 * Everything runs on the camera's own thread; writing to the pipe
 * blocks it while the source is behind, and the image reader then drops frames. Frames are turned
 * by [rotation] degrees clockwise (upright for the app's display) before they are encoded; when
 * the display turns ([turn]) so do they, changing shape with it.
 *
 * In device mode ([deviceMode]) the pipe is the app's `/dev/video*` ([com.garathon.flatdroid.proot.Fdv4l2]),
 * read by the app itself, and nothing reports links: the pipe tells. This end is opened to read and
 * write, so neither side waits for the other; a frame is written only into an empty pipe (the app
 * gets the newest, and none is encoded for nothing), and one always waits there. The pipe
 * emptied means the app reads it ([onUsers] 1); a frame left in it for [NOT_READ_MS] means it
 * stopped (0). Opening the device to list it reads nothing, so it isn't use. The size stays as it
 * started: a V4L2 app fixes it when it starts streaming.
 */
class CameraStreamer(
    private val context: Context,
    private val camera: AndroidCameras.Camera,
    private val pipe: File,
    private var rotation: Int = 0,
    private val deviceMode: Boolean = false,
    /** Device mode: the pipe through which the V4L2 library says which format the app chose. */
    private val control: File? = null,
) {
    /** Device mode: whether the app reads its device (1) or not (0), on this streamer's thread, each time it changes. */
    @Volatile var onUsers: ((Int) -> Unit)? = null
    private val outWidth get() = camera.turnedSize(rotation).first
    private val outHeight get() = camera.turnedSize(rotation).second
    private val turned by lazy { ByteArray(Nv21.bytes(camera.width, camera.height)) }
    private val jpeg = ByteArrayOutputStream()
    private val part = Part()
    private val thread = HandlerThread("camera-${camera.id}").apply { start() }
    private val handler = Handler(thread.looper)
    private val manager = context.getSystemService(CameraManager::class.java)
    // Any camera, not only this one: an app that took another camera can still have this one
    // shut out, and only reports its own camera once it closes; when it leaves the foreground,
    // Android reports the change of priorities instead.
    private val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) = retry()

        override fun onCameraAccessPrioritiesChanged() = retry()
    }
    private val frame = ByteArray(Nv21.bytes(camera.width, camera.height))
    private val row = ByteArray(camera.width)
    private var output: FileOutputStream? = null
    /** Device mode: this end of the device's pipe, and of the control pipe. */
    private var deviceEnd: FileDescriptor? = null
    private var controlEnd: FileDescriptor? = null
    /**
     * Device mode: what the app takes, as the library tells at stream-on. Motion-JPEG before: the
     * black frame waiting for the first read must fit the pipe, and a raw one doesn't.
     */
    private var format = Fdv4l2.Format.MJPEG
    private val controlBytes = ByteArray(CONTROL_BYTES)
    /** Device mode: whether the app reads the device, when the pipe was last seen empty, and when a frame was last written. */
    private var reading = false
    private var lastEmpty = 0L
    private var lastWrite = 0L
    private val watch = object : Runnable {
        override fun run() {
            watchDevice()
            handler.postDelayed(this, WATCH_MS)
        }
    }
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    /** An openCamera() not answered yet: one at a time, even when the app relinks meanwhile. */
    private var opening = false
    private var order: Nv21.Order? = null
    private var users = 0
    private var allowed = false
    /** A black frame with its part header, at the size and in the format it was made for (the size changes when the display turns). */
    private var black: Pair<Triple<Int, Int, Fdv4l2.Format>, ByteArray>? = null
    private val blackFrames = object : Runnable {
        override fun run() {
            if (output == null && deviceEnd == null) return
            try {
                // In device mode not while the app hasn't taken the last one; the next turn tries again.
                send(blackFrame())
            } catch (e: IOException) {
                if (!closed) DebugLog.w("Camera ${camera.id}: ${e.message}")
                return
            }
            handler.postDelayed(this, 1000L / camera.frameRate)
        }
    }
    /** The camera opened but couldn't be set up: closing it makes Android report it available, which isn't a reason to try again. */
    private var failedSetup = false
    /**
     * Debug builds only: frames written, frames Android's camera captured (more means each
     * frame's work is the limit), and each step's time per frame.
     */
    private val meter = DebugMeter("Camera ${camera.id}", "frames", listOf("pack", "turn", "JPEG", "write"), counted = "captured")
    @Volatile private var closed = false

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
                    .onFailure { DebugLog.w("Camera ${camera.id}: pipe size: ${it.message}") }
            }
            stream.write(blackFrame())
            meter.reset()
            manager?.registerAvailabilityCallback(availability, handler)
        } catch (e: IOException) {
            DebugLog.w("Camera ${camera.id}: ${e.message}")
        }
    }

    /**
     * Frames are turned by [degrees] from now on. The JPEG in each says its size, so an app decoding
     * them follows; the node goes on announcing the size it started with (an app that trusts
     * that instead of the frames: verify).
     */
    fun turn(degrees: Int) = handler.post { rotation = degrees }

    /** How many links lead from the node: Android's camera runs while there are any, once allowed. */
    fun setUsers(count: Int) = handler.post {
        if (closed || count == users) return@post
        users = count
        failedSetup = false
        update()
    }

    /** Whether the user allowed the app the camera, for now. */
    fun setAllowed(on: Boolean) = handler.post {
        if (closed || on == allowed) return@post
        allowed = on
        update()
    }

    /** Android's camera while linked and allowed, black frames while linked and not, nothing while not linked. */
    private fun update() {
        handler.removeCallbacks(blackFrames)
        when {
            users > 0 && allowed -> start()
            users > 0 -> {
                stopCamera()
                blackFrames.run()
            }
            else -> stopCamera()
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
            manager?.unregisterAvailabilityCallback(availability)
            stopCamera()
            runCatching { output?.close() }
            output = null
            deviceEnd?.let { runCatching { Os.close(it) } }
            deviceEnd = null
            controlEnd?.let { runCatching { Os.close(it) } }
            controlEnd = null
            thread.quitSafely()
        }
    }

    private fun openDevice() {
        try {
            val end = Os.open(pipe.path, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
            deviceEnd = end
            control?.let { controlEnd = Os.open(it.path, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { Os.fcntlInt(end, F_SETPIPE_SZ, CameraScript.PIPE_BYTES) }
                    .onFailure { DebugLog.w("Camera ${camera.id}: pipe size: ${it.message}") }
            }
            meter.reset()
            manager?.registerAvailabilityCallback(availability, handler)
            handler.postDelayed(watch, WATCH_MS)
            // The frame the app's first read takes, which shows it reads.
            send(blackFrame())
        } catch (e: Exception) {
            DebugLog.w("Camera ${camera.id}: ${e.message}")
        }
    }

    /** Device mode, every [WATCH_MS]: whether the app reads, and a black frame when none came for a while (Android's camera lost), so one waits. */
    private fun watchDevice() {
        readControl()
        val empty = pipeEmpty()
        val now = SystemClock.uptimeMillis()
        val reads = lastEmpty > 0 && now - lastEmpty < NOT_READ_MS
        if (reads != reading) {
            reading = reads
            onUsers?.invoke(if (reads) 1 else 0)
        }
        if (empty && now - lastWrite > REFILL_MS) runCatching { send(blackFrame()) }
    }

    /** Device mode: the format the app chose since the last look, if it said; the frame waiting is then dropped for one in it. */
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
        val chosen = Fdv4l2.chosenFormat(text) ?: return
        if (chosen == format) return
        format = chosen
        DebugLog.i("Camera ${camera.id}: the app takes ${chosen.id}")
    }

    /** Device mode: nothing waits in the pipe (the app took the last frame, once one was written). */
    private fun pipeEmpty(): Boolean {
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

    @SuppressLint("MissingPermission") // allowed only with Android's permission (asked at Allow); refused, openCamera throws, caught below
    private fun start() {
        val manager = manager ?: return
        if (device != null || opening) return
        opening = true
        try {
            manager.openCamera(camera.id, object : CameraDevice.StateCallback() {
                override fun onOpened(opened: CameraDevice) {
                    opening = false
                    if (closed || users == 0) return opened.close()
                    device = opened
                    // Made only now: a reader made with an earlier open could be closed already.
                    val imageReader = ImageReader.newInstance(camera.width, camera.height, ImageFormat.YUV_420_888, 3)
                    reader = imageReader
                    imageReader.setOnImageAvailableListener({ onImage(it) }, handler)
                    try {
                        capture(manager, opened, imageReader)
                    } catch (e: Exception) {
                        // Taken back between opening and configuring: as when Android reports it.
                        lost(opened, e.message ?: e.toString(), setupFailed = true)
                    }
                }

                override fun onDisconnected(opened: CameraDevice) = lost(opened, "Android took it back")

                override fun onError(opened: CameraDevice, error: Int) = lost(opened, "error $error")
            }, handler)
            DebugLog.i("Camera ${camera.id}: on (${camera.width}x${camera.height})")
        } catch (e: Exception) {
            // In use or disabled: tried again once Android reports it available.
            opening = false
            DebugLog.w("Camera ${camera.id}: ${e.message}")
        }
    }

    /** After Android took the camera away or couldn't open it, while an app is still linked. */
    private fun retry() {
        if (!closed && users > 0 && allowed && !failedSetup) start()
    }

    /**
     * Android closed [opened], or couldn't open it: the app keeps the last frame until it opens again.
     * After a [setupFailed] the camera stays shut until the app links again, since the same setup would fail again.
     */
    private fun lost(opened: CameraDevice, why: String, setupFailed: Boolean = false) {
        opening = false
        if (setupFailed) failedSetup = true
        opened.close()
        if (device == opened) stopCamera()
        DebugLog.w("Camera ${camera.id}: $why; " + if (setupFailed) "tried again when the app links again" else "tried again once Android reports a camera available")
    }

    private fun capture(manager: CameraManager, opened: CameraDevice, imageReader: ImageReader) {
        val request = opened.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(imageReader.surface)
            fpsRange(manager)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }.build()
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                if (device != opened) return configured.close()
                session = configured
                try {
                    configured.setRepeatingRequest(request, if (BuildConfig.DEBUG) captured else null, handler)
                } catch (e: Exception) {
                    lost(opened, e.message ?: e.toString(), setupFailed = true)
                }
            }

            override fun onConfigureFailed(configured: CameraCaptureSession) {
                // Closed as when Android takes it back, so the camera (and its indicator) isn't held with no frames (verify: hard to time).
                configured.close()
                lost(opened, "the capture session couldn't be configured", setupFailed = true)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // The request also as the session's parameters: cameras that take the frame rate as a
            // session key pick their sensor mode only here (at 30 frames/s without it, whatever
            // the repeating request asks).
            opened.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(imageReader.surface)),
                    { handler.post(it) }, callback,
                ).apply { sessionParameters = request }
            )
        } else {
            @Suppress("DEPRECATION") // the only session API before Android 9
            opened.createCaptureSession(listOf(imageReader.surface), callback, handler)
        }
    }

    /** The camera's frame rate if it offers a range reaching it, preferring a fixed one. */
    private fun fpsRange(manager: CameraManager): Range<Int>? =
        manager.getCameraCharacteristics(camera.id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper == camera.frameRate }?.maxByOrNull { it.lower }

    private val captured = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) = meter.count()
    }

    private fun onImage(imageReader: ImageReader) {
        val image = imageReader.acquireLatestImage() ?: return
        try {
            if (output == null && deviceEnd == null) return
            // Device mode: the app hasn't taken the last frame yet, so this one isn't encoded.
            if (deviceEnd != null && !pipeEmpty()) return
            val (y, u, v) = image.planes
            if (order == null && v.pixelStride == 2) order = Nv21.order(u.buffer, v.buffer)
            meter.start()
            Nv21.pack(
                camera.width, camera.height, y.buffer, y.rowStride,
                u.buffer, v.buffer, u.rowStride, u.pixelStride, order, frame, row,
                into = if (format == Fdv4l2.Format.NV12) Nv21.Order.UV else Nv21.Order.VU,
            )
            meter.step(0)
            if (rotation != 0) Nv21.rotate(frame, camera.width, camera.height, rotation, turned)
            meter.step(1)
            writeFrame(if (rotation != 0) turned else frame)
            meter.round()
        } catch (e: IOException) {
            if (!closed) DebugLog.w("Camera ${camera.id}: ${e.message}")
        } finally {
            image.close()
        }
    }

    /** [nv21] ([outWidth]×[outHeight]; NV12 when the app takes that) as JPEG, or as it is, with its part header. */
    private fun writeFrame(nv21: ByteArray) {
        if (format == Fdv4l2.Format.NV12) {
            meter.step(2)
            val size = Nv21.bytes(outWidth, outHeight)
            send(CameraScript.frameHeader(size, format.contentType), nv21, size)
        } else {
            encode(nv21)
            meter.step(2)
            send(part.bytes(), part.size())
        }
        meter.step(3)
    }

    /** [nv21] ([outWidth]×[outHeight]) as JPEG with its part header, into [part]. */
    private fun encode(nv21: ByteArray) {
        jpeg.reset()
        YuvImage(nv21, ImageFormat.NV21, outWidth, outHeight, null).compressToJpeg(Rect(0, 0, outWidth, outHeight), JPEG_QUALITY, jpeg)
        part.reset()
        part.write(CameraScript.frameHeader(jpeg.size()))
        jpeg.writeTo(part)
        part.write(CameraScript.FRAME_END)
    }

    /** A black frame at the current size and format, made once for each. */
    private fun blackFrame(): ByteArray {
        val key = Triple(outWidth, outHeight, format)
        black?.let { (made, bytes) -> if (made == key) return bytes }
        val nv21 = Nv21.black(outWidth, outHeight)
        val bytes = if (format == Fdv4l2.Format.NV12) {
            // Black is the same in either chroma order.
            CameraScript.frameHeader(nv21.size, format.contentType) + nv21 + CameraScript.FRAME_END
        } else {
            encode(nv21)
            part.toByteArray()
        }
        return bytes.also { black = key to it }
    }

    private fun stopCamera() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        if (device != null) DebugLog.i("Camera ${camera.id}: off")
        session = null
        device = null
        reader = null
        // The next count starts with the camera, not with this run or the black frame.
        meter.reset()
    }

    /** A ByteArrayOutputStream whose bytes are written without a copy. */
    private class Part : ByteArrayOutputStream() {
        fun bytes(): ByteArray = buf
    }

    private companion object {
        /** gst's jpegenc default. */
        const val JPEG_QUALITY = 85
        /** Device mode: how often the pipe is looked at, how long a frame left in it means the app stopped reading, and after how long without a frame a black one is written. */
        const val WATCH_MS = 100L
        const val NOT_READ_MS = 1000L
        const val REFILL_MS = 500L
        const val PARTIAL_WAIT_MS = 500L
        const val CONTROL_BYTES = 256
        /** Linux's fcntl to resize a pipe; not in OsConstants. */
        const val F_SETPIPE_SZ = 1031
    }
}
