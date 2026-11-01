package com.garathon.flatdroid.flatpak

import android.content.Context
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.launcher.LaunchPlanner
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Orchestrates the file-integrity check over all the deployments one app uses (the app, its
 * runtime and their extensions). Checks every deployment and gathers every change, so a report
 * covers all affected packages, not just the first. Baselines and the check both run from the
 * host side (outside proot); the manifests live in Flatdroid's private storage.
 *
 * A baseline, once recorded for a commit, is never replaced: a reinstall or update that brings
 * the same commit back must match it, so damaged local data Flatpak reuses can't become the new
 * "known good" (link2symlink keeps a deployed file and its repository object in one file).
 */
class IntegrityGuard(context: Context) {
    private val env = AlpineEnvironment.get(context)
    private val installation = FlatpakInstallation(env.flatpakDir, env.arch)
    private val check = FileIntegrityCheck(AppData.integrityDir(context), File(env.paths().l2sDir))

    /** A deployment whose files changed since its baseline. */
    data class Problem(val deployment: Deployment, val tampered: List<String>, val removed: List<String>) {
        val ref: FlatpakRef get() = deployment.ref
        /** The deployed files behind [tampered]. */
        val changedFiles: List<File> get() = tampered.map { if (it == METADATA) File(deployment.dir, "metadata") else File(deployment.files, it) }
    }

    companion object {
        /**
         * Deployment-relative paths Flatdroid overrides at every launch with its own file (the
         * `/etc` files the launcher binds). The app only ever reads Flatdroid's version, never the
         * deployment's, and proot leaves an empty stub where the runtime ships none (so a launch
         * changes the deployment). They are therefore left out of the check.
         */
        val OVERRIDDEN_PATHS: Set<String> = LaunchPlanner.ETC_OVERRIDES.map { "etc/$it" }.toSet()

        /** How a changed `metadata` (beside `files/`) is reported among the changed paths. */
        const val METADATA = "../metadata"

        /** Deployments listed ahead at once ([verify]); listing is mostly waiting on storage. */
        private const val LISTING_THREADS = 3
    }

    private val ignore: (String) -> Boolean = { it in OVERRIDDEN_PATHS }

