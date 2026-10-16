package com.garathon.flatdroid.launcher

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExecutableTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun readsTheElfMachineField() {
        assertEquals(Executable.Elf("aarch64"), Executable.of(elf(0xb7)))
        assertEquals(Executable.Elf("x86_64"), Executable.of(elf(0x3e)))
        assertEquals(Executable.Elf("machine-40"), Executable.of(elf(40)))
    }

    @Test
    fun recognisesScriptsAndRejectsOthers() {
        val script = File(tmp.root, "run.sh").apply { writeText("#!/bin/sh\nexec zypak-wrapper /app/x \"\$@\"\n") }
        assertEquals(Executable.Script(script.readText()), Executable.of(script))
        assertEquals(Executable.Unknown, Executable.of(File(tmp.root, "missing")))
        assertEquals(Executable.Unknown, Executable.of(byteArrayOf(0x7f, 'E'.code.toByte())))
    }

    @Test
    fun readsAnElfFile() {
        val bin = File(tmp.root, "bin").apply { writeBytes(elf(0x3e) + ByteArray(100)) }
        assertEquals(Executable.Elf("x86_64"), Executable.of(bin))
    }

    companion object {
        fun elf(machine: Int): ByteArray = ByteArray(64).also {
            it[0] = 0x7f; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte(); it[3] = 'F'.code.toByte()
            it[4] = 2; it[5] = 1
            it[18] = machine.toByte(); it[19] = (machine shr 8).toByte()
        }
    }
}
