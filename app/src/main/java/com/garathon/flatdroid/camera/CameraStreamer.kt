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
import android.system.Os
import android.system.OsConstants
import android.util.Range
import com.garathon.flatdroid.BuildConfig
import com.garathon.flatdroid.util.DebugLog
import com.garathon.flatdroid.util.DebugMeter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * One Android camera into its node's pipe ([CameraScript]): JPEG frames at the camera's size and
 * rate, each in one write. [open] writes a black frame, which makes the node appear; Android's
 * camera then runs only while an app is linked to the node ([setUsers]), so Android's camera
 * indicator shows only meanwhile. When Android takes the camera away (another app, a call) or
 * can't open it, it is tried again whenever Android reports a camera available or a change of
 * who may use cameras, while an app is still linked.
 * Everything runs on the camera's own thread; writing to the pipe
 * blocks it while the source is behind, and the image reader then drops frames. Frames are turned
 * by [rotation] degrees clockwise (upright for the app's display) before they are encoded; when
 * the display turns ([turn]) so do they, changing shape with it.
 */
class CameraStreamer(private val context: Context, private val camera: AndroidCameras.Camera, private val pipe: File, private var rotation: Int = 0) {
    private val outWidth get() = camera.turnedSize(rotation).first
    private val outHeight get() = camera.turnedSize(rotation).second
    private val turned by lazy { ByteArray(Nv21.bytes(camera.width, camera.height)) }
    private val jpeg = ByteArrayOutputStream()
    private val part = ByteArrayOutputStream()
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
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    /** An openCamera() not answered yet: one at a time, even when the app relinks meanwhile. */
    private var opening = false
    private var order: Nv21.Order? = null
    private var users = 0
    /**
     * Debug builds only: frames written, frames Android's camera captured (more means each
     * frame's work is the limit), and each step's time per frame.
     */
    private val meter = DebugMeter("Camera ${camera.id}", "frames", listOf("pack", "turn", "JPEG", "write"), counted = "captured")
    @Volatile private var closed = false

    /** Opens the pipe (once the source reads it) and writes the black frame. */
    fun open() = handler.post {
        try {
            val stream = FileOutputStream(pipe)
            output = stream
            // A frame then fits the pipe whole, so the source reads it at once (fewer reads through
            // proot). Os.fcntlInt is public from Android 11; before, the pipe keeps its size.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { Os.fcntlInt(stream.fd, F_SETPIPE_SZ, CameraScript.PIPE_BYTES) }
                    .onFailure { DebugLog.w("Camera ${camera.id}: pipe size: ${it.message}") }
            }
            writeFrame(stream, Nv21.black(outWidth, outHeight))
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

    /** How many links lead from the node: Android's camera runs while there are any. */
    fun setUsers(count: Int) = handler.post {
        if (closed || count == users) return@post
        users = count
        if (count > 0) start() else stopCamera()
    }

    /** After the camera's helpers have stopped, so a write still waiting for the source fails. */
    fun close() {
        closed = true
        // An open() still waiting for a reader (the source never started) goes on, then fails to write.
        runCatching { Os.close(Os.open(pipe.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)) }
        handler.post {
            manager?.unregisterAvailabilityCallback(availability)
            stopCamera()
            runCatching { output?.close() }
            output = null
            thread.quitSafely()
        }
    }

    @SuppressLint("MissingPermission") // checked before the session starts the camera's helpers
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
                    capture(manager, opened, imageReader)
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
        if (!closed && users > 0) start()
    }

    /** Android closed [opened], or couldn't open it: the app keeps the last frame until it opens again. */
    private fun lost(opened: CameraDevice, why: String) {
        opening = false
        opened.close()
        if (device == opened) stopCamera()
        DebugLog.w("Camera ${camera.id}: $why; tried again once Android reports a camera available")
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
                configured.setRepeatingRequest(request, if (BuildConfig.DEBUG) captured else null, handler)
            }

            override fun onConfigureFailed(configured: CameraCaptureSession) {
                DebugLog.w("Camera ${camera.id}: the capture session couldn't be configured")
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
            val stream = output ?: return
            val (y, u, v) = image.planes
            if (order == null && v.pixelStride == 2) order = Nv21.order(u.buffer, v.buffer)
            meter.start()
            Nv21.pack(
                camera.width, camera.height, y.buffer, y.rowStride,
                u.buffer, v.buffer, u.rowStride, u.pixelStride, order, frame, row,
            )
            meter.step(0)
            if (rotation != 0) Nv21.rotate(frame, camera.width, camera.height, rotation, turned)
            meter.step(1)
            writeFrame(stream, if (rotation != 0) turned else frame)
            meter.round()
        } catch (e: IOException) {
            if (!closed) DebugLog.w("Camera ${camera.id}: ${e.message}")
        } finally {
            image.close()
        }
    }

    /** [nv21] ([outWidth]×[outHeight]) as JPEG, with its part header, in one write. */
    private fun writeFrame(stream: FileOutputStream, nv21: ByteArray) {
        jpeg.reset()
        YuvImage(nv21, ImageFormat.NV21, outWidth, outHeight, null).compressToJpeg(Rect(0, 0, outWidth, outHeight), JPEG_QUALITY, jpeg)
        meter.step(2)
        part.reset()
        part.write(CameraScript.frameHeader(jpeg.size()))
        jpeg.writeTo(part)
        part.write(CameraScript.FRAME_END)
        part.writeTo(stream)
        meter.step(3)
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

    private companion object {
        /** gst's jpegenc default. */
        const val JPEG_QUALITY = 85
        /** Linux's fcntl to resize a pipe; not in OsConstants. */
        const val F_SETPIPE_SZ = 1031
    }
}
