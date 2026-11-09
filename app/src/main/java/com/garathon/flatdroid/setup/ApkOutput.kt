package com.garathon.flatdroid.setup

/** apk's last line: "OK: 375.8 MiB in 202 packages" or "15 errors; 380.3 MiB in 202 packages". */
data class ApkSummary(val line: String?, val errors: List<String>, val networkErrors: List<String> = emptyList()) {
    val ok: Boolean get() = line != null && line.startsWith("OK:")

    companion object {
        private val SUMMARY = Regex("""^(OK:|\d+ errors?;)""")
        private val SCRIPT_ERROR = Regex("""^ERROR: \S+\.(pre|post)-(install|upgrade|deinstall): .*|^ERROR: \S+\.trigger: .*""")

        /**
         * What apk says when it can't reach the package servers or find what it was asked for (a
         * failed index fetch is only a warning, and the summary can still say OK when the packages
         * are installed already, so these lines are what tells an offline run).
         */
        private val NETWORK_ERROR = Regex(
            """^(ERROR|WARNING):.*(temporary error|network error|updating and opening|unable to select|unsatisfiable|APKINDEX|does not resolve|connection (refused|timed out)|failed to fetch|bad address|DNS)""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * An error that ends the run, whatever it is (a full disk, a bad signature, packages that
         * can't be selected): any apk "ERROR:" but a package's own script error, which `apk fix`
         * runs again. Together with [isNetworkError] it is what stops a run at its first line.
         */
        fun isFatalError(line: String): Boolean {
            val trimmed = line.trim()
            return trimmed.startsWith("ERROR:") && !SCRIPT_ERROR.matches(trimmed)
        }

        /** Whether [line] is one of those ([networkErrors]), so a run can stop at the first one instead of at its end. */
        fun isNetworkError(line: String) = NETWORK_ERROR.containsMatchIn(line.trim())

        fun parse(lines: List<String>) = ApkSummary(
            line = lines.lastOrNull { SUMMARY.containsMatchIn(it.trim()) }?.trim(),
            errors = lines.map { it.trim() }.filter { SCRIPT_ERROR.matches(it) },
            networkErrors = lines.map { it.trim() }.filter { isNetworkError(it) },
        )
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
