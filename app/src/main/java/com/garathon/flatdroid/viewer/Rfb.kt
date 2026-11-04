package com.garathon.flatdroid.viewer

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * The parts of the RFB protocol (RFC 6143, plus the pseudo-encodings TigerVNC and wayvnc share)
 * that Flatdroid's viewer uses: the session is local, so Raw pixels only, no compression.
 */
object Rfb {
    const val VERSION = "RFB 003.008\n"
    const val SECURITY_NONE = 1

    // Encodings and pseudo-encodings the viewer asks for.
    const val RAW = 0
    const val COPY_RECT = 1
    const val CURSOR = -239
    const val DESKTOP_SIZE = -223
    const val LAST_RECT = -224
    const val EXTENDED_DESKTOP_SIZE = -308
    /** Where the server moved the pointer itself (no data). */
    const val CURSOR_POSITION = 0x574d5666
    /** TigerVNC's extended clipboard (UTF-8, larger than Latin-1 cut text). */
    const val EXTENDED_CLIPBOARD = 0xc0a1e5ce.toInt()

    /** The cursor ones are asked for only so the server doesn't draw the pointer into the screen; the viewer draws none. */
    val ENCODINGS = listOf(COPY_RECT, RAW, CURSOR, CURSOR_POSITION, DESKTOP_SIZE, EXTENDED_DESKTOP_SIZE, LAST_RECT, EXTENDED_CLIPBOARD)

    // Server-to-client message types.
    const val FRAMEBUFFER_UPDATE = 0
    const val SET_COLOUR_MAP = 1
    const val BELL = 2
    const val SERVER_CUT_TEXT = 3

    /**
     * 32 bits per pixel, little-endian, red in the lowest byte: each pixel arrives as the bytes
     * R, G, B and one unused, the order an Android ARGB_8888 bitmap keeps in memory, so a
     * framebuffer of them is copied into the bitmap as it is (the unused byte is ignored by an
     * opaque bitmap).
     */
    val PIXEL_FORMAT: ByteArray = ByteArray(16).also {
        it[0] = 32 // bits per pixel
        it[1] = 24 // depth
        it[2] = 0 // big-endian: no
        it[3] = 1 // true colour
        it[4] = 0; it[5] = 255.toByte() // red max
        it[6] = 0; it[7] = 255.toByte() // green max
        it[8] = 0; it[9] = 255.toByte() // blue max
        it[10] = 0 // red shift
        it[11] = 8 // green shift
        it[12] = 16 // blue shift
    }
    const val BYTES_PER_PIXEL = 4

    // ---- Client-to-server messages, each as the bytes to send.

    fun setPixelFormat(): ByteArray = message {
        writeByte(0); write(ByteArray(3))
        write(PIXEL_FORMAT)
    }

    fun setEncodings(encodings: List<Int> = ENCODINGS): ByteArray = message {
        writeByte(2); writeByte(0); writeShort(encodings.size)
        encodings.forEach(::writeInt)
    }

    fun updateRequest(incremental: Boolean, x: Int, y: Int, width: Int, height: Int): ByteArray = message {
        writeByte(3); writeByte(if (incremental) 1 else 0)
        writeShort(x); writeShort(y); writeShort(width); writeShort(height)
    }

    fun keyEvent(down: Boolean, keysym: Int): ByteArray = message {
        writeByte(4); writeByte(if (down) 1 else 0); writeShort(0); writeInt(keysym)
    }

    /** [buttons]: bit 0 left, 1 middle, 2 right, 3/4 wheel up/down, 5/6 wheel left/right. */
    fun pointerEvent(buttons: Int, x: Int, y: Int): ByteArray = message {
        writeByte(5); writeByte(buttons); writeShort(x.coerceIn(0, 0xffff)); writeShort(y.coerceIn(0, 0xffff))
    }

    /** ExtendedDesktopSize's SetDesktopSize: one screen covering [width] x [height]. */
    fun setDesktopSize(width: Int, height: Int, screenId: Int = 0): ByteArray = message {
        writeByte(251); writeByte(0); writeShort(width); writeShort(height)
        writeByte(1); writeByte(0) // one screen, padding
        writeInt(screenId); writeShort(0); writeShort(0); writeShort(width); writeShort(height); writeInt(0)
    }

    private fun message(write: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).write() }.toByteArray()
}
