package com.garathon.flatdroid.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.io.File
import java.nio.file.Files

class RootfsInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeSource(
        private val bytes: ByteArray,
        override val sha256: String,
        override val version: String = "3.24.2",
    ) : RootfsSource {
        var cleanedUp = false
        override fun open(): InputStream = ByteArrayInputStream(bytes)
        override fun cleanup() { cleanedUp = true }
    }

    private val tarGz: ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use {
            it.write(Tar().apply {
                entry("./bin/", '5', 0x1ed)
                entry("./bin/busybox", '0', 0x1ed, "ELF".toByteArray())
            }.bytes())
        }
    }.toByteArray()

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun installsAndCleansUp() {
        val installer = RootfsInstaller(tmp.newFolder("alpine"), tmp.newFolder("cache"), JvmTarFs)
        val source = FakeSource(tarGz, sha256(tarGz))
        installer.install(source)
        assertEquals("ELF", File(installer.rootfs, "bin/busybox").readText())
        assertTrue(File(installer.rootfs, ".l2s").isDirectory)
        assertEquals("3.24.2", installer.installedVersion())
        assertTrue(source.cleanedUp)
    }

    @Test
    fun checksumMismatchWritesNothing() {
        val alpine = tmp.newFolder("alpine")
        val cache = tmp.newFolder("cache")
        val installer = RootfsInstaller(alpine, cache, JvmTarFs)
        val source = FakeSource(tarGz, "0".repeat(64))
        try {
            installer.install(source)
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }
        assertTrue(source.cleanedUp)
        assertNull(installer.installedVersion())
        assertFalse(installer.rootfs.exists())
        assertFalse(File(cache, "rootfs.partial").exists())
    }

    @Test
    fun completeRootfsIsNeverReplaced() {
        val installer = RootfsInstaller(tmp.newFolder("alpine"), tmp.newFolder("cache"), JvmTarFs)
        installer.install(FakeSource(tarGz, sha256(tarGz)))
        val installedFile = File(installer.rootfs, "root/installed-later").apply {
            parentFile!!.mkdirs()
            writeText("flatpak")
        }
        val newer = FakeSource(tarGz, sha256(tarGz), version = "3.25.0")
        installer.install(newer)
        assertEquals("3.24.2", installer.installedVersion())
        assertTrue(installedFile.exists())
        assertFalse(newer.cleanedUp)
    }

    @Test
    fun incompleteRootfsIsReplacedWithoutFollowingSymlinks() {
        val alpine = tmp.newFolder("alpine")
        val outside = tmp.newFolder("outside")
        val precious = File(outside, "precious").apply { writeText("keep") }
        val stale = File(alpine, "rootfs").apply { mkdirs() } // no marker
        Files.createSymbolicLink(File(stale, "sdcard").toPath(), outside.toPath())

        val installer = RootfsInstaller(alpine, tmp.newFolder("cache"), JvmTarFs)
        installer.install(FakeSource(tarGz, sha256(tarGz)))
        assertEquals("3.24.2", installer.installedVersion())
        assertFalse(File(installer.rootfs, "sdcard").exists())
        assertEquals("keep", precious.readText())
    }
}
