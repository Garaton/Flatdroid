package com.garathon.flatdroid.storage

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import com.garathon.flatdroid.util.copySparse
import com.garathon.flatdroid.util.copyWithProgress
import com.garathon.flatdroid.util.writeAtomically
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Zips folders (a home, an app's settings) with java.util.zip, and extracts such zips. Links are
 * left out (a zip can't hold them as such, and following one could export files outside the
 * folder); file modes aren't kept.
 */
object HomeZip {
    /** Folders Flatdroid binds from elsewhere over a home: an app's settings and cache, not its files. */
    val SETTINGS_DIRS = setOf(".config", ".local", ".pki", ".var", ".cache")

    /** A folder to zip: its contents go under [prefix] (empty or ending in "/"), without the paths in [exclude] (relative to [dir]). */
    data class Part(val dir: File, val prefix: String = "", val exclude: Set<String> = emptySet())

    /** A home's files: everything but the settings directories bound over it. */
    fun write(dir: File, out: OutputStream, isLink: (File) -> Boolean, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }) =
        write(listOf(Part(dir, exclude = SETTINGS_DIRS)), out, isLink, onProgress)

    /** Writes [parts] to [out]; [onProgress] gets bytes written so far and the total. */
    fun write(parts: List<Part>, out: OutputStream, isLink: (File) -> Boolean, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }) {
        val entries = mutableListOf<Pair<String, File>>()
        fun collect(file: File, relative: String, part: Part) {
            for (child in file.listFiles().orEmpty().sortedBy { it.name }) {
                val path = relative + child.name
                if (path in part.exclude || isLink(child)) continue
                if (child.isDirectory) {
                    entries += "${part.prefix}$path/" to child
                    collect(child, "$path/", part)
                } else if (child.isFile) {
                    entries += part.prefix + path to child
                }
            }
        }
        for (part in parts) {
            if (!part.dir.isDirectory) continue
            if (part.prefix.isNotEmpty()) entries += part.prefix to part.dir
            collect(part.dir, "", part)
        }
        val total = entries.sumOf { (name, file) -> if (name.endsWith("/")) 0L else file.length() }
        var done = 0L
        val zip = ZipOutputStream(out)
        // Homes are mostly pictures, video and archives already compressed; deflating them hard
        // on a phone costs time for nearly nothing.
        zip.setLevel(Deflater.BEST_SPEED)
        for ((name, file) in entries) {
            zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
            if (!name.endsWith("/")) {
                val before = done
                file.inputStream().use { input -> input.copyWithProgress(zip) { copied -> done = before + copied; onProgress(done, total) } }
            }
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }

    /** What to do with a file the zip holds when the destination already has one by that name. */
    enum class Clash {
        /** Keep both: the zip's gets "name (1)" and so on. */
        RENAME,
        /** The zip's replaces it. */
        REPLACE,
        /** The one there stays; the zip's isn't extracted. */
        SKIP,
    }

    /** [skipped]: entries that would have left the destination; [kept]: files already there that [Clash.SKIP] kept. */
    data class Extracted(val files: Int, val skipped: Int, val kept: Int = 0)

    /**
     * A zip entry's name as a safe relative path ("a/b.txt"), or null for one that could leave
     * the destination: absolute, with "..", "." or empty parts.
     */
    fun relative(name: String): String? {
        val path = name.replace('\\', '/').trimEnd('/')
        if (path.isEmpty() || path.startsWith("/")) return null
        if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return path
    }

    /**
     * Extracts [input] (a zip). [destination] maps each entry's safe relative path to where it
     * goes, or null to skip it; every destination must stay inside [base] once links are
     * resolved, or the entry is skipped. Each file is written beside its destination and then
     * renamed into place, so one that fails half-way leaves nothing, and a replaced file stays as
     * it was. Stops with an error when less than [minFreeBytes] would be left on [base]'s storage.
     *
     * To continue an extraction that stopped, [skipEntries] entries (counted as [onEntry] reports
     * them, each once it is fully handled) are passed over, and [onResume] gets the destination of
     * the first one handled (where a write cut off by the stop was); the result counts only the
     * rest.
     */
    fun extract(
        input: InputStream,
        base: File,
        destination: (String) -> File?,
        clash: Clash,
        minFreeBytes: Long = MIN_FREE_BYTES,
        skipEntries: Int = 0,
        onEntry: (done: Int) -> Unit = {},
        onResume: (File) -> Unit = {},
    ): Extracted {
        val basePath = base.canonicalPath
        var files = 0
        var skipped = 0
        var kept = 0
        var written = 0L
        var index = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (Thread.interrupted()) throw InterruptedException()
                index++
                if (index <= skipEntries) continue
                val dest = relative(entry.name)?.let(destination)
                if (index == skipEntries + 1 && skipEntries > 0 && dest != null) onResume(dest)
                when {
                    dest == null || !inside(dest.parentFile ?: dest, basePath) || (dest.exists() && !inside(dest, basePath)) -> skipped++
                    entry.isDirectory -> if (!dest.isDirectory && !dest.mkdirs()) skipped++
                    dest.exists() && clash == Clash.SKIP -> kept++
                    else -> {
                        dest.parentFile?.mkdirs()
                        val target = when {
                            !dest.exists() -> dest
                            clash == Clash.REPLACE && dest.isFile -> dest
                            else -> Names.unique(dest.parentFile!!, dest.name)
                        }
                        target.writeAtomically(durable = false) { out ->
                            var checked = written
                            val before = written
                            // Holes the zip couldn't keep are made again ([copySparse]).
                            zip.copySparse(out) { copied ->
                                written = before + copied
                                if (written - checked >= CHECK_SPACE_EVERY) {
                                    checked = written
                                    if (base.usableSpace < minFreeBytes) throw IOException("Not enough free storage")
                                }
                            }
                        }
                        files++
                    }
                }
                onEntry(index)
            }
        }
        return Extracted(files, skipped, kept)
    }

    private fun inside(file: File, basePath: String): Boolean {
        val path = file.canonicalPath
        return path == basePath || path.startsWith("$basePath/")
    }

    private const val MIN_FREE_BYTES = 200L shl 20
    private const val CHECK_SPACE_EVERY = 16L shl 20
}
