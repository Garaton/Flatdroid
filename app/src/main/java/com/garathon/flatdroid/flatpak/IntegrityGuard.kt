package com.garathon.flatdroid.flatpak

import android.content.Context
import com.garathon.flatdroid.alpine.AlpineEnvironment
import java.io.File

/**
 * Orchestrates the file-integrity check over all the deployments one app uses (the app, its
 * runtime and their extensions). Checks every deployment and gathers every change, so a report
 * covers all affected packages, not just the first. Baselines and the check both run from the
 * host side (outside proot); the manifests live in Flatdroid's private storage.
 */
class IntegrityGuard(context: Context) {
    private val env = AlpineEnvironment.get(context)
    private val installation = FlatpakInstallation(env.flatpakDir, env.arch)
    private val check = FileIntegrityCheck(AppData.integrityDir(context))

    /** A deployment whose files changed since its baseline. */
    data class Problem(val ref: FlatpakRef, val tampered: List<String>, val removed: List<String>)

    companion object {
        /**
         * Deployment-relative paths Flatdroid overrides at every launch with its own file (the
         * `/etc` overlays built in FlatpakLauncher.hostDirs). The app only ever reads Flatdroid's
         * version, never the deployment's, and proot leaves an empty stub where the runtime ships
         * none (so a launch changes the deployment). They are therefore left out of the check.
         */
        val OVERRIDDEN_ETC = listOf("resolv.conf", "hosts", "machine-id", "passwd", "group")
        val OVERRIDDEN_PATHS: Set<String> = OVERRIDDEN_ETC.map { "etc/$it" }.toSet()
    }

    private val ignore: (String) -> Boolean = { it in OVERRIDDEN_PATHS }

    /**
     * Records the baseline of each deployment [appId] uses that doesn't have one yet (manifests are
     * keyed by the deployment's commit, so after an update an app whose commit didn't change keeps
     * its baseline and only new or updated commits are hashed; a runtime shared by several apps is
     * hashed once). [force] re-records them all, for a reinstall that has restored pristine files.
     * [onStart] is called for each deployment that is actually baselined, before it is hashed.
     */
    fun recordBaseline(
        appId: String,
        force: Boolean = false,
        baselinedCommits: MutableSet<String> = mutableSetOf(),
        onStart: (ref: FlatpakRef) -> Unit = {},
        onProgress: (ref: FlatpakRef, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ) {
        for (d in installation.deploymentsFor(appId)) {
            // A commit already baselined in this run (a runtime or extension shared by several apps)
            // is skipped even when forcing, so it isn't hashed once per app.
            if (!baselinedCommits.add(d.dir.name)) continue
            if (!force && check.hasBaseline(d.dir.name)) continue
            onStart(d.ref)
            check.baseline(d.dir.name, d.files, ignore, { done, total -> onProgress(d.ref, done, total) })
        }
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
    ): List<Problem> {
        val problems = mutableListOf<Problem>()
        for (d in installation.deploymentsFor(appId)) {
            if (cancelled()) break
            val diff = check.verify(d.dir.name, d.files, ignore, { done, total -> onProgress(d.ref, done, total) }, cancelled)
            if (diff == null) {
                // First time (or install predating the check): trust the current files as the baseline.
                check.baseline(d.dir.name, d.files, ignore, { done, total -> onProgress(d.ref, done, total) }, cancelled)
            } else if (!diff.clean) {
                problems += Problem(d.ref, diff.tampered, diff.removed)
            }
        }
        return problems
    }

    /** Removes manifests for commits no longer deployed (after an update, uninstall or reset). */
    fun cleanOrphans() {
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
        check.cleanExcept(deployed)
    }
}
