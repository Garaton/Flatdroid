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
    /** Makes [link] a symlink to [target], replacing what is there in one step. */
    fun replaceLink(link: File, target: String)
    /** Whether [file] is there (a content file in the store: a plain file). */
    fun exists(file: File): Boolean = file.exists()
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
    /**
     * Points stranded intermediates back at their content; returns how many. An intermediate is
     * stranded when the content file it names is gone but one other content file of it is there:
     * link2symlink renamed the content to its new count, and a kill came before it rewrote the
     * intermediate. Every link through it then fails (proot answers EPERM), though nothing is lost.
     * The content's own count is kept: it is link2symlink's latest, and recounting from Flatpak's
     * folder alone could miss links elsewhere. One listing of `.l2s` and one read per intermediate;
     * the names of the intermediates repaired.
     */
    fun relinkStranded(): List<String> {
        val names = fs.list(l2sDir).orEmpty()
        val nameSet = names.toHashSet()
        val contents = names.filter { COUNT.containsMatchIn(it) }.groupBy { it.dropLast(COUNT_LENGTH) }
        val relinked = mutableListOf<String>()
        for (name in names) {
            if (Thread.interrupted()) throw InterruptedException()
            if (COUNT.containsMatchIn(name)) continue
            val own = contents[name]?.singleOrNull() ?: continue
            val intermediate = File(l2sDir, name)
            val target = fs.readlink(intermediate) ?: continue
            val targetName = File(target).name
            if (targetName in nameSet) continue
            // Same directory form as link2symlink wrote it (its path for the store, not ours).
            fs.replaceLink(intermediate, File(File(target).parentFile ?: l2sDir, own).path)
            relinked += name
        }
        return relinked
    }

    /**
     * [relinkStranded] for the repository objects an error names ([namedObjects]) only: no listing
     * of `.l2s`, a few reads per object. The names of the intermediates repaired; empty when none
     * of them was stranded (or can't be told so), and the full check is left to the caller.
     */
    fun relinkObjects(objectsDir: File, objects: Collection<String>): List<String> = objects.mapNotNull { relative ->
        val stored = fs.readlink(File(objectsDir, relative))?.let { L2s.storedName(it, l2sDir) } ?: return@mapNotNull null
        val intermediate = File(l2sDir, stored)
        val target = fs.readlink(intermediate) ?: return@mapNotNull null
        if (fs.exists(File(l2sDir, File(target).name))) return@mapNotNull null
        // The content under some other count: link2symlink's counts are small, so they are tried in turn.
        val own = (1..MAX_COUNT).asSequence().map { "$stored.%04d".format(it) }.firstOrNull { fs.exists(File(l2sDir, it)) } ?: return@mapNotNull null
        fs.replaceLink(intermediate, File(File(target).parentFile ?: l2sDir, own).path)
        stored
    }

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

    companion object {
        /**
         * Whether a command's error looks like a stranded intermediate ([relinkStranded]): proot
         * answers a rename, unlink or link through one with EPERM, which ostree reports as
         * `renameat(…): Operation not permitted`. Only then is `.l2s` looked at.
         */
        fun mayBeStranded(error: String?): Boolean = error != null && STRANDED_ERROR.containsMatchIn(error)

        /** The repository objects (`xx/<checksum>.<kind>`, under `objects/`) an error names, as ostree prints them. */
        fun namedObjects(error: String?): List<String> =
            error?.let { OBJECT.findAll(it).map { m -> m.value }.distinct().toList() }.orEmpty()

        private val OBJECT = Regex("""\b[0-9a-f]{2}/[0-9a-f]{62}\.(file|dirtree|dirmeta|commit)\b""")
        private const val MAX_COUNT = 9999

        private val STRANDED_ERROR = Regex("""\b(rename|renameat|unlink|unlinkat|link|linkat)\b[^\n]*Operation not permitted""")

        /** A content file's ending: its link count. */
        private val COUNT = Regex("""\.\d{4}$""")
        private const val COUNT_LENGTH = 5
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

    override fun replaceLink(link: File, target: String) {
        val temporary = File(link.parentFile, link.name + ".relink")
        temporary.delete()
        Os.symlink(target, temporary.path)
        Os.rename(temporary.path, link.path)
    }
}
