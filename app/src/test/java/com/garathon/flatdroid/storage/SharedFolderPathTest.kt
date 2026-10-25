package com.garathon.flatdroid.storage

import com.garathon.flatdroid.storage.SharedFolderPath.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedFolderPathTest {
    private val root = "/storage/emulated/0"
    private fun of(authority: String, id: String, broad: Boolean = false) = SharedFolderPath.of(authority, id, root, broad)

    @Test
    fun documentsAndDownloadWithoutPermission() {
        assertEquals(Result.Ok("$root/Documents/Flatdroid"), of(SharedFolderPath.EXTERNAL_STORAGE, "primary:Documents/Flatdroid"))
        // Shared storage ignores case; the path keeps the picker's.
        assertEquals(Result.Ok("$root/download/Home"), of(SharedFolderPath.EXTERNAL_STORAGE, "primary:download/Home"))
        assertEquals(Result.Ok("$root/Download/Home"), of(SharedFolderPath.DOWNLOADS, "raw:$root/Download/Home"))
        assertEquals(Result.NotAllowed, of(SharedFolderPath.EXTERNAL_STORAGE, "primary:Music/Home"))
        assertEquals(Result.NotAllowed, of(SharedFolderPath.EXTERNAL_STORAGE, "primary:"))
    }

    @Test
    fun anyFolderWithAccessToAllFiles() {
        assertEquals(Result.Ok("$root/Music/Home"), of(SharedFolderPath.EXTERNAL_STORAGE, "primary:Music/Home", broad = true))
        assertEquals(Result.Ok("/storage/1234-ABCD/Home"), of(SharedFolderPath.EXTERNAL_STORAGE, "1234-ABCD:Home", broad = true))
        assertEquals(Result.Unknown, of(SharedFolderPath.EXTERNAL_STORAGE, "1234-ABCD:Home"))
    }

    @Test
    fun foldersWithoutAPathAreRefused() {
        assertEquals(Result.Unknown, of(SharedFolderPath.DOWNLOADS, "msf:1234"))
        assertEquals(Result.Unknown, of("com.example.cloud", "primary:Documents"))
        assertEquals(Result.Unknown, of(SharedFolderPath.EXTERNAL_STORAGE, "primary:Documents/../Android"))
        assertEquals(Result.Unknown, of(SharedFolderPath.EXTERNAL_STORAGE, "primary:Documents//x"))
    }

    @Test
    fun displayIsRelativeToInternalStorage() {
        assertEquals("Documents/Flatdroid", SharedFolderPath.display("$root/Documents/Flatdroid", root))
        assertEquals("/storage/1234-ABCD/Home", SharedFolderPath.display("/storage/1234-ABCD/Home", root))
    }

    @Test
    fun aSavedPathMustBeOnSharedStorage() {
        fun check(path: String, broad: Boolean = false) = SharedFolderPath.check(path, root, broad)
        assertEquals(Result.Ok("$root/Documents/Home"), check("$root/Documents/Home"))
        assertEquals(Result.NotAllowed, check("$root/Music/Home"))
        assertEquals(Result.Ok("/storage/1234-ABCD/Home"), check("/storage/1234-ABCD/Home", broad = true))
        for (path in listOf(
            "/data/user/0/com.garathon.flatdroid/files", "/data/data/com.garathon.flatdroid", "/", "/storage/emulated/10/Documents",
            "/storage/self/primary/Documents", "$root/Documents/../../../../data", "relative/Documents", "$root/Documents/",
        )) {
            assertEquals(path, Result.Unknown, check(path, broad = true))
        }
    }
}
