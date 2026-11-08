package com.garathon.flatdroid.flatpak

import com.garathon.flatdroid.setup.ApkProgress

/**
 * Follows `flatpak install/update/uninstall -y` output (not on a terminal): first a table of
 * what it will do (` 1.  <ID>  <branch>  <op>  <remote>  <size>`, tab-separated), then lines
 * such as `Installing 2/4… ███  45%  1.2 MB/s` while each one runs (`Installing… ███  45%` when
 * there is only one). Alpine's packages update as steps too ([ApkProgress]).
 */
class FlatpakProgress {
    /** The operation running: [verb] as flatpak prints it, which of [total], its ref, and its percent. */
    data class Step(val verb: String, val index: Int, val total: Int, val ref: String?, val percent: Int?) {
        /**
         * Downloaded; flatpak now writes the files into place (each through proot and
         * link2symlink, minutes for a large runtime) and prints nothing until the next step.
         */
        val deploying: Boolean get() = percent == 100
    }

    private val refs = mutableMapOf<Int, String>()

    var current: Step? = null
        private set

    /**
     * Every step is done ("Changes complete."); flatpak still removes old files and unused
     * repository objects, printing nothing.
     */
    var finishing = false
        private set

    /** Reads one output line; returns true if it was a progress line. */
    fun feed(line: String): Boolean {
        apkStep(line)?.let {
            current = it
            return true
        }
        if (COMPLETE.containsMatchIn(line)) {
            current = null
            finishing = true
            return false
        }
        TABLE_ROW.find(line)?.let { row ->
            refs[row.groupValues[1].toInt()] = "${row.groupValues[2]} ${row.groupValues[3]}"
            return false
        }
        val progress = PROGRESS.find(line) ?: return false
        val index = progress.groupValues[2].toIntOrNull() ?: 1
        current = Step(
            verb = progress.groupValues[1],
            index = index,
            total = progress.groupValues[3].toIntOrNull() ?: 1,
            ref = refs[index],
            percent = progress.groupValues[4].toIntOrNull()?.coerceIn(0, 100),
        )
        return true
    }

    /** A package among [Step.total]: the percent counts those before it, so it never reads as [Step.deploying]. */
    private fun apkStep(line: String): Step? {
        val (index, total) = ApkProgress.parse(line) ?: return null
        val (verb, name) = ApkProgress.action(line) ?: return null
        return Step(verb, index, total, name, (index - 1) * 100 / total)
    }

    companion object {
        private val TABLE_ROW = Regex("""^\s*(\d+)\.\s+([A-Za-z0-9_.-]+)\s+(\S+)\s+[a-z]\b""")
        private val PROGRESS = Regex("""^(\p{L}+)(?: (\d+)/(\d+))?…(?:.*?(\d+)%)?""")
        private val COMPLETE = Regex("""^(Changes|Installation|Uninstall) complete\.""")

        /**
         * Output lines worth showing as the task's status: what flatpak is doing ("Looking for
         * updates…"), that there is nothing to do, that it finished, or that an app is already
         * installed. Everything else (end-of-life notices, warnings, pinned runtimes, tables, apk's
         * package list) stays in the log only. Progress lines are shown as steps instead.
         */
        private val STATUS = listOf(
            Regex("""\p{L}[\p{L} ]{0,40}…"""),
            Regex("""Nothing [\p{L} ]{0,40}\.?"""),
            Regex("""(Changes|Installation|Uninstall) complete\."""),
            Regex("""Skipping: \S+ is already installed"""),
        )

        fun isStatus(line: String): Boolean = line.trim().let { text -> STATUS.any { it.matches(text) } }
        /** True if [a] and [b] report the same operation, so [b] can replace [a] in a log. */
        fun sameStep(a: String, b: String): Boolean {
            val x = PROGRESS.find(a) ?: return false
            val y = PROGRESS.find(b) ?: return false
            return (1..3).all { x.groupValues[it] == y.groupValues[it] }
        }
    }
}
