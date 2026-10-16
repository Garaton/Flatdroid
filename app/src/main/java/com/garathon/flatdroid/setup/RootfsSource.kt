package com.garathon.flatdroid.setup

import com.garathon.flatdroid.util.httpDownload
import java.io.File
import java.io.InputStream

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

    private fun download() = httpDownload(url, cacheFile, MAX_BYTES, onProgress)

    companion object {
        /** A minirootfs is a few MiB; the checksum is checked later, so stop runaway downloads here. */
        private const val MAX_BYTES = 64L shl 20

        fun alpineUrl(branch: String, version: String, arch: String) =
            "https://dl-cdn.alpinelinux.org/alpine/$branch/releases/$arch/alpine-minirootfs-$version-$arch.tar.gz"
    }
}
