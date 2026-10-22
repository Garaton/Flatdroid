package com.garathon.flatdroid.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Writes this file with [write] into a new hidden file beside it, then renames that over this
 * file, so readers (including a proot that binds the file) see either the old or the new content,
 * never a partial one, and a failed write leaves the old file as it was. With [durable], the new
 * file is synced before the rename, so a power loss can't leave an empty file in its place; files
 * rebuilt at every launch skip that. Creates missing parent directories.
 */
fun File.writeAtomically(durable: Boolean = true, write: (FileOutputStream) -> Unit) {
    val dir = parentFile ?: throw IOException("No folder for $this")
    dir.mkdirs()
    // A unique name, so a file of the user's called "<name>.new" is never touched.
    val tmp = File.createTempFile(".$name.", ".part", dir)
    try {
        FileOutputStream(tmp).use { out ->
            write(out)
            if (durable) out.fd.sync()
        }
        if (!tmp.renameTo(this)) throw IOException("Could not write $this")
    } catch (e: Throwable) {
        tmp.delete()
        throw e
    }
}

/**
 * Deletes the temporary files [writeAtomically] left in [dir] when it was stopped mid-write
 * (killed), made at or after [since] (milliseconds since the epoch): hidden, named
 * `.<name>.<number>.part`. For a task continuing where it stopped.
 */
fun removeAtomicLeftovers(dir: File, since: Long) {
    for (file in dir.listFiles().orEmpty()) {
        if (LEFTOVER.matches(file.name) && file.isFile && file.lastModified() >= since) file.delete()
    }
}

private val LEFTOVER = Regex("""\..+\.\d+\.part""")

fun File.writeTextAtomically(text: String, durable: Boolean = true) =
    writeAtomically(durable) { it.write(text.toByteArray(Charsets.UTF_8)) }

/**
 * Puts [staged] (a file or folder on the same filesystem) where this one is. The old one is
 * moved aside first and deleted with [deleteTree] only once [staged] is in place; if that move
 * fails, the old one is put back, so a failure never leaves neither.
 */
fun File.replaceWith(staged: File, deleteTree: (File) -> Unit) {
    val dir = parentFile ?: throw IOException("No folder for $this")
    dir.mkdirs()
    val aside = File(dir, ".$name.old")
    deleteTree(aside)
    // By name, so a dangling link (which exists() denies) counts too.
    val hadOld = dir.list()?.contains(name) == true
    if (hadOld && !renameTo(aside)) throw IOException("Could not move $this aside")
    if (!staged.renameTo(this)) {
        if (hadOld) aside.renameTo(this)
        throw IOException("Could not move $staged to $this")
    }
    deleteTree(aside)
}

