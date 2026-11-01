package com.garathon.flatdroid.flatpak

import java.io.File

/**
 * The file-integrity baseline and check for a deployment's tree (an app's or a runtime's `files/`).
 * Flatpaks are not isolated from each other: proot has no read-only binds, so one Flatpak can
 * change another's deployed code through the shared link2symlink store. The Hardened file check
 * records a baseline when a deployment is installed and compares against it before launching, from
 * the host side (outside proot, so proot's faked metadata — which makes `flatpak repair` report
 * false corruption — never enters into it).
 *
 * Each file is recorded as its symlink target (if it is one), its followed `stat` (inode, ctime,
 * size) and its content SHA-256. The check's fast path trusts a file whose target and
 * (inode, ctime, size) are unchanged and skips re-hashing it: a Flatpak can't set ctime into the
 * past (no syscall for it; the system clock needs a privilege it lacks), and the one Flatpak that
 * ran before is fully stopped, so no live mapping hides a change. Only files whose stat differs are
 * re-hashed, and the SHA-256 is the authority — a bare ctime bump with the same content is benign.
 *
 * This class is pure: the walk, stat, readlink and hashing are injected ([Fs]) so it is unit-tested
 * and the Android caller does the Os calls and runs the hashing in parallel.
 */
object FileIntegrity {
    /** One file's baseline: its symlink target (null if not a symlink), followed stat, and content hash. */
    data class Entry(val link: String?, val ino: Long, val ctime: Long, val size: Long, val sha256: String?) {
        /** Everything but the content hash, from a cheap `stat`; the fast path compares these. */
        fun sameShape(other: Entry) = link == other.link && ino == other.ino && ctime == other.ctime && size == other.size

        /** A symlink the deployment's tree itself holds (see [entry]): only its target is recorded. */
        val linkOnly: Boolean get() = link != null && ino == 0L && ctime == 0L && size == 0L && sha256 == null
    }



    /** Host operations, injected for testing. All take an absolute file under a deployment's tree. */
    data class Fs(
        /** Whether [file] is a symlink, without following it. */
        val isSymlink: (File) -> Boolean,
        /** A symlink's target, or null when [file] is not a symlink. */
        val readlink: (File) -> String?,
        /** (inode, ctime seconds, size) following symlinks, or null when the path can't be followed. */
        val stat: (File) -> Triple<Long, Long, Long>?,
        /** Content SHA-256 (hex) following symlinks, or null for a directory or an unreadable file. */
        val sha256: (File) -> String?,
        /** Whether a link's target is a file in link2symlink's store ([L2s.storedName]). */
        val isStoreLink: (String) -> Boolean = { File(it).name.startsWith(L2s.PREFIX) },
    )

    /**
     * Relative paths of every leaf under [root] (regular files and symlinks), recursing only real
     * directories, so a symlinked directory is a leaf and isn't followed. Sorted, for a stable
     * manifest. Every name is kept, tabs and newlines included: the manifest escapes them
     * ([write]), and a file another app added under such a name must be reported like any other.
     */
    fun walk(root: File, fs: Fs, cancelled: () -> Boolean = { false }): List<String> {
        val out = mutableListOf<String>()
        fun recurse(dir: File, prefix: String) {
            if (cancelled()) return
            val children = dir.listFiles()?.sortedBy { it.name } ?: return
            for (child in children) {
                if (cancelled()) return // the walk (sequential, before hashing) must also stop on a skip
                val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                // A real directory (not a symlink to one) is recursed; everything else is a leaf.
                if (!fs.isSymlink(child) && child.isDirectory) recurse(child, rel) else out += rel
            }
        }
        recurse(root, "")
        return out
    }

