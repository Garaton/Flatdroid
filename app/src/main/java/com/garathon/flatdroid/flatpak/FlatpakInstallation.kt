package com.garathon.flatdroid.flatpak

import com.garathon.flatdroid.util.readTextUpTo
import java.io.File

/** An installed app or runtime: its ref and deployed directory (with `files/` and `metadata`). */
data class Deployment(val ref: FlatpakRef, val dir: File, val metadata: FlatpakMetadata) {
    val files: File get() = File(dir, "files")
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
        return Deployment(ref, dir, metadata)
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
