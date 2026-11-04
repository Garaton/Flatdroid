package com.garathon.flatdroid.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WaylandWireTest {
    private fun ints(vararg values: Int): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder())
        values.forEach(buffer::putInt)
        return buffer.array()
    }

    @Test
    fun aMessageHasItsSizeAndOpcodeInOneWord() {
        assertArrayEquals(ints(1, (12 shl 16) or 1, 2), WaylandWire.message(1, 1, 2))
    }

    @Test
    fun stringsCountTheirNulAndArePaddedTo4Bytes() {
        val message = WaylandWire.message(5, 0, "abc") // 3 + NUL: 4 bytes, no more padding
        assertEquals(8 + 4 + 4, message.size)
        assertEquals(8 + 4 + 8, WaylandWire.message(5, 0, "abcd").size) // 4 + NUL + 3 of padding
    }

    @Test
    fun wholeMessagesAreParsedAndAPartOneLeft() {
        val first = WaylandWire.message(2, 0, 7, "wl_seat", 9)
        val second = WaylandWire.message(3, 1, 4)
        val data = first + second
        val (events, used) = WaylandWire.parse(data, data.size - 2)
        assertEquals(first.size, used)
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals(2, event.target)
        assertEquals(7, event.int())
        assertEquals("wl_seat", event.string())
        assertEquals(9, event.int())
    }
}
