package com.garathon.flatdroid.setup

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where the gzipped Alpine minirootfs comes from. The installer checks [sha256] itself, so a
 * source only needs to provide the bytes. [open] may be called more than once.
 */
interface RootfsSource {
    val version: String
    val sha256: String
    fun open(): InputStream
    /** Drops any local copy; called after a successful install or a checksum mismatch. */
    fun cleanup() {}
}

/**
 * Downloads [url] to [cacheFile] once, then reads it from there. An interrupted download continues
 * from its `.part` file with an HTTP Range request (starting over if the server ignores it).
 */
class DownloadRootfsSource(
    override val version: String,
    override val sha256: String,
    val url: String,
    private val cacheFile: File,
    private val onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
) : RootfsSource {

    override fun open(): InputStream {
        if (!cacheFile.isFile) download()
        return cacheFile.inputStream()
    }

    override fun cleanup() {
        cacheFile.delete()
        File(cacheFile.path + ".part").delete()
    }

    private fun download() {
        cacheFile.parentFile?.mkdirs()
        val part = File(cacheFile.path + ".part")
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
                416 -> { // Range not satisfiable: the part file is unusable.
                    part.delete()
                    throw IOException("$url: HTTP 416, partial download discarded; try again")
                }
                else -> throw IOException("$url: HTTP ${conn.responseCode}")
            }
            var done = if (append) have else 0L
            val length = conn.contentLengthLong
            val total = if (length >= 0) done + length else -1L
            if (total > MAX_BYTES) throw IOException("$url: $total bytes is more than a minirootfs")
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.interrupted()) throw InterruptedException()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (done > MAX_BYTES) throw IOException("$url: more than ${MAX_BYTES shr 20} MiB, not a minirootfs")
                        onProgress(done, total)
                    }
                }
            }
            if (total >= 0 && done != total) throw IOException("$url: connection closed at $done of $total bytes")
        } finally {
            conn.disconnect()
        }
        if (!part.renameTo(cacheFile)) throw IOException("Could not move $part to $cacheFile")
    }

    companion object {
        /** A minirootfs is a few MiB; the checksum is checked later, so stop runaway downloads here. */
        private const val MAX_BYTES = 64L shl 20

        fun alpineUrl(branch: String, version: String, arch: String) =
            "https://dl-cdn.alpinelinux.org/alpine/$branch/releases/$arch/alpine-minirootfs-$version-$arch.tar.gz"
    }
}
