package com.garathon.flatdroid.setup

/** apk's last line: "OK: 375.8 MiB in 202 packages" or "15 errors; 380.3 MiB in 202 packages". */
data class ApkSummary(val line: String?, val errors: List<String>) {
    val ok: Boolean get() = line != null && line.startsWith("OK:")

    companion object {
        private val SUMMARY = Regex("""^(OK:|\d+ errors?;)""")
        private val SCRIPT_ERROR = Regex("""^ERROR: \S+\.(pre|post)-(install|upgrade|deinstall): .*|^ERROR: \S+\.trigger: .*""")

        fun parse(lines: List<String>) = ApkSummary(
            line = lines.lastOrNull { SUMMARY.containsMatchIn(it.trim()) }?.trim(),
            errors = lines.map { it.trim() }.filter { SCRIPT_ERROR.matches(it) },
        )
    }
}

/** Reads apk's per-package lines, e.g. "( 12/186) Installing glib (2.88.1-r1)". */
object ApkProgress {
    private val LINE = Regex("""^\(\s*(\d+)/(\d+)\)\s""")

    /** (done, total) for a progress line, or null for any other line. */
    fun parse(line: String): Pair<Int, Int>? {
        val m = LINE.find(line) ?: return null
        val done = m.groupValues[1].toInt()
        val total = m.groupValues[2].toInt()
        return if (total > 0 && done <= total) done to total else null
    }
}
