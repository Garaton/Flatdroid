package com.garathon.flatdroid.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
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
import android.util.Range
import com.garathon.flatdroid.BuildConfig
import com.garathon.flatdroid.proot.Fdv4l2
import com.garathon.flatdroid.session.Sensor
import com.garathon.flatdroid.util.debug.DebugLog
import java.io.File
import java.io.IOException

/**
 * One Android camera into its node's pipe ([FrameStreamer]), at the camera's size and rate.
 * Android's camera runs only while an app is linked to the node and the user allowed it, so
 * Android's camera indicator shows only meanwhile. When Android takes the camera away (another
 * app, a call) or can't open it, it is tried again whenever Android reports a camera available or
 * a change of who may use cameras, while an app is still linked. The image reader drops frames
 * while the pipe is behind. Frames are turned by [rotation] degrees clockwise (upright for the
 * app's display) before they are encoded; when the display turns ([turn]) so do they, changing
 * shape with it.
 */
class CameraStreamer(
    context: Context,
    private val camera: AndroidCameras.Camera,
    pipe: File,
    private var rotation: Int = 0,
    deviceMode: Boolean = false,
    control: File? = null,
    media: File? = null,
) : FrameStreamer(pipe, "Camera ${camera.id}", deviceMode, control, media, "camera-${camera.id}") {
    override val sensor = Sensor.CAMERA
    override val outWidth get() = camera.turnedSize(rotation).first
    override val outHeight get() = camera.turnedSize(rotation).second
    override val frameRate get() = camera.frameRate
    private val turned by lazy { ByteArray(Nv21.bytes(camera.width, camera.height)) }
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
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    /** An openCamera() not answered yet: one at a time, even when the app relinks meanwhile. */
    private var opening = false
    private var order: Nv21.Order? = null
    /** The camera opened but couldn't be set up: closing it makes Android report it available, which isn't a reason to try again. */
    private var failedSetup = false

    override fun opened() {
        manager?.registerAvailabilityCallback(availability, handler)
    }

    override fun closing() {
        manager?.unregisterAvailabilityCallback(availability)
    }

    override fun usersChanged() {
        failedSetup = false
    }

    /**
     * Frames are turned by [degrees] from now on. The JPEG in each says its size, so an app decoding
     * them follows; the node goes on announcing the size it started with (an app that trusts
     * that instead of the frames: verify).
     */
    fun turn(degrees: Int) = handler.post { rotation = degrees }

    @SuppressLint("MissingPermission") // allowed only with Android's permission (asked at Allow); refused, openCamera throws, caught below
    override fun startSource() {
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
            DebugLog.i("$label: on (${camera.width}x${camera.height})")
        } catch (e: Exception) {
            // In use or disabled: tried again once Android reports it available.
            opening = false
            DebugLog.w("$label: ${e.message}")
        }
    }

    /** After Android took the camera away or couldn't open it, while an app is still linked. */
    private fun retry() {
        if (!closed && users > 0 && allowed && !failedSetup) startSource()
    }

    /**
     * Android closed [opened], or couldn't open it: the app keeps the last frame until it opens again.
     * After a [setupFailed] the camera stays shut until the app links again, since the same setup would fail again.
     */
    private fun lost(opened: CameraDevice, why: String, setupFailed: Boolean = false) {
        opening = false
        if (setupFailed) failedSetup = true
        opened.close()
        if (device == opened) stopSource()
        DebugLog.w("$label: $why; " + if (setupFailed) "tried again when the app links again" else "tried again once Android reports a camera available")
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
            // Device mode: the app hasn't taken the last frame yet, so this one isn't encoded.
            if (!wantsFrame()) return
            val (y, u, v) = image.planes
            if (order == null && v.pixelStride == 2) order = Nv21.order(u.buffer, v.buffer)
            meter.start()
            val into = if (format == Fdv4l2.Format.NV12) Nv21.Order.UV else Nv21.Order.VU
            val written = Nv21.pack(
                camera.width, camera.height, y.buffer, y.rowStride,
                u.buffer, v.buffer, u.rowStride, u.pixelStride, order, frame, row, into,
            )
            meter.step(0)
            if (rotation != 0) {
                NativeFrames.rotate(frame, camera.width, camera.height, rotation, turned, swapChroma = written != into)
            } else if (written != into) {
                NativeFrames.swapChroma(frame, camera.width, camera.height)
            }
            meter.step(1)
            writeFrame(if (rotation != 0) turned else frame)
            meter.round()
        } catch (e: IOException) {
            if (!closed) DebugLog.w("$label: ${e.message}")
        } finally {
            image.close()
        }
    }

    override fun stopSource() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        if (device != null) DebugLog.i("$label: off")
        session = null
        device = null
        reader = null
        // The next count starts with the camera, not with this run or the black frame.
        meter.reset()
    }
}
