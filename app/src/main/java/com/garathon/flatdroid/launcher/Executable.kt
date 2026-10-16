package com.garathon.flatdroid.launcher

import java.io.File
import java.io.InputStream

/** What a file a launch would execute is: an ELF binary for some machine, a script, or neither. */
sealed interface Executable {
    /** [arch] is the Flatpak name (aarch64, x86_64), or `machine-<n>` for others. */
    data class Elf(val arch: String) : Executable
    /** A `#!` script; [text] is its content (scripts are small). */
    data class Script(val text: String) : Executable
    data object Unknown : Executable

    companion object {
        private const val EM_X86_64 = 0x3e
        private const val EM_AARCH64 = 0xb7
        private const val MAX_SCRIPT_BYTES = 64 * 1024

        /** Looks at the first bytes of [header]: the ELF machine field (bytes 18–19) or a shebang. */
        fun of(header: ByteArray, readScript: () -> String = { String(header) }): Executable {
            if (header.size >= 20 && header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
            ) {
                // Little-endian (EI_DATA = 1) on every architecture we support.
                val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
                return Elf(
                    when (machine) {
                        EM_AARCH64 -> "aarch64"
                        EM_X86_64 -> "x86_64"
                        else -> "machine-$machine"
                    }
                )
            }
            if (header.size >= 2 && header[0] == '#'.code.toByte() && header[1] == '!'.code.toByte()) {
                return Script(readScript())
            }
            return Unknown
        }

        fun of(file: File): Executable = try {
            val header = file.inputStream().use { it.readUpTo(20) }
            of(header) { file.inputStream().use { String(it.readUpTo(MAX_SCRIPT_BYTES)) } }
        } catch (_: Exception) {
            Unknown
        }
    }
}

/** Reads up to [count] bytes (InputStream.readNBytes needs API 33). */
internal fun InputStream.readUpTo(count: Int): ByteArray {
    val out = ByteArray(count)
    var n = 0
    while (n < count) {
        val r = read(out, n, count - n)
        if (r < 0) break
        n += r
    }
    return out.copyOf(n)
}
