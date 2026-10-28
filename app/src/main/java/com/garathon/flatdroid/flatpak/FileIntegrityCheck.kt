package com.garathon.flatdroid.flatpak

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.garathon.flatdroid.flatpak.FileIntegrity.Entry
import com.garathon.flatdroid.util.sha256
import com.garathon.flatdroid.util.writeTextAtomically
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records and checks a deployment's file-integrity baseline on Android: the host-side `Os` calls
 * for [FileIntegrity], the manifest files (one per deployment commit, in Flatdroid's private
 * storage, never bound into a Flatpak root), and the parallel hashing. One deployment commit = one
 * manifest, so an update (a new commit) gets a fresh baseline and old manifests are cleaned up.
 */
class FileIntegrityCheck(
    private val integrityDir: File,
    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
) {
    private val statOnly = fs(hash = false)
    private val withHash = fs(hash = true)

    fun manifestFile(commit: String): File = File(integrityDir, "$commit.manifest")
    fun hasBaseline(commit: String): Boolean = manifestFile(commit).isFile

    /** Whether any baseline exists at all; false on a device where the check has never been on. */
    fun hasAny(): Boolean = integrityDir.listFiles()?.any { it.name.endsWith(SUFFIX) } == true

    /**
     * Records the baseline of [files] under [commit] (hashing every file, in parallel). [ignore]
     * leaves out deployment-relative paths Flatdroid overrides at launch, which the app never reads
     * from the deployment and which proot can leave a stub for (see [IntegrityGuard]).
     */
    fun baseline(
        commit: String,
        files: File,
        ignore: (String) -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ) {
        val leaves = FileIntegrity.walk(files, statOnly, cancelled).filterNot(ignore)
        val entries = parallel(leaves, onProgress, cancelled) { rel -> rel to FileIntegrity.entry(File(files, rel), withHash) }.toMap()
        // A skipped baseline writes nothing, so the next launch records it afresh (and trusts it).
        if (cancelled()) return
        integrityDir.mkdirs()
        manifestFile(commit).writeTextAtomically(FileIntegrity.write(entries), durable = false)
    }

    /**
     * Checks [files] against the baseline for [commit], re-hashing only the files whose shape
     * (symlink target, inode, ctime, size) changed. Null when there is no baseline for [commit].
     * [cancelled] is polled so the user can skip; a cancelled check returns a clean [FileIntegrity.Diff].
     */
    fun verify(
        commit: String,
        files: File,
        ignore: (String) -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): FileIntegrity.Diff? {
        // Drop ignored paths from both sides, so an older baseline that still lists one doesn't
        // read as removed once it is left out of the current scan.
        val baseline = (runCatching { FileIntegrity.read(manifestFile(commit).readText()) }.getOrNull() ?: return null)
            .filterKeys { !ignore(it) }
        val leaves = FileIntegrity.walk(files, statOnly, cancelled).filterNot(ignore)
        if (cancelled()) return FileIntegrity.Diff(emptyList(), emptyList())
        // Cheap pass: shape only (stat + readlink, no content read).
        val shapes = parallel(leaves, onProgress, cancelled) { rel -> rel to FileIntegrity.entry(File(files, rel), statOnly) }.toMap()
        if (cancelled()) return FileIntegrity.Diff(emptyList(), emptyList())
        // Only files whose shape changed or are new need their content read.
        val toHash = shapes.filter { (rel, shape) -> baseline[rel]?.sameShape(shape) != true }.keys.toList()
        val hashed = parallel(toHash, onProgress, cancelled) { rel -> rel to FileIntegrity.entry(File(files, rel), withHash) }.toMap()
        if (cancelled()) return FileIntegrity.Diff(emptyList(), emptyList())
        return FileIntegrity.diff(baseline, shapes) { hashed[it] }
    }

    /** Removes manifests for commits not in [keep]. */
    fun cleanExcept(keep: Set<String>) {
        integrityDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(SUFFIX) && file.name.removeSuffix(SUFFIX) !in keep) file.delete()
        }
    }

    /**
     * Runs [work] over [items] across [threads], reporting progress; order of the result is
     * undefined. When [cancelled] turns true it stops waiting at once and returns the results so far
     * (the caller discards them on a skip), shutting the pool down with an interrupt so a skipped
     * check doesn't hold the launch while a large runtime finishes hashing.
     */
    private fun <T> parallel(
        items: List<String>,
        onProgress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
        work: (String) -> T,
    ): List<T> {
        if (items.isEmpty()) return emptyList()
        val total = items.size
        val done = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads.coerceAtMost(total))
        try {
            val futures = items.map { item ->
                pool.submit(java.util.concurrent.Callable {
                    val r = work(item)
                    onProgress(done.incrementAndGet(), total)
                    r
                })
            }
            val results = ArrayList<T>(total)
            collect@ for (f in futures) {
                while (!cancelled()) {
                    try {
                        results.add(f.get(POLL_MS, TimeUnit.MILLISECONDS)); continue@collect
                    } catch (e: TimeoutException) {
                        // Still running; re-check cancelled and keep waiting.
                    }
                }
                break // skipped: stop collecting, leave the rest to the interrupt below
            }
            return results
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val SUFFIX = ".manifest"
        const val POLL_MS = 50L

        fun fs(hash: Boolean) = FileIntegrity.Fs(
            isSymlink = { isSymlink(it) },
            readlink = { if (isSymlink(it)) runCatching { Os.readlink(it.path) }.getOrNull() else null },
            stat = {
                runCatching { Os.stat(it.path) }.getOrNull()?.let { s -> Triple(s.st_ino, s.st_ctime, s.st_size) }
            },
            sha256 = { if (hash && it.isFile) runCatching { sha256(it) }.getOrNull() else null },
        )

        fun isSymlink(file: File): Boolean =
            runCatching { OsConstants.S_ISLNK(Os.lstat(file.path).st_mode) }.getOrDefault(false)
    }
}
