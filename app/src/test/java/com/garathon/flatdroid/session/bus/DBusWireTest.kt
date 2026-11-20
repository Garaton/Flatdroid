package com.garathon.flatdroid.session.bus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DBusWireTest {
    private fun bytes(vararg parts: Any): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (part in parts) when (part) {
            is Int -> out.write(part)
            is String -> out.write(part.toByteArray())
            else -> error(part)
        }
        return out.toByteArray()
    }

    /** A reply worked out by hand from the specification: fields padded to 8, the header to 8, the body after. */
    private val reply = bytes(
        "l", 2, 1, 1, 7, 0, 0, 0, 7, 0, 0, 0, 31, 0, 0, 0,
        5, 1, "u", 0, 5, 0, 0, 0,
        6, 1, "s", 0, 4, 0, 0, 0, ":1.3", 0, 0, 0, 0,
        8, 1, "g", 0, 1, "s", 0,
        0,
        2, 0, 0, 0, "hi", 0,
    )

    @Test
    fun encodesAsTheSpecificationLaysOut() {
        val message = Message(DBusWire.METHOD_RETURN, DBusWire.NO_REPLY_EXPECTED, replySerial = 5u, destination = ":1.3", signature = "s", body = listOf("hi"))
        assertArrayEquals(reply, DBusWire.encode(message, 7u))
        assertEquals(55L, DBusWire.messageLength(reply.copyOf(16)))
    }

    @Test
    fun decodesWhatItEncodes() {
        val decoded = DBusWire.decode(reply)
        assertEquals(DBusWire.METHOD_RETURN, decoded.type)
        assertEquals(7u, decoded.serial)
        assertEquals(5u, decoded.replySerial)
        assertEquals(":1.3", decoded.destination)
        assertEquals(listOf("hi"), decoded.body)
    }

    @Test
    fun roundTripsEveryTypeFlatdroidUses() {
        val body = listOf(
            7.toUByte(), true, (-3).toShort(), 65000.toUShort(), -5, 4000000000u, -6L, 18000000000000000000uL, 1.5,
            "text", "/org/example", "a{sv}", UnixFd(0),
            listOf("a", "b"),
            mapOf("org.freedesktop.appearance" to mapOf("color-scheme" to Variant("u", 1u))),
            Struct(1.toUByte(), Variant("v", Variant("s", "nested"))),
            emptyList<Any>(),
            listOf(1L, 2L),
        )
        val signature = "ybnqiuxtdsogh" + "as" + "a{sa{sv}}" + "(yv)" + "ax" + "ax"
        val call = Message.call("org.example", "/org/example", "org.example.I", "M", signature, *body.toTypedArray())
        val decoded = DBusWire.decode(DBusWire.encode(call, 9u))
        assertEquals(body, decoded.body)
        assertEquals("org.example.I", decoded.iface)
        assertEquals("M", decoded.member)
        assertEquals("/org/example", decoded.path)
        assertEquals(signature, decoded.signature)
    }

    @Test
    fun anEmptyArrayStillPadsToItsElements() {
        // An empty array of 8-aligned elements: length 0, then padding to 8 (which the length leaves out).
        val call = Message.call("a.b", "/", "a.b", "M", "yax", 1.toUByte(), emptyList<Long>())
        val encoded = DBusWire.encode(call, 1u)
        val body = encoded.copyOfRange(encoded.size - 8, encoded.size)
        assertArrayEquals(bytes(1, 0, 0, 0, 0, 0, 0, 0), body)
        assertEquals(emptyList<Long>(), DBusWire.decode(encoded).body[1])
    }

    @Test
    fun readsBigEndianMessages() {
        val big = ByteBuffer.allocate(reply.size).order(ByteOrder.BIG_ENDIAN)
        big.put(bytes("B", 2, 1, 1)).putInt(7).putInt(7).putInt(31)
        big.put(bytes(5, 1, "u", 0)).putInt(5)
        big.put(bytes(6, 1, "s", 0)).putInt(4).put(bytes(":1.3", 0, 0, 0, 0))
        big.put(bytes(8, 1, "g", 0, 1, "s", 0, 0)).putInt(2).put(bytes("hi", 0))
        val decoded = DBusWire.decode(big.array())
        assertEquals(5u, decoded.replySerial)
        assertEquals(listOf("hi"), decoded.body)
    }

    @Test
    fun refusesWhatIsntValid() {
        fun broken(at: Int, value: Int) = reply.copyOf().also { it[at] = value.toByte() }
        // Endianness, version, a body longer than the bytes, non-zero padding, a string without its NUL.
        for (bad in listOf(broken(0, 'x'.code), broken(3, 2), broken(4, 9), broken(47, 1), broken(54, 'x'.code))) {
            assertThrows(WireException::class.java) { DBusWire.decode(bad) }
        }
        // A boolean other than 0 or 1.
        val bool = DBusWire.encode(Message.call("a.b", "/", "a.b", "M", "b", true), 1u)
        bool[bool.size - 4] = 2
        assertThrows(WireException::class.java) { DBusWire.decode(bool) }
        // A string that isn't UTF-8.
        val text = DBusWire.encode(Message.call("a.b", "/", "a.b", "M", "s", "ab"), 1u)
        text[text.size - 3] = 0xff.toByte()
        assertThrows(WireException::class.java) { DBusWire.decode(text) }
    }

    @Test
    fun arraysCantReachPastTheMessage() {
        val list = DBusWire.encode(Message.call("a.b", "/", "a.b", "M", "ay", listOf(1.toUByte())), 1u)
        // The array's length (just before its one byte, after padding) claims far more.
        ByteBuffer.wrap(list).order(ByteOrder.LITTLE_ENDIAN).putInt(list.size - 5, 0x7fffffff)
        assertThrows(WireException::class.java) { DBusWire.decode(list) }
    }

    @Test
    fun largeMessagesAreMeasuredNotRead() {
        val head = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).put(bytes("l", 1, 0, 1)).putInt(-1).putInt(1).putInt(16).array()
        assertEquals(16L + 16 + 0xffffffffL, DBusWire.messageLength(head))
        assertThrows(WireException::class.java) { DBusWire.decode(head) }
    }

    @Test
    fun signatures() {
        assertEquals(listOf("a{sa{sv}}", "s", "(yv)", "v"), Signature.split("a{sa{sv}}s(yv)v"))
        for (bad in listOf("a", "(", "()", "a{vs}", "{ss}", "a{s}", "z", "a{ss", "(s", "a".repeat(33) + "y", "(".repeat(33) + "y" + ")".repeat(33))) {
            assertThrows(bad, WireException::class.java) { Signature.split(bad) }
        }
        Signature.split("a".repeat(32) + "y")
    }

    @Test
    fun objectPaths() {
        for (good in listOf("/", "/org/freedesktop/portal/desktop", "/a_1")) assertEquals(good, true, Signature.isObjectPath(good))
        for (bad in listOf("", "org", "/org/", "//", "/a-b", "/a.b")) assertEquals(bad, false, Signature.isObjectPath(bad))
    }

    @Test
    fun valuesMustMatchTheSignature() {
        assertThrows(WireException::class.java) { DBusWire.encode(Message.call("a.b", "/", "a.b", "M", "u", 1), 1u) }
        assertThrows(WireException::class.java) { DBusWire.encode(Message.call("a.b", "/", "a.b", "M", "ss", "x"), 1u) }
        assertThrows(WireException::class.java) { DBusWire.encode(Message.call("a.b", "/", "a.b", "M", "s", "a\u0000b"), 1u) }
    }

    @Test
    fun authenticationLines() {
        assertEquals("AUTH EXTERNAL 3130323334\r\n", BusAuth.external(10234))
        assertEquals("AUTH ANONYMOUS 666c617464726f6964\r\n", BusAuth.ANONYMOUS)
    }
}
