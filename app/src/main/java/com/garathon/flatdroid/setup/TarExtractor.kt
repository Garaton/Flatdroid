package com.garathon.flatdroid.setup

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

/** File operations java.io lacks below API 26. */
interface TarFs {
    fun symlink(target: String, link: File)
    fun chmod(file: File, mode: Int)
    /** True for a symlink itself (lstat), whatever it points to. */
    fun isSymlink(file: File): Boolean
    /**
     * Gives [file] (a link itself, not its target) the app's own group. Files made in the cache
     * get the cache group, which Android counts as cache wherever the file later moves.
     */
    fun useOwnGroup(file: File) {}
}

/**
 * Deletes [file] and, for a real directory, everything below it, without following symlinks.
 * Kotlin's File.deleteRecursively() follows symlinked directories, and a rootfs holds symlinks
 * with absolute targets that resolve on the host. Throws if anything is left.
 */
fun TarFs.deleteTree(file: File) {
    if (!isSymlink(file) && file.isDirectory) {
        file.listFiles()?.forEach { deleteTree(it) }
    }
    if (!file.delete() && (isSymlink(file) || file.exists())) {
        throw IOException("Could not delete $file")
    }
}

/**
 * Minimal tar reader for the pinned Alpine minirootfs: regular files, directories and symlinks;
 * hard links become copies (link() is refused in app storage). Reads ustar, pax (path, linkpath,
 * size) and GNU long names. Devices, FIFOs and other types are skipped. Ownership is ignored;
 * setuid/setgid/sticky bits are dropped and the owner always keeps rw(x) so the tree can be
 * removed later.
 */
class TarExtractor(private val fs: TarFs) {

    fun extract(input: InputStream, dest: File) {
        dest.mkdirs()
        val root = dest.canonicalFile
        val header = ByteArray(BLOCK)
        var paxPath: String? = null
        var paxLink: String? = null
        var paxSize: Long? = null
        var longName: String? = null
        var longLink: String? = null

        while (true) {
            if (!readBlock(input, header)) return
            if (header.all { it == 0.toByte() }) return

            val type = header[156].toInt().toChar()
            val size = paxSize ?: parseSize(header)
            when (type) {
                'x' -> {
                    val records = parsePax(readData(input, size))
                    paxPath = records["path"]
                    paxLink = records["linkpath"]
                    paxSize = records["size"]?.toLong()
                    continue
                }
                'g' -> { skip(input, size); continue }
                'L' -> { longName = cString(readData(input, size)); continue }
                'K' -> { longLink = cString(readData(input, size)); continue }
            }

            val name = paxPath ?: longName ?: ustarName(header)
            val link = paxLink ?: longLink ?: field(header, 157, 100)
            paxPath = null; paxLink = null; paxSize = null; longName = null; longLink = null

            val mode = parseOctal(header, 100, 8).toInt() and 0x1ff
            val rel = safeRelative(name)
            if (rel == null) {
                skip(input, size)
                if (name.trimStart('.', '/').isEmpty()) continue
                throw IOException("Unsafe tar entry: $name")
            }
            val target = File(root, rel)
            ensureInside(root, target.parentFile!!)

            when (type) {
                '0', '\u0000', '7' -> {
                    target.parentFile!!.mkdirs()
                    target.delete()
                    target.outputStream().use { out -> copyData(input, out, size) }
                    fs.chmod(target, mode or 0x180)
                }
                '5' -> {
                    if (target.exists()) ensureInside(root, target)
                    target.mkdirs()
                    fs.chmod(target, mode or 0x1c0)
                    skip(input, size)
                }
                '2' -> {
                    target.parentFile!!.mkdirs()
                    target.delete()
                    fs.symlink(link, target)
                    skip(input, size)
                }
                '1' -> {
                    val relSource = safeRelative(link) ?: throw IOException("Unsafe hard link: $link")
                    val source = File(root, relSource)
                    ensureInside(root, source)
                    target.parentFile!!.mkdirs()
                    target.delete()
                    source.copyTo(target)
                    fs.chmod(target, mode or 0x180)
                    skip(input, size)
                }
                else -> skip(input, size)
            }
        }
    }

    private fun ustarName(h: ByteArray): String {
        val name = field(h, 0, 100)
        val isUstar = field(h, 257, 5) == "ustar"
        val prefix = if (isUstar) field(h, 345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    /** Normalised relative path, or null for an empty, absolute-escaping or ".." path. */
    private fun safeRelative(name: String): String? {
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** Refuses paths that would leave [root] through an already extracted symlink. */
    private fun ensureInside(root: File, file: File) {
        val path = file.canonicalPath
        if (path != root.path && !path.startsWith(root.path + File.separator)) {
            throw IOException("Tar entry escapes the destination: $file")
        }
    }

    private fun readData(input: InputStream, size: Long): ByteArray {
        if (size > MAX_META) throw IOException("Tar metadata entry too large: $size")
        val data = ByteArray(size.toInt())
        readFully(input, data, data.size)
        skipPadding(input, size)
        return data
    }

    private fun copyData(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("Truncated tar entry")
            out.write(buf, 0, n)
            left -= n
        }
        skipPadding(input, size)
    }

    private fun skip(input: InputStream, size: Long) {
        var left = size + padding(size)
        val buf = ByteArray(BLOCK)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(BLOCK.toLong(), left).toInt())
            if (n < 0) throw EOFException("Truncated tar entry")
            left -= n
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = padding(size)
        if (pad > 0) readFully(input, ByteArray(pad), pad)
    }

    private fun padding(size: Long): Int = ((BLOCK - size % BLOCK) % BLOCK).toInt()

    /** True when a full block was read, false at a clean end of stream. */
    private fun readBlock(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < BLOCK) {
            val n = input.read(buf, off, BLOCK - off)
            if (n < 0) {
                if (off == 0) return false
                throw EOFException("Truncated tar header")
            }
            off += n
        }
        return true
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw EOFException("Truncated tar entry")
            off += n
        }
    }

    internal companion object {
        const val BLOCK = 512
        private const val MAX_META = 1L shl 20

        fun field(h: ByteArray, off: Int, len: Int): String {
            var end = off
            while (end < off + len && h[end] != 0.toByte()) end++
            return String(h, off, end - off, Charsets.UTF_8)
        }

        fun parseOctal(h: ByteArray, off: Int, len: Int): Long =
            field(h, off, len).trim().ifEmpty { "0" }.toLong(8)

        /** Size field: octal, or GNU base-256 when the high bit of the first byte is set. */
        fun parseSize(h: ByteArray): Long {
            if (h[124].toInt() and 0x80 != 0) {
                var v = 0L
                for (i in 125 until 136) v = (v shl 8) or (h[i].toLong() and 0xff)
                return v
            }
            return parseOctal(h, 124, 12)
        }

        /** Pax records: "<len> <key>=<value>\n", len counting the whole record in bytes. */
        fun parsePax(data: ByteArray): Map<String, String> {
            val out = HashMap<String, String>()
            var pos = 0
            while (pos < data.size) {
                var sp = pos
                while (sp < data.size && data[sp] != ' '.code.toByte()) sp++
                if (sp >= data.size) break
                val len = String(data, pos, sp - pos, Charsets.US_ASCII).toIntOrNull() ?: break
                if (len <= 0 || pos + len > data.size) break
                val record = String(data, sp + 1, pos + len - sp - 2, Charsets.UTF_8)
                val eq = record.indexOf('=')
                if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
                pos += len
            }
            return out
        }

        fun cString(data: ByteArray): String = field(data, 0, data.size)
    }
}
