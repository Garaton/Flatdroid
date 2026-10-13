package com.garathon.flatdroid.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class TarExtractorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun extract(tar: Tar): File {
        val dest = tmp.newFolder("root")
        TarExtractor(JvmTarFs).extract(ByteArrayInputStream(tar.bytes()), dest)
        return dest
    }

    @Test
    fun extractsFilesDirectoriesAndSymlinks() {
        val dest = extract(Tar().apply {
            entry("./", '5', 0x1ed)
            entry("./bin/", '5', 0x1ed)
            entry("./bin/busybox", '0', 0x1ed, "ELF".toByteArray())
            entry("./bin/echo", '2', link = "/bin/busybox")
            entry("./etc/alpine-release", '0', data = "3.24.2\n".toByteArray())
        })
        assertEquals("ELF", File(dest, "bin/busybox").readText())
        assertTrue(File(dest, "bin/busybox").canExecute())
        assertEquals("/bin/busybox", Files.readSymbolicLink(File(dest, "bin/echo").toPath()).toString())
        assertEquals("3.24.2\n", File(dest, "etc/alpine-release").readText())
    }

    @Test
    fun hardLinkBecomesCopy() {
        val dest = extract(Tar().apply {
            entry("a", '0', data = "x".toByteArray())
            entry("b", '1', link = "a")
        })
        assertEquals("x", File(dest, "b").readText())
        assertFalse(Files.isSymbolicLink(File(dest, "b").toPath()))
    }

    @Test
    fun paxPathOverridesHeaderName() {
        val long = "d/" + "n".repeat(150)
        val dest = extract(Tar().apply {
            pax(mapOf("path" to long))
            entry("truncated", '0', data = "p".toByteArray())
        })
        assertEquals("p", File(dest, long).readText())
    }

    @Test
    fun gnuLongNameIsUsed() {
        val long = "e/" + "m".repeat(120)
        val dest = extract(Tar().apply {
            entry("././@LongLink", 'L', data = (long + "\u0000").toByteArray())
            entry("short", '0', data = "g".toByteArray())
        })
        assertEquals("g", File(dest, long).readText())
    }

    @Test
    fun setuidBitsAreDroppedAndOwnerKeepsWrite() {
        val dest = extract(Tar().apply { entry("s", '0', 0xded /* 06755 */, "x".toByteArray()) })
        val perms = Files.getPosixFilePermissions(File(dest, "s").toPath())
        assertTrue(PosixFilePermission.OWNER_WRITE in perms)
        assertTrue(PosixFilePermission.OTHERS_EXECUTE in perms)
    }

    @Test(expected = IOException::class)
    fun dotDotIsRejected() {
        extract(Tar().apply { entry("../evil", '0', data = "x".toByteArray()) })
    }

    @Test(expected = IOException::class)
    fun writingThroughSymlinkOutsideIsRejected() {
        val outside = tmp.newFolder("outside")
        extract(Tar().apply {
            entry("link", '2', link = outside.path)
            entry("link/evil", '0', data = "x".toByteArray())
        })
    }

    @Test(expected = IOException::class)
    fun directoryEntryThroughSymlinkOutsideIsRejected() {
        val outside = tmp.newFolder("outside")
        extract(Tar().apply {
            entry("link", '2', link = outside.path)
            entry("link/", '5', 0x1ff)
        })
    }

    @Test
    fun deleteTreeDoesNotFollowSymlinks() {
        val outside = tmp.newFolder("outside")
        val precious = File(outside, "precious").apply { writeText("keep") }
        val tree = tmp.newFolder("tree")
        File(tree, "dir").mkdirs()
        File(tree, "dir/file").writeText("x")
        Files.createSymbolicLink(File(tree, "dir/link").toPath(), outside.toPath())
        JvmTarFs.deleteTree(tree)
        assertFalse(tree.exists())
        assertEquals("keep", precious.readText())
    }

    @Test(expected = java.io.EOFException::class)
    fun truncatedArchiveFails() {
        val dest = tmp.newFolder("root")
        val bytes = Tar().apply { entry("a", '0', data = ByteArray(2000)) }.out.toByteArray()
        TarExtractor(JvmTarFs).extract(ByteArrayInputStream(bytes.copyOf(1000)), dest)
    }
}
