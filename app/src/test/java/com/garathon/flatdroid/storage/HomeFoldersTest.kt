package com.garathon.flatdroid.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HomeFoldersTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun aNewHomeGetsTheStandardFoldersOnce() {
        val home = File(tmp.root, "Documents/Flatdroid")
        assertTrue(HomeFolders.create(home))
        assertEquals(listOf("Desktop", "Documents", "Downloads", "Music", "Pictures", "Videos"), home.list()!!.sorted())
        // Deleted by the user: not made again, since the home already exists.
        File(home, "Pictures").delete()
        assertFalse(HomeFolders.create(home))
        assertFalse(File(home, "Pictures").exists())
    }

    @Test
    fun userDirsGiveMissingFoldersTheHome() {
        val text = HomeFolders.userDirs { it != "Pictures" }
        assertTrue("XDG_DOWNLOAD_DIR=\"\$HOME/Downloads\"\n" in text)
        assertTrue("XDG_PICTURES_DIR=\"\$HOME/\"\n" in text)
        assertTrue("XDG_TEMPLATES_DIR=\"\$HOME/\"\n" in text)
        assertTrue("XDG_PUBLICSHARE_DIR=\"\$HOME/\"\n" in text)
    }

    @Test
    fun importsGoToDownloadsWhileItExists() {
        val home = tmp.newFolder("home")
        assertEquals(home, HomeFolders.importDir(home))
        File(home, "Downloads").mkdir()
        assertEquals(File(home, "Downloads"), HomeFolders.importDir(home))
    }

    @Test
    fun aTemporaryHomeWithOnlyEmptyStandardFoldersHasNothingToExport() {
        val home = File(tmp.root, "temp")
        HomeFolders.create(home)
        val isLink = { _: File -> false }
        assertFalse(TempHomes.hasContent(home, isLink))
        File(home, "Pictures/a.png").writeText("x")
        assertTrue(TempHomes.hasContent(home, isLink))
        File(home, "Pictures/a.png").delete()
        File(home, "notes").mkdir()
        assertTrue(TempHomes.hasContent(home, isLink))
    }
}
