package com.garathon.flatdroid.launcher

/** How an app ended when not normally ([ProotExit.ending]). */
sealed interface AppEnding {
    /** Killed by [signal], one a program gets for its own fault (SIGSEGV, SIGBUS, SIGABRT and the like): a crash. */
    data class Crashed(val signal: Int) : AppEnding
    /** Killed by [signal], one sent to end it (SIGTERM, SIGKILL and the like): not a crash of its own. */
    data class Ended(val signal: Int) : AppEnding
    /** Exited with [code], not 0. */
    data class Failed(val code: Int) : AppEnding
}

/**
 * How an app's proot says its process ended. proot exits 0 when the app was killed by a signal, and
 * says so only in its own output: `proot info: vpid 1: terminated with signal 11`. Process 1 is the
 * app (fdsec execs it), so that line, in the app's proot's output, is the app's own end.
 */
object ProotExit {
    private val APP_SIGNAL = Regex("""vpid 1: terminated with signal (\d+)""")

    /** The signal that ended the app, from one line of its proot's output; null for any other line. */
    fun signalOf(line: String): Int? = APP_SIGNAL.find(line)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * How the app ended, from the signal its proot reported ([signalOf], 0 for none) and proot's exit
     * code; null when it closed normally. An app started by a shell script that doesn't `exec` it
     * ends as the shell reports it: 128 plus the signal, as the exit code.
     */
    fun ending(signal: Int, code: Int): AppEnding? {
        val killedBy = when {
            signal != 0 -> signal
            code in SHELL_SIGNAL_CODES -> code - 128
            else -> 0
        }
        return when {
            killedBy in CRASH_SIGNALS -> AppEnding.Crashed(killedBy)
            killedBy != 0 -> AppEnding.Ended(killedBy)
            code != 0 -> AppEnding.Failed(code)
            else -> null
        }
    }

    /** A signal's usual name (`SIGSEGV`), or `signal N` for one without a common name. */
    fun signalName(signal: Int): String = NAMES[signal] ?: "signal $signal"

    // Linux's numbers on aarch64 and x86_64 (the same for these).
    private val NAMES = mapOf(
        1 to "SIGHUP", 2 to "SIGINT", 3 to "SIGQUIT", 4 to "SIGILL", 5 to "SIGTRAP", 6 to "SIGABRT",
        7 to "SIGBUS", 8 to "SIGFPE", 9 to "SIGKILL", 11 to "SIGSEGV", 13 to "SIGPIPE", 15 to "SIGTERM", 31 to "SIGSYS",
    )

    /** The signals a program gets for its own fault (they would leave a core dump): SIGQUIT is sent by others. */
    private val CRASH_SIGNALS = setOf(4, 5, 6, 7, 8, 11, 31)

    /** A shell's exit code for a child killed by one of the standard signals (1 to 31). */
    private val SHELL_SIGNAL_CODES = 129..159
}
