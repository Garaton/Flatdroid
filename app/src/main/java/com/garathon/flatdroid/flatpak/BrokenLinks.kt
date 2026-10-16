package com.garathon.flatdroid.flatpak

import android.system.ErrnoException
import android.system.Os
import com.garathon.flatdroid.setup.AndroidTarFs
import java.io.File

/** The file operations [BrokenLinks] needs; never follows links. */
interface LinkFs {
    fun isSymlink(file: File): Boolean
    /** The link's target, or null if [file] isn't a link. */
    fun readlink(file: File): String?
    fun delete(file: File)
}

/**
 * Finds Flatpak files whose link2symlink chain is broken and removes them, from the host side
 * (inside proot such a file can't even be stat()ed, so neither ostree nor rm can remove it).
 *
 * Under link2symlink a hard-linked file is a symlink to an intermediate link in `<rootfs>/.l2s`,
 * which points to the file that holds the content (its name ends in the link count). If a process
 * is killed after proot lowered that count but before its unlink ran, the count ends up too low,
 * and deleting the remaining links later removes the content while a link still points to it.
 *
 * Only links under one directory are looked at, and only broken ones are touched: other links to
 * the same content can live outside it (in apps' homes), so counts can't be judged from here.
 */
class BrokenLinks(private val l2sDir: File, private val fs: LinkFs) {
    /** Removes the broken links under [dir] (and their dangling intermediates); returns how many. */
    fun removeUnder(dir: File): Int {
        val prefix = l2sDir.path + "/"
        var removed = 0
        val pending = ArrayDeque(listOf(dir))
        while (pending.isNotEmpty()) {
            if (Thread.interrupted()) throw InterruptedException()
            val current = pending.removeFirst()
            for (entry in current.listFiles() ?: continue) {
                if (!fs.isSymlink(entry)) {
                    if (entry.isDirectory) pending.addLast(entry)
                    continue
                }
                val intermediate = fs.readlink(entry)?.takeIf { it.startsWith(prefix) }?.let(::File) ?: continue
                val content = fs.readlink(intermediate)?.let(::File)
                if (content != null && content.exists()) continue
                fs.delete(entry)
                if (content != null) fs.delete(intermediate)
                removed++
            }
        }
        return removed
    }
}

object AndroidLinkFs : LinkFs {
    override fun isSymlink(file: File) = AndroidTarFs.isSymlink(file)

    override fun readlink(file: File): String? = try {
        Os.readlink(file.path)
    } catch (_: ErrnoException) {
        null
    }

    override fun delete(file: File) {
        file.delete()
    }
}
