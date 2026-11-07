package com.garathon.flatdroid.viewer

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** What the server said that the viewer acts on. Called on the reading thread. */
interface RfbListener {
    /** The framebuffer changed inside this rectangle (already written into [Framebuffer]). */
    fun damaged(x: Int, y: Int, width: Int, height: Int) {}
    /** One FramebufferUpdate is complete: the time to show it and ask for the next one. */
    fun updateDone() {}
    /** The desktop changed size; [Framebuffer] has been reallocated (blank). */
    fun resized(width: Int, height: Int) {}
    /** The server's clipboard text (an app copied). */
    fun clipboard(text: String) {}
    /** An app pastes what [RfbClient.announceClipboard] offered: answer with [RfbClient.provideClipboard]. */
    fun clipboardRequested() {}
    /** The server moved the pointer itself (an app warped it). */
    fun pointerMoved(x: Int, y: Int) {}
}

class RfbException(message: String) : IOException(message)

/** Takes the pixels of each update instead of [Framebuffer] ([NativeFrames]). Reading thread. */
interface PixelSink {
    /** What the connection is read through. */
    val input: InputStream
    fun begin(width: Int, height: Int)
    /** Reads a Raw rectangle's pixels from the connection itself. */
    fun raw(x: Int, y: Int, width: Int, height: Int)
    fun copy(sx: Int, sy: Int, x: Int, y: Int, width: Int, height: Int)
    fun end()
}

/**
 * The pixels the server has sent: [BYTES_PER_PIXEL][Rfb.BYTES_PER_PIXEL] bytes each, row after
 * row with no padding, in the order an ARGB_8888 bitmap keeps them (see [Rfb.PIXEL_FORMAT]).
 */
class Framebuffer(val width: Int, val height: Int, keepPixels: Boolean = true) {
    /** Empty when a [PixelSink] takes the pixels. Under [RfbClient.frameLock]. */
    val pixels = ByteArray(if (keepPixels) width * height * Rfb.BYTES_PER_PIXEL else 0)
    val stride get() = width * Rfb.BYTES_PER_PIXEL

    /** Told of each changed rectangle (under [RfbClient.frameLock]). */
    var onChange: ((x: Int, y: Int, width: Int, height: Int) -> Unit)? = null

    fun markChanged(x: Int, y: Int, width: Int, height: Int) {
        onChange?.invoke(x, y, width, height)
    }
}

/**
 * An RFB 3.8 client for a local server: security None only, Raw and CopyRect, the desktop-size,
 * cursor and extended clipboard pseudo-encodings. Not thread-safe for reading: one thread calls
 * [connect] and then [readMessage] in a loop; the sending methods may be called from any thread.
 *
 * The server only sends a FramebufferUpdate in answer to a request, and the viewer asks for the
 * next one only after showing the last ([requestUpdate]), so updates never queue up behind a slow
 * screen and the reading thread never writes into the framebuffer while it is being copied out.
 */
