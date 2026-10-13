package com.garathon.flatdroid.setup

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Extracts the Alpine minirootfs to `<alpineDir>/rootfs`. The tarball's sha256 is checked before
 * anything is written; extraction goes to `<workDir>/rootfs.partial` and is renamed into place
 * when complete, so an interrupted run starts over instead of leaving a half-extracted root.
 * [workDir] is the cache directory, which is on the same filesystem as files/ (both in the app's
 * data directory), so the rename is atomic.
 *
 * A complete rootfs is never replaced: Alpine is upgraded inside it with apk, and re-extracting
 * would delete everything installed in it, Flatpaks included.
 */
class RootfsInstaller(alpineDir: File, private val workDir: File, private val fs: TarFs) {
    val rootfs = File(alpineDir, "rootfs")
    private val marker = File(rootfs, MARKER)

    /** The Alpine version the rootfs was extracted from, or null when there is no complete rootfs. */
    fun installedVersion(): String? = marker.takeIf { it.isFile }?.readText()?.trim()

    fun install(source: RootfsSource) {
        if (installedVersion() != null) return

        val actual = sha256(source)
        if (actual != source.sha256) {
            source.cleanup()
            throw IOException("Alpine rootfs checksum mismatch: $actual, expected ${source.sha256}")
        }

        val partial = File(workDir, "rootfs.partial")
        fs.deleteTree(partial)
        fs.deleteTree(rootfs) // incomplete: no marker
        source.open().use { raw ->
            GZIPInputStream(raw.buffered(), 64 * 1024).use { TarExtractor(fs).extract(it, partial) }
        }
        File(partial, ".l2s").mkdirs()
        File(partial, MARKER).writeText(source.version + "\n")
        rootfs.parentFile?.mkdirs()
        if (!partial.renameTo(rootfs)) throw IOException("Could not move $partial to $rootfs")
        source.cleanup()
    }

    private fun sha256(source: RootfsSource): String {
        val digest = MessageDigest.getInstance("SHA-256")
        source.open().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Written last; holds the Alpine version. Its absence means the rootfs is incomplete. */
        const val MARKER = ".flatdroid-rootfs"
    }
}
