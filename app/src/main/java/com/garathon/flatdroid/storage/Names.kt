package com.garathon.flatdroid.storage

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** File names for files Flatdroid creates in a folder. */
object Names {
    /**
     * [name] made safe as one file name: no path separators or control characters, not empty and
     * not `.` or `..`, and at most [MAX_NAME_BYTES] bytes long.
     */
    fun safe(name: String?, fallback: String): String {
        val cleaned = name.orEmpty().map { if (it == '/' || it.isISOControl()) '_' else it }.joinToString("").trim()
        return cleaned.takeIf { it.isNotEmpty() && it != "." && it != ".." }?.let(::shorten) ?: fallback
    }

    /**
     * [name] cut to [MAX_NAME_BYTES] UTF-8 bytes (a file system's limit is in bytes, 255 on most, and
     * Flatdroid adds a temporary name's random part or " (n)"), at a code point boundary and keeping a
     * short extension.
     */
    private fun shorten(name: String): String {
        if (utf8Size(name) <= MAX_NAME_BYTES) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && utf8Size(name.substring(dot)) <= MAX_EXTENSION_BYTES) name.substring(dot) else ""
        val base = cut(name.substring(0, name.length - ext.length), MAX_NAME_BYTES - utf8Size(ext)).trimEnd()
        return (base + ext).takeIf { base.isNotEmpty() } ?: cut(name, MAX_NAME_BYTES)
    }

    private fun utf8Size(text: String) = text.toByteArray(Charsets.UTF_8).size

    /** The longest start of [text] that fits [maxBytes] in UTF-8. */
    private fun cut(text: String, maxBytes: Int): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val point = text.codePointAt(end)
            val size = String(Character.toChars(point)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(point)
        }
        return text.substring(0, end)
    }

    /** [name] in [dir], or "name (1).ext" and so on when it is taken. */
    fun unique(dir: File, name: String): File = File(dir, unique(name) { File(dir, it).exists() })

    /** [name], or "name (1).ext" and so on when [taken] says it is. */
    fun unique(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val base = name.substringBeforeLast('.', name).takeIf { it.isNotEmpty() } ?: name
        val ext = if (base == name) "" else "." + name.substringAfterLast('.')
        for (i in 1..MAX_COPIES) {
            val candidate = "$base ($i)$ext"
            if (!taken(candidate)) return candidate
        }
        throw IOException("No free name for $name")
    }

    /** Now, for names of exports ("2026-10-20 13-28": no colons, which some file systems refuse). */
    fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.ROOT).format(Date())

    private const val MAX_NAME_BYTES = 200
    private const val MAX_EXTENSION_BYTES = 16
    private const val MAX_COPIES = 9999
}
