package com.garathon.flatdroid.launcher

import com.garathon.flatdroid.flatpak.Deployment
import com.garathon.flatdroid.setup.TarFs
import com.garathon.flatdroid.setup.deleteTree
import com.garathon.flatdroid.util.writeTextAtomically
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * An Electron build for this device standing in for one an app ships for another architecture
 * (Flathub's aarch64 Unofficial Homestuck Collection contains x86_64 Electron 9.4.4). Same
 * version, the app's own `resources/` copied in, renamed to the app's binary name, and bound over
 * the app's Electron directory. Kept in `files/electron/<ID>`; prepared again when the app updates.
 * 
 * (The Homestuck rule)
 */
class ElectronSwap(
    private val dir: File,
    private val cacheDir: File,
    private val arch: String,
    /**
     * Where the app's resources may resolve to: the Flatpak installation and link2symlink's
     * directory. A link elsewhere is refused, since the swap is bound into the app's root.
     */
    private val allowedSources: List<File>,
    private val fs: TarFs,
    /** Downloads a URL to a file (resumable), reporting bytes done and total (-1 if unknown). */
    private val download: (url: String, dest: File, maxBytes: Long, onProgress: (Long, Long) -> Unit) -> Unit,
    private val sha256: (File) -> String,
) {
    private val marker get() = File(dir, MARKER)

    /** The prepared directory, if it was made for this deployment of the app (and is still a directory, not a link). */
    fun preparedFor(app: Deployment): File? =
        dir.takeIf { !fs.isSymlink(it) && marker.isFile && marker.readText().lines().firstOrNull() == "deploy=${app.dir.name}" }

    /** What [prepare] is doing, for its progress. */
    enum class Step { CHECKSUMS, DOWNLOAD, VERIFY, EXTRACT, RESOURCES }

    /**
     * Downloads Electron [needed]'s version for this device, checks it against the release's
     * SHASUMS256.txt, and builds the swap directory. [onProgress] gets the step, the Electron zip's
     * name and the download's fraction. Interruptible; a half-built swap is never marked as prepared.
     */
    fun prepare(app: Deployment, needed: PlanResult.ElectronSwapNeeded, onProgress: (Step, String, Float?) -> Unit) {
        val version = needed.version ?: throw IOException("The app's Electron version could not be read")
        if (needed.hasNativeModules) throw IOException("The app has native modules built for ${needed.binaryArch}; another Electron can't load them")
        val zipName = Electron.zipName(version, arch)
        val downloads = File(cacheDir, "electron-$version").apply { mkdirs() }

        onProgress(Step.CHECKSUMS, zipName, null)
        val sums = File(downloads, "SHASUMS256.txt")
        if (!sums.isFile) download(Electron.releaseUrl(version, "SHASUMS256.txt"), sums, 1L shl 20) { _, _ -> }
        val expected = Electron.sha256(sums.readText(), zipName)
            ?: throw IOException("$zipName is not in Electron $version's SHASUMS256.txt")

        val zip = File(downloads, zipName)
        if (!zip.isFile) {
            download(Electron.releaseUrl(version, zipName), zip, MAX_ZIP_BYTES) { done, total ->
                onProgress(Step.DOWNLOAD, zipName, if (total > 0) done.toFloat() / total else null)
            }
        }
        onProgress(Step.VERIFY, zipName, null)
        val actual = sha256(zip)
        if (actual != expected) {
            zip.delete()
            throw IOException("$zipName checksum mismatch: $actual, expected $expected")
        }

        onProgress(Step.EXTRACT, zipName, null)
        val work = File(dir.path + ".new")
        fs.deleteTree(work)
        work.mkdirs()
        extract(zip, work)

        // The app's own code and assets, with its links resolved (they point into .l2s).
        onProgress(Step.RESOURCES, zipName, null)
        val resources = File(work, "resources")
        File(resources, "default_app.asar").delete()
        copyResolved(File(needed.hostAppDir, "resources"), resources)

        val electron = File(work, "electron")
        if (!electron.isFile) throw IOException("$zipName has no electron binary")
        if (needed.binaryName != "electron" && !electron.renameTo(File(work, needed.binaryName))) {
            throw IOException("Could not rename electron to ${needed.binaryName}")
        }
        for (name in listOf(needed.binaryName, "chrome-sandbox", "chrome_crashpad_handler")) {
            File(work, name).takeIf { it.isFile }?.let { fs.chmod(it, 0x1ed) } // 0755
        }
        File(work, MARKER).writeTextAtomically("deploy=${app.dir.name}\nversion=$version\narch=$arch\n")

        fs.deleteTree(dir)
        if (!work.renameTo(dir)) throw IOException("Could not move $work to $dir")
        fs.deleteTree(downloads)
    }

    /** Removes the swap (e.g. when the app is uninstalled). */
    fun delete() {
        fs.deleteTree(dir)
        fs.deleteTree(File(dir.path + ".new"))
    }

    private fun extract(zip: File, dest: File) {
        val root = dest.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                if (Thread.interrupted()) throw InterruptedException()
                val entry = input.nextEntry ?: break
                val name = entry.name.trimStart('/')
                if (name.split('/').any { it == ".." }) throw IOException("Unsafe path in the zip: ${entry.name}")
                val out = File(root, name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    /**
     * Copies [from] with its file links resolved (hard links are links into .l2s). Linked
     * directories aren't followed, and a link resolving outside [allowedSources] fails the copy:
     * an app could otherwise get any of Flatdroid's files copied into its own root.
     */
    private fun copyResolved(from: File, to: File) {
        if (!from.isDirectory) throw IOException("The app has no resources directory at $from")
        val allowed = allowedSources.map { it.canonicalPath + "/" }
        to.mkdirs()
        from.walkTopDown().onEnter { it == from || !fs.isSymlink(it) }.forEach { src ->
            if (Thread.interrupted()) throw InterruptedException()
            val relative = src.toRelativeString(from)
            if (relative.isEmpty()) return@forEach
            val dst = File(to, relative)
            when {
                fs.isSymlink(src) && src.isDirectory -> throw IOException("$relative links to a directory; not copied")
                src.isDirectory -> dst.mkdirs()
                else -> {
                    val real = src.canonicalPath
                    if (allowed.none { real.startsWith(it) }) throw IOException("$relative links outside the app ($real)")
                    src.copyTo(dst, overwrite = true)
                }
            }
        }
    }

    private companion object {
        const val MARKER = ".flatdroid-electron"
        /** Electron's Linux zips are 60–120 MiB. */
        const val MAX_ZIP_BYTES = 256L shl 20
    }
}
