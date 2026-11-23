package com.garathon.flatdroid.util.io

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.FileTime

/** [DirReader] on the JVM's file API (Linux's `unix:` attributes), for unit tests; allocated bytes are the size in 4 KiB blocks. */
object JvmDirReader : DirReader {
    override fun list(dir: File): DirReader.Listing? {
        if (Files.isSymbolicLink(dir.toPath()) || !dir.isDirectory) return null
        val names = dir.list()?.toList() ?: return null
        val types = ByteArray(names.size) { i ->
            val path = File(dir, names[i]).toPath()
            when {
                Files.isSymbolicLink(path) -> DirReader.LINK
                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> DirReader.DIRECTORY
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> DirReader.FILE
                else -> DirReader.OTHER
            }
        }
        return DirReader.Listing(names, types)
    }

    override fun readlinks(dir: File, names: List<String>): Array<String?> = Array(names.size) { i ->
        runCatching { Files.readSymbolicLink(File(dir, names[i]).toPath()).toString() }.getOrNull()
    }

    override fun stats(dir: File, names: List<String>, follow: Boolean): DirReader.Stats {
        val values = DirReader.Stats.of(names.size)
        val options = if (follow) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS)
        for (i in names.indices) {
            val a = runCatching { Files.readAttributes(File(dir, names[i]).toPath(), "unix:ino,ctime,size,mode", *options) }.getOrNull() ?: continue
            val size = a["size"] as Long
            values[i * DirReader.Stats.FIELDS] = a["ino"] as Long
            values[i * DirReader.Stats.FIELDS + 1] = (a["ctime"] as FileTime).toInstant().let { it.epochSecond * 1_000_000_000L + it.nano }
            values[i * DirReader.Stats.FIELDS + 2] = size
            values[i * DirReader.Stats.FIELDS + 3] = (a["mode"] as Int).toLong()
            values[i * DirReader.Stats.FIELDS + 4] = (size + 4095) / 4096 * 4096
        }
        return DirReader.Stats(values)
    }
}
