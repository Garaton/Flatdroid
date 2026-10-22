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
     * not `.` or `..`.
     */
    fun safe(name: String?, fallback: String): String {
        val cleaned = name.orEmpty().map { if (it == '/' || it.isISOControl()) '_' else it }.joinToString("").trim()
        return cleaned.takeIf { it.isNotEmpty() && it != "." && it != ".." }?.take(MAX_NAME) ?: fallback
    }

    /** [name] in [dir], or "name (1).ext" and so on when it is taken. */
    fun unique(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val base = name.substringBeforeLast('.', name).takeIf { it.isNotEmpty() } ?: name
        val ext = if (base == name) "" else "." + name.substringAfterLast('.')
        for (i in 1..MAX_COPIES) {
            val candidate = File(dir, "$base ($i)$ext")
            if (!candidate.exists()) return candidate
        }
        throw IOException("No free name for $name in $dir")
    }

    /** Now, for names of exports ("2026-10-20 13-28": no colons, which some file systems refuse). */
    fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.ROOT).format(Date())

    private const val MAX_NAME = 200
    private const val MAX_COPIES = 9999
}
