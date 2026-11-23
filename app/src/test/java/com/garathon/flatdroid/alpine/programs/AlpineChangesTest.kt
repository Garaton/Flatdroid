package com.garathon.flatdroid.alpine.programs

import com.garathon.flatdroid.alpine.programs.AlpineChanges.Stamp
import org.junit.Assert.assertEquals
import org.junit.Test

class AlpineChangesTest {
    @Test
    fun addedRemovedAndChangedEntriesAreChanges() {
        val before = mapOf("usr/bin/a" to Stamp(1, 10, 5), "usr/bin/b" to Stamp(1, 11, 5), "etc" to Stamp(1, 2, 0))
        val after = mapOf("usr/bin/a" to Stamp(1, 10, 5), "usr/bin/b" to Stamp(2, 11, 5), "usr/bin/c" to Stamp(2, 12, 1))
        assertEquals(listOf("etc", "usr/bin/b", "usr/bin/c"), AlpineChanges.changes(before, after))
        // A file replaced by another with the same times shows by its inode.
        assertEquals(listOf("x"), AlpineChanges.changes(mapOf("x" to Stamp(1, 1, 1)), mapOf("x" to Stamp(1, 2, 1))))
        assertEquals(emptyList<String>(), AlpineChanges.changes(before, before))
    }

    private val dir = 0x41ed
    private val file = 0x81a4

    @Test
    fun aMovedTimeAloneIsLeftOut() {
        val before = mapOf(
            "etc" to Stamp(1, 2, 0, dir), "etc/hosts" to Stamp(1, 3, 9, file),
            "usr/bin" to Stamp(1, 4, 0, dir), "usr/bin/a" to Stamp(1, 5, 9, file), "usr/bin/b" to Stamp(1, 6, 9, file),
        )
        val after = mapOf(
            // A stand-in made and removed: same entries.
            "etc" to Stamp(2, 2, 0, dir), "etc/hosts" to Stamp(1, 3, 9, file),
            // a: touched, still as installed; b: rewritten in place; c: new.
            "usr/bin" to Stamp(2, 4, 0, dir), "usr/bin/a" to Stamp(2, 5, 9, file), "usr/bin/b" to Stamp(2, 6, 9, file),
            "usr/bin/c" to Stamp(2, 7, 1, file),
        )
        val changed = AlpineChanges.changes(before, after)
        assertEquals(listOf("usr/bin", "usr/bin/b", "usr/bin/c"), AlpineChanges.real(changed, before, after) { it == "usr/bin/a" })
        // A changed mode or inode is never left out, nor a file apk didn't install.
        val chmod = after + ("usr/bin/a" to Stamp(2, 5, 9, 0x81ed))
        assertEquals(true, "usr/bin/a" in AlpineChanges.real(AlpineChanges.changes(before, chmod), before, chmod) { true })
        assertEquals(true, "usr/bin/a" in AlpineChanges.real(changed, before, after) { false })
    }

    @Test
    fun theSummaryListsFilesAndGroupsLargeFolders() {
        assertEquals(listOf("usr/bin/b", "usr/bin/c"), AlpineChanges.summary(listOf("usr/bin", "usr/bin/b", "usr/bin/c")))
        val many = (1..12).map { "usr/lib/x$it" }
        assertEquals(listOf("etc/profile", "usr/lib/ (12)"), AlpineChanges.summary(listOf("usr/lib") + many + "etc/profile"))
        // A folder whose entries didn't change itself stays.
        assertEquals(listOf("etc"), AlpineChanges.summary(listOf("etc")))
    }
}
