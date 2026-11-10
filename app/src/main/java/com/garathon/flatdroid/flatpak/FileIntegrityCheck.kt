package com.garathon.flatdroid.flatpak

import android.system.Os
import android.system.OsConstants
import com.garathon.flatdroid.util.sha256
import com.garathon.flatdroid.util.writeTextAtomically
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records and checks a deployment's file-integrity baseline on Android: the host-side `Os` calls
 * for [FileIntegrity], the manifest files (one per deployment commit, in Flatdroid's private
 * storage, never bound into a Flatpak root), and the parallel hashing. One deployment commit = one
 * manifest, so an update (a new commit) gets a fresh baseline and old manifests are cleaned up.
 * The deployment's `metadata` (its command, runtime, extensions and environment, which the
 * launcher reads at every launch) has its own hash beside the manifest.
 */
class FileIntegrityCheck(
    private val integrityDir: File,
    /** link2symlink's store: links into it are followed, any other is recorded by its target. */
    private val l2sDir: File,
    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
) {
    private val statOnly = fs(hash = false)
    private val withHash = fs(hash = true)

    private fun fs(hash: Boolean) = FileIntegrity.Fs(
        isSymlink = { isSymlink(it) },
        readlink = { if (isSymlink(it)) runCatching { Os.readlink(it.path) }.getOrNull() else null },
        stat = {
            runCatching { Os.stat(it.path) }.getOrNull()?.let { s -> Triple(s.st_ino, s.st_ctime, s.st_size) }
        },
        sha256 = { if (hash && it.isFile) runCatching { sha256(it) }.getOrNull() else null },
        isStoreLink = { L2s.storedName(it, l2sDir) != null },
    )

    fun manifestFile(commit: String): File = File(integrityDir, "$commit$SUFFIX")
    fun hasBaseline(commit: String): Boolean = manifestFile(commit).isFile
    private fun metadataHashFile(commit: String): File = File(integrityDir, "$commit$METADATA_SUFFIX")

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
        write(commit, entries)
    }

    /** The leaves [verify] looks at in [files]: [FileIntegrity.walk]'s, without the [ignore]d paths. */
    fun leaves(files: File, ignore: (String) -> Boolean = { false }, cancelled: () -> Boolean = { false }): List<String> =
        FileIntegrity.walk(files, statOnly, cancelled).filterNot(ignore)

    /**
     * Checks [files] against the baseline for [commit], re-hashing only the files whose shape
     * (symlink target, inode, ctime, size) changed. Null when there is no baseline for [commit].
     * [cancelled] is polled so the user can skip; a cancelled check returns a clean [FileIntegrity.Diff].
     * When everything matches, files whose stat changed but whose content didn't are written back,
     * so the next check takes the fast path for them again. [listed] is [leaves]' result when the
     * caller listed them already.
     */
    fun verify(
        commit: String,
        files: File,
        ignore: (String) -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
        listed: List<String>? = null,
    ): FileIntegrity.Diff? {
        // Drop ignored paths from both sides, so an older baseline that still lists one doesn't
        // read as removed once it is left out of the current scan.
        val baseline = (runCatching { FileIntegrity.read(manifestFile(commit).readText()) }.getOrNull() ?: return null)
            .filterKeys { !ignore(it) }
        val leaves = listed ?: leaves(files, ignore, cancelled)
        if (cancelled()) return FileIntegrity.Diff(emptyList(), emptyList())
        // Cheap pass: shape only (stat + readlink, no content read).
        val shapes = parallel(leaves, onProgress, cancelled) { rel -> rel to FileIntegrity.entry(File(files, rel), statOnly) }.toMap()
        if (cancelled()) return FileIntegrity.Diff(emptyList(), emptyList())
        // Only files the baseline has, whose shape changed, need their content read (a new file is
        // tampering whatever it holds, and a link-only entry is compared by its target).
        val toHash = shapes.filter { (rel, shape) ->
            val base = baseline[rel]
            base != null && !shape.linkOnly && !base.sameShape(shape)
        }.keys.toList()
        val hashed = parallel(toHash, onProgress, cancelled) { rel -> rel to FileIntegrity.entry(File(files, rel), withHash) }.toMap()
        if (cancelled()) return FileIntegrity.Diff(emptyList(), emptyList())
        val diff = FileIntegrity.diff(baseline, shapes) { hashed[it] }
        if (diff.clean && diff.refreshed.isNotEmpty()) write(commit, baseline + diff.refreshed)
        return diff
    }

    /**
     * Re-records [paths] of [commit]'s baseline from [files] as they are now, dropping those that
     * no longer exist; other entries stay.
     */
    fun refresh(commit: String, files: File, paths: Collection<String>) {
        if (paths.isEmpty()) return
        val baseline = runCatching { FileIntegrity.read(manifestFile(commit).readText()) }.getOrNull() ?: return
        val (present, gone) = paths.partition { runCatching { Os.lstat(File(files, it).path) }.isSuccess }
        write(commit, baseline - gone.toSet() + present.associateWith { FileIntegrity.entry(File(files, it), withHash) })
    }

    /**
     * After link2symlink intermediates were repaired ([BrokenLinks.relinkStranded]): entries any
     * baseline recorded as unreadable (no hash: the link was broken then) whose link goes through
     * one of [intermediates] (their names) are recorded again from the file as it reads now. A
     * baseline taken while a link was broken would otherwise report its file changed once it reads
     * again. Returns how many entries were recorded again.
     */
    fun rereadRepaired(intermediates: Collection<String>): Int {
        if (intermediates.isEmpty()) return 0
        val names = intermediates.toHashSet()
        var reread = 0
        for (file in integrityDir.listFiles().orEmpty()) {
            if (!file.name.endsWith(SUFFIX)) continue
            val baseline = runCatching { FileIntegrity.read(file.readText()) }.getOrNull() ?: continue
            val fixed = baseline.mapValues { (_, entry) ->
                val link = entry.link
                if (entry.sha256 != null || link == null || File(link).name !in names) return@mapValues entry
                val target = File(link)
                val stat = withHash.stat(target) ?: return@mapValues entry
                reread++
                FileIntegrity.Entry(link, stat.first, stat.second, stat.third, withHash.sha256(target))
            }
            if (fixed != baseline) write(file.name.removeSuffix(SUFFIX), fixed)
        }
        return reread
    }

    /** Records [metadata]'s hash for [commit]; nothing when it can't be read. */
    fun baselineMetadata(commit: String, metadata: File) {
        val hash = runCatching { sha256(metadata) }.getOrNull() ?: return
        integrityDir.mkdirs()
        metadataHashFile(commit).writeTextAtomically(hash + "\n", durable = false)
    }

    /** Whether [metadata] still has [commit]'s recorded hash; null when none is recorded yet. */
    fun metadataMatches(commit: String, metadata: File): Boolean? {
        val recorded = runCatching { metadataHashFile(commit).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { sha256(metadata) }.getOrNull() == recorded
    }

    /** Removes the manifests and metadata hashes of commits not in [keep]. */
    fun cleanExcept(keep: Set<String>) {
        integrityDir.listFiles()?.forEach { file ->
            val commit = when {
                file.name.endsWith(SUFFIX) -> file.name.removeSuffix(SUFFIX)
                file.name.endsWith(METADATA_SUFFIX) -> file.name.removeSuffix(METADATA_SUFFIX)
                else -> return@forEach
            }
            if (commit !in keep) file.delete()
        }
    }

    private fun write(commit: String, entries: Map<String, FileIntegrity.Entry>) {
        integrityDir.mkdirs()
        manifestFile(commit).writeTextAtomically(FileIntegrity.write(entries), durable = false)
    }

    /**
     * Runs [work] over [items] across [threads], in chunks of [CHUNK] (one task per file would
     * cost more than a stat), reporting progress; order of the result is undefined. When
     * [cancelled] turns true the chunks stop between files and the wait ends at once, returning the
     * results so far (the caller discards them on a skip); the pool is shut down with an interrupt,
     * so a skipped check doesn't hold the launch while a large runtime finishes hashing.
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
        val chunks = items.chunked(CHUNK)
        val pool = Executors.newFixedThreadPool(threads.coerceAtMost(chunks.size))
        try {
            val futures = chunks.map { chunk ->
                pool.submit(Callable {
                    val results = ArrayList<T>(chunk.size)
                    for (item in chunk) {
                        // Skipped, or stopped (the pool is shut down with an interrupt): no more files.
                        if (cancelled() || Thread.currentThread().isInterrupted) break
                        results += work(item)
                        onProgress(done.incrementAndGet(), total)
                    }
                    results
                })
            }
            val results = ArrayList<T>(total)
            collect@ for (f in futures) {
                while (!cancelled()) {
                    try {
                        results.addAll(f.get(POLL_MS, TimeUnit.MILLISECONDS)); continue@collect
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
        const val METADATA_SUFFIX = ".metadata"
        const val POLL_MS = 50L
        const val CHUNK = 64


        fun isSymlink(file: File): Boolean =
            runCatching { OsConstants.S_ISLNK(Os.lstat(file.path).st_mode) }.getOrDefault(false)
    }
}
