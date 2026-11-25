package com.garathon.flatdroid.util.io

import java.io.File
import java.io.InputStream

/** Most Flatdroid reads from a file a Flatpak controls (its metadata, desktop entry, swap marker). */
const val MAX_FLATPAK_TEXT_BYTES = 1L shl 20

/**
 * The file's text, or null when it is larger than [maxBytes]: the file may come from a Flatpak,
 * and reading a huge one whole could exhaust Flatdroid's memory.
 */
fun File.readTextUpTo(maxBytes: Long = MAX_FLATPAK_TEXT_BYTES): String? {
    if (length() > maxBytes) return null
    // length() can change before the read: readBytesUpTo notices.
    val bytes = inputStream().use { it.readBytesUpTo(maxBytes) } ?: return null
    return String(bytes, Charsets.UTF_8)
}

/** Up to [max] bytes, or null when there are more ([InputStream.readNBytes] needs Android 13). */
fun InputStream.readBytesUpTo(max: Long): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    // One byte past the limit is read to notice a larger one.
    while (total <= max) {
        val n = read(buffer, 0, minOf(buffer.size.toLong(), max + 1 - total).toInt())
        if (n < 0) break
        out.write(buffer, 0, n)
        total += n
    }
    return if (total > max) null else out.toByteArray()
}
