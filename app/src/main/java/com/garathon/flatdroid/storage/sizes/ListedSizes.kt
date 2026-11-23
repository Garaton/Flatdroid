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
 * stat-ing them all. When no directory of the last walk changed (their change times, which move
 * whenever an entry is added, removed or renamed in them), nothing is listed: the saved total
 * stands. Saved only after a whole walk, in one rename, so a stop part-way leaves the last
 * complete record.
 *
 * Paths of files are kept as 64-bit hashes, so the record stays a few MB for a few hundred thousand files.
 */
class ListedSizes(private val dir: File) {
    /**
     * What [ListedSizes] needs of an entry, without following links: whether it is a directory,
     * its blocks in bytes, and (for a directory) its change time.
     */
    data class Entry(val isDirectory: Boolean, val bytes: Long, val changed: Long = 0L)

    /**
     * Everything under [root] (itself included), as [StorageSizes.of] counts it, saved as [name].
     * [stats] lstats names in a directory, many at a time (null for one that is gone); [list] lists
     * a directory's names; [stop] is checked at each directory and ends the walk with [MeasureStopped].
     */
    fun measure(
        name: String,
        root: File,
        stats: (File, List<String>) -> List<Entry?>,
        list: (File) -> Array<String>?,
        stop: () -> Boolean,
    ): Long {
        val file = File(dir, name)
        val saved = read(file)
        if (saved != null && unchanged(saved, root, stats)) return saved.total
        val hashes = LongArray(INITIAL)
        val values = LongArray(INITIAL)
        var count = 0
        var seen = hashes to values
        val folders = LinkedHashMap<String, Long>()
        fun add(hash: Long, value: Long) {
            if (count == seen.first.size) seen = seen.first.copyOf(count * 2) to seen.second.copyOf(count * 2)
            seen.first[count] = hash
            seen.second[count] = value
            count++
        }
        fun walk(entry: File, path: String, stat: Entry): Long {
            if (!stat.isDirectory) {
                add(hash(path), stat.bytes)
                return stat.bytes
            }
            add(hash(path), DIRECTORY)
            folders[path] = stat.changed
            if (stop()) throw MeasureStopped()
            val names = list(entry) ?: return stat.bytes
            // Known files from the record; the rest (new names, directories) stat'ed in one call.
            var sum = stat.bytes
            val unknown = ArrayList<String>()
            for (child in names) {
                val childHash = hash("$path/$child")
                val known = saved?.get(childHash)
                if (known != null && known != DIRECTORY) {
                    add(childHash, known)
                    sum += known
                } else {
                    unknown += child
                }
            }
            val unknownStats = if (unknown.isEmpty()) emptyList() else stats(entry, unknown)
            for (i in unknown.indices) {
                val childStat = unknownStats.getOrNull(i) ?: continue
                sum += walk(File(entry, unknown[i]), "$path/${unknown[i]}", childStat)
            }
            return sum
        }
        val rootStat = stats(root.parentFile ?: File("/"), listOf(root.name)).firstOrNull() ?: return 0L
        val total = walk(root, "", rootStat)
        if (!stop()) write(file, seen.first.copyOf(count), seen.second.copyOf(count), folders, total)
        return total
    }

    /** Whether every directory [saved] walked is still a directory with the same change time. */
    private fun unchanged(saved: Saved, root: File, stats: (File, List<String>) -> List<Entry?>): Boolean {
        if (saved.folders.isEmpty()) return false
        val paths = saved.folders.keys.toList()
        // Relative to the root's parent, so the root itself ("") is one of them.
        val parent = root.parentFile ?: return false
        val now = stats(parent, paths.map { root.name + it })
        return paths.indices.all { i -> now.getOrNull(i)?.let { it.isDirectory && it.changed == saved.folders.getValue(paths[i]) && it.changed != 0L } == true }
    }

    private class Saved(val hashes: LongArray, val values: LongArray, val folders: Map<String, Long>, val total: Long) {
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
            val folderCount = input.readInt()
            if (folderCount < 0 || folderCount > MAX_FOLDERS) return null
            val folders = LinkedHashMap<String, Long>()
            repeat(folderCount) { folders[input.readUTF()] = input.readLong() }
            val total = input.readLong()
            if (input.readInt() != END) return null
            Saved(hashes, values, folders, total)
        }
    } catch (_: Exception) {
        null
    }

    private fun write(file: File, hashes: LongArray, values: LongArray, folders: Map<String, Long>, total: Long) {
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
                // Too many directories to check cheaply, or a name writeUTF can't hold: none saved, so every walk lists.
                val kept = folders.takeIf { it.size <= MAX_FOLDERS && it.keys.all { k -> k.length < MAX_PATH } }.orEmpty()
                out.writeInt(kept.size)
                for ((path, changed) in kept) {
                    out.writeUTF(path)
                    out.writeLong(changed)
                }
                out.writeLong(total)
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
        private const val MAGIC = 0x46444c32 // "FDL2" (FDL1 had no directories or total: read as missing)
        private const val END = 0x454e4421 // "END!"
        private const val MAX_ENTRIES = 50_000_000
        private const val INITIAL = 1024
        /** Directories whose change times are checked before a walk; a tree with more is always walked. */
        private const val MAX_FOLDERS = 4096
        /** writeUTF's limit is 65,535 bytes; paths here are far shorter. */
        private const val MAX_PATH = 16_000

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
