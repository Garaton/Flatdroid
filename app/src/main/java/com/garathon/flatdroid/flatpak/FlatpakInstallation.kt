package com.garathon.flatdroid.flatpak

import com.garathon.flatdroid.util.readTextUpTo
import java.io.File

/** An installed app or runtime: its ref and deployed directory (with `files/` and `metadata`). */
data class Deployment(val ref: FlatpakRef, val dir: File, val metadata: FlatpakMetadata, val kind: String = "runtime") {
    val files: File get() = File(dir, "files")
    /** The ref Flatpak installs it by: `app/…` or `runtime/…`. */
    val installRef: String get() = "$kind/$ref"
    /** `<kind>/<ID>/<arch>/<branch>`, relative to the installation. */
    val branch: String get() = "$kind/${ref.name}/${ref.arch}/${ref.branch}"
    val export: File get() = File(dir, "export")
}

/** An installed extension and where it is mounted, relative to the guest's /usr or /app. */
data class MountedExtension(val deployment: Deployment, val guestPath: String, val addLdPath: String?)

/**
 * Reads the Flatpak user installation from the host side (`<rootfs>/root/.local/share/flatpak`),
 * without running Flatpak. Only deployments whose resolved path stays inside the installation are
 * used; Flatpak's links are relative, and anything resolving elsewhere is treated as missing.
 */
class FlatpakInstallation(root: File, private val arch: String) {
    private val root = root.canonicalFile

    /** Installed app IDs, sorted. */
    fun appIds(): List<String> =
        File(root, "app").listFiles { f -> f.isDirectory }?.map { it.name }?.filter(::isValidId)?.sorted() ?: emptyList()

    /**
     * When the app was installed, in milliseconds since the epoch: its directory changes only when
     * a branch or architecture is added, not on updates; null if it isn't installed.
     */
    fun installed(id: String): Long? =
        if (isValidId(id)) File(root, "app/$id").lastModified().takeIf { it > 0 } else null

    /** The app's current deployment (the branch Flatpak marks as current, else the only one). */
    fun app(id: String): Deployment? {
        if (!isValidId(id)) return null
        val appDir = File(root, "app/$id")
        val current = File(appDir, "current")
        val branch = inside(current)?.takeIf { it.parentFile?.name == arch }?.name
            ?: File(appDir, arch).list()?.singleOrNull()
            ?: return null
        return deployment("app", FlatpakRef(id, arch, branch))
    }

    fun runtime(ref: FlatpakRef): Deployment? = deployment("runtime", ref)

    /**
     * The deployments a launch of [appId] uses: the app, its runtime, and the extensions mounted
     * under each (GL, codecs, locales, …). Used to baseline and to check files; a deployment's
     * `dir.name` is its OSTree commit, which changes on update, so each has its own baseline.
     */
    fun deploymentsFor(appId: String): List<Deployment> {
        val app = app(appId) ?: return emptyList()
        val runtimeRef = app.metadata.runtime ?: return listOf(app)
        val runtime = runtime(runtimeRef) ?: return listOf(app)
        val extensions = extensions(runtime.metadata.extensionPoints, runtimeRef.branch) +
            extensions(app.metadata.extensionPoints, app.ref.branch)
        return listOf(app, runtime) + extensions.map { it.deployment }
    }

    /**
     * Flathub refs to reinstall to restore every deployment [appId] uses — the app, its runtime and
     * their extensions — so a reinstall after the file check flags a change restores the changed
     * extension too, not only the app and base runtime. `deploymentsFor` returns the app first and
     * the runtime and extensions (all runtime-type refs) after it.
     */
    fun reinstallRefsFor(appId: String): List<String> {
        val deployments = deploymentsFor(appId)
        if (deployments.isEmpty()) return emptyList()
        return deployments.map { it.installRef }.distinct()
    }

    /** `app/<ID>/<arch>/<branch>` and `runtime/…` of each deployment [appId] uses, relative to the installation. */
    fun branchesFor(appId: String): List<String> = deploymentsFor(appId).map { it.branch }.distinct()

    /**
     * Installed extensions for [points] of a parent on [parentBranch]: for each point, the first of
     * its versions that is installed (or the parent's branch when it lists none). With
     * subdirectories, every installed `<point>.<suffix>` is mounted at `<directory>/<suffix>`.
     */
    fun extensions(points: List<ExtensionPoint>, parentBranch: String): List<MountedExtension> {
        val runtimes = File(root, "runtime").list()?.toList() ?: emptyList()
        val mounted = mutableListOf<MountedExtension>()
        for (point in points) {
            val versions = point.versions.ifEmpty { listOf(parentBranch) }
            val names = if (point.subdirectories) {
                runtimes.filter { it.startsWith(point.name + ".") }.sorted()
            } else {
                listOf(point.name).filter { it in runtimes }
            }
            for (name in names) {
                val deployment = versions.firstNotNullOfOrNull { runtime(FlatpakRef(name, arch, it)) } ?: continue
                val guest = if (point.subdirectories) "${point.directory}/${name.removePrefix(point.name + ".")}" else point.directory
                mounted += MountedExtension(deployment, guest, point.addLdPath)
            }
        }
        return mounted
    }

    private fun deployment(kind: String, ref: FlatpakRef): Deployment? {
        if (!isValidId(ref.name) || !isValidId(ref.branch) || !isValidId(ref.arch)) return null
        val dir = inside(File(root, "$kind/${ref.name}/${ref.arch}/${ref.branch}/active")) ?: return null
        val metadata = try {
            FlatpakMetadata.parse(File(dir, "metadata").readTextUpTo() ?: return null)
        } catch (_: Exception) {
            return null
        }
        if (!File(dir, "files").isDirectory) return null
        return Deployment(ref, dir, metadata, kind)
    }

    /** [file] resolved, or null if it doesn't exist or resolves outside the installation. */
    private fun inside(file: File): File? {
        val resolved = try {
            file.canonicalFile
        } catch (_: Exception) {
            return null
        }
        return resolved.takeIf { it.exists() && it.path.startsWith(root.path + "/") }
    }

    private fun isValidId(part: String) = part.isNotEmpty() && part != "." && part != ".." && '/' !in part && ':' !in part
}
