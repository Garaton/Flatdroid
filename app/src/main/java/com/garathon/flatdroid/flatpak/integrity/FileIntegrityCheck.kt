package com.garathon.flatdroid.flatpak.integrity

import android.system.Os
import android.system.OsConstants
import com.garathon.flatdroid.BuildConfig
import com.garathon.flatdroid.proot.L2s
import com.garathon.flatdroid.util.debug.DebugLog
import com.garathon.flatdroid.util.io.DirReader
import com.garathon.flatdroid.util.io.NativeFs
import com.garathon.flatdroid.util.io.sha256
import com.garathon.flatdroid.util.io.writeTextAtomically
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS),
    /** Folders listed, links read and files stat'ed many at a time. */
    private val reader: DirReader = NativeFs,
) {
    private val store = L2s.Store(l2sDir)

    private val statOnly = fs(hash = false)
    private val withHash = fs(hash = true)

    private fun fs(hash: Boolean) = FileIntegrity.Fs(
        isSymlink = { isSymlink(it) },
        // No lstat first: nearly every deployed file is a link, and readlink says EINVAL for one that isn't.
        readlink = { runCatching { Os.readlink(it.path) }.getOrNull() },
        stat = {
            runCatching { Os.stat(it.path) }.getOrNull()?.let { s -> Triple(s.st_ino, s.st_ctime, s.st_size) }
        },
        sha256 = { if (hash && it.isFile) runCatching { sha256(it) }.getOrNull() else null },
        isStoreLink = { store.storedName(it) != null },
        list = { dir -> reader.list(dir)?.let { l -> List(l.size) { l.names[it] to l.isDirectory(it) } } },
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
        ignore: Set<String> = emptySet(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ) {
        val leaves = leaves(files, ignore, cancelled)
        val entries = withPool { pool ->
            parallel(pool, leaves, onProgress, cancelled) { rel -> rel to FileIntegrity.entry(File(files, rel), withHash) }
        }.toMap()
        // A skipped baseline writes nothing, so the next launch records it afresh (and trusts it).
        if (cancelled()) return
        write(commit, entries)
    }

    /** The leaves [verify] looks at in [files]: [FileIntegrity.walk]'s, without the [ignore]d paths. */
    fun leaves(files: File, ignore: Set<String> = emptySet(), cancelled: () -> Boolean = { false }): List<String> =
        FileIntegrity.walk(files, statOnly, cancelled).filterNot { it in ignore }

    /**
     * Checks [files] against the baseline for [commit], re-hashing only the files whose shape
     * (symlink target, inode, ctime, size) changed. Null when there is no baseline for [commit].
     * The walk and the compare are [NativeCheck]'s, on [threads]; the hashing is this pool's.
     * [cancelled] is polled so the user can skip; a cancelled check returns a clean [FileIntegrity.Diff].
     * When everything matches, files whose stat changed but whose content didn't are written back,
     * so the next check takes the fast path for them again.
     */
    fun verify(
        commit: String,
        files: File,
        ignore: Set<String> = emptySet(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): FileIntegrity.Diff? {
        val started = System.nanoTime()
        val manifest = manifestFile(commit)
        val skipped = FileIntegrity.Diff(emptyList(), emptyList())
        val storePaths = store.paths.toList()
        val found = when (val outcome = NativeCheck.compare(
            files, manifest, listOf(storePaths[0], storePaths.getOrElse(1) { "" }, store.canonical.orEmpty()), ignore, threads, onProgress, cancelled,
        )) {
            null -> return null
            NativeCheck.Cancelled -> return skipped
            is NativeCheck.Found -> outcome
        }
        // Only files the baseline has, whose shape changed, need their content read (a new file is
        // tampering whatever it holds, and a link-only entry is compared by its target).
        val toHash = found.current.filter { (rel, shape) -> rel in found.baseline && !shape.linkOnly }.keys.toList()
        val hashed = if (toHash.isEmpty()) emptyMap() else withPool { pool ->
            parallel(pool, toHash, onProgress, cancelled) { rel -> rel to FileIntegrity.entry(File(files, rel), withHash) }.toMap()
        }
        if (cancelled()) return skipped
        if (BuildConfig.DEBUG) {
            DebugLog.i { "File check: ${found.leaves} files in ${(System.nanoTime() - started) / 1_000_000} ms, ${found.current.size} differ, ${toHash.size} hashed, ${found.removed.size} removed" }
        }
        // Entries recorded without a hash (a link broken then) that read now: looked up in the
        // manifests only when there are any.
        val unhashed = toHash.mapNotNullTo(HashSet()) { rel -> found.baseline[rel]?.takeIf { it.sha256 == null }?.link?.let(store::storedName) }
        val recorded by lazy { recordedHashes(unhashed) }
        val diff = FileIntegrity.diff(found.baseline, found.current, { link -> store.storedName(link)?.let { recorded[it] }.orEmpty() }) { hashed[it] }
        val checked = FileIntegrity.Diff(diff.tampered, found.removed.sorted(), diff.refreshed)
        if (checked.clean && checked.refreshed.isNotEmpty()) {
            val baseline = runCatching { FileIntegrity.read(manifest.readText()) }.getOrNull()?.filterKeys { it !in ignore }
            if (baseline != null) {
                write(commit, baseline + checked.refreshed)
                countAdopted(checked.refreshed.filter { (rel, entry) -> entry.sha256 != null && found.baseline[rel]?.sha256 == null })
            }
        }
        return checked
    }

    /**
     * Entries that took a hash recorded elsewhere ([recordedHashes]): their link reads through a
     * store entry made again after it was deleted, which link2symlink never counted it in, so it
     * is counted now ([BrokenLinks.countOneMore]). After the manifest is written: a stop in
     * between leaves the count as it was, never counted twice.
     */
    private fun countAdopted(adopted: Map<String, FileIntegrity.Entry>) {
        if (adopted.isEmpty()) return
        val links = BrokenLinks(l2sDir, AndroidLinkFs)
        for ((rel, entry) in adopted) {
            val name = entry.link?.let(store::storedName) ?: continue
            val counted = runCatching { links.countOneMore(name) }.getOrDefault(false)
            if (BuildConfig.DEBUG) DebugLog.i { "File check: $rel took a recorded hash; its link ${if (counted) "is counted now" else "couldn't be counted"}" }
        }
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
     * again. When another entry recorded a hash for the same content file, the file must still
     * hold it, or the entry stays as it was (and the check reports the file). Returns how many
     * entries were recorded again.
     */
    fun rereadRepaired(intermediates: Collection<String>): Int {
        if (intermediates.isEmpty()) return 0
        val names = intermediates.toHashSet()
        val recorded = recordedHashes(names)
        var reread = 0
        for (file in integrityDir.listFiles().orEmpty()) {
            if (!file.name.endsWith(SUFFIX)) continue
            val baseline = runCatching { FileIntegrity.read(file.readText()) }.getOrNull() ?: continue
            val fixed = baseline.mapValues { (_, entry) ->
                val link = entry.link
                if (entry.sha256 != null || link == null || File(link).name !in names) return@mapValues entry
                val target = File(link)
                val stat = withHash.stat(target) ?: return@mapValues entry
                val hash = withHash.sha256(target)
                if (recorded[File(link).name]?.let { hash !in it } == true) return@mapValues entry
                reread++
                FileIntegrity.Entry(link, stat.first, stat.second, stat.third, hash)
            }
            if (fixed != baseline) write(file.name.removeSuffix(SUFFIX), fixed)
        }
        return reread
    }

    /**
     * The hashes the manifests recorded for the content files behind the intermediates [names]
     * (link2symlink's store: every hard link to one content file goes through one intermediate),
     * by intermediate. One read of each manifest, and only the lines that name one are parsed;
     * nothing is read when [names] is empty.
     */
    private fun recordedHashes(names: Set<String>): Map<String, Set<String>> {
        if (names.isEmpty()) return emptyMap()
        val recorded = HashMap<String, MutableSet<String>>()
        for (file in integrityDir.listFiles().orEmpty()) {
            if (!file.name.endsWith(SUFFIX)) continue
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            for (name in names) {
                var at = text.indexOf(name)
                while (at >= 0) {
                    val end = text.indexOf('\n', at).let { if (it < 0) text.length else it }
                    for (entry in FileIntegrity.read(text.substring(text.lastIndexOf('\n', at) + 1, end)).values) {
                        val hash = entry.sha256 ?: continue
                        val link = entry.link ?: continue
                        if (link.substring(link.lastIndexOf('/') + 1) == name && store.storedName(link) != null) {
                            recorded.getOrPut(name, ::HashSet) += hash
                        }
                    }
                    at = text.indexOf(name, end)
                }
            }
        }
        return recorded
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

    /** [parallelChunks] with [work] done one item at a time, progress reported per item. */
    private fun <T> parallel(
        pool: ExecutorService,
        items: List<String>,
        onProgress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
        work: (String) -> T,
    ): List<T> {
        val done = AtomicInteger()
        return parallelChunks(pool, items, { _, _ -> }, cancelled) { chunk ->
            val results = ArrayList<T>(chunk.size)
            for (item in chunk) {
                // Skipped, or stopped (the chunk is cancelled with an interrupt): no more files.
                if (cancelled() || Thread.currentThread().isInterrupted) break
                results += work(item)
                onProgress(done.incrementAndGet(), items.size)
            }
            results
        }
    }

    /**
     * Runs [work] over [items] across [threads], in chunks of [CHUNK] (one task per file would
     * cost more than a stat), reporting progress per chunk; order of the result is undefined. When
     * [cancelled] turns true the chunks stop between files and the wait ends at once, returning the
     * results so far (the caller discards them on a skip); the unfinished chunks are cancelled with
     * an interrupt, so a skipped check doesn't hold the launch while a large runtime finishes hashing.
     * [pool] is [withPool]'s.
     */
    private fun <T> parallelChunks(
        pool: ExecutorService,
        items: List<String>,
        onProgress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
        work: (List<String>) -> List<T>,
    ): List<T> {
        if (items.isEmpty()) return emptyList()
        val total = items.size
        val done = AtomicInteger()
        val chunks = items.chunked(CHUNK)
        val futures = ArrayList<Future<ArrayList<T>>>(chunks.size)
        try {
            for (chunk in chunks) {
                futures += pool.submit(Callable {
                    if (cancelled() || Thread.currentThread().isInterrupted) return@Callable ArrayList<T>()
                    val results = ArrayList(work(chunk))
                    onProgress(done.addAndGet(results.size), total)
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
            // Nothing is left running when this returns normally; after a skip or a stop the interrupt ends the chunks.
            futures.forEach { it.cancel(true) }
        }
    }

    /** One pool of [threads] for a check, ended (with an interrupt, for what is still running) when [block] returns. */
    private fun <T> withPool(block: (ExecutorService) -> T): T {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            return block(pool)
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val SUFFIX = ".manifest"
        const val METADATA_SUFFIX = ".metadata"
        const val POLL_MS = 50L
        const val CHUNK = 64
        /** Threads for the hashing and the check's reads: more gained little on a slow phone, and older phones have fewer cores. */
        const val MAX_THREADS = 4


        fun isSymlink(file: File): Boolean =
            runCatching { OsConstants.S_ISLNK(Os.lstat(file.path).st_mode) }.getOrDefault(false)
    }
}