    /** The [Entry] for one leaf: its link target, stat and (unless a directory) content hash. */
    fun entry(file: File, fs: Fs): Entry {
        val link = fs.readlink(file)
        // A symlink of the Flatpak's own tree names a guest path: followed from the host it would
        // read the host's file (or nothing), so only its target is recorded. link2symlink's links
        // lead to the file's content in its store and are followed.
        if (link != null && !fs.isStoreLink(link)) return Entry(link, 0, 0, 0, null)
        val stat = fs.stat(file) ?: Triple(0L, 0L, 0L)
        val sha = fs.sha256(file)
        return Entry(link, stat.first, stat.second, stat.third, sha)
    }

    /** What a check turns up, by relative path. [tampered] covers changed and added files. */
    data class Diff(
        val tampered: List<String>,
        val removed: List<String>,
        /** Entries whose stat changed but whose content was confirmed the same: written back, so they aren't re-hashed every check. */
        val refreshed: Map<String, Entry> = emptyMap(),
    ) {
        val clean: Boolean get() = tampered.isEmpty() && removed.isEmpty()
    }

    /**
     * Compares [current] against [baseline]. [needsHash] is called for each path whose shape
     * (link + stat) differs or that is new, and must return the file's current [Entry] with its
     * hash computed; the caller runs those in parallel. A hash that matches the baseline is benign
     * (a bare ctime touch, such as link2symlink renaming its file when another hard link is made)
     * and goes into [Diff.refreshed]; anything else is tampering. A new path is tampering without
     * a hash, and a link-only entry ([Entry.linkOnly]) is compared by its target alone.
     */
    fun diff(baseline: Map<String, Entry>, current: Map<String, Entry>, needsHash: (String) -> Entry?): Diff {
        val tampered = mutableListOf<String>()
        val refreshed = LinkedHashMap<String, Entry>()
        for ((path, shape) in current) {
            val base = baseline[path]
            if (base == null) { tampered += path; continue }    // added
            if (shape.sameShape(base)) continue                 // fast path: unchanged
            if (shape.linkOnly) {
                if (shape.link == base.link) refreshed[path] = shape else tampered += path
                continue
            }
            val hashed = needsHash(path)                        // shape changed: re-hash
            if (hashed == null || hashed.link != base.link || hashed.sha256 == null || hashed.sha256 != base.sha256) tampered += path
            else refreshed[path] = hashed
        }
        val removed = baseline.keys.filter { it !in current }
        return Diff(tampered.sorted(), removed.sorted(), refreshed)
    }

    // ---- manifest serialization: one tab-separated line per entry, "-" for an absent field. Paths
    // and link targets are [escape]d, so a tab, newline or backslash in a name can't break a line.

    fun write(manifest: Map<String, Entry>): String = buildString {
        for ((path, e) in manifest.toSortedMap()) {
            append(e.sha256 ?: "-").append('\t').append(e.ino).append('\t').append(e.ctime).append('\t')
                .append(e.size).append('\t').append(e.link?.let(::escape) ?: "-").append('\t').append(escape(path)).append('\n')
        }
    }

    /** [text] with backslash, tab and newline written as `\\`, `\t` and `\n`; also how such paths are logged. */
    fun escape(text: String): String =
        if (text.none { it == '\\' || it == '\t' || it == '\n' }) text
        else text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    /** The reverse of [escape]; any other backslash pair is kept as it is. */
    fun unescape(text: String): String {
        if ('\\' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    '\\' -> { out.append('\\'); i += 2; continue }
                    't' -> { out.append('\t'); i += 2; continue }
                    'n' -> { out.append('\n'); i += 2; continue }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    fun read(text: String): Map<String, Entry> {
        val map = LinkedHashMap<String, Entry>()
        for (line in text.lineSequence()) {
            if (line.isEmpty()) continue
            val f = line.split('\t')
            if (f.size != 6) continue
            val ino = f[1].toLongOrNull() ?: continue
            val ctime = f[2].toLongOrNull() ?: continue
            val size = f[3].toLongOrNull() ?: continue
            map[unescape(f[5])] = Entry(f[4].takeIf { it != "-" }?.let(::unescape), ino, ctime, size, f[0].takeIf { it != "-" })
        }
        return map
    }
}
