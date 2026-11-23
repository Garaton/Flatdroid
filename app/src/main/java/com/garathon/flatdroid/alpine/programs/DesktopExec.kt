package com.garathon.flatdroid.alpine.programs

/**
 * A desktop file's `Exec` (already unescaped as a keyfile string) as arguments, by the desktop
 * entry specification: arguments are separated by spaces; a double-quoted one keeps its spaces,
 * and in it a backslash escapes `"`, `` ` ``, `$` and `\`. Field codes are dropped, since the
 * program starts without files or links (`%f`, `%u` and the others); `%%` is a percent sign.
 */
object DesktopExec {
    /** The arguments; null for an empty line or an unclosed quote. */
    fun parse(exec: String): List<String>? {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var inArg = false
        var quoted = false
        var i = 0
        while (i < exec.length) {
            val c = exec[i]
            when {
                quoted && c == '\\' && i + 1 < exec.length && exec[i + 1] in QUOTED_ESCAPES -> { current.append(exec[i + 1]); i++ }
                c == '"' -> { quoted = !quoted; inArg = true }
                !quoted && (c == ' ' || c == '\t') -> {
                    if (inArg) fieldCodesRemoved(current.toString())?.let(args::add)
                    current.clear()
                    inArg = false
                }
                else -> { current.append(c); inArg = true }
            }
            i++
        }
        if (quoted) return null
        if (inArg) fieldCodesRemoved(current.toString())?.let(args::add)
        return args.takeIf { it.isNotEmpty() }
    }

    /** [arg] without its field codes; null when it was a field code alone. */
    private fun fieldCodesRemoved(arg: String): String? {
        if (arg.length == 2 && arg[0] == '%' && arg[1] != '%') return null
        val out = StringBuilder()
        var i = 0
        while (i < arg.length) {
            if (arg[i] == '%' && i + 1 < arg.length) {
                if (arg[i + 1] == '%') out.append('%')
                i += 2
                continue
            }
            out.append(arg[i])
            i++
        }
        return out.toString()
    }

    private const val QUOTED_ESCAPES = "\"`$\\"
}
