package com.garathon.flatdroid.flatpak.integrity

import android.system.ErrnoException
import android.system.Os
import com.garathon.flatdroid.proot.L2s
import com.garathon.flatdroid.setup.rootfs.AndroidTarFs
import com.garathon.flatdroid.util.io.DirReader
import com.garathon.flatdroid.util.io.NativeFs
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
    /** Renames [from] to [to] in one step. */
    fun rename(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("Couldn't rename $from")
    }

    /** [dir]'s entries with their types (a link to a folder is a link); null if it can't be listed. */
    fun listTyped(dir: File): DirReader.Listing? = list(dir)?.let { names ->
        DirReader.Listing(names.toList(), ByteArray(names.size) { i ->
            val file = File(dir, names[i])
            when {
                isSymlink(file) -> DirReader.LINK
                isDirectory(file) -> DirReader.DIRECTORY
                else -> DirReader.FILE
            }
        })
    }

    /** [readlink] of each of [listing]'s entries at [indices] ([listing] is [dir]'s, from [listTyped]). */
    fun readlinks(dir: File, listing: DirReader.Listing, indices: IntArray): Array<String?> =
        Array(indices.size) { readlink(File(dir, listing.names[indices[it]])) }
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
        val listing = fs.listTyped(l2sDir) ?: return emptyList()
        val names = listing.names
        val nameSet = names.toHashSet()
        // Each intermediate's content file when it has exactly one (null when it has several).
        val contents = HashMap<String, String?>()
        for (name in names) if (hasCount(name)) {
            val intermediate = name.dropLast(COUNT_LENGTH)
            contents[intermediate] = if (intermediate in contents) null else name
        }
        val candidates = listing.indicesWhere { !hasCount(names[it]) && contents[names[it]] != null }
        val relinked = mutableListOf<String>()
        for (from in candidates.indices step BATCH) {
            if (Thread.interrupted()) throw InterruptedException()
            val batch = candidates.copyOfRange(from, minOf(candidates.size, from + BATCH))
            val targets = fs.readlinks(l2sDir, listing, batch)
            for (i in batch.indices) {
                val target = targets[i] ?: continue
                if (target.substring(target.lastIndexOf('/') + 1) in nameSet) continue
                val name = names[batch[i]]
                // Same directory form as link2symlink wrote it (its path for the store, not ours).
                fs.replaceLink(File(l2sDir, name), File(File(target).parentFile ?: l2sDir, contents.getValue(name)!!).path)
                relinked += name
            }
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

    /**
     * Counts one more link to the content behind [intermediate] (its name in the store): a link
     * that points at it although link2symlink never counted it, such as one left on a store entry
     * that was deleted and later made again under the same name. Too low a count deletes the
     * content while that link still needs it; one too high only keeps it. The content goes to its
     * next count first, then the intermediate follows; a stop between the two leaves it stranded,
     * which [relinkStranded] repairs keeping the new count; a failed rewrite puts the content
     * back. False when the intermediate, its content or its count isn't as expected (an
     * intermediate pointing outside the store included): nothing is changed then.
     */
    fun countOneMore(intermediate: String): Boolean {
        val link = File(l2sDir, intermediate)
        val target = fs.readlink(link) ?: return false
        val content = File(target).name
        if (content.length != intermediate.length + COUNT_LENGTH || !content.startsWith(intermediate) || !hasCount(content)) return false
        if (!L2s.inStore(target, l2sDir)) return false
        val count = content.takeLast(COUNT_LENGTH - 1).toInt()
        val next = "$intermediate.%04d".format(count + 1)
        val from = File(l2sDir, content)
        val to = File(l2sDir, next)
        if (count >= MAX_COUNT || !fs.exists(from) || fs.exists(to)) return false
        fs.rename(from, to)
        try {
            // Same directory form as link2symlink wrote it (its path for the store, not ours).
            fs.replaceLink(link, File(File(target).parentFile ?: l2sDir, next).path)
        } catch (e: Exception) {
            runCatching { fs.rename(to, from) }
            throw e
        }
        return true
    }

    /**
     * Removes the broken links under [dir] (and their dangling intermediates); returns how many.
     * Folders are read on [threads] threads: the walk waits on storage, which answers several
     * reads at once. Interruptible: nothing is deleted once it has returned.
     */
    fun removeUnder(dir: File, threads: Int = WALK_THREADS): Int {
        val names = fs.list(l2sDir).orEmpty().toHashSet()
        val withContent = names.mapNotNullTo(HashSet()) { name -> if (hasCount(name)) name.dropLast(COUNT_LENGTH) else null }
        val store = L2s.Store(l2sDir)
        val removed = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads)
        val pending = AtomicInteger()
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val stopped = AtomicBoolean()
        fun visit(folder: File, submit: (File) -> Unit) {
            val listing = fs.listTyped(folder) ?: return
            for (i in 0 until listing.size) if (listing.isDirectory(i)) submit(File(folder, listing.names[i]))
            val links = listing.indicesWhere { listing.isLink(it) }
            val targets = fs.readlinks(folder, listing, links)
            for (i in links.indices) {
                if (stopped.get()) return
                val intermediate = targets[i]?.let(store::storedName) ?: continue
                if (intermediate in names && intermediate in withContent) continue
                fs.delete(File(folder, listing.names[links[i]]))
                if (intermediate in names) fs.delete(File(l2sDir, intermediate))
                removed.incrementAndGet()
            }
        }
        fun submit(folder: File) {
            pending.incrementAndGet()
            pool.execute {
                try {
                    if (failure.get() == null && !stopped.get()) visit(folder, ::submit)
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    if (pending.decrementAndGet() == 0) finished.countDown()
                }
            }
        }
        try {
            submit(dir)
            finished.await()
        } finally {
            stopped.set(true)
            pool.shutdownNow()
            // A visit running now stops at its next link.
            awaitUninterruptibly(pool)
        }
        failure.get()?.let { throw it }
        return removed.get()
    }

    /** Waits for [pool]'s threads to end; an interrupt meanwhile is kept for the caller. */
    private fun awaitUninterruptibly(pool: ExecutorService) {
        var interrupted = false
        while (true) {
            try {
                if (pool.awaitTermination(1, TimeUnit.MINUTES)) break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
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

        /** Store entries read per call: an interrupt is noticed between them. */
        private const val BATCH = 4096
        /** Threads for [removeUnder]'s walk: more gained nothing on a slow phone's storage. */
        const val WALK_THREADS = 4

        private val OBJECT = Regex("""\b[0-9a-f]{2}/[0-9a-f]{62}\.(file|dirtree|dirmeta|commit)\b""")
        private const val MAX_COUNT = 9999

        private val STRANDED_ERROR = Regex("""\b(rename|renameat|unlink|unlinkat|link|linkat)\b[^\n]*Operation not permitted""")

        /** Whether [name] ends as a content file does: a dot and its four-digit link count (checked by hand: a regex per name cost 20 µs on ART). */
        fun hasCount(name: String): Boolean {
            val n = name.length
            if (n < COUNT_LENGTH || name[n - COUNT_LENGTH] != '.') return false
            for (i in n - 4 until n) if (name[i] !in '0'..'9') return false
            return true
        }

        private const val COUNT_LENGTH = 5
    }
}

object AndroidLinkFs : LinkFs {
    override fun listTyped(dir: File): DirReader.Listing? = NativeFs.list(dir)

    override fun readlinks(dir: File, listing: DirReader.Listing, indices: IntArray): Array<String?> = NativeFs.readlinks(dir, listing, indices)

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

    override fun rename(from: File, to: File) = Os.rename(from.path, to.path)

    override fun replaceLink(link: File, target: String) {
        val temporary = File(link.parentFile, link.name + ".relink")
        temporary.delete()
        Os.symlink(target, temporary.path)
        Os.rename(temporary.path, link.path)
    }
}
