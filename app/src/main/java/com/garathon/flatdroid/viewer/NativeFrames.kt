package com.garathon.flatdroid.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.hardware.HardwareBuffer
import android.net.LocalSocket
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import com.garathon.flatdroid.util.DebugLog
import java.io.EOFException
import java.io.InputStream

/**
 * Pixels read from the socket straight into hardware buffers the GPU draws from (native/fdview.c):
 * no copy into a bitmap and no upload (13 to 27% less CPU than bitmaps when most of the screen
 * changes, measured). Updates carry only what changed, so each buffer also needs what changed
 * since it was last written: that much is copied from the newest buffer, less what the new update
 * replaces anyway. Three buffers, as one is shown and the GPU may still be reading the one before
 * (updates are paced, one per frame shown, so the third has had a frame to finish; verify on a
 * slow GPU). Android 10 and later.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class NativeFrames private constructor(private val descriptor: ParcelFileDescriptor) : PixelSink, FrameDrawer {
    private val fd = descriptor.fd

    override val input = object : InputStream() {
        override fun read() = readByte(fd)
        override fun read(b: ByteArray, off: Int, len: Int) = readBytes(fd, b, off, len)
    }

    /** The connection ended. */
    fun close() = runCatching { descriptor.close() }

    private class Frame(val buffer: HardwareBuffer, val bitmap: Bitmap, val stride: Int) {
        /** Changed since this buffer was last written. */
        val stale = Region()
    }

    private var frames: List<Frame> = emptyList()
    private var width = 0
    private var height = 0
    private var newest = 0
    private var target = 0
    private var pixels = 0L
    private val written = Region()
    private val rect = Rect()
    /** What [draw] shows; kept whole across a resize, as Android may still be drawing it. */
    private var current: Frame? = null

    override fun begin(width: Int, height: Int) {
        if (width != this.width || height != this.height) allocate(width, height)
        target = (newest + 1) % frames.size
        pixels = lock(frames[target].buffer)
        written.setEmpty()
    }

    private fun allocate(width: Int, height: Int) {
        frames.forEach { it.buffer.close() } // the bitmaps keep what they need
        this.width = width
        this.height = height
        frames = List(3) {
            val buffer = HardwareBuffer.create(width, height, HardwareBuffer.RGBX_8888, 1, USAGE)
            Frame(buffer, Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))!!, stride(buffer))
        }
        newest = 0
    }

    override fun raw(x: Int, y: Int, width: Int, height: Int) {
        if (readRect(fd, pixels, frames[target].stride, x, y, width, height) != 0) throw EOFException()
        written.op(x, y, x + width, y + height, Region.Op.UNION)
    }

    override fun copy(sx: Int, sy: Int, x: Int, y: Int, width: Int, height: Int) {
        catchUp() // the source must be current first
        moveRect(pixels, frames[target].stride, sx, sy, x, y, width, height)
        written.op(x, y, x + width, y + height, Region.Op.UNION)
    }

    /** Brings the target up to date, except for what this update has written. */
    private fun catchUp() {
        val frame = frames[target]
        frame.stale.op(written, Region.Op.DIFFERENCE)
        if (frame.stale.isEmpty) return
        val source = frames[newest]
        val from = lock(source.buffer)
        val rects = RegionIterator(frame.stale)
        while (rects.next(rect)) copyRect(pixels, from, frame.stride, rect.left, rect.top, rect.width(), rect.height())
        unlock(source.buffer)
        frame.stale.setEmpty()
    }

    override fun end() {
        catchUp()
        val frame = frames[target]
        unlock(frame.buffer)
        for (other in frames) if (other !== frame) other.stale.op(written, Region.Op.UNION)
        newest = target
    }

    override val hasHidden get() = false

    override fun frame(fb: Framebuffer, visible: Rect): Boolean {
        val latest = frames.getOrNull(newest) ?: return false
        if (latest === current) return false
        current = latest
        return true
    }

    override fun reveal(fb: Framebuffer, visible: Rect) = false

    override fun draw(canvas: Canvas, matrix: Matrix, paint: Paint) {
        current?.let { canvas.drawBitmap(it.bitmap, matrix, paint) }
    }

    override fun hashRows(fb: Framebuffer, out: IntArray) {
        val frame = frames.getOrNull(newest) ?: return
        val pixels = lock(frame.buffer)
        hashRows(pixels, frame.stride, width, height, ScrollMeter.BANDS, out)
        unlock(frame.buffer)
    }

    companion object {
        /** A [NativeFrames] reading [socket], or null where it can't run (Android before 10, or no such buffers). */
        fun open(socket: LocalSocket): NativeFrames? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return runCatching {
                System.loadLibrary("fdview")
                reset()
                // Some devices may refuse the format: better known now than in the middle of an update.
                HardwareBuffer.create(16, 16, HardwareBuffer.RGBX_8888, 1, USAGE).use { buffer ->
                    checkNotNull(Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)))
                }
                NativeFrames(ParcelFileDescriptor.dup(socket.fileDescriptor))
            }.onFailure { DebugLog.w("Viewer: no native frames: $it") }.getOrNull()
        }

        private const val USAGE = HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_CPU_WRITE_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE

        @JvmStatic private external fun reset()
        @JvmStatic private external fun readByte(fd: Int): Int
        @JvmStatic private external fun readBytes(fd: Int, to: ByteArray, offset: Int, length: Int): Int
        @JvmStatic private external fun lock(buffer: HardwareBuffer): Long
        @JvmStatic private external fun unlock(buffer: HardwareBuffer)
        @JvmStatic private external fun stride(buffer: HardwareBuffer): Int
        @JvmStatic private external fun readRect(fd: Int, pixels: Long, stride: Int, x: Int, y: Int, width: Int, height: Int): Int
        @JvmStatic private external fun copyRect(to: Long, from: Long, stride: Int, x: Int, y: Int, width: Int, height: Int)
        @JvmStatic private external fun moveRect(pixels: Long, stride: Int, sx: Int, sy: Int, x: Int, y: Int, width: Int, height: Int)
        @JvmStatic private external fun hashRows(pixels: Long, stride: Int, width: Int, height: Int, bands: Int, out: IntArray)
    }
}
