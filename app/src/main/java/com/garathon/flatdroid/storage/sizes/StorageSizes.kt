package com.garathon.flatdroid.storage.sizes

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.garathon.flatdroid.proot.L2s
import com.garathon.flatdroid.storage.AppFiles
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
 * checked at each directory; when it is true the walk ends with [MeasureStopped].
 */
object StorageSizes {
    /** Under [file], without following links; an entry [instead] gives a size for isn't walked. */
    fun of(file: File, stop: () -> Boolean = { false }, instead: (File) -> Long? = { null }): Long {
        fun walk(entry: File): Long {
            instead(entry)?.let { return it }
            val stat = lstat(entry) ?: return 0L
            val own = stat.st_blocks * 512
            if (!OsConstants.S_ISDIR(stat.st_mode)) return own
            if (stop()) throw MeasureStopped()
            return own + (entry.listFiles()?.sumOf(::walk) ?: 0L)
        }
        return walk(file)
    }

    /** What [dir] holds, without the directory itself. */
    fun contents(dir: File): Long = dir.listFiles()?.sumOf { of(it) } ?: 0L

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
    ): Long {
        val seen = HashSet<Long>()
        fun content(inode: Long, bytes: Long): Long =
            if (seen.add(inode)) { onContent(inode, bytes); bytes } else 0L
        fun walk(entry: File, depth: Int): Long {
            val stat = lstat(entry) ?: return 0L
            return when {
                OsConstants.S_ISDIR(stat.st_mode) -> {
                    if (stop()) throw MeasureStopped()
                    if (deployments != null && depth == DEPLOYMENT_DEPTH && DeploymentSizes.COMMIT.matches(entry.name)) {
                        val size = deployments(entry)
                        var sum = size.own
                        for (i in size.inodes.indices) sum += content(size.inodes[i], size.bytes[i])
                        sum
                    } else {
                        stat.st_blocks * 512 + (entry.listFiles()?.sumOf { walk(it, depth + 1) } ?: 0L)
                    }
                }
                OsConstants.S_ISLNK(stat.st_mode) -> {
                    val target = try { Os.readlink(entry.path) } catch (_: ErrnoException) { null }
                    if (target != null && L2s.inStore(target, l2sDir)) {
                        val stored = try { Os.stat(entry.path) } catch (_: ErrnoException) { null }
                        stat.st_blocks * 512 + if (stored != null) content(stored.st_ino, stored.st_blocks * 512) else 0L
                    } else {
                        stat.st_blocks * 512
                    }
                }
                else -> stat.st_blocks * 512
            }
        }
        return files.sumOf { walk(it, 0) }
    }

    /** One deployment's directory walked ([following] over it alone), for [DeploymentSizes]. */
    fun deployment(dir: File, l2sDir: File, stop: () -> Boolean = { false }): DeploymentSize {
        val inodes = ArrayList<Long>()
        val bytes = ArrayList<Long>()
        val total = following(listOf(dir), l2sDir, stop) { inode, size -> inodes += inode; bytes += size }
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
        val each = dirs.map { dir -> following(listOf(dir), l2sDir, stop, deployments) { inode, bytes -> if (!all.add(inode)) again += bytes } }
        return each to each.sum() - again
    }

    /** `<arch>/<branch>/<commit>` below a Flatpak ID's directory. */
    private const val DEPLOYMENT_DEPTH = 3

    /** [ListedSizes] over [root], saved in [sizes] as [name]. */
    fun listed(sizes: ListedSizes, name: String, root: File, stop: () -> Boolean): Long = sizes.measure(
        name, root,
        stat = { file -> lstat(file)?.let { ListedSizes.Entry(OsConstants.S_ISDIR(it.st_mode), it.st_blocks * 512) } },
        list = { it.list() },
        stop = stop,
    )

    private fun lstat(file: File) = try {
        Os.lstat(file.path)
    } catch (_: ErrnoException) {
        null
    }
}
