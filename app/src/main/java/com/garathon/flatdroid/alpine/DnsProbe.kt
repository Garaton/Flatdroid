package com.garathon.flatdroid.alpine

/**
 * A minimal plain DNS query and the check of its reply, to find out whether a server answers
 * ordinary DNS on port 53 (some Private DNS providers only answer DNS over TLS or HTTPS). Pure, so
 * it can be unit-tested.
 */
object DnsProbe {
    /** A recursive query for [name]'s A record with [id]. */
    fun query(id: Int, name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // Header: id, flags (recursion desired), one question, no other records.
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in name.trimEnd('.').split('.').filter { it.isNotEmpty() }) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "Not a DNS label: $label" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(byteArrayOf(0, 0, 1, 0, 1)) // end of name, type A, class IN
        return out.toByteArray()
    }

    /** Whether [reply] (its first [length] bytes) answers query [id]: a response with no error or "no such name". */
    fun answers(reply: ByteArray, length: Int, id: Int): Boolean {
        if (length < 12) return false
        val replyId = ((reply[0].toInt() and 0xff) shl 8) or (reply[1].toInt() and 0xff)
        val isResponse = reply[2].toInt() and 0x80 != 0
        val rcode = reply[3].toInt() and 0x0f
        return replyId == id && isResponse && (rcode == 0 || rcode == 3)
    }
}
