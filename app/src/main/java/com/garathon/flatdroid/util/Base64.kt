package com.garathon.flatdroid.util

// Standard base64 with padding: java.util.Base64 needs Android 8.0, and Android's own isn't in unit tests.

private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/** [bytes] as standard base64 with padding. */
fun base64(bytes: ByteArray): String {
    val out = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xff
        val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
        val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
        out.append(ALPHABET[b0 shr 2]).append(ALPHABET[((b0 and 3) shl 4) or (b1 shr 4)])
        out.append(if (i + 1 < bytes.size) ALPHABET[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
        out.append(if (i + 2 < bytes.size) ALPHABET[b2 and 63] else '=')
        i += 3
    }
    return out.toString()
}

/** The bytes of standard base64 with padding; null if [text] isn't that. */
fun fromBase64(text: String): ByteArray? {
    if (text.length % 4 != 0) return null
    val pad = text.length - text.trimEnd('=').length
    if (pad > 2) return null
    val out = ByteArray(text.length / 4 * 3 - pad)
    var o = 0
    var i = 0
    while (i < text.length) {
        var group = 0
        for (j in 0 until 4) {
            val c = text[i + j]
            val v = if (c == '=' && i + j >= text.length - pad) 0 else ALPHABET.indexOf(c).takeIf { it >= 0 } ?: return null
            group = (group shl 6) or v
        }
        for (shift in intArrayOf(16, 8, 0)) if (o < out.size) out[o++] = (group shr shift).toByte()
        i += 4
    }
    return out
}
