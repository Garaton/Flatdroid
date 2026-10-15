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
 * data directory), so the renames here are atomic and copy nothing.
 *
 * A complete rootfs is never replaced by [install]: Alpine is upgraded inside it with apk. A reset
 * ([setAside] or [discardKept], then [wipe]) is the only way to start over; [CARRIED] paths set
 * aside are moved into the next rootfs at the same place.
 */
class RootfsInstaller(alpineDir: File, private val workDir: File, private val fs: TarFs) {
    val rootfs = File(alpineDir, "rootfs")
    private val marker = File(rootfs, MARKER)
    private val partial = File(workDir, "rootfs.partial")
    private val keptDir = File(alpineDir, "kept")

    /** The Alpine version the rootfs was extracted from, or null when there is no complete rootfs. */
    fun installedVersion(): String? = marker.takeIf { it.isFile }?.readText()?.trim()

    /** True when [CARRIED] paths are waiting to go into the next rootfs. */
    fun hasKept(): Boolean = CARRIED.any { lexists(File(keptDir, it)) }

    fun install(source: RootfsSource) {
        if (installedVersion() != null) return

        val actual = sha256(source)
        if (actual != source.sha256) {
            source.cleanup()
            throw IOException("Alpine rootfs checksum mismatch: $actual, expected ${source.sha256}")
        }

        rescueFromPartial()
        fs.deleteTree(partial)
        fs.deleteTree(rootfs) // incomplete: no marker
        source.open().use { raw ->
            GZIPInputStream(raw.buffered(), 64 * 1024).use { TarExtractor(fs).extract(it, partial) }
        }
        for (rel in CARRIED) {
            val kept = File(keptDir, rel)
            if (!lexists(kept)) continue
            val target = File(partial, rel)
            fs.deleteTree(target)
            target.parentFile!!.mkdirs()
            move(kept, target)
        }
        File(partial, ".l2s").mkdirs()
        File(partial, MARKER).writeText(source.version + "\n")
        rootfs.parentFile?.mkdirs()
        move(partial, rootfs)
        fs.deleteTree(keptDir)
        source.cleanup()
    }

    /**
     * First half of a reset that keeps Flatpaks: marks the rootfs incomplete, then moves the
     * [CARRIED] paths out of it. Safe to repeat after an interruption.
     */
    fun setAside() {
        marker.delete()
        rescueFromPartial()
        val root = rootfs.canonicalFile
        for (rel in CARRIED) {
            val source = File(rootfs, rel)
            val kept = File(keptDir, rel)
            if (!lexists(source) || lexists(kept)) continue
            // Refuse a path that leaves the rootfs through a symlinked parent.
            val parent = source.parentFile!!.canonicalPath
            if (parent != root.path && !parent.startsWith(root.path + File.separator)) {
                throw IOException("$source is outside the rootfs")
            }
            kept.parentFile!!.mkdirs()
            move(source, kept)
        }
    }

    /** First half of a reset that wipes everything: marks the rootfs incomplete and drops anything kept. */
    fun discardKept() {
        marker.delete()
        fs.deleteTree(partial)
        fs.deleteTree(keptDir)
    }

    /** Second half of a reset: deletes the (incomplete) rootfs without following symlinks. */
    fun wipe() {
        check(installedVersion() == null) { "Refusing to wipe a complete rootfs; set it aside first" }
        fs.deleteTree(partial)
        fs.deleteTree(rootfs)
    }

    /**
     * An install interrupted after moving kept paths into the partial rootfs: move them back
     * before the partial rootfs is deleted. (The minirootfs contains neither path, so anything
     * there came from [keptDir].)
     */
    private fun rescueFromPartial() {
        for (rel in CARRIED) {
            val inPartial = File(partial, rel)
            val kept = File(keptDir, rel)
            if (lexists(inPartial) && !lexists(kept)) {
                kept.parentFile!!.mkdirs()
                move(inPartial, kept)
            }
        }
    }

    private fun lexists(file: File) = fs.isSymlink(file) || file.exists()

    private fun move(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("Could not move $from to $to")
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

        /**
         * What "keep Flatpaks" keeps, relative to the rootfs: Flatpak's user installation (apps,
         * runtimes, remotes, its config and overrides) and link2symlink's backing files, which its
         * files point to by absolute host path. Both go back to the same paths, so those links
         * stay valid.
         */
        val CARRIED = listOf(".l2s", "root/.local/share/flatpak")
    }
}
