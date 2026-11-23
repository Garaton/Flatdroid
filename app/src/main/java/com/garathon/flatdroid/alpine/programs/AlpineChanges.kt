package com.garathon.flatdroid.alpine.programs

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.garathon.flatdroid.R
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
    /** An entry's change time (nanoseconds; whole seconds before Android 8.1), inode, size and mode (type and permissions). */
    data class Stamp(val ctime: Long, val inode: Long, val size: Long, val mode: Int = 0)

    /**
     * Every entry under [AlpineRoot.DIRS] (relative to the rootfs) and every store entry Alpine's
     * links lead through (host paths); [l2sBinds] are the store's files, each bound at the path
     * Alpine's links name (the store's own entry there is a link to it).
     */
    class Snapshot(val stamps: Map<String, Stamp>, val l2sBinds: List<Pair<String, String>>)

    /** Links followed inside the store before giving up on an entry. */
    private const val MAX_STORE_HOPS = 4

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
     * Walks [rootfs]'s [AlpineRoot.DIRS]. Without nanoseconds the walk waits for the next whole
     * second, so a change right after it can't share its second.
     */
    fun snapshot(rootfs: File, l2sDir: File, settle: Boolean = true): Snapshot {
        val stamps = HashMap<String, Stamp>(8192)
        val binds = LinkedHashMap<String, String>()
        val store = l2sDir.path.trimEnd('/') + "/"
        fun inStore(path: String?) = path != null && path.startsWith(store) && '/' !in path.removePrefix(store) && path.none { it == ':' }
        // link2symlink's entry for a hard link is a link to the file itself (`<name>` to `<name>.0001`).
        fun storeFile(entry: String) {
            var path = entry
            repeat(MAX_STORE_HOPS) {
                val st = try { Os.lstat(path) } catch (_: ErrnoException) { return }
                stamps[path] = stamp(st)
                if (!OsConstants.S_ISLNK(st.st_mode)) {
                    if (OsConstants.S_ISREG(st.st_mode)) binds[entry] = path
                    return
                }
                path = (try { Os.readlink(path) } catch (_: ErrnoException) { null })?.takeIf(::inStore) ?: return
            }
        }
        fun visit(rel: String) {
            val path = "${rootfs.path}/$rel"
            val st = try { Os.lstat(path) } catch (_: ErrnoException) { return }
            stamps[rel] = stamp(st)
            when {
                OsConstants.S_ISDIR(st.st_mode) -> File(path).list()?.sorted()?.forEach { visit("$rel/$it") }
                OsConstants.S_ISLNK(st.st_mode) -> {
                    val target = try { Os.readlink(path) } catch (_: ErrnoException) { null }
                    if (target != null && inStore(target) && target !in binds) storeFile(target)
                }
            }
        }
        for (dir in AlpineRoot.DIRS) visit(dir)
        if (settle && Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) SystemClock.sleep(1_000 - System.currentTimeMillis() % 1_000 + 10)
        return Snapshot(stamps, binds.map { (guest, host) -> host to guest })
    }

    /** The paths added, removed or changed between [before] and [after], sorted. */
    fun changes(before: Map<String, Stamp>, after: Map<String, Stamp>): List<String> =
        (before.keys + after.keys).filter { before[it] != after[it] }.sorted()

    /**
     * Of [changed] ([changes]), those that leave Alpine different. Left out, with the same inode and
     * mode: a regular file of the same size whose content apk recorded ([sameAsInstalled]: a
     * `touch` or a `chmod` to the same mode moves ctime alone), and a folder that holds the same
     * entries (something was made in it and removed again, as proot's stand-ins for binds are; its
     * entries' own changes are reported each).
     */
    fun real(changed: List<String>, before: Map<String, Stamp>, after: Map<String, Stamp>, sameAsInstalled: (String) -> Boolean): List<String> {
        val entriesBefore by lazy { entries(before.keys) }
        val entriesAfter by lazy { entries(after.keys) }
        return changed.filterNot { path ->
            val was = before[path] ?: return@filterNot false
            val now = after[path] ?: return@filterNot false
            if (was.inode != now.inode || was.mode != now.mode) return@filterNot false
            when (now.mode and S_IFMT) {
                S_IFDIR -> entriesBefore[path].orEmpty() == entriesAfter[path].orEmpty()
                S_IFREG -> was.size == now.size && sameAsInstalled(path)
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

    /** Each folder's entry names, from every path under the walk. */
    private fun entries(paths: Set<String>): Map<String, Set<String>> {
        val map = HashMap<String, MutableSet<String>>()
        for (path in paths) {
            val slash = path.lastIndexOf('/')
            if (slash > 0) map.getOrPut(path.substring(0, slash)) { HashSet() } += path.substring(slash + 1)
        }
        return map
    }

    private const val S_IFMT = 0xF000
    private const val S_IFDIR = 0x4000
    private const val S_IFREG = 0x8000

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

    private fun stamp(st: android.system.StructStat): Stamp {
        val ctime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) st.st_ctim.tv_sec * 1_000_000_000L + st.st_ctim.tv_nsec else st.st_ctime * 1_000_000_000L
        return Stamp(ctime, st.st_ino, st.st_size, st.st_mode)
    }
}
