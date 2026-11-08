package com.garathon.flatdroid.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class RfbClientTest {
    /** The bytes a server sends: written with [server], read by the client from the start. */
    private fun client(server: DataOutputStream.() -> Unit): Pair<RfbClient, ByteArrayOutputStream> {
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).server() }.toByteArray()
        val sent = ByteArrayOutputStream()
        return RfbClient(ByteArrayInputStream(bytes), sent) to sent
    }

    private fun DataOutputStream.handshake(width: Int = 4, height: Int = 3) {
        writeBytes("RFB 003.008\n")
        writeByte(1); writeByte(1) // one security type: None
        writeInt(0) // security result: OK
        writeShort(width); writeShort(height)
        write(ByteArray(16)) // the server's pixel format
        writeInt(4); writeBytes("test")
    }

    private fun DataOutputStream.update(vararg rects: DataOutputStream.() -> Unit) {
        writeByte(0); writeByte(0); writeShort(rects.size)
        rects.forEach { it() }
    }

    private fun DataOutputStream.rect(x: Int, y: Int, w: Int, h: Int, encoding: Int) {
        writeShort(x); writeShort(y); writeShort(w); writeShort(h); writeInt(encoding)
    }

    private class Recorder : RfbListener {
        val events = mutableListOf<String>()
        override fun damaged(x: Int, y: Int, width: Int, height: Int) { events += "damaged $x,$y ${width}x$height" }
        override fun updateDone() { events += "done" }
        override fun resized(width: Int, height: Int) { events += "resized ${width}x$height" }
        override fun clipboard(text: String) { events += "clipboard $text" }
        override fun clipboardRequested() { events += "requested" }
    }

    @Test
    fun theHandshakeAsksForNoLoginOurPixelFormatAndAFullUpdate() {
        val (client, sent) = client { handshake() }
        client.connect()
        assertEquals("test", client.name)
        assertEquals(4, client.framebuffer.width)
        assertEquals(3, client.framebuffer.height)
        val expected = "RFB 003.008\n".toByteArray() + byteArrayOf(1, 1) +
            Rfb.setPixelFormat() + Rfb.setEncodings() + Rfb.updateRequest(false, 0, 0, 4, 3)
        assertArrayEquals(expected, sent.toByteArray())
    }

    @Test
    fun ourPixelFormatIsTheBitmapsByteOrder() {
        val format = Rfb.setPixelFormat()
        assertEquals(20, format.size)
        val f = DataInputStream(format.inputStream(4, 16))
        assertEquals(32, f.readUnsignedByte()) // bits per pixel
        assertEquals(24, f.readUnsignedByte()) // depth
        assertEquals(0, f.readUnsignedByte()) // little-endian
        assertEquals(1, f.readUnsignedByte()) // true colour
        repeat(3) { assertEquals(255, f.readUnsignedShort()) }
        assertEquals(listOf(0, 8, 16), List(3) { f.readUnsignedByte() }) // red, green, blue shifts: bytes R, G, B, unused
    }

    @Test
    fun aServerThatWantsALoginIsRefused() {
        val (client, _) = client {
            writeBytes("RFB 003.008\n")
            writeByte(1); writeByte(2) // VncAuth only
        }
        assertRefused("the server asks for a login") { client.connect() }
    }

    @Test
    fun theServersReasonIsKeptWhenItRefuses() {
        val (client, _) = client {
            writeBytes("RFB 003.008\n")
            writeByte(0); writeInt(4); writeBytes("busy")
        }
        assertRefused("busy") { client.connect() }
    }

    @Test
    fun oldProtocolVersionsAreRefused() {
        val (client, _) = client { writeBytes("RFB 003.003\n") }
        assertRefused("RFB 3.3 is too old") { client.connect() }
    }

    @Test
    fun rawRectanglesLandInTheFramebuffer() {
        val (client, _) = client {
            handshake()
            update(
                { rect(1, 1, 2, 1, Rfb.RAW); write(byteArrayOf(1, 2, 3, 0, 4, 5, 6, 0)) },
                { rect(0, 2, 4, 1, Rfb.RAW); write(ByteArray(16) { 9 }) },
            )
        }
        client.connect()
        val recorder = Recorder()
        client.readMessage(recorder)
        val fb = client.framebuffer
        assertArrayEquals(byteArrayOf(1, 2, 3, 0, 4, 5, 6, 0), fb.pixels.copyOfRange(fb.stride + 4, fb.stride + 12))
        assertTrue(fb.pixels.copyOfRange(2 * fb.stride, 3 * fb.stride).all { it == 9.toByte() })
        assertTrue(fb.pixels.copyOfRange(0, fb.stride).all { it == 0.toByte() })
        assertEquals(listOf("damaged 1,1 2x1", "damaged 0,2 4x1", "done"), recorder.events)
    }

    @Test
    fun copyRectMovesPixelsEvenWhenSourceAndDestinationOverlap() {
        val (client, _) = client {
            handshake(width = 1, height = 3)
            update({ rect(0, 0, 1, 3, Rfb.RAW); write(byteArrayOf(1, 1, 1, 0, 2, 2, 2, 0, 3, 3, 3, 0)) })
            update({ rect(0, 1, 1, 2, Rfb.COPY_RECT); writeShort(0); writeShort(0) }) // rows 0-1 down by one
        }
        client.connect()
        client.readMessage(Recorder())
        client.readMessage(Recorder())
        assertArrayEquals(byteArrayOf(1, 1, 1, 0, 1, 1, 1, 0, 2, 2, 2, 0), client.framebuffer.pixels)
    }

    @Test
    fun rectanglesOutsideTheScreenAreSkipped() {
        // A server can send one update of the old size after a resize: its rectangles are passed
        // over, and those that fit still land.
        val (client, _) = client {
            handshake()
            update(
                { rect(3, 0, 2, 1, Rfb.RAW); write(ByteArray(8) { 7 }) },
                { rect(0, 0, 2, 2, Rfb.COPY_RECT); writeShort(3); writeShort(0) },
                { rect(0, 0, 1, 1, Rfb.RAW); write(byteArrayOf(1, 2, 3, 0)) },
            )
        }
        client.connect()
        client.readMessage(Recorder())
        val pixels = client.framebuffer.pixels
        assertArrayEquals(byteArrayOf(1, 2, 3, 0), pixels.copyOfRange(0, 4))
        assertEquals(1, pixels.count { it == 1.toByte() }) // nothing of the skipped rectangles
    }

    @Test
    fun rectanglesPastAnyScreenEndTheConnection() {
        val (client, _) = client {
            handshake()
            update({ rect(0, 0, RfbClient.MAX_SCREEN + 1, 1, Rfb.RAW) })
        }
        client.connect()
        assertRefused("rectangle outside the screen") { client.readMessage(Recorder()) }
    }

    @Test
    fun screensPastTheLimitAreRefused() {
        val (client, _) = client { handshake(width = RfbClient.MAX_SCREEN + 1, height = 2) }
        assertRefused("screen too large (8193x2)") { client.connect() }
    }

    @Test
    fun aDesktopSizeUpdateReplacesTheFramebuffer() {
        val (client, _) = client {
            handshake()
            update({ rect(0, 0, 8, 6, Rfb.DESKTOP_SIZE) })
        }
        client.connect()
        val recorder = Recorder()
        client.readMessage(recorder)
        assertEquals(8, client.framebuffer.width)
        assertEquals(listOf("resized 8x6", "done"), recorder.events)
    }

    @Test
    fun extendedDesktopSizeAllowsResizingWithTheServersScreenId() {
        val (client, sent) = client {
            handshake()
            update({
                rect(0, 0, 4, 3, Rfb.EXTENDED_DESKTOP_SIZE)
                writeByte(1); write(ByteArray(3))
                writeInt(0x1234); writeShort(0); writeShort(0); writeShort(4); writeShort(3); writeInt(0)
            })
        }
        client.connect()
        val before = sent.size()
        client.resize(10, 10)
        assertEquals(before, sent.size()) // not offered yet: nothing sent
        val recorder = Recorder()
        client.readMessage(recorder)
        assertEquals(listOf("done"), recorder.events) // same size: no new framebuffer
        client.resize(8, 6)
        assertArrayEquals(Rfb.setDesktopSize(8, 6, 0x1234), sent.toByteArray().copyOfRange(before, sent.size()))
    }

    @Test
    fun theBellIsIgnoredAndPlainCutTextIsLatin1() {
        val (client, _) = client {
            handshake()
            writeByte(Rfb.BELL)
            writeByte(Rfb.SERVER_CUT_TEXT); write(ByteArray(3)); writeInt(3); write(byteArrayOf(0x61, 0xe9.toByte(), 0x62))
        }
        client.connect()
        val recorder = Recorder()
        client.readMessage(recorder)
        client.readMessage(recorder)
        assertEquals(listOf("clipboard aéb"), recorder.events)
    }

    @Test
    fun theExtendedClipboardAnswersCapsAsksForAnnouncedTextAndReadsIt() {
        val caps = ExtendedClipboard.caps().drop(8).toByteArray() // the payload of the client's own caps
        val provide = ExtendedClipboard.provide("ünï\ncode").drop(8).toByteArray()
        val notify = ExtendedClipboard.notify().drop(8).toByteArray()
        val (client, sent) = client {
            handshake()
            for (payload in listOf(caps, notify, provide)) {
                writeByte(Rfb.SERVER_CUT_TEXT); write(ByteArray(3)); writeInt(-payload.size); write(payload)
            }
        }
        client.connect()
        val start = sent.size()
        val recorder = Recorder()
        repeat(3) { client.readMessage(recorder) }
        assertArrayEquals(ExtendedClipboard.caps() + ExtendedClipboard.request(), sent.toByteArray().copyOfRange(start, sent.size()))
        assertEquals(listOf("clipboard ünï\ncode"), recorder.events)
    }

    @Test
    fun ourTextIsOnlyAnnouncedAndAskedForWhenAnAppPastes() {
        val caps = ExtendedClipboard.caps().drop(8).toByteArray()
        val request = ExtendedClipboard.request().drop(8).toByteArray()
        val (client, sent) = client {
            handshake()
            for (payload in listOf(caps, request)) {
                writeByte(Rfb.SERVER_CUT_TEXT); write(ByteArray(3)); writeInt(-payload.size); write(payload)
            }
        }
        client.connect()
        // Announced before the server said it takes the extended clipboard: sent once it does.
        client.announceClipboard()
        val start = sent.size()
        val recorder = Recorder()
        client.readMessage(recorder)
        assertArrayEquals(ExtendedClipboard.caps() + ExtendedClipboard.notify(), sent.toByteArray().copyOfRange(start, sent.size()))
        // The paste asks for the text; only then is it sent.
        client.readMessage(recorder)
        assertEquals(listOf("requested"), recorder.events)
        val before = sent.size()
        client.provideClipboard("a\nb")
        val provided = sent.toByteArray().copyOfRange(before, sent.size())
        assertEquals(ExtendedClipboard.Message.Provide("a\nb"), ExtendedClipboard.parse(provided.copyOfRange(8, provided.size)))
    }

    @Test
    fun aServerWithoutTheExtendedClipboardGetsNothingOfOurs() {
        val (client, sent) = client { handshake() }
        client.connect()
        val start = sent.size()
        client.announceClipboard()
        assertEquals(start, sent.size())
    }

    @Test
    fun aRequestForTextNeverAnnouncedIsIgnored() {
        val caps = ExtendedClipboard.caps().drop(8).toByteArray()
        val request = ExtendedClipboard.request().drop(8).toByteArray()
        val (client, _) = client {
            handshake()
            for (payload in listOf(caps, request)) {
                writeByte(Rfb.SERVER_CUT_TEXT); write(ByteArray(3)); writeInt(-payload.size); write(payload)
            }
        }
        client.connect()
        val recorder = Recorder()
        repeat(2) { client.readMessage(recorder) }
        assertEquals(emptyList<String>(), recorder.events)
    }

    @Test
    fun updateRequestsStayInsideTheScreen() {
        val (client, sent) = client { handshake() }
        client.connect()
        val start = sent.size()
        client.requestUpdate(x = 2, y = 1, width = 100, height = 100)
        assertArrayEquals(Rfb.updateRequest(true, 2, 1, 2, 2), sent.toByteArray().copyOfRange(start, sent.size()))
    }

    @Test
    fun pointerAndKeyMessages() {
        assertArrayEquals(byteArrayOf(5, 4, 0, 10, 0, 20), Rfb.pointerEvent(4, 10, 20))
        assertArrayEquals(byteArrayOf(5, 0, 0, 0, 0, 0), Rfb.pointerEvent(0, -5, -1))
        assertArrayEquals(byteArrayOf(4, 1, 0, 0, 0, 0, 0xff.toByte(), 0x0d), Rfb.keyEvent(true, 0xff0d))
    }

    private fun assertRefused(message: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected a refusal")
        } catch (e: RfbException) {
            assertEquals(message, e.message)
        } catch (e: IOException) {
            throw AssertionError("expected RfbException($message), got $e")
        }
    }
}
