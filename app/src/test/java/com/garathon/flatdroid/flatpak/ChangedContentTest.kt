package com.garathon.flatdroid.flatpak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ChangedContentTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fs = object : LinkFs {
        override fun isSymlink(file: File) = Files.isSymbolicLink(file.toPath())
        override fun readlink(file: File) = if (isSymlink(file)) Files.readSymbolicLink(file.toPath()).toString() else null
        override fun delete(file: File) {
            Files.deleteIfExists(file.toPath())
        }
        override fun list(dir: File): Array<String>? = dir.list()
        override fun isDirectory(file: File) = file.isDirectory
    }

    private fun link(link: File, target: File) {
        link.parentFile?.mkdirs()
        Files.createSymbolicLink(link.toPath(), target.toPath())
    }

    @Test
    fun theChangedFileAndItsRepositoryObjectLoseTheirLinksAndNothingElseIsTouched() {
        val rootfs = tmp.newFolder("rootfs")
        val l2s = File(rootfs, ".l2s").apply { mkdirs() }
        val deploy = File(rootfs, "deploy")
        val objects = File(rootfs, "repo/objects")
        // A changed file, hard-linked from its repository object objects/93/a4eb.file and shared by
        // another deployment: three links, as the count in the content file's name says.
        val content = File(l2s, ".l2s.a4eb.file0001.0003").apply { writeText("changed") }
        val intermediate = File(l2s, ".l2s.a4eb.file0001").also { link(it, content) }
        val changed = File(deploy, "lib/libx.so").also { link(it, intermediate) }
        val obj = File(objects, "93/a4eb.file").also { link(it, intermediate) }
        // Another deployment sharing that content, and an object of the same name in another folder.
        val sharer = File(rootfs, "other/lib/libx.so").also { link(it, intermediate) }
        val unrelated = File(objects, "11/a4eb.file").also { link(it, File(l2s, ".l2s.a4eb.file0002")) }
        // A Flatpak's own link and a plain changed file.
        val outside = tmp.newFile("precious").apply { writeText("keep") }
        val own = File(deploy, "lib/own").also { link(it, outside) }
        val plain = File(deploy, "metadata").apply { writeText("changed") }

        assertEquals(3, ChangedContent(l2s, objects, fs).discard(listOf(changed, own, plain)))

        assertFalse(fs.isSymlink(changed) || fs.isSymlink(obj) || plain.exists())
        // The content stays for the other deployment, and nothing else moved.
        assertEquals("changed", content.readText())
        assertEquals("changed", sharer.readText())
        assertTrue(fs.isSymlink(unrelated) && fs.isSymlink(own))
        assertEquals("keep", outside.readText())
    }

    @Test
    fun contentNothingElseLinksToGoesWithItsIntermediate() {
        val rootfs = tmp.newFolder("rootfs")
        val l2s = File(rootfs, ".l2s").apply { mkdirs() }
        val content = File(l2s, ".l2s.c1.file0001.0002").apply { writeText("changed") }
        val intermediate = File(l2s, ".l2s.c1.file0001").also { link(it, content) }
        val changed = File(rootfs, "deploy/bin/app").also { link(it, intermediate) }
        File(rootfs, "repo/objects/9f/c1.file").also { link(it, intermediate) }

        assertEquals(2, ChangedContent(l2s, File(rootfs, "repo/objects"), fs).discard(listOf(changed)))
        assertFalse(content.exists() || fs.isSymlink(intermediate))
    }

    @Test
    fun theStoreIsKnownByItsNameWhicheverPathALinkReachesItBy() {
        // Android reaches app data as /data/data/<package> and /data/user/0/<package>: one store, two prefixes.
        val real = tmp.newFolder("user0", "pkg", "rootfs")
        val alias = File(tmp.root, "data").also { it.mkdirs(); Files.createSymbolicLink(File(it, "pkg").toPath(), real.parentFile.toPath()) }
        val l2s = File(real, ".l2s").apply { mkdirs() }
        File(l2s, ".l2s.b7.file0001.0001").writeText("changed")
        val viaAlias = File(alias, "pkg/rootfs/.l2s/.l2s.b7.file0001")
        link(File(l2s, ".l2s.b7.file0001"), File(l2s, ".l2s.b7.file0001.0001"))
        val changed = File(real, "deploy/bin/app").also { link(it, viaAlias) }
        val obj = File(real, "repo/objects/c0/b7.file").also { link(it, viaAlias) }

        assertEquals(2, ChangedContent(l2s, File(real, "repo/objects"), fs).discard(listOf(changed)))
        assertFalse(fs.isSymlink(changed) || fs.isSymlink(obj))
        // Its two links were the only ones (count 0001 here is below them, so it is kept as inconsistent).
        assertTrue(File(l2s, ".l2s.b7.file0001.0001").exists())
        assertEquals("b7.file", L2s.firstName(".l2s.b7.file0001"))
    }
}
