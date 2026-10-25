package com.garathon.flatdroid.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

class HomeZipTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun zipsFilesAndFoldersWithoutSettingsOrLinks() {
        val home = tmp.newFolder("home")
        File(home, "Pictures").mkdir()
        File(home, "Documents").mkdir()
        File(home, "Documents/a.txt").writeText("hello")
        File(home, ".config/app").mkdirs()
        File(home, ".config/app/x").writeText("settings")
        File(home, ".mozilla").mkdir()
        Files.createSymbolicLink(File(home, "outside").toPath(), tmp.root.toPath())
        val out = ByteArrayOutputStream()
        var last = 0L to 0L
        HomeZip.write(home, out, isLink = { Files.isSymbolicLink(it.toPath()) }) { done, total -> last = done to total }

        val entries = mutableMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().decodeToString()
            }
        }
        assertEquals(setOf(".mozilla/", "Documents/", "Documents/a.txt", "Pictures/"), entries.keys)
        assertEquals("hello", entries["Documents/a.txt"])
        assertEquals(5L to 5L, last)
        assertFalse(entries.keys.any { it.startsWith(".config") || it.startsWith("outside") })
        assertTrue(entries.isNotEmpty())
    }

    private fun zip(vararg entries: Pair<String, String?>): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                text?.let { zip.write(it.toByteArray()) }
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun extractKeepsEverythingInsideTheDestination() {
        val home = tmp.newFolder("dest")
        val outside = File(tmp.root, "outside.txt")
        val bytes = zip("Documents/" to null, "Documents/a.txt" to "a", "../outside.txt" to "x", "/abs" to "x", "b//c" to "x")
        val result = HomeZip.extract(bytes.inputStream(), home, { File(home, it) }, HomeZip.Clash.RENAME, minFreeBytes = 0)
        assertEquals(HomeZip.Extracted(files = 1, skipped = 3), result)
        assertEquals("a", File(home, "Documents/a.txt").readText())
        assertFalse(outside.exists())
    }

    @Test
    fun extractDoesNotFollowLinksOut() {
        val home = tmp.newFolder("linked")
        val elsewhere = tmp.newFolder("elsewhere")
        Files.createSymbolicLink(File(home, "link").toPath(), elsewhere.toPath())
        val result = HomeZip.extract(zip("link/x.txt" to "x").inputStream(), home, { File(home, it) }, HomeZip.Clash.REPLACE, minFreeBytes = 0)
        assertEquals(1, result.skipped)
        assertFalse(File(elsewhere, "x.txt").exists())
    }

    @Test
    fun clashesAreRenamedOrReplacedAsChosen() {
        val home = tmp.newFolder("clash")
        File(home, "a.txt").writeText("old")
        val bytes = zip("a.txt" to "new")
        HomeZip.extract(bytes.inputStream(), home, { File(home, it) }, HomeZip.Clash.RENAME, minFreeBytes = 0)
        assertEquals("old", File(home, "a.txt").readText())
        assertEquals("new", File(home, "a (1).txt").readText())
        File(home, "a.txt").writeText("old")
        File(home, "a (1).txt").delete()
        val skipped = HomeZip.extract(bytes.inputStream(), home, { File(home, it) }, HomeZip.Clash.SKIP, minFreeBytes = 0)
        assertEquals(HomeZip.Extracted(files = 0, skipped = 0, kept = 1), skipped)
        assertEquals("old", File(home, "a.txt").readText())
        assertEquals(listOf("a.txt"), home.list()!!.toList())
        HomeZip.extract(bytes.inputStream(), home, { File(home, it) }, HomeZip.Clash.REPLACE, minFreeBytes = 0)
        assertEquals("new", File(home, "a.txt").readText())
    }

    @Test
    fun appSettingsUseTheHomesLayout() {
        val appData = tmp.newFolder("appdata")
        File(appData, "config/kate").mkdirs()
        File(appData, "config/kate/katerc").writeText("rc")
        File(appData, "var/data").mkdirs()
        File(appData, "var/data/d").writeText("d")
        File(appData, "config/user-dirs.dirs").writeText("")
        File(appData, "var/cache").mkdirs()
        File(appData, "home").mkdirs()
        File(appData, "home/notes.txt").writeText("not a setting")
        val out = ByteArrayOutputStream()
        HomeZip.write(AppFiles.parts(appData, "org.kde.kate"), out, isLink = { false })
        val names = mutableListOf<String>()
        java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { zip -> while (true) names += (zip.nextEntry ?: break).name }
        assertTrue(".config/kate/katerc" in names)
        assertTrue(".var/app/org.kde.kate/data/d" in names)
        assertFalse(names.any { "notes" in it || "user-dirs" in it || it.endsWith("/cache/") })

        val restored = tmp.newFolder("restored")
        HomeZip.extract(out.toByteArray().inputStream(), restored, { AppFiles.destination(restored, "org.kde.kate", it) }, HomeZip.Clash.REPLACE, minFreeBytes = 0)
        assertEquals("rc", File(restored, "config/kate/katerc").readText())
        assertEquals("d", File(restored, "var/data/d").readText())
        assertEquals(null, AppFiles.destination(restored, "org.kde.kate", ".var/app/org.other.App/x"))
    }

    @Test
    fun aReplaceThatFailsKeepsTheOriginal() {
        val home = tmp.newFolder("failing")
        File(home, "a.txt").writeText("old")
        // A zip cut off in the middle of its file's data.
        val whole = zip("a.txt" to "n".repeat(100_000))
        val cut = whole.copyOf(whole.size / 2)
        assertThrows(Exception::class.java) {
            HomeZip.extract(cut.inputStream(), home, { File(home, it) }, HomeZip.Clash.REPLACE, minFreeBytes = 0)
        }
        assertEquals("old", File(home, "a.txt").readText())
        assertEquals(listOf("a.txt"), home.list()!!.toList())
    }

    @Test
    fun anExtractionThatStoppedGoesOnWhereItStopped() {
        val home = tmp.newFolder("resumed")
        val bytes = zip("a.txt" to "a", "b.txt" to "b", "c.txt" to "c")
        var done = 0
        // Stopped after two entries, as if Flatdroid had been killed.
        assertThrows(IllegalStateException::class.java) {
            HomeZip.extract(bytes.inputStream(), home, { File(home, it) }, HomeZip.Clash.RENAME, minFreeBytes = 0, onEntry = { n ->
                done = n
                if (n == 2) throw IllegalStateException("stopped")
            })
        }
        var resumedAt: File? = null
        val rest = HomeZip.extract(bytes.inputStream(), home, { File(home, it) }, HomeZip.Clash.RENAME, minFreeBytes = 0, skipEntries = done, onResume = { resumedAt = it })
        assertEquals(File(home, "c.txt"), resumedAt)
        assertEquals(HomeZip.Extracted(files = 1, skipped = 0), rest)
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), home.list()!!.sorted())
    }
}
