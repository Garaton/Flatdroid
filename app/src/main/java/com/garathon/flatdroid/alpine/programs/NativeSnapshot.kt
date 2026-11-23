package com.garathon.flatdroid.alpine.programs

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Alpine safe launch's walk in C (`native/fdsnap.c`, in libfdwalk.so): one call stamps every entry
 * under Alpine's folders and every store entry Alpine's links lead through, on the calling thread.
 */
object NativeSnapshot {
    init {
        System.loadLibrary("fdwalk")
    }

    /** [AlpineChanges.snapshot] of [dirs] (relative to [rootfs]), [l2sDir] being link2symlink's store. */
    fun take(rootfs: File, l2sDir: File, dirs: List<String>): AlpineChanges.Snapshot {
        val encodedDirs = dirs.map { it.toByteArray(Charsets.UTF_8) }
        val joined = ByteArray(encodedDirs.sumOf { it.size + 1 })
        var at = 0
        for (dir in encodedDirs) { dir.copyInto(joined, at); at += dir.size + 1 }
        val blob = take(bytes(rootfs.path), bytes(l2sDir.path), joined, dirs.size) ?: throw IOException("Alpine's files couldn't be recorded")
        val numbers = ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder())
        val count = numbers.getLong(0).toInt()
        val bound = numbers.getLong(8).toInt()
        var text = HEADER + count * STAMP
        fun next(): String {
            var end = text
            while (blob[end] != ZERO) end++
            return String(blob, text, end - text, Charsets.UTF_8).also { text = end + 1 }
        }
        val stamps = HashMap<String, AlpineChanges.Stamp>(count * 2)
        for (i in 0 until count) {
            val n = HEADER + i * STAMP
            stamps[next()] = AlpineChanges.Stamp(numbers.getLong(n), numbers.getLong(n + 8), numbers.getLong(n + 16), numbers.getLong(n + 24).toInt())
        }
        val binds = List(bound) { val guest = next(); next() to guest }
        return AlpineChanges.Snapshot(stamps, binds)
    }

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    private const val HEADER = 16
    private const val STAMP = 32
    private const val ZERO: Byte = 0

    @JvmStatic private external fun take(rootfs: ByteArray, store: ByteArray, dirs: ByteArray, count: Int): ByteArray?
}
