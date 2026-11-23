package com.garathon.flatdroid.util.io

import java.io.File

/**
 * [DirReader] in C (`native/fdwalk.c`): a folder is opened once and its entries read relative to
 * it, one JNI call per folder. On a Cortex-A53 a link read costs about 7 µs this way against about
 * 38 µs through `Os.readlink`, whose path is also looked up from `/` each time. Names cross as
 * UTF-8 bytes, decoded as `File.list` decodes them; a listing's own bytes go back as they came.
 */
object NativeFs : DirReader {
    init {
        System.loadLibrary("fdwalk")
    }

    override fun list(dir: File): DirReader.Listing? {
        val blob = list(bytes(dir.path)) ?: return null
        var count = 0
        for (b in blob) if (b == ZERO) count++
        val names = ArrayList<String>(count)
        val types = ByteArray(count)
        val starts = IntArray(count)
        var i = 0
        var n = 0
        while (i < blob.size) {
            types[n] = blob[i]
            val start = i + 1
            var end = start
            while (blob[end] != ZERO) end++
            starts[n] = start
            names += String(blob, start, end - start, Charsets.UTF_8)
            n++
            i = end + 1
        }
        return DirReader.Listing(names, types, blob, starts)
    }

    override fun readlinks(dir: File, names: List<String>): Array<String?> {
        if (names.isEmpty()) return arrayOfNulls(0)
        val (blob, starts) = joined(names)
        return targets(readlinks(bytes(dir.path), blob, starts, names.size), names.size)
    }

    override fun readlinks(dir: File, listing: DirReader.Listing, indices: IntArray): Array<String?> {
        val raw = listing.raw ?: return super.readlinks(dir, listing, indices)
        val starts = listing.starts ?: return super.readlinks(dir, listing, indices)
        if (indices.isEmpty()) return arrayOfNulls(0)
        return targets(readlinks(bytes(dir.path), raw, IntArray(indices.size) { starts[indices[it]] }, indices.size), indices.size)
    }

    override fun stats(dir: File, names: List<String>, follow: Boolean): DirReader.Stats {
        val values = DirReader.Stats.of(names.size)
        if (names.isNotEmpty()) {
            val (blob, starts) = joined(names)
            stats(bytes(dir.path), blob, starts, names.size, follow, values)
        }
        return DirReader.Stats(values)
    }

    override fun stats(dir: File, listing: DirReader.Listing, indices: IntArray, follow: Boolean): DirReader.Stats {
        val raw = listing.raw ?: return super.stats(dir, listing, indices, follow)
        val starts = listing.starts ?: return super.stats(dir, listing, indices, follow)
        val values = DirReader.Stats.of(indices.size)
        if (indices.isNotEmpty()) stats(bytes(dir.path), raw, IntArray(indices.size) { starts[indices[it]] }, indices.size, follow, values)
        return DirReader.Stats(values)
    }

    /** readlinks()' answers: per name a status byte, then the target up to a NUL. */
    private fun targets(blob: ByteArray?, count: Int): Array<String?> {
        val out = arrayOfNulls<String>(count)
        blob ?: return out
        var i = 0
        var n = 0
        while (i < blob.size && n < count) {
            val ok = blob[i] == 1.toByte()
            val start = i + 1
            var end = start
            while (blob[end] != ZERO) end++
            if (ok) out[n] = String(blob, start, end - start, Charsets.UTF_8)
            n++
            i = end + 1
        }
        return out
    }

    private fun bytes(path: String) = path.toByteArray(Charsets.UTF_8)

    /** [names] as UTF-8, each followed by a NUL, and where each starts. */
    private fun joined(names: List<String>): Pair<ByteArray, IntArray> {
        val encoded = Array(names.size) { names[it].toByteArray(Charsets.UTF_8) }
        val out = ByteArray(encoded.sumOf { it.size + 1 })
        val starts = IntArray(names.size)
        var at = 0
        for (i in encoded.indices) {
            starts[i] = at
            System.arraycopy(encoded[i], 0, out, at, encoded[i].size)
            at += encoded[i].size + 1
        }
        return out to starts
    }

    private const val ZERO: Byte = 0

    @JvmStatic private external fun list(dir: ByteArray): ByteArray?
    @JvmStatic private external fun readlinks(dir: ByteArray, names: ByteArray, starts: IntArray, count: Int): ByteArray?
    @JvmStatic private external fun stats(dir: ByteArray, names: ByteArray, starts: IntArray, count: Int, follow: Boolean, out: LongArray): Boolean
}
