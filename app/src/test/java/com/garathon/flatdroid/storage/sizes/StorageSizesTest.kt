package com.garathon.flatdroid.storage.sizes

import com.garathon.flatdroid.util.io.JvmDirReader
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class StorageSizesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun blocks(file: File) = JvmDirReader.stat(file, follow = false).bytes(0)

    @Test
    fun linksIntoTheStoreCountTheirContentOnce() {
        val store = tmp.newFolder("rootfs", ".l2s")
        val content = File(store, ".l2s.obj0001.0002").apply { writeText("x".repeat(10_000)) }
        val files = tmp.newFolder("files")
        File(files, "sub").mkdir()
        Files.createSymbolicLink(File(files, "a").toPath(), content.toPath())
        Files.createSymbolicLink(File(files, "sub/b").toPath(), content.toPath())
        Files.createSymbolicLink(File(files, "elsewhere").toPath(), File("/usr/share").toPath())
        var contents = 0
        val total = StorageSizes.following(listOf(files), store, onContent = { _, _ -> contents++ }, reader = JvmDirReader)
        val own = listOf(files, File(files, "sub"), File(files, "a"), File(files, "sub/b"), File(files, "elsewhere")).sumOf(::blocks)
        assertEquals(own + blocks(content), total)
        assertEquals(1, contents)
    }

    @Test
    fun anEntryGivenASizeIsntWalked() {
        val root = tmp.newFolder("root")
        File(root, "skip").mkdir()
        File(root, "skip/big").writeText("x".repeat(50_000))
        File(root, "keep").writeText("y")
        val total = StorageSizes.of(root, instead = { if (it.name == "skip") 7L else null }, reader = JvmDirReader)
        assertEquals(blocks(root) + blocks(File(root, "keep")) + 7L, total)
    }
}
