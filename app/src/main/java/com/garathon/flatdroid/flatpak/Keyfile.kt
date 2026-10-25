package com.garathon.flatdroid.flatpak

/**
 * A GLib keyfile, the format of Flatpak metadata and .desktop files: `[group]` headers and
 * `key=value` lines. Spaces around `=` are trimmed (runtime metadata uses `key = value`); blank
 * lines and `#` comments are skipped. Values keep their escapes until read through [string] or
 * [list], since `\;` only means something when splitting a list.
 */
class Keyfile private constructor(private val groups: Map<String, Map<String, String>>) {
    val groupNames: Set<String> get() = groups.keys

    /** The raw (still escaped) values of [group], in file order. */
    fun group(group: String): Map<String, String> = groups[group] ?: emptyMap()

    fun string(group: String, key: String): String? = groups[group]?.get(key)?.let(::unescape)

    /** A `;`-separated list; empty items (such as after a trailing `;`) are dropped. */
    fun list(group: String, key: String): List<String> {
        val raw = groups[group]?.get(key) ?: return emptyList()
        val items = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> { current.append(c).append(raw[i + 1]); i++ }
                c == ';' -> { items += unescape(current.toString()); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        items += unescape(current.toString())
        return items.map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun boolean(group: String, key: String): Boolean? = when (string(group, key)?.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

    companion object {
        fun parse(text: String): Keyfile {
            val groups = linkedMapOf<String, LinkedHashMap<String, String>>()
            var current: LinkedHashMap<String, String>? = null
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith("[") && line.endsWith("]")) {
                    current = groups.getOrPut(line.substring(1, line.length - 1)) { linkedMapOf() }
                    continue
                }
                val eq = line.indexOf('=')
                if (eq <= 0 || current == null) continue
                // A repeated key keeps its first value.
                current.putIfAbsent(line.substring(0, eq).trim(), line.substring(eq + 1).trim())
            }
            return Keyfile(groups)
        }

        private fun unescape(value: String): String {
            if ('\\' !in value) return value
            val out = StringBuilder()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    out.append(
                        when (val n = value[i + 1]) {
                            's' -> ' '
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            else -> n
                        }
                    )
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}
