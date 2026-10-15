package com.garathon.flatdroid.setup

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

object JvmTarFs : TarFs {
    override fun symlink(target: String, link: File) {
        Files.createSymbolicLink(link.toPath(), File(target).toPath())
    }
    override fun chmod(file: File, mode: Int) {
        val bits = PosixFilePermission.entries // OWNER_READ .. OTHERS_EXECUTE, high bit first
        Files.setPosixFilePermissions(file.toPath(), bits.filterIndexed { i, _ -> mode and (0x100 shr i) != 0 }.toSet())
    }
    override fun isSymlink(file: File) = Files.isSymbolicLink(file.toPath())
}

/** Minimal ustar writer for test archives. */
class Tar {
    val out = ByteArrayOutputStream()

    fun entry(name: String, type: Char, mode: Int = 0x1a4, data: ByteArray = ByteArray(0), link: String = "") {
        val h = ByteArray(512)
        fun put(off: Int, s: String) = s.toByteArray().copyInto(h, off)
        fun octal(off: Int, len: Int, v: Long) = put(off, v.toString(8).padStart(len - 1, '0'))
        put(0, name)
        octal(100, 8, mode.toLong()); octal(108, 8, 0); octal(116, 8, 0)
        octal(124, 12, data.size.toLong()); octal(136, 12, 0)
        h[156] = type.code.toByte()
        put(157, link)
        put(257, "ustar"); put(263, "00")
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        octal(148, 7, h.sumOf { it.toInt() and 0xff }.toLong())
        out.write(h)
        out.write(data)
        out.write(ByteArray((512 - data.size % 512) % 512))
    }

    fun pax(records: Map<String, String>) {
        val body = records.entries.joinToString("") { (k, v) ->
            val rest = " $k=$v\n"
            var len = rest.length + 1
            while ((len.toString() + rest).length != len) len++
            "$len$rest"
        }
        entry("././@PaxHeader", 'x', data = body.toByteArray())
    }

    fun bytes(): ByteArray = out.toByteArray() + ByteArray(1024)
}
