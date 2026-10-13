package com.garathon.flatdroid.setup

import java.io.File
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
 * Downloads the pinned minirootfs from Alpine's CDN to [cacheFile] once, then reads it from there.
 * An interrupted download starts over (resume comes with the setup flow).
 */
class DownloadRootfsSource(
    override val version: String,
    branch: String,
    arch: String,
    override val sha256: String,
    private val cacheFile: File,
    private val onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
) : RootfsSource {
    val url = "https://dl-cdn.alpinelinux.org/alpine/$branch/releases/$arch/alpine-minirootfs-$version-$arch.tar.gz"

    override fun open(): InputStream {
        if (!cacheFile.isFile) download()
        return cacheFile.inputStream()
    }

    override fun cleanup() {
        cacheFile.delete()
    }

    private fun download() {
        cacheFile.parentFile?.mkdirs()
        val part = File(cacheFile.path + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("$url: HTTP ${conn.responseCode}")
            }
            val total = conn.contentLength.toLong()
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!part.renameTo(cacheFile)) throw IOException("Could not move $part to $cacheFile")
    }
}
