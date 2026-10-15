package com.garathon.flatdroid.util

import java.io.File
import java.io.IOException

/**
 * Writes [text] to a sibling `.new` file, then renames it over this file, so readers (including
 * a proot that binds the file) see either the old or the new content, never a partial one.
 * Creates missing parent directories.
 */
fun File.writeTextAtomically(text: String) {
    parentFile?.mkdirs()
    val tmp = File("$path.new")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) throw IOException("Could not write $this")
}
