package com.garathon.flatdroid.util.io

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads [url] to [dest]. An interrupted download continues from its `.part` file with an
 * HTTP Range request (starting over if the server ignores it). Larger than [maxBytes] is an
 * error, since checksums are only checked afterwards. A part file the server refuses to continue
 * (HTTP 416) is deleted and the download starts again. Interruptible between reads.
 */
fun httpDownload(url: String, dest: File, maxBytes: Long, onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> }) {
    dest.parentFile?.mkdirs()
    val part = File(dest.path + ".part")
    val have = if (part.isFile) part.length() else 0L
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        // Android's HttpURLConnection otherwise asks for gzip and unpacks it transparently.
        conn.setRequestProperty("Accept-Encoding", "identity")
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        val append = when (conn.responseCode) {
            HttpURLConnection.HTTP_PARTIAL -> true
            HttpURLConnection.HTTP_OK -> false
            416 -> {
                // Range not satisfiable: the part file is unusable, or was complete when a stop came
                // before the rename. Once more from the start; without a range there is nothing to refuse.
                if (have == 0L) throw IOException("$url: HTTP 416")
                part.delete()
                conn.disconnect()
                return httpDownload(url, dest, maxBytes, onProgress)
            }
            else -> throw IOException("$url: HTTP ${conn.responseCode}")
        }
        val start = if (append) have else 0L
        var done = start
        val length = conn.contentLengthLong
        val total = if (length >= 0) done + length else -1L
        if (total > maxBytes) throw IOException("$url: $total bytes is more than expected (${maxBytes shr 20} MiB)")
        conn.inputStream.use { input ->
            FileOutputStream(part, append).use { output ->
                input.copyWithProgress(output) { copied ->
                    done = start + copied
                    if (done > maxBytes) throw IOException("$url: more than ${maxBytes shr 20} MiB, more than expected")
                    onProgress(done, total)
                }
            }
        }
        if (total >= 0 && done != total) throw IOException("$url: connection closed at $done of $total bytes")
    } finally {
        conn.disconnect()
    }
    if (!part.renameTo(dest)) throw IOException("Could not move $part to $dest")
}

/** Lowercase hex SHA-256 of [file]. */
fun sha256(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = BUFFER.get()!!
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    val bytes = digest.digest()
    val hex = CharArray(bytes.size * 2)
    for (i in bytes.indices) {
        val b = bytes[i].toInt() and 0xff
        hex[2 * i] = HEX[b ushr 4]
        hex[2 * i + 1] = HEX[b and 0x0f]
    }
    return String(hex)
}

/** One read buffer per thread: a baseline hashes tens of thousands of files, most of them small. */
private val BUFFER = ThreadLocal.withInitial { ByteArray(64 * 1024) }

private val HEX = "0123456789abcdef".toCharArray()
