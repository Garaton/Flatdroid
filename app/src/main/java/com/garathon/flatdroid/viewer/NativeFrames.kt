package com.garathon.flatdroid.viewer

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.LocalSocket
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import com.garathon.flatdroid.util.DebugLog
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Pixels read from the socket straight into one frame in native memory (native/fdview.c), and
 * each update's changed area copied from there into the next buffer of a surface's queue, which
 * Android's compositor shows as it is, placed and scaled by the compositor: no copy into a bitmap,
 * no upload and no drawing in the app (on a 720x1440 phone 5 to 10% of a core less than drawing
 * hardware buffers, measured). The queue paces the copies, copies forward what an update left
 * alone, and is ordinary rendering to Android, so the system's frame-based CPU boosts still see
 * the app working (frames handed to the compositor without a queue lost them: measured, the
 * session's processes moved to slow cores). Nothing is shown until [useSurface]. Android 10 and later.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class NativeFrames private constructor(private val descriptor: ParcelFileDescriptor) : PixelSink, FrameDrawer {
    private val fd = descriptor.fd

    override val input = object : InputStream() {
        override fun read() = readByte(fd)
        override fun read(b: ByteArray, off: Int, len: Int) = readBytes(fd, b, off, len)
    }

    /** The frame (native); 0 once the connection ended. Released on the main thread. */
    @Volatile private var frame = create().also { if (it == 0L) throw IOException("no memory for the frame") }
    private var width = 0
    private var height = 0
    /** Between [begin] and [end]: the frame is being written (under [RfbClient.frameLock]). */
    private var updating = false
    /** What the update being read changed: left, top, right, bottom; empty when right is 0. */
    private val changed = Rect()
    private val posting = Rect()
    private val postMeter = ViewerDebug.postMeter()

    // Main thread: the surface frames are shown on, and where it was put.
    private var control: SurfaceControl? = null
    private var surface: Surface? = null
    private val placed = RectF()
    private var placedWidth = 0
    private var placedHeight = 0
    private val placing = SurfaceControl.Transaction()
    private val source = Rect()
    private val rounded = Rect()

    /** The connection ended (reading thread). */
    fun close() {
        runCatching { descriptor.close() }
        Handler(Looper.getMainLooper()).post {
            useSurface(null)
            val handle = frame
            frame = 0L
            if (handle != 0L) destroy(handle)
        }
    }

    override fun begin(width: Int, height: Int) {
        if (width != this.width || height != this.height) {
            if (!resize(frame, width, height)) throw IOException("no memory for a ${width}x$height screen")
            this.width = width
            this.height = height
        }
        updating = true
        changed.setEmpty()
    }

    override fun raw(x: Int, y: Int, width: Int, height: Int) {
        if (readRect(fd, frame, x, y, width, height) != 0) throw EOFException()
        changed.union(x, y, x + width, y + height)
    }

    override fun copy(sx: Int, sy: Int, x: Int, y: Int, width: Int, height: Int) {
        moveRect(frame, sx, sy, x, y, width, height)
        changed.union(x, y, x + width, y + height)
    }

    override fun end() {
        updating = false
        posting.set(changed)
    }

    override fun post() {
        if (posting.isEmpty) return
        val start = if (postMeter != null) System.nanoTime() else 0L
        post(frame, posting.left, posting.top, posting.right, posting.bottom)
        postMeter?.invoke(System.nanoTime() - start)
    }

    override val hasHidden get() = false

    override fun frame(fb: Framebuffer, visible: Rect) = true

    override fun reveal(fb: Framebuffer, visible: Rect) = false

    /** Nothing: frames are only shown on the surface. */
    override fun draw(canvas: Canvas, matrix: Matrix, paint: Paint) = Unit

    override fun useSurface(surface: SurfaceView?): Boolean {
        control?.let { old ->
            // Off screen first: the compositor lets go of the queue's buffers, so a post waiting
            // for one returns and the window can be let go (verify under heavy updates).
            SurfaceControl.Transaction().reparent(old, null).apply()
            attach(frame, null)
            old.release()
            this.surface?.release()
        }
        control = null
        this.surface = null
        placedWidth = 0
        if (surface == null || frame == 0L) return false
        // A layer of our own under the view's surface: placed and scaled to follow zoom and pan.
        val control = SurfaceControl.Builder().setName("Flatdroid frames").setParent(surface.surfaceControl)
            .setBufferSize(maxOf(width, 1), maxOf(height, 1)).setOpaque(true).build()
        val queue = Surface(control)
        if (!attach(frame, queue)) {
            queue.release()
            control.release()
            return false
        }
        SurfaceControl.Transaction().setVisibility(control, true).setLayer(control, 1).apply()
        this.control = control
        this.surface = queue
        // What there is so far, unless an update is being read (its post then shows all of it).
        if (!updating && width > 0) post(frame, 0, 0, width, height)
        return true
    }

    override fun show(destination: RectF) {
        val control = control ?: return
        if (width == 0 || destination.isEmpty) return
        if (destination == placed && width == placedWidth && height == placedHeight) return
        // The layer's bounds follow the frame's size, or a resized frame is cut to the old one.
        if (width != placedWidth || height != placedHeight) placing.setBufferSize(control, width, height)
        placed.set(destination)
        placedWidth = width
        placedHeight = height
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Placed to the fraction of a pixel, as the view's zoom and pan put it.
            placing.setPosition(control, destination.left, destination.top)
                .setScale(control, destination.width() / width, destination.height() / height)
        } else {
            source.set(0, 0, width, height)
            destination.round(rounded)
            @Suppress("DEPRECATION") // the only way before Android 12
            placing.setGeometry(control, source, rounded, Surface.ROTATION_0)
        }
        placing.apply()
    }

    override fun hashRows(fb: Framebuffer, out: IntArray): Boolean {
        if (updating || frame == 0L || width != fb.width || height != fb.height) return false
        hashRows(frame, ScrollMeter.BANDS, out)
        return true
    }

    companion object {
        /** A [NativeFrames] reading [socket], or null where it can't run (Android before 10). */
        fun open(socket: LocalSocket): NativeFrames? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return runCatching {
                System.loadLibrary("fdview")
                reset()
                NativeFrames(ParcelFileDescriptor.dup(socket.fileDescriptor))
            }.onFailure { DebugLog.w("Viewer: no native frames: $it") }.getOrNull()
        }

        @JvmStatic private external fun reset()
        @JvmStatic private external fun readByte(fd: Int): Int
        @JvmStatic private external fun readBytes(fd: Int, to: ByteArray, offset: Int, length: Int): Int
        @JvmStatic private external fun create(): Long
        @JvmStatic private external fun destroy(frame: Long)
        @JvmStatic private external fun resize(frame: Long, width: Int, height: Int): Boolean
        @JvmStatic private external fun readRect(fd: Int, frame: Long, x: Int, y: Int, width: Int, height: Int): Int
        @JvmStatic private external fun moveRect(frame: Long, sx: Int, sy: Int, x: Int, y: Int, width: Int, height: Int)
        @JvmStatic private external fun hashRows(frame: Long, bands: Int, out: IntArray)
        @JvmStatic private external fun attach(frame: Long, surface: Surface?): Boolean
        @JvmStatic private external fun post(frame: Long, left: Int, top: Int, right: Int, bottom: Int)
    }
}
