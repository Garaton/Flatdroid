package com.garathon.flatdroid.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderDocumentsTest {
    private val homes = "/data/user/0/com.garathon.flatdroid/files/appdata"
    private val storage = "/storage/emulated/0"
    private fun of(path: String) = FolderDocuments.of(path, homes, storage)

    @Test
    fun privateHomesThroughFlatdroidsProvider() {
        assertEquals(HomesProvider.AUTHORITY to "org.xfce.mousepad", of("$homes/org.xfce.mousepad/home"))
        assertEquals(HomesProvider.AUTHORITY to "org.xfce.mousepad/Documents/notes", of("$homes/org.xfce.mousepad/home/Documents/notes"))
    }

    @Test
    fun sharedStorageThroughAndroids() {
        assertEquals("com.android.externalstorage.documents" to "primary:Documents/Flatdroid", of("$storage/Documents/Flatdroid"))
        assertEquals("com.android.externalstorage.documents" to "primary:", of(storage))
    }

    @Test
    fun nothingElse() {
        assertNull(of("$homes/org.xfce.mousepad/var/config"))
        assertNull(of("$homes/org.xfce.mousepad"))
        assertNull(of("$homes/.shared-dotfiles/home"))
        assertNull(of("/data/user/0/com.garathon.flatdroid/files/alpine"))
        assertNull(of("$homes/org.xfce.mousepad/home/../../x"))
        assertNull(of("/storage/emulated/01/x"))
    }
}