    /**
     * Records the baseline of each deployment [appId] uses that doesn't have one yet (manifests are
     * keyed by the deployment's commit, so after an update an app whose commit didn't change keeps
     * its baseline and only new or updated commits are hashed). [baselinedCommits], shared across
     * the apps of one task, keeps a runtime used by several apps from being hashed once per app.
     * [onStart] is called for each deployment that is actually baselined, before it is hashed.
     * Commits in [skip] are left for later (deployments whose extra-data script hasn't run yet).
     */
    fun recordBaseline(
        appId: String,
        baselinedCommits: MutableSet<String> = mutableSetOf(),
        skip: Set<String> = emptySet(),
        onStart: (ref: FlatpakRef) -> Unit = {},
        onProgress: (ref: FlatpakRef, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ) {
        for (d in installation.deploymentsFor(appId)) {
            if (d.dir.name in skip || !baselinedCommits.add(d.dir.name)) continue
            record(d.dir.name, d.dir) { onStart(d.ref); { done, total -> onProgress(d.ref, done, total) } }
        }
    }

    /**
     * Records the baseline of the deployment in [dir] (`<kind>/<ID>/<arch>/<branch>/<commit>`) if
     * its commit has none: one whose extra-data script just ran.
     */
    fun recordDeployment(dir: File, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) = record(dir.name, dir) { onProgress }

    /** [commit]'s metadata hash and file baseline from [dir], each unless recorded; [starting] is called before hashing the files. */
    private fun record(commit: String, dir: File, starting: () -> (Int, Int) -> Unit) {
        val metadata = File(dir, "metadata")
        if (check.metadataMatches(commit, metadata) == null) check.baselineMetadata(commit, metadata)
        if (check.hasBaseline(commit)) return
        val progress = starting()
        check.baseline(commit, File(dir, "files"), ignore, progress)
    }

    /**
     * Checks every deployment [appId] uses. A deployment with no baseline yet is baselined now and
     * trusted (the state before the check was first on can't be verified). Returns every deployment
     * that changed; empty means all files match. [cancelled] lets the user skip.
     */
    fun verify(
        appId: String,
        onProgress: (ref: FlatpakRef, done: Int, total: Int) -> Unit = { _, _, _ -> },
        cancelled: () -> Boolean = { false },
    ): List<Problem> = verify(installation.deploymentsFor(appId), onProgress, cancelled, recordMissing = true)

    /**
     * [verify] over every deployment [appIds] use, each commit once (before an update reuses their
     * data, or to find what a reinstall must restore). A deployment without a baseline is skipped:
     * there is nothing to compare it with, and hashing it all here would hold up the task (it is
     * recorded at its app's next launch, or by Record baselines).
     */
    fun verifyAll(appIds: List<String>, onProgress: (ref: FlatpakRef, done: Int, total: Int) -> Unit = { _, _, _ -> }): List<Problem> =
        verify(appIds.flatMap { installation.deploymentsFor(it) }.distinctBy { it.dir.name }, onProgress, { false }, recordMissing = false)

    /**
     * Checks [deployments] in order. Listing a deployment's files is one thread's work and reports
     * no progress, so the next deployments are listed meanwhile ([LISTING_THREADS] at a time),
     * while the current one is stat'ed and hashed; [onProgress] gets `0` of `0` when a
     * deployment's turn comes and its list isn't ready yet.
     */
    private fun verify(
        deployments: List<Deployment>,
        onProgress: (ref: FlatpakRef, done: Int, total: Int) -> Unit,
        cancelled: () -> Boolean,
        recordMissing: Boolean,
    ): List<Problem> {
        val problems = mutableListOf<Problem>()
        val checked = deployments.filter { recordMissing || check.hasBaseline(it.dir.name) }
        if (checked.isEmpty()) return problems
        val listing = Executors.newFixedThreadPool(LISTING_THREADS.coerceAtMost(checked.size))
        try {
            val lists = checked.map { d -> listing.submit(Callable { check.leaves(d.files, ignore, cancelled) }) }
            for ((i, d) in checked.withIndex()) {
                if (cancelled()) break
                if (!lists[i].isDone) onProgress(d.ref, 0, 0)
                val listed = lists[i].get()
                val metadata = File(d.dir, "metadata")
                val metadataChanged = when (check.metadataMatches(d.dir.name, metadata)) {
                    null -> { if (recordMissing) check.baselineMetadata(d.dir.name, metadata); false }
                    true -> false
                    false -> true
                }
                val diff = check.verify(d.dir.name, d.files, ignore, { done, total -> onProgress(d.ref, done, total) }, cancelled, listed)
                if (diff == null) {
                    // First time (or install predating the check): trust the current files as the baseline.
                    check.baseline(d.dir.name, d.files, ignore, { done, total -> onProgress(d.ref, done, total) }, cancelled)
                }
                val tampered = (if (metadataChanged) listOf(METADATA) else emptyList()) + diff?.tampered.orEmpty()
                val removed = diff?.removed.orEmpty()
                if (tampered.isNotEmpty() || removed.isNotEmpty()) problems += Problem(d, tampered, removed)
            }
        } finally {
            listing.shutdownNow()
        }
        return problems
    }

    /** Whether every deployed commit has a baseline (false after installs or updates while the check was off). */
    fun allBaselined(): Boolean = deployedCommits().all(check::hasBaseline)

    /**
     * After a reinstall that removed [problems]' changed files from the repository ([ChangedContent]):
     * those files had to be downloaded again, and OSTree checks every object it fetches against its
     * commit (a repository file no Flatpak can reach), so their new content replaces their baseline
     * entries, which may have been recorded from the changed files ("trust" after a change). A
     * changed or removed path the fresh deployment doesn't have isn't part of the commit (written
     * into it after the baseline was taken, by a tool or an older Flatdroid) and leaves the
     * baseline. Only those paths, and only where the same commit is still deployed; every other
     * file must match.
     */
    fun adoptRedownloaded(problems: List<Problem>) {
        for (p in problems) {
            val d = p.deployment
            if (!d.dir.isDirectory) continue
            if (METADATA in p.tampered) check.baselineMetadata(d.dir.name, File(d.dir, "metadata"))
            check.refresh(d.dir.name, d.files, p.tampered + p.removed - METADATA)
        }
    }

    /** Removes manifests for commits no longer deployed (after an update, uninstall or reset). */
    fun cleanOrphans() = check.cleanExcept(deployedCommits())

    private fun deployedCommits(): Set<String> {
        val deployed = mutableSetOf<String>()
        for (kind in listOf("app", "runtime")) {
            File(env.flatpakDir, kind).listFiles().orEmpty().forEach { id ->
                id.listFiles().orEmpty().forEach { arch ->
                    arch.listFiles().orEmpty().forEach { branch ->
                        val active = File(branch, "active")
                        runCatching { active.canonicalFile.name }.getOrNull()?.takeIf { active.exists() }?.let(deployed::add)
                    }
                }
            }
        }
        return deployed
    }
}
