package com.garathon.flatdroid.storage.sizes

import android.system.ErrnoException
import android.system.Os
import com.garathon.flatdroid.proot.L2s
import com.garathon.flatdroid.storage.AppFiles
import com.garathon.flatdroid.util.io.DirReader
import com.garathon.flatdroid.util.io.NativeFs
import java.io.File

/** One app's storage, in bytes, as its Storage section shows it. */
data class AppStorage(
    /** The app's own Flatpak files (its deployments and its own extensions such as translations), without runtimes. */
    val app: Long,
    /** What is in its cache directory. */
    val cache: Long,
    val privateHome: Long,
    /** Its settings and other files of its own ([AppFiles]). */
    val settings: Long,
    /** These are the last measured sizes and a new measurement is under way (or waits for a task to end). */
    val measuring: Boolean = false,
) {
    val total: Long get() = app + cache + privateHome + settings
}

/** Thrown by a measurement whose [StorageSizes] stop check said to end it. */
class MeasureStopped : Exception("Measuring stopped")

/**
 * Disk space as `du` counts it: allocated blocks of every entry, directories included. [stop] is
 * checked at each directory; when it is true the walk ends with [MeasureStopped]. Folders are
 * read through a [DirReader] ([NativeFs] in the app), their entries stat'ed many at a time, on the calling thread only: this
 * is background work.
 */
object StorageSizes {
    /** Under [file], without following links; an entry [instead] gives a size for isn't walked. */
    fun of(file: File, stop: () -> Boolean = { false }, instead: (File) -> Long? = { null }, reader: DirReader = NativeFs): Long {
        fun walk(entry: File, st: DirReader.Stats, i: Int): Long {
            val own = st.bytes(i)
            if (!st.isDirectory(i)) return own
            if (stop()) throw MeasureStopped()
            val listing = reader.list(entry) ?: return own
            var sum = own
            val walked = listing.indicesWhere { i ->
                val replaced = instead(File(entry, listing.names[i]))
                if (replaced != null) sum += replaced
                replaced == null
            }
            val stats = reader.stats(entry, listing, walked, follow = false)
            for (k in walked.indices) if (stats.found(k)) sum += walk(File(entry, listing.names[walked[k]]), stats, k)
            return sum
        }
        instead(file)?.let { return it }
        val st = reader.stat(file, follow = false)
        return if (st.found(0)) walk(file, st, 0) else 0L
    }

    /** What [dir] holds, without the directory itself. */
    fun contents(dir: File, reader: DirReader = NativeFs): Long = reader.list(dir)?.names?.sumOf { of(File(dir, it), reader = reader) } ?: 0L

    /**
     * Under [files], counting each link into [l2sDir] as itself (a long link takes a block) and
     * the content it leads to (Flatpak's files are such links), each content once however many
     * links lead to it: Flatpak keeps one copy of identical files, and deployments share them.
     * With [deployments], [files] are Flatpak IDs' directories (`<kind>/<ID>`), and each deployment
     * in them (`<arch>/<branch>/<commit>`) is counted from what [deployments] gives for it instead
     * of being walked here: the same sum.
     */
    fun following(
        files: List<File>,
        l2sDir: File,
        stop: () -> Boolean = { false },
        deployments: ((File) -> DeploymentSize)? = null,
        /** Each content counted, with its bytes: once per call. */
        onContent: (inode: Long, bytes: Long) -> Unit = { _, _ -> },
        reader: DirReader = NativeFs,
    ): Long {
        val seen = HashSet<Long>()
        val store = L2s.Store(l2sDir)
        fun content(inode: Long, bytes: Long): Long =
            if (seen.add(inode)) { onContent(inode, bytes); bytes } else 0L
        fun walk(entry: File, st: DirReader.Stats, i: Int, depth: Int): Long {
            if (!st.isDirectory(i)) return st.bytes(i)
            if (stop()) throw MeasureStopped()
            if (deployments != null && depth == DEPLOYMENT_DEPTH && DeploymentSizes.COMMIT.matches(entry.name)) {
                val size = deployments(entry)
                var sum = size.own
                for (k in size.inodes.indices) sum += content(size.inodes[k], size.bytes[k])
                return sum
            }
            val listing = reader.list(entry) ?: return st.bytes(i)
            val stats = reader.stats(entry, listing, listing.all, follow = false)
            var sum = st.bytes(i)
            val links = listing.indicesWhere { stats.isLink(it) }
            val targets = reader.readlinks(entry, listing, links)
            // Links into the store count as themselves and, once, the content they lead to.
            val stored = links.indices.filter { k -> targets[k]?.let(store::inStore) == true }.map { links[it] }.toIntArray()
            val followed = reader.stats(entry, listing, stored, follow = true)
            for (k in stored.indices) if (followed.found(k)) sum += content(followed.inode(k), followed.bytes(k))
            for (k in 0 until listing.size) {
                if (!stats.found(k)) continue
                sum += if (stats.isDirectory(k)) walk(File(entry, listing.names[k]), stats, k, depth + 1) else stats.bytes(k)
            }
            return sum
        }
        return files.sumOf { file ->
            val st = reader.stat(file, follow = false)
            if (st.found(0)) walk(file, st, 0, 0) else 0L
        }
    }

    /** One deployment's directory walked ([following] over it alone), for [DeploymentSizes]. */
    fun deployment(dir: File, l2sDir: File, stop: () -> Boolean = { false }): DeploymentSize {
        val inodes = ArrayList<Long>()
        val bytes = ArrayList<Long>()
        val total = following(listOf(dir), l2sDir, stop, onContent = { inode, size -> inodes += inode; bytes += size })
        return DeploymentSize(total - bytes.sum(), inodes.toLongArray(), bytes.toLongArray())
    }

    /**
     * What tells a deployment's directory apart from one put in its place since (a reinstall
     * deploys the same commit at the same path): inode and change times. Null when it can't be read.
     */
    fun fingerprint(dir: File): LongArray? = lstat(dir)?.let { longArrayOf(it.st_ino, it.st_mtime, it.st_ctime) }

    /**
     * [following] for each of [dirs] on its own (what each holds, contents it shares with others
     * included), and their sum with each content counted once across all of them: Flatpak's
     * runtimes share many identical files (one runtime built on another), and adding up their own
     * sizes counted those again for each.
     */
    fun followingEach(
        dirs: List<File>,
        l2sDir: File,
        stop: () -> Boolean = { false },
        deployments: ((File) -> DeploymentSize)? = null,
    ): Pair<List<Long>, Long> {
        val all = HashSet<Long>()
        var again = 0L
        val each = dirs.map { dir -> following(listOf(dir), l2sDir, stop, deployments, onContent = { inode, bytes -> if (!all.add(inode)) again += bytes }) }
        return each to each.sum() - again
    }

    /** `<arch>/<branch>/<commit>` below a Flatpak ID's directory. */
    private const val DEPLOYMENT_DEPTH = 3

    /** [ListedSizes] over [root], saved in [sizes] as [name]. */
    fun listed(sizes: ListedSizes, name: String, root: File, stop: () -> Boolean, reader: DirReader = NativeFs): Long = sizes.measure(
        name, root,
        stats = { dir, names ->
            val st = reader.stats(dir, names, follow = false)
            List(names.size) { i -> if (st.found(i)) ListedSizes.Entry(st.isDirectory(i), st.bytes(i), st.ctimeNanos(i)) else null }
        },
        list = { dir -> reader.list(dir)?.names?.toTypedArray() },
        stop = stop,
    )

    private fun lstat(file: File) = try {
        Os.lstat(file.path)
    } catch (_: ErrnoException) {
        null
    }
}
