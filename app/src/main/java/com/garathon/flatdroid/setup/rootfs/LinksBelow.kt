package com.garathon.flatdroid.setup.rootfs

import java.io.File

/**
 * Folders an app can write to may hold links it made, with any host path as the target. Making or
 * removing something below one must not follow them. These take a [base] Flatdroid trusts and a
 * path [rel] below it.
 */

/** Whether any part of [rel] below [base], the last one included, is a link itself (lstat). */
fun TarFs.hasLinkBelow(base: File, rel: String): Boolean {
    var current = base
    for (part in rel.split('/').filter { it.isNotEmpty() }) {
        current = File(current, part)
        if (isSymlink(current)) return true
    }
    return false
}

/**
 * Makes the folders of [rel] below [base] one by one. A link where a folder should be (made by the
 * app, so what it leads to is not Flatdroid's to fill) is removed first, never followed. Whether
 * the whole path is there and real.
 */
fun TarFs.makeDirsBelow(base: File, rel: String): Boolean {
    var current = base
    for (part in rel.split('/').filter { it.isNotEmpty() }) {
        current = File(current, part)
        if (isSymlink(current)) current.delete()
        current.mkdir()
    }
    return current.isDirectory && !isSymlink(current)
}
