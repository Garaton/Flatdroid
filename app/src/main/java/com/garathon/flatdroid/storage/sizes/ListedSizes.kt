package com.garathon.flatdroid.storage.sizes

import com.garathon.flatdroid.util.io.writeAtomically
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Sizes under directories whose files are named by their content and never change under the
 * same name (link2symlink's store, Flatpak's repository objects), saved in [dir] by path: a new
 * measurement only lists the names and stats the ones it hasn't seen, which is a fraction of
 * stat-ing them all. Directories are stat'ed every time (they grow). Saved only after a whole
 * walk, in one rename, so a stop part-way leaves the last complete record.
 *
 * Paths are kept as 64-bit hashes, so the record stays a few MB for a few hundred thousand files.
 */
class ListedSizes(private val dir: File) {
    /** What [ListedSizes] needs of an entry, without following links: whether it is a directory, and its blocks in bytes. */
    data class Entry(val isDirectory: Boolean, val bytes: Long)

    /**
     * Everything under [root] (itself included), as [StorageSizes.of] counts it, saved as [name].
     * [stat] is lstat (null when the entry is gone); [list] lists a directory's names; [stop] is
     * checked at each directory and ends the walk with [MeasureStopped].
     */
    fun measure(name: String, root: File, stat: (File) -> Entry?, list: (File) -> Array<String>?, stop: () -> Boolean): Long {
        val file = File(dir, name)
        val saved = read(file)
        val hashes = LongArray(INITIAL)
        val values = LongArray(INITIAL)
        var count = 0
        var seen = hashes to values
        fun add(hash: Long, value: Long) {
            if (count == seen.first.size) seen = seen.first.copyOf(count * 2) to seen.second.copyOf(count * 2)
            seen.first[count] = hash
            seen.second[count] = value
            count++
        }
        fun walk(entry: File, path: String, knownDirectory: Boolean): Long {
            val hash = hash(path)
            val known = if (knownDirectory) DIRECTORY else saved?.get(hash)
            if (known != null && known != DIRECTORY) {
                add(hash, known)
                return known
            }
            val stat = stat(entry) ?: return 0L
            if (!stat.isDirectory) {
                add(hash, stat.bytes)
                return stat.bytes
            }
            add(hash, DIRECTORY)
            if (stop()) throw MeasureStopped()
            return stat.bytes + (list(entry)?.sumOf { child -> walk(File(entry, child), "$path/$child", knownDirectory = false) } ?: 0L)
        }
        val total = walk(root, "", knownDirectory = true)
        if (!stop()) write(file, seen.first.copyOf(count), seen.second.copyOf(count))
        return total
    }

    private class Saved(val hashes: LongArray, val values: LongArray) {
        fun get(hash: Long): Long? = hashes.binarySearch(hash).takeIf { it >= 0 }?.let { values[it] }
    }

    private fun read(file: File): Saved? = try {
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            if (input.readInt() != MAGIC) return null
            val count = input.readInt()
            if (count < 0 || count > MAX_ENTRIES) return null
            val hashes = LongArray(count)
            val values = LongArray(count)
            for (i in 0 until count) {
                hashes[i] = input.readLong()
                values[i] = input.readLong()
                if (values[i] < DIRECTORY || (i > 0 && hashes[i] <= hashes[i - 1])) return null
            }
            if (input.readInt() != END) return null
            Saved(hashes, values)
        }
    } catch (_: Exception) {
        null
    }

    private fun write(file: File, hashes: LongArray, values: LongArray) {
        val order = hashes.indices.sortedBy { hashes[it] }
        try {
            file.writeAtomically(durable = false) { stream ->
                val out = DataOutputStream(BufferedOutputStream(stream))
                out.writeInt(MAGIC)
                // A hash two paths share (alike in 64 bits) isn't kept: both are stat'ed each time.
                val unique = order.filterIndexed { i, at ->
                    (i == 0 || hashes[at] != hashes[order[i - 1]]) && (i == order.lastIndex || hashes[at] != hashes[order[i + 1]])
                }
                out.writeInt(unique.size)
                for (at in unique) {
                    out.writeLong(hashes[at])
                    out.writeLong(values[at])
                }
                out.writeInt(END)
                out.flush()
            }
        } catch (_: Exception) {
            file.delete()
        }
    }

    companion object {
        /** Saved for a directory, which is stat'ed and listed every time. */
        private const val DIRECTORY = -1L
        private const val MAGIC = 0x46444c31 // "FDL1"
        private const val END = 0x454e4421 // "END!"
        private const val MAX_ENTRIES = 50_000_000
        private const val INITIAL = 1024

        /** 64-bit FNV-1a over the path's characters. */
        fun hash(path: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (c in path) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }
    }
}
