package com.garathon.flatdroid.camera

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.view.Display
import com.garathon.flatdroid.proot.Fdv4l2
import com.garathon.flatdroid.session.Sensor
import com.garathon.flatdroid.util.debug.DebugLog
import java.io.File
import java.io.IOException

/**
 * Android's screen as one more camera ([FrameStreamer]), through a screen share ([ScreenShare])
 * the user gave Android's consent to for this use. The share runs while an app is linked and
 * allowed, and ends [GRACE_MS] after the app stops using it, or when the user stops it in Android;
 * either way [onEnded] is told, so the next use asks again. After the user stopped it, the app
 * keeps the last frame. Android draws a frame only when the screen changes: frames are written
 * at most [FRAME_RATE] a second, and the last is written again after [REPEAT_MS] without one, so
 * an app doesn't take a still screen for a lost camera.
 *
 * Frames are the screen's size at [scale] percent ([SCALES]), in the shape the screen has: in portal mode the
 * share follows the screen as it turns (the JPEG in each frame says its size, as a turned camera's
 * does); in device mode the size stays the node's ([width]×[height]), and Android fits a turned
 * screen in it with black bars (verify).
 */
class ScreenStreamer(
    private val context: Context,
    pipe: File,
    private var width: Int,
    private var height: Int,
    /** The frames' size in percent of the screen's ([fit]). */
    private val scale: Int = DEFAULT_SCALE,
    deviceMode: Boolean = false,
    control: File? = null,
    media: File? = null,
    private val onEnded: () -> Unit,
) : FrameStreamer(pipe, "Screen", deviceMode, control, media, "screen") {
    override val sensor = Sensor.SCREEN
    override val outWidth get() = width
    override val outHeight get() = height
    override val frameRate = FRAME_RATE
    private val displays = context.getSystemService(DisplayManager::class.java)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    /** The frame [NativeFrames.fromRgba] writes (NV21, or NV12 for an app that takes raw frames). */
    private var raw: ByteArray? = null
    private var pullPending = false
    private val pullLater = Runnable {
        pullPending = false
        pull()
    }
    private val end = Runnable { endShare() }
    /** Portal mode: the last frame again, while the app is linked and allowed and nothing changed. */
    private val repeat = object : Runnable {
        override fun run() {
            if (users > 0 && allowed && sinceLastWrite() >= REPEAT_MS) {
                try {
                    repeatLast()
                } catch (e: IOException) {
                    if (!closed) DebugLog.w("$label: ${e.message}")
                }
            }
            handler.postDelayed(this, REPEAT_MS)
        }
    }
    private val stopped = object : MediaProjection.Callback() {
        override fun onStop() {
            // The user (or Android) stopped the share; the app keeps the last frame.
            if (projection == null) return
            DebugLog.i("$label: the share was stopped")
            release()
            projection = null
            onEnded()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            // Android 14 and later: the screen turned, or one app is shared and changed size (verify both).
            if (!deviceMode) fit(width, height, scale).let { (w, h) -> resize(w, h) }
        }
    }
    private val turned = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val (w, h) = size(context, scale)
            resize(w, h)
        }

        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    override fun opened() {
        if (!deviceMode) handler.postDelayed(repeat, REPEAT_MS)
    }

    override fun closing() {
        handler.removeCallbacks(repeat)
        handler.removeCallbacks(pullLater)
        handler.removeCallbacks(end)
        endShare()
    }

    override fun refill() {
        if (!repeatLast()) super.refill()
    }

    override fun startSource() {
        handler.removeCallbacks(end)
        if (display != null) return
        val share = projection ?: ScreenShare.take() ?: return DebugLog.w("$label: no share to use")
        projection = share
        try {
            share.registerCallback(stopped, handler)
            if (!deviceMode) {
                val (w, h) = size(context, scale)
                width = w
                height = h
            }
            val imageReader = newReader()
            display = share.createVirtualDisplay(
                "Flatdroid screen", width, height, density(), DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, handler,
            )
            if (!deviceMode && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) displays?.registerDisplayListener(turned, handler)
            DebugLog.i("$label: on (${width}x$height)")
        } catch (e: Exception) {
            DebugLog.w("$label: ${e.message}")
            endShare()
        }
    }

    /** The app stopped using the screen, or was refused it: the share ends after [GRACE_MS], unless the app comes back. */
    override fun stopSource() {
        if (projection == null) return
        handler.removeCallbacks(end)
        handler.postDelayed(end, GRACE_MS)
    }

    private fun endShare() {
        val share = projection ?: return
        projection = null
        release()
        share.unregisterCallback(stopped)
        share.stop()
        DebugLog.i("$label: off")
        onEnded()
    }

    private fun release() {
        displays?.unregisterDisplayListener(turned)
        handler.removeCallbacks(pullLater)
        pullPending = false
        runCatching { display?.release() }
        display = null
        runCatching { reader?.close() }
        reader = null
        meter.reset()
    }

    /** Portal mode: frames of [w]×[h] from now on, the share resized to it. */
    private fun resize(w: Int, h: Int) {
        val shown = display ?: return
        if (w == width && h == height || w <= 0 || h <= 0) return
        width = w
        height = h
        val old = reader
        val imageReader = newReader()
        shown.resize(w, h, density())
        shown.surface = imageReader.surface
        runCatching { old?.close() }
        DebugLog.i("$label: now ${w}x$h")
    }

    private fun newReader(): ImageReader =
        // RGBA: a share into a YUV reader gave no frames (Cortex-A53 tablet, Android 13).
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also {
            it.setOnImageAvailableListener({ meter.count(); pull() }, handler)
            reader = it
        }

    /** The screen's density, scaled as the frames are. */
    private fun density(): Int =
        maxOf(1, context.resources.displayMetrics.densityDpi * minOf(width, height) / screenSize(context).let { (w, h) -> maxOf(1, minOf(w, h)) })

    /**
     * The newest frame, when one can go out now: not sooner than [FRAME_RATE] allows, and in device
     * mode not before the app took the last. Otherwise it waits in the reader, and is taken later.
     */
    private fun pull() {
        val imageReader = reader ?: return
        val wait = maxOf(1000L / FRAME_RATE - sinceLastWrite(), if (wantsFrame()) 0L else WAIT_MS)
        if (wait > 0) {
            if (!pullPending) {
                pullPending = true
                handler.postDelayed(pullLater, wait)
            }
            return
        }
        val image = try {
            imageReader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        try {
            // A frame of the size before a resize: the next is the right one.
            if (image.width != width || image.height != height) return
            val plane = image.planes[0]
            meter.start()
            // The camera's path: NV21 (NV12 for an app that takes it), then Android's JPEG encoder
            // for NV21, faster than its bitmap encoder from RGBA.
            val frame = raw?.takeIf { it.size == Nv21.bytes(width, height) } ?: ByteArray(Nv21.bytes(width, height)).also { raw = it }
            NativeFrames.fromRgba(plane.buffer, plane.rowStride, width, height, frame, if (format == Fdv4l2.Format.NV12) Nv21.Order.UV else Nv21.Order.VU)
            meter.step(0)
            meter.step(1)
            writeFrame(frame)
            meter.round()
        } catch (e: IOException) {
            if (!closed) DebugLog.w("$label: ${e.message}")
        } finally {
            image.close()
        }
    }

    companion object {
        /** The sizes the frames can have, in percent of the screen's own. */
        val SCALES = listOf(100, 75, 50, 25)
        /**
         * Half: an app encoding video in software falls far behind at the full size on a Cortex-A53
         * (one core at 720x1440), and Flatdroid itself makes twice the frames (19 a second against 9).
         */
        const val DEFAULT_SCALE = 50
        const val FRAME_RATE = 30
        const val GRACE_MS = 5_000L
        const val REPEAT_MS = 500L
        /** Device mode: how long to wait before looking again whether the app took the last frame. */
        private const val WAIT_MS = 10L

        /** [width]×[height] at [scale] percent, in the same shape, each side a multiple of 8. */
        fun fit(width: Int, height: Int, scale: Int): Pair<Int, Int> =
            aligned(width * scale / 100.0) to aligned(height * scale / 100.0)

        /** The screen's frames now: [fit] of its real size in its current shape. */
        fun size(context: Context, scale: Int): Pair<Int, Int> = screenSize(context).let { (w, h) -> fit(w, h, scale) }

        /** The screen's real size in its current shape. */
        fun screenSize(context: Context): Pair<Int, Int> {
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            val point = Point()
            @Suppress("DEPRECATION") // the real size in the current rotation, from any context
            display?.getRealSize(point)
            if (point.x <= 0 || point.y <= 0) {
                val metrics = context.resources.displayMetrics
                point.set(metrics.widthPixels, metrics.heightPixels)
            }
            return point.x to point.y
        }

        /** [size] in the shape of a [landscape] (or portrait) screen. */
        fun size(context: Context, landscape: Boolean, scale: Int): Pair<Int, Int> {
            val (w, h) = size(context, scale)
            return if ((w > h) == landscape) w to h else h to w
        }

        /**
         * Down to a multiple of 8: GStreamer lays raw rows out 4-aligned, so a frame 1918 wide is
         * bigger there than the device's buffer (every frame failed, `gst_memory_resize`).
         */
        private fun aligned(value: Double): Int = maxOf(ALIGN, value.toInt() / ALIGN * ALIGN)

        private const val ALIGN = 8

    }
}
