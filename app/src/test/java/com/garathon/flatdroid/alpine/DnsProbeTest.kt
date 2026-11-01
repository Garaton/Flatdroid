package com.garathon.flatdroid.alpine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsProbeTest {
    @Test
    fun queryIsOneRecursiveQuestionForTheName() {
        val expected = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0) +
            byteArrayOf(3) + "dns".toByteArray() + byteArrayOf(6) + "google".toByteArray() + byteArrayOf(0, 0, 1, 0, 1)
        assertArrayEquals(expected, DnsProbe.query(0x1234, "dns.google"))
        assertArrayEquals(expected, DnsProbe.query(0x1234, "dns.google."))
    }

    @Test
    fun onlyAMatchingResponseWithAnAnswerOrNoSuchNameCounts() {
        fun reply(id: Int, flags: Int, rcode: Int) =
            byteArrayOf((id shr 8).toByte(), id.toByte(), flags.toByte(), rcode.toByte(), 0, 1, 0, 1, 0, 0, 0, 0)
        assertTrue(DnsProbe.answers(reply(0x1234, 0x81, 0x80), 12, 0x1234)) // no error
        assertTrue(DnsProbe.answers(reply(0x1234, 0x81, 0x83), 12, 0x1234)) // no such name
        assertFalse(DnsProbe.answers(reply(0x1234, 0x81, 0x85), 12, 0x1234)) // refused
        assertFalse(DnsProbe.answers(reply(0x1235, 0x81, 0x80), 12, 0x1234)) // another query's
        assertFalse(DnsProbe.answers(reply(0x1234, 0x01, 0x80), 12, 0x1234)) // a query, not a response
        assertFalse(DnsProbe.answers(reply(0x1234, 0x81, 0x80), 11, 0x1234)) // too short
    }
}
