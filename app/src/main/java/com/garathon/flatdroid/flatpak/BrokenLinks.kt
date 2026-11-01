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
    /** The names in [dir], or null if it can't be listed. */
    fun list(dir: File): Array<String>?
    /** A directory itself (only asked of entries that aren't links). */
    fun isDirectory(file: File): Boolean
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
 *
 * Every file of every installed Flatpak is such a link, so this reads one listing of `.l2s` and
 * then each link once: a chain is intact when its intermediate is listed with a content file
 * named after it (the intermediate's name plus ".NNNN", the count), with no further reads.
 */
class BrokenLinks(private val l2sDir: File, private val fs: LinkFs) {
    /** Removes the broken links under [dir] (and their dangling intermediates); returns how many. */
    fun removeUnder(dir: File): Int {
        val names = fs.list(l2sDir).orEmpty().toHashSet()
        val withContent = names.mapNotNullTo(HashSet()) { name -> if (COUNT.containsMatchIn(name)) name.dropLast(COUNT_LENGTH) else null }
        var removed = 0
        val pending = ArrayDeque(listOf(dir))
        while (pending.isNotEmpty()) {
            if (Thread.interrupted()) throw InterruptedException()
            val current = pending.removeFirst()
            for (name in fs.list(current) ?: continue) {
                val entry = File(current, name)
                val target = fs.readlink(entry)
                if (target == null) {
                    if (fs.isDirectory(entry)) pending.addLast(entry)
                    continue
                }
                val intermediate = L2s.storedName(target, l2sDir) ?: continue
                if (intermediate in names && intermediate in withContent) continue
                fs.delete(entry)
                if (intermediate in names) fs.delete(File(l2sDir, intermediate))
                removed++
            }
        }
        return removed
    }

    private companion object {
        /** A content file's ending: its link count. */
        val COUNT = Regex("""\.\d{4}$""")
        const val COUNT_LENGTH = 5
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

    override fun list(dir: File): Array<String>? = dir.list()

    override fun isDirectory(file: File) = file.isDirectory
}
