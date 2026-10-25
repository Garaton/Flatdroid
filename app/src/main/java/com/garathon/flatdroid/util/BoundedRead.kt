package com.garathon.flatdroid.util

import java.io.File

/** Most Flatdroid reads from a file a Flatpak controls (its metadata, desktop entry, swap marker). */
const val MAX_FLATPAK_TEXT_BYTES = 1L shl 20

/**
 * The file's text, or null when it is larger than [maxBytes]: the file may come from a Flatpak,
 * and reading a huge one whole could exhaust Flatdroid's memory.
 */
fun File.readTextUpTo(maxBytes: Long = MAX_FLATPAK_TEXT_BYTES): String? {
    if (length() > maxBytes) return null
    // length() can change before the read; read at most one byte past the limit to notice.
    val bytes = inputStream().use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (total <= maxBytes) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        if (total > maxBytes) return null
        out.toByteArray()
    }
    return String(bytes, Charsets.UTF_8)
}
