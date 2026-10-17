package com.garathon.flatdroid.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Writes [text] to a sibling `.new` file, then renames it over this file, so readers (including
 * a proot that binds the file) see either the old or the new content, never a partial one. With
 * [durable], the new file is synced before the rename, so a power loss can't leave an empty file
 * in its place; files rebuilt at every launch skip that. Creates missing parent directories.
 */
fun File.writeTextAtomically(text: String, durable: Boolean = true) {
    parentFile?.mkdirs()
    val tmp = File("$path.new")
    FileOutputStream(tmp).use { out ->
        out.write(text.toByteArray(Charsets.UTF_8))
        if (durable) out.fd.sync()
    }
    if (!tmp.renameTo(this)) throw IOException("Could not write $this")
}
