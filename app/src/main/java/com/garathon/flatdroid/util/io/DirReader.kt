package com.garathon.flatdroid.util.io

import java.io.File

/**
 * A folder's entries, their link targets and their stats, read many at a time: what Flatdroid's
 * walks (sizes, the file check, Alpine safe launch, the repository clean-up) ask of the file
 * system. On Android it is [NativeFs], one call per folder; unit tests use a plain implementation.
 * Never follows a link unless asked.
 */
interface DirReader {
    /** [dir]'s entries without `.` and `..`, in no particular order; null when it can't be listed. */
    fun list(dir: File): Listing?

    /** The link target of each of [names] (relative to [dir]; may hold `/`); null where it isn't a link or is gone. */
    fun readlinks(dir: File, names: List<String>): Array<String?>

    /** The stat of each of [names] (relative to [dir]; may hold `/`), following a link only when [follow]. */
    fun stats(dir: File, names: List<String>, follow: Boolean): Stats

    /** [readlinks] of [listing]'s entries at [indices] ([listing] is [dir]'s). */
    fun readlinks(dir: File, listing: Listing, indices: IntArray): Array<String?> = readlinks(dir, indices.map { listing.names[it] })

    /** [stats] of [listing]'s entries at [indices] ([listing] is [dir]'s). */
    fun stats(dir: File, listing: Listing, indices: IntArray, follow: Boolean): Stats = stats(dir, indices.map { listing.names[it] }, follow)

    /** One entry's stat: [stats] of its name in its folder. */
    fun stat(file: File, follow: Boolean): Stats = stats(file.parentFile ?: File("/"), listOf(file.name), follow)

    /**
     * A folder's entries. [raw] and [starts], when a reader gives them, are the names as it read
     * them (where each starts, up to a NUL), so it can be given them back without encoding again.
     */
    class Listing(val names: List<String>, private val types: ByteArray, val raw: ByteArray? = null, val starts: IntArray? = null) {
        val size: Int get() = names.size
        fun isDirectory(i: Int) = types[i] == DIRECTORY
        fun isLink(i: Int) = types[i] == LINK
        fun isFile(i: Int) = types[i] == FILE

        /** The indices of the entries [keep] says yes to. */
        inline fun indicesWhere(keep: (Int) -> Boolean): IntArray {
            val out = IntArray(size)
            var n = 0
            for (i in 0 until size) if (keep(i)) out[n++] = i
            return out.copyOf(n)
        }

        val all: IntArray get() = IntArray(size) { it }
    }

    /** Stats by index: inode, change time in nanoseconds, size, mode, allocated bytes; [found] false when it couldn't be read. */
    class Stats(private val values: LongArray) {
        val size: Int get() = values.size / FIELDS
        fun found(i: Int) = values[i * FIELDS + 3] >= 0
        fun inode(i: Int) = values[i * FIELDS]
        fun ctimeNanos(i: Int) = values[i * FIELDS + 1]
        fun length(i: Int) = values[i * FIELDS + 2]
        fun mode(i: Int) = values[i * FIELDS + 3].toInt()
        /** Allocated blocks in bytes, as `du` counts. */
        fun bytes(i: Int) = values[i * FIELDS + 4]
        fun isDirectory(i: Int) = found(i) && mode(i) and S_IFMT == S_IFDIR
        fun isLink(i: Int) = found(i) && mode(i) and S_IFMT == S_IFLNK

        companion object {
            const val FIELDS = 5
            fun of(count: Int) = LongArray(count * FIELDS).also { for (i in 0 until count) it[i * FIELDS + 3] = -1 }
        }
    }

    companion object {
        const val DIRECTORY: Byte = 1
        const val LINK: Byte = 2
        const val FILE: Byte = 3
        const val OTHER: Byte = 4

        const val S_IFMT = 0xF000
        const val S_IFDIR = 0x4000
        const val S_IFREG = 0x8000
        const val S_IFLNK = 0xA000
    }
}
