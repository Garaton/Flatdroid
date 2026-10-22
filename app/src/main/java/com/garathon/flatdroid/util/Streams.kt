package com.garathon.flatdroid.util

import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Copies the rest of this stream to [out] and returns how many bytes that was. [onCopied] gets the
 * total so far after each chunk (it may throw to stop, e.g. over a size limit). Interruptible
 * between chunks.
 */
fun InputStream.copyWithProgress(out: OutputStream, onCopied: (Long) -> Unit = {}): Long {
    val buffer = ByteArray(COPY_BUFFER)
    var copied = 0L
    while (true) {
        if (Thread.interrupted()) throw InterruptedException()
        val n = read(buffer)
        if (n < 0) return copied
        out.write(buffer, 0, n)
        copied += n
        onCopied(copied)
    }
}

/**
 * As [copyWithProgress], into a file, leaving each block of zeros ([SPARSE_BLOCK] bytes at a
 * block-aligned offset) as a hole instead of writing it, so a sparse file (one with regions never
 * written, which take no space) stays sparse after a zip, which can't record holes. It reads the
 * same either way. Only zero blocks are read in full: any other block stops at its first chunk
 * that isn't zero.
 */
fun InputStream.copySparse(out: FileOutputStream, onCopied: (Long) -> Unit = {}): Long {
    val channel = out.channel
    val buffer = ByteArray(COPY_BUFFER)
    var copied = 0L
    while (true) {
        if (Thread.interrupted()) throw InterruptedException()
        // Whole buffers, so blocks stay aligned to the file's offsets; only the last is short.
        var n = 0
        while (n < buffer.size) {
            val read = read(buffer, n, buffer.size - n)
            if (read < 0) break
            n += read
        }
        if (n == 0) break
        // From [written] on, the buffer isn't in the file yet.
        var written = 0
        var block = 0
        while (block < n) {
            val end = minOf(block + SPARSE_BLOCK, n)
            if (isZero(buffer, block, end)) {
                if (written < block) channel.write(ByteBuffer.wrap(buffer, written, block - written))
                channel.position(copied + end)
                written = end
            }
            block = end
        }
        if (written < n) channel.write(ByteBuffer.wrap(buffer, written, n - written))
        copied += n
        onCopied(copied)
        if (n < buffer.size) break
    }
    // A hole at the end doesn't extend the file: its last byte (a zero) does.
    if (channel.size() < copied) {
        channel.position(copied - 1)
        channel.write(ByteBuffer.wrap(ByteArray(1)))
    }
    return copied
}

/**
 * Whether the bytes from [from] until [to] are all zero. They are OR-ed together [ZERO_CHUNK]
 * bytes at a time, a loop Android's compiler can turn into vector instructions (one that stops at
 * each non-zero byte can't be), stopping at the first chunk that isn't zero. Reading 8 bytes at a
 * time through a ByteBuffer was 6 times slower on Android's runtime.
 */
private fun isZero(bytes: ByteArray, from: Int, to: Int): Boolean {
    var chunk = from
    while (chunk < to) {
        val end = minOf(chunk + ZERO_CHUNK, to)
        var any = 0
        for (i in chunk until end) any = any or bytes[i].toInt()
        if (any != 0) return false
        chunk = end
    }
    return true
}

/** Passes [input] through, calling [onRead] with the bytes read so far (for progress through a stream another reader consumes). */
class CountingInputStream(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) counted(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) counted(it.toLong()) }

    override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) counted(it) }

    private fun counted(n: Long) {
        count += n
        onRead(count)
    }
}

private const val COPY_BUFFER = 64 * 1024
/** The block size of Android's file systems (ext4, f2fs), so a hole frees whole blocks. */
private const val SPARSE_BLOCK = 4096
private const val ZERO_CHUNK = 256
