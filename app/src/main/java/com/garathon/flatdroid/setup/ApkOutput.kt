package com.garathon.flatdroid.setup

/** apk's last line: "OK: 375.8 MiB in 202 packages" or "15 errors; 380.3 MiB in 202 packages". */
data class ApkSummary(
    val line: String?,
    val errors: List<String>,
    val networkErrors: List<String> = emptyList(),
    /** apk said it can't select the packages it was asked for ([UNSELECTABLE]). */
    val unselectable: Boolean = false,
    /** The packages it named as missing then ("foo (no such package)"). */
    val missingPackages: List<String> = emptyList(),
) {
    val ok: Boolean get() = line != null && line.startsWith("OK:")

    companion object {
        private val SUMMARY = Regex("""^(OK:|\d+ errors?;)""")
        private val SCRIPT_ERROR = Regex("""^ERROR: \S+\.(pre|post)-(install|upgrade|deinstall): .*|^ERROR: \S+\.trigger: .*""")

        /**
         * What apk says when it can't reach the package servers (a failed index fetch is only a
         * warning, and the summary can still say OK when the packages are installed already, so
         * these lines are what tells an offline run).
         */
        private val NETWORK_ERROR = Regex(
            """^(ERROR|WARNING):.*(temporary error|network error|updating and opening|APKINDEX|does not resolve|connection (refused|timed out)|timed out|network is unreachable|no route to host|failed to fetch|bad address|\bDNS\b)""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * The first line of apk's answer when a package doesn't exist or conflicts; the reasons follow
         * on indented lines, and apk ends by itself right after them.
         */
        private val UNSELECTABLE = Regex("""^ERROR: unable to select packages""")
        private val NO_SUCH_PACKAGE = Regex("""^(\S+) \(no such package\)""")
        private val NO_SPACE = Regex("""No space left on device""", RegexOption.IGNORE_CASE)

        /**
         * An error that ends the run, whatever it is (a full disk, a bad signature): any apk "ERROR:"
         * but a package's own script error, which `apk fix` runs again, and [UNSELECTABLE], whose
         * reasons come on the next lines. Together with [isNetworkError] it is what stops a run at
         * its first line.
         */
        fun isFatalError(line: String): Boolean {
            val trimmed = line.trim()
            return trimmed.startsWith("ERROR:") && !SCRIPT_ERROR.matches(trimmed) && !UNSELECTABLE.containsMatchIn(trimmed)
        }

        /** Whether [line] is one of those ([networkErrors]), so a run can stop at the first one instead of at its end. */
        fun isNetworkError(line: String) = NETWORK_ERROR.containsMatchIn(line.trim())

        /** Whether [line] says the device's storage is full. */
        fun isNoSpace(line: String) = NO_SPACE.containsMatchIn(line)

        fun parse(lines: List<String>): ApkSummary {
            val trimmed = lines.map { it.trim() }
            return ApkSummary(
                line = trimmed.lastOrNull { SUMMARY.containsMatchIn(it) },
                errors = trimmed.filter { SCRIPT_ERROR.matches(it) },
                networkErrors = trimmed.filter { isNetworkError(it) },
                unselectable = trimmed.any { UNSELECTABLE.containsMatchIn(it) },
                missingPackages = trimmed.mapNotNull { NO_SUCH_PACKAGE.find(it)?.groupValues?.get(1) }.distinct(),
            )
        }
    }
}

/** Reads apk's per-package lines, e.g. "( 12/186) Installing glib (2.88.1-r1)". */
object ApkProgress {
    private val LINE = Regex("""^\(\s*(\d+)/(\d+)\)\s""")
    private val ACTION = Regex("""^\(\s*\d+/\d+\)\s+(\p{L}+)\s+(\S+)""")

    /** (done, total) for a progress line, or null for any other line. */
    fun parse(line: String): Pair<Int, Int>? {
        val m = LINE.find(line) ?: return null
        val done = m.groupValues[1].toInt()
        val total = m.groupValues[2].toInt()
        return if (total > 0 && done <= total) done to total else null
    }

    /** What a progress line does and to which package ("Upgrading" and "glib"), or null for any other line. */
    fun action(line: String): Pair<String, String>? = ACTION.find(line)?.let { it.groupValues[1] to it.groupValues[2] }
}
