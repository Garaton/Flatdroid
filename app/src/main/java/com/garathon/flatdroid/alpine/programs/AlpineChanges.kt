package com.garathon.flatdroid.alpine.programs

import android.content.Context
import com.garathon.flatdroid.R
import com.garathon.flatdroid.util.io.DirReader
import com.garathon.flatdroid.util.io.writeTextAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * Alpine safe launch. An Alpine program's root binds Alpine's own folders ([AlpineRoot.DIRS]), and
 * proot has no read-only binds, so the program can change files that Flatdroid's helpers and
 * Flatpak tasks run later in Alpine's wider proot. Before it starts, each entry's change time
 * (ctime, which a process can't set back), inode and size are recorded ([snapshot]); when its
 * session ends, any difference ([changes]) is kept in [changedFile] until the user reinstalls
 * Alpine or keeps it. While it is kept, nothing runs in Alpine ([AlpineEnvironment]).
 *
 * A moved ctime only makes an entry a suspect, as in the Flatpak file check: a file whose content
 * is still what apk installed, and a folder that holds the same entries, are left out ([real]).
 *
 * The same walk finds Alpine's links into link2symlink's store (`.l2s`, Alpine's hard links),
 * which the program's root binds one by one instead of the whole store ([AlpineRoot]).
 */
object AlpineChanges {
    /** An entry's change time (nanoseconds), inode, size and mode (type and permissions). */
    data class Stamp(val ctime: Long, val inode: Long, val size: Long, val mode: Int = 0)

    /**
     * Every entry under [AlpineRoot.DIRS] (relative to the rootfs) and every store entry Alpine's
     * links lead through (host paths); [l2sBinds] are the store's files, each bound at the path
     * Alpine's links name (the store's own entry there is a link to it).
     */
    class Snapshot(val stamps: Map<String, Stamp>, val l2sBinds: List<Pair<String, String>>)

    /** At most this many changed paths are kept and shown. */
    const val MAX_SHOWN = 50

    /** Refused while changes wait for the user ([requireUnchanged]). */
    class ChangedException(message: String) : IOException(message)

    /**
     * Changes waiting for the user: the program that ran, how many paths changed, how many lines
     * their [summary] has, and the first [MAX_SHOWN] of those lines.
     */
    data class Pending(val program: String, val total: Int, val lines: Int, val paths: List<String>)

    private val _pending = MutableStateFlow<Pending?>(null)

    /** [pending] as last read or written, for the screens ([refresh] reads it at start-up). */
    val pendingState: StateFlow<Pending?> = _pending.asStateFlow()

    fun refresh(context: Context) {
        _pending.value = pending(context)
    }

    /** Nothing runs in Alpine while it has changes the user hasn't answered. */
    fun requireUnchanged(context: Context) {
        if (changedFile(context).isFile) throw ChangedException(context.getString(R.string.alpine_changed_refused))
    }

    /**
     * Walks [rootfs]'s [AlpineRoot.DIRS] in C ([NativeSnapshot]): folders in name order, links
     * never followed but into the store, where an entry is followed through a few links to the
     * file it names. Change times are read in nanoseconds on every Android version, so a change
     * right after the walk can't share its time with what was recorded.
     */
    fun snapshot(rootfs: File, l2sDir: File): Snapshot = NativeSnapshot.take(rootfs, l2sDir, AlpineRoot.DIRS)

    /** The paths added, removed or changed between [before] and [after], sorted. */
    fun changes(before: Map<String, Stamp>, after: Map<String, Stamp>): List<String> =
        (before.keys + after.keys).filter { before[it] != after[it] }.sorted()

    /**
     * Of [changed] ([changes]), those that leave Alpine different. Left out, with the same inode and
     * mode: a regular file of the same size whose content apk recorded ([sameAsInstalled]: a
     * `touch` or a `chmod` to the same mode moves ctime alone), and a folder that holds the same
     * entries (something was made in it and removed again, as proot's stand-ins for binds are; its
     * entries' own changes are reported each). Also left out: new folders that hold nothing but
     * other new empty folders, and a folder's changed entries when those are all such folders
     * (an office suite making its extension cache in its own install at its first start). Nothing in an
     * empty folder can be loaded or run; a file made in one is reported.
     */
    fun real(changed: List<String>, before: Map<String, Stamp>, after: Map<String, Stamp>, sameAsInstalled: (String) -> Boolean): List<String> {
        val entriesBefore by lazy { entries(before.keys) }
        val entriesAfter by lazy { entries(after.keys) }
        val empty = emptyNewFolders(before, after)
        return changed.filterNot { path ->
            if (path in empty) return@filterNot true
            val was = before[path] ?: return@filterNot false
            val now = after[path] ?: return@filterNot false
            if (was.inode != now.inode || was.mode != now.mode) return@filterNot false
            when (now.mode and DirReader.S_IFMT) {
                DirReader.S_IFDIR -> entriesBefore[path].orEmpty() == entriesAfter[path].orEmpty().filterNot { "$path/$it" in empty }.toSet()
                DirReader.S_IFREG -> was.size == now.size && sameAsInstalled(path)
                else -> false
            }
        }
    }

    /**
     * [changed] as the user reads it: a folder whose own entries are listed is left out (they say
     * what changed in it), and a folder with more than [GROUP_LIMIT] changed entries is one line,
     * its path and how many.
     */
    fun summary(changed: List<String>): List<String> {
        val explained = changed.filter { path -> changed.any { it.startsWith("$path/") } }.toSet()
        val shown = changed - explained
        return shown.groupBy { it.substringBeforeLast('/', "") }.toSortedMap().flatMap { (folder, paths) ->
            if (paths.size > GROUP_LIMIT && folder.isNotEmpty()) listOf("$folder/ (${paths.size})") else paths.sorted()
        }
    }

    /** Folders in [after] but not [before] with no new file, link or other entry anywhere under them. */
    private fun emptyNewFolders(before: Map<String, Stamp>, after: Map<String, Stamp>): Set<String> {
        fun isFolder(path: String) = (after.getValue(path).mode and DirReader.S_IFMT) == DirReader.S_IFDIR
        val added = after.keys.filter { it !in before }
        // Every folder above something new that isn't a folder holds something.
        val holding = HashSet<String>()
        for (path in added) {
            if (isFolder(path)) continue
            var slash = path.lastIndexOf('/')
            while (slash > 0) {
                holding += path.substring(0, slash)
                slash = path.lastIndexOf('/', slash - 1)
            }
        }
        return added.filter { isFolder(it) && it !in holding }.toSet()
    }

    /** Each folder's entry names, from every path under the walk. */
    private fun entries(paths: Set<String>): Map<String, Set<String>> {
        val map = HashMap<String, MutableSet<String>>()
        for (path in paths) {
            val slash = path.lastIndexOf('/')
            if (slash > 0) map.getOrPut(path.substring(0, slash)) { HashSet() } += path.substring(slash + 1)
        }
        return map
    }


    /** Changed entries of one folder shown one by one up to this many ([summary]). */
    const val GROUP_LIMIT = 10

    /** Where the changes wait for the user (Flatdroid's files: it must survive a restart, not a cache clear). */
    fun changedFile(context: Context) = File(context.filesDir, "alpine-changed")

    /** The kept changes; null when there are none. The file holds the program's name, the two counts, then the lines. */
    fun pending(context: Context): Pending? {
        val file = changedFile(context).takeIf { it.isFile } ?: return null
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        val shown = lines.drop(3).take(MAX_SHOWN)
        return Pending(lines.getOrNull(0) ?: "?", lines.getOrNull(1)?.toIntOrNull() ?: 0, lines.getOrNull(2)?.toIntOrNull() ?: shown.size, shown)
    }

    fun keep(context: Context, program: String, changes: List<String>) {
        val summary = summary(changes)
        val lines = listOf(program.replace('\n', ' '), changes.size.toString(), summary.size.toString()) + summary.take(MAX_SHOWN).map { it.replace('\n', ' ') }
        changedFile(context).writeTextAtomically(lines.joinToString("\n", postfix = "\n"))
        refresh(context)
    }

    /** The user keeps Alpine as it is now, or it is being reinstalled. */
    fun clear(context: Context) {
        changedFile(context).delete()
        refresh(context)
    }
}
