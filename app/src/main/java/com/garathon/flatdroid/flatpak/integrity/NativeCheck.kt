package com.garathon.flatdroid.flatpak.integrity

import java.io.File
import java.io.IOException

/**
 * The file check's walk and compare in C (`native/fdcheck.c`, in libfdwalk.so): a deployment's
 * tree walked, every leaf's link and followed stat read on [compare]'s threads, its manifest read,
 * and only what differs handed back. On a Cortex-A53 a runtime of 21,000 files takes about 0.5 s
 * warm this way against about 2 s with the walk and the compare in Kotlin.
 */
object NativeCheck {
    init {
        System.loadLibrary("fdwalk")
    }

    /** How a [compare] ended: [Found], or [Cancelled]. */
    sealed interface Outcome

    /**
     * What differs between a tree and its manifest. [current] holds the leaves whose shape
     * ([FileIntegrity.entry] without the hash) isn't the manifest's, and those it doesn't have; [baseline] the
     * manifest's entries for them; [removed] the manifest's paths the tree no longer has.
     */
    class Found(
        val current: Map<String, FileIntegrity.Entry>,
        val baseline: Map<String, FileIntegrity.Entry>,
        val removed: List<String>,
        val leaves: Int,
    ) : Outcome

    /** The check ended because [compare]'s cancelled said so. */
    object Cancelled : Outcome

    /**
     * Compares the tree at [files] with [manifest], leaving out [ignore]d paths on both sides;
     * links into [store] (its paths as [com.garathon.flatdroid.proot.L2s.Store.paths] gives them,
     * then its canonical path) are followed. [onProgress] gets the leaves read and their number
     * (0 while the tree is still walked), each time they change; [cancelled] is polled. Returns [Found], [Cancelled], or
     * null when the manifest can't be read.
     */
    fun compare(
        files: File,
        manifest: File,
        store: List<String>,
        ignore: Set<String>,
        threads: Int,
        onProgress: (done: Int, total: Int) -> Unit,
        cancelled: () -> Boolean,
    ): Outcome? {
        require(store.size == 3)
        val handle = start(
            bytes(files.path), bytes(manifest.path), joined(store), joined(ignore.toList()), ignore.size, threads,
        )
        if (handle == 0L) throw IOException("The file check couldn't start")
        try {
            val progress = IntArray(2)
            var reported = -1L
            var status: Int
            do {
                if (cancelled()) cancel(handle)
                status = await(handle, POLL_MS, progress)
                // Once per change: the last count stays while the compare runs.
                val now = progress[0].toLong() shl 32 or progress[1].toLong()
                if (now != reported) onProgress(progress[0], progress[1])
                reported = now
            } while (status < 0)
            return when (status) {
                DONE -> found(strings(handle) ?: ByteArray(0), numbers(handle) ?: throw IOException("The file check gave no result"))
                NO_BASELINE -> null
                CANCELLED -> Cancelled
                else -> throw IOException("The file check failed")
            }
        } finally {
            free(handle)
        }
    }

    private fun found(strings: ByteArray, numbers: LongArray): Found {
        var at = 0
        fun next(): String {
            var end = at
            while (strings[end] != ZERO) end++
            return String(strings, at, end - at, Charsets.UTF_8).also { at = end + 1 }
        }
        val records = numbers[0].toInt()
        val removedCount = numbers[1].toInt()
        val current = HashMap<String, FileIntegrity.Entry>(records * 2)
        val baseline = HashMap<String, FileIntegrity.Entry>(records * 2)
        for (r in 0 until records) {
            val n = HEADER + r * RECORD
            val path = next()
            val link = next()
            val baseLink = next()
            val baseSha = next()
            current[path] = FileIntegrity.Entry(link.takeIf { numbers[n + 1] != 0L }, numbers[n + 2], numbers[n + 3], numbers[n + 4], null)
            if (numbers[n] != 0L) {
                baseline[path] = FileIntegrity.Entry(
                    baseLink.takeIf { numbers[n + 5] != 0L }, numbers[n + 7], numbers[n + 8], numbers[n + 9], baseSha.takeIf { numbers[n + 6] != 0L },
                )
            }
        }
        val removed = List(removedCount) { next() }
        return Found(current, baseline, removed, numbers[2].toInt())
    }

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    /** [items] as UTF-8, each followed by a NUL. */
    private fun joined(items: List<String>): ByteArray {
        val encoded = items.map(::bytes)
        val out = ByteArray(encoded.sumOf { it.size + 1 })
        var at = 0
        for (item in encoded) { item.copyInto(out, at); at += item.size + 1 }
        return out
    }

    private const val POLL_MS = 50
    private const val DONE = 0
    private const val NO_BASELINE = 1
    private const val CANCELLED = 2
    private const val HEADER = 3
    private const val RECORD = 10
    private const val ZERO: Byte = 0

    @JvmStatic private external fun start(root: ByteArray, manifest: ByteArray, store: ByteArray, ignore: ByteArray, ignoreCount: Int, threads: Int): Long
    @JvmStatic private external fun await(handle: Long, millis: Int, progress: IntArray): Int
    @JvmStatic private external fun cancel(handle: Long)
    @JvmStatic private external fun strings(handle: Long): ByteArray?
    @JvmStatic private external fun numbers(handle: Long): LongArray?
    @JvmStatic private external fun free(handle: Long)
}
