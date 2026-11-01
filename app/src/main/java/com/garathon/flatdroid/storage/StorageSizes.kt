package com.garathon.flatdroid.storage

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.garathon.flatdroid.flatpak.L2s
import java.io.File

/** One app's storage, in bytes, as its Storage section shows it. */
data class AppStorage(
    /** The app's own Flatpak files (its deployments and its own extensions such as translations), without runtimes. */
    val app: Long,
    /** What is in its cache directory. */
    val cache: Long,
    val privateHome: Long,
    /** Its settings and other files of its own ([AppFiles]). */
    val settings: Long,
    /** These are the last measured sizes and a new measurement is under way (or waits for a task to end). */
    val measuring: Boolean = false,
) {
    val total: Long get() = app + cache + privateHome + settings
}

/** Thrown by a measurement whose [StorageSizes] stop check said to end it. */
class MeasureStopped : Exception("Measuring stopped")

/**
 * Disk space as `du` counts it: allocated blocks of every entry, directories included. [stop] is
 * checked at each directory; when it is true the walk ends with [MeasureStopped].
 */
object StorageSizes {
    /** Under [file], without following links. */
    fun of(file: File, stop: () -> Boolean = { false }): Long {
        fun walk(entry: File): Long {
            val stat = lstat(entry) ?: return 0L
            val own = stat.st_blocks * 512
            if (!OsConstants.S_ISDIR(stat.st_mode)) return own
            if (stop()) throw MeasureStopped()
            return own + (entry.listFiles()?.sumOf(::walk) ?: 0L)
        }
        return walk(file)
    }

    /** What [dir] holds, without the directory itself. */
    fun contents(dir: File): Long = dir.listFiles()?.sumOf { of(it) } ?: 0L

    /**
     * Under [files], counting each link into [l2sDir] as itself (a long link takes a block) and
     * the content it leads to (Flatpak's files are such links), each content once however many
     * links lead to it: Flatpak keeps one copy of identical files, and deployments share them.
     */
    fun following(files: List<File>, l2sDir: File, stop: () -> Boolean = { false }): Long {
        val seen = HashSet<Long>()
        fun walk(entry: File): Long {
            val stat = lstat(entry) ?: return 0L
            return when {
                OsConstants.S_ISDIR(stat.st_mode) -> {
                    if (stop()) throw MeasureStopped()
                    stat.st_blocks * 512 + (entry.listFiles()?.sumOf(::walk) ?: 0L)
                }
                OsConstants.S_ISLNK(stat.st_mode) -> {
                    val target = try { Os.readlink(entry.path) } catch (_: ErrnoException) { null }
                    if (target != null && L2s.inStore(target, l2sDir)) {
                        val content = try { Os.stat(entry.path) } catch (_: ErrnoException) { null }
                        stat.st_blocks * 512 + if (content != null && seen.add(content.st_ino)) content.st_blocks * 512 else 0L
                    } else {
                        stat.st_blocks * 512
                    }
                }
                else -> stat.st_blocks * 512
            }
        }
        return files.sumOf(::walk)
    }

    private fun lstat(file: File) = try {
        Os.lstat(file.path)
    } catch (_: ErrnoException) {
        null
    }
}