class RfbClient(
    input: InputStream,
    private val output: OutputStream,
    /** Told how long reading each Raw rectangle's pixels took (debug measurements; null in release builds). */
    private val rawMeter: ((bytes: Int, nanos: Long) -> Unit)? = null,
    /** Told how long each CopyRect's copy took (debug measurements; null in release builds). */
    private val copyRectMeter: ((pixels: Int, nanos: Long) -> Unit)? = null,
    /** Takes the pixels instead of [framebuffer], and is what the connection is read through. */
    private val pixelSink: PixelSink? = null,
) {
    private val input = DataInputStream(pixelSink?.input ?: input.buffered(64 * 1024))
    private val sendLock = Any()
    /** Held while an update is written into [framebuffer]; whoever copies it out holds it too. */
    val frameLock = Any()

    var framebuffer: Framebuffer = Framebuffer(1, 1)
        private set
    var name: String = ""
        private set
    /** The server can be resized by the client (ExtendedDesktopSize seen). */
    var canResize = false
        private set
    private var screenId = 0
    @Volatile private var extendedClipboard = false
    /** We have text to offer: announced once the server says it takes the extended clipboard. */
    @Volatile private var announced = false

    fun connect() {
        val version = ByteArray(12).also(input::readFully).toString(Charsets.US_ASCII)
        if (!version.startsWith("RFB 003.")) throw RfbException("not an RFB server")
        val minor = version.substring(8, 11).toIntOrNull() ?: throw RfbException("not an RFB server")
        if (minor < 8) throw RfbException("RFB 3.$minor is too old")
        send(Rfb.VERSION.toByteArray(Charsets.US_ASCII))
        val count = input.readUnsignedByte()
        if (count == 0) throw RfbException(reason())
        val types = ByteArray(count).also(input::readFully).map { it.toInt() and 0xff }
        if (Rfb.SECURITY_NONE !in types) throw RfbException("the server asks for a login")
        send(byteArrayOf(Rfb.SECURITY_NONE.toByte()))
        if (input.readInt() != 0) throw RfbException(reason())
        send(byteArrayOf(1)) // ClientInit: shared (the server is set never to share anyway)
        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        input.skipFully(16) // the server's pixel format: replaced by ours below
        name = String(ByteArray(input.readInt().also(::checkLength)).also(input::readFully), Charsets.UTF_8)
        framebuffer = Framebuffer(width, height, keepPixels = pixelSink == null)
        send(Rfb.setPixelFormat())
        send(Rfb.setEncodings())
        requestUpdate(incremental = false)
    }

    /** Asks for the changes since the last update, within the rectangle (the whole screen by default). */
    fun requestUpdate(incremental: Boolean = true, x: Int = 0, y: Int = 0, width: Int = framebuffer.width, height: Int = framebuffer.height) {
        val fb = framebuffer
        val left = x.coerceIn(0, fb.width)
        val top = y.coerceIn(0, fb.height)
        send(Rfb.updateRequest(incremental, left, top, width.coerceIn(0, fb.width - left), height.coerceIn(0, fb.height - top)))
    }

    fun key(down: Boolean, keysym: Int) = send(Rfb.keyEvent(down, keysym))

    fun pointer(buttons: Int, x: Int, y: Int) = send(Rfb.pointerEvent(buttons, x, y))

    /** Asks the server for a new desktop size; it answers with a desktop-size update (or refuses it, keeping the size). */
    fun resize(width: Int, height: Int) {
        if (canResize) send(Rfb.setDesktopSize(width, height, screenId))
    }

    /**
     * Our clipboard has new text: only announced, so the text itself goes only when an app pastes
     * it ([RfbListener.clipboardRequested]). Extended clipboard only: a server without it gets
     * nothing, as plain cut text would mean sending every copy whether used or not.
     */
    fun announceClipboard() {
        announced = true
        if (extendedClipboard) send(ExtendedClipboard.notify())
    }

    /** The text an app pasting asked for ([RfbListener.clipboardRequested]); empty when there is none to give. */
    fun provideClipboard(text: String) = send(ExtendedClipboard.provide(text))

    /** Reads and handles one server message; throws when the connection ends. */
    fun readMessage(listener: RfbListener) {
        when (val type = input.readUnsignedByte()) {
            // A sink reads into a buffer nothing shows yet: the lock is taken only to change what shows.
            Rfb.FRAMEBUFFER_UPDATE -> if (pixelSink != null) readUpdate(listener) else synchronized(frameLock) { readUpdate(listener) }
            Rfb.SET_COLOUR_MAP -> { // never sent for a true-colour format; skipped if it is
                input.skipFully(3)
                input.skipFully(input.readUnsignedShort() * 6)
            }
            Rfb.BELL -> Unit // ignored
            Rfb.SERVER_CUT_TEXT -> readCutText(listener)
            else -> throw RfbException("unknown message $type")
        }
    }

    private fun readUpdate(listener: RfbListener) {
        input.skipFully(1)
        val count = input.readUnsignedShort()
        pixelSink?.let { synchronized(frameLock) { it.begin(framebuffer.width, framebuffer.height) } }
        for (i in 0 until count) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            when (val encoding = input.readInt()) {
                Rfb.RAW -> {
                    val meter = rawMeter
                    if (meter == null) {
                        raw(x, y, w, h)
                    } else {
                        val start = System.nanoTime()
                        raw(x, y, w, h)
                        meter(w * h * Rfb.BYTES_PER_PIXEL, System.nanoTime() - start)
                    }
                    framebuffer.markChanged(x, y, w, h)
                    listener.damaged(x, y, w, h)
                }
                Rfb.COPY_RECT -> {
                    val sx = input.readUnsignedShort()
                    val sy = input.readUnsignedShort()
                    val meter = copyRectMeter
                    if (pixelSink != null) {
                        checkRect(x, y, w, h)
                        checkRect(sx, sy, w, h)
                        pixelSink.copy(sx, sy, x, y, w, h)
                    } else if (meter == null) {
                        copy(sx, sy, x, y, w, h)
                    } else {
                        val start = System.nanoTime()
                        copy(sx, sy, x, y, w, h)
                        meter(w * h, System.nanoTime() - start)
                    }
                    framebuffer.markChanged(x, y, w, h)
                    listener.damaged(x, y, w, h)
                }
                Rfb.DESKTOP_SIZE -> resize(w, h, listener)
                Rfb.EXTENDED_DESKTOP_SIZE -> {
                    canResize = true
                    val screens = input.readUnsignedByte()
                    input.skipFully(3)
                    for (s in 0 until screens) {
                        val id = input.readInt()
                        input.skipFully(12)
                        if (s == 0) screenId = id
                    }
                    // y is the result of a resize request: anything but 0 means refused, size unchanged.
                    if (w != framebuffer.width || h != framebuffer.height) resize(w, h, listener)
                }
                // Asked for only so the server leaves the pointer out of the screen; never drawn
                // (touch needs none, and Android draws a mouse's own).
                Rfb.CURSOR -> input.skipFully(w * h * Rfb.BYTES_PER_PIXEL + (w + 7) / 8 * h)
                Rfb.CURSOR_POSITION -> listener.pointerMoved(x, y)
                Rfb.LAST_RECT -> break
                else -> throw RfbException("unrequested encoding $encoding")
            }
        }
        pixelSink?.let { synchronized(frameLock) { it.end() } }
        listener.updateDone()
    }

    private fun resize(width: Int, height: Int, listener: RfbListener) {
        synchronized(frameLock) {
            framebuffer = Framebuffer(width, height, keepPixels = pixelSink == null)
            pixelSink?.let {
                it.end()
                it.begin(width, height)
            }
        }
        listener.resized(width, height)
    }

    private fun raw(x: Int, y: Int, w: Int, h: Int) {
        if (pixelSink == null) return readRaw(x, y, w, h)
        checkRect(x, y, w, h)
        pixelSink.raw(x, y, w, h)
    }

    private fun checkRect(x: Int, y: Int, w: Int, h: Int) {
        if (x + w > framebuffer.width || y + h > framebuffer.height) throw RfbException("rectangle outside the screen")
    }

    private fun readRaw(x: Int, y: Int, w: Int, h: Int) {
        checkRect(x, y, w, h)
        val fb = framebuffer
        val row = w * Rfb.BYTES_PER_PIXEL
        if (x == 0 && w == fb.width) {
            input.readFully(fb.pixels, y * fb.stride, row * h)
        } else {
            for (r in 0 until h) input.readFully(fb.pixels, (y + r) * fb.stride + x * Rfb.BYTES_PER_PIXEL, row)
        }
    }

    private fun copy(sx: Int, sy: Int, x: Int, y: Int, w: Int, h: Int) {
        checkRect(x, y, w, h)
        checkRect(sx, sy, w, h)
        val fb = framebuffer
        val row = w * Rfb.BYTES_PER_PIXEL
        // Rows in the order that never overwrites a source row before it is copied.
        val rows = if (sy < y) (h - 1 downTo 0) else (0 until h)
        for (r in rows) {
            System.arraycopy(fb.pixels, (sy + r) * fb.stride + sx * Rfb.BYTES_PER_PIXEL, fb.pixels, (y + r) * fb.stride + x * Rfb.BYTES_PER_PIXEL, row)
        }
    }

    private fun readCutText(listener: RfbListener) {
        input.skipFully(3)
        val length = input.readInt()
        if (length < 0) { // extended clipboard: the length is negated
            val data = ByteArray((-length).also(::checkLength)).also(input::readFully)
            when (val message = ExtendedClipboard.parse(data)) {
                is ExtendedClipboard.Message.Caps -> {
                    extendedClipboard = true
                    send(ExtendedClipboard.caps())
                    if (announced) send(ExtendedClipboard.notify())
                }
                is ExtendedClipboard.Message.Notify -> if (message.text) send(ExtendedClipboard.request())
                is ExtendedClipboard.Message.Provide -> message.text?.let(listener::clipboard)
                is ExtendedClipboard.Message.Request -> if (message.text && announced) listener.clipboardRequested()
                null -> Unit
            }
        } else {
            val bytes = ByteArray(length.also(::checkLength)).also(input::readFully)
            listener.clipboard(String(bytes, Charsets.ISO_8859_1))
        }
    }

    private fun reason(): String {
        val length = input.readInt().also(::checkLength)
        return String(ByteArray(length).also(input::readFully), Charsets.UTF_8).ifBlank { "the server refused the connection" }
    }

    private fun checkLength(length: Int) {
        if (length < 0 || length > MAX_TEXT) throw RfbException("message too long")
    }

    private fun send(bytes: ByteArray) = synchronized(sendLock) {
        output.write(bytes)
        output.flush()
    }

    private fun DataInputStream.skipFully(count: Int) {
        var left = count
        while (left > 0) {
            val skipped = skipBytes(left)
            if (skipped <= 0) {
                if (read() < 0) throw EOFException()
                left--
            } else {
                left -= skipped
            }
        }
    }

    companion object {
        /** Longest name, reason or clipboard text accepted. */
        const val MAX_TEXT = 16 * 1024 * 1024
    }
}
