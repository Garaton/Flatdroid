package com.garathon.flatdroid.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PackageGroupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun aSessionNeedsX11OnlyWithoutWaylandAndTheTerminalOnlyForATerminalApp() {
        assertEquals(emptyList<PackageGroup>(), PackageGroup.needed(waylandSession = true, terminal = false))
        assertEquals(listOf(PackageGroup.X11), PackageGroup.needed(waylandSession = false, terminal = false))
        assertEquals(listOf(PackageGroup.TERMINAL), PackageGroup.needed(waylandSession = true, terminal = true))
    }

    @Test
    fun setupLeavesTheGroupsOut() {
        for (group in PackageGroup.entries) {
            for (monospace in listOf(true, false)) assertTrue(group.packages(monospace).none { it in AlpineSetup.PACKAGES })
            assertTrue(group.files.none { it in AlpineSetup.PACKAGE_FILES })
        }
        assertEquals(listOf("foot"), PackageGroup.TERMINAL.packages(androidMonospace = true))
        assertEquals(listOf("foot", PackageGroup.DEJAVU), PackageGroup.TERMINAL.packages(androidMonospace = false))
    }

    @Test
    fun aGroupIsAddedWhenItsProgramsAreThereAndUpdatesKeepIt() {
        val rootfs = tmp.newFolder("rootfs")
        assertFalse(PackageGroup.X11.addedIn(rootfs))
        assertEquals(AlpineSetup.PACKAGES, AlpineSetup.updateList(rootfs, androidMonospace = true))
        // An Alpine set up before X11 was left out of setup already has it.
        for (path in PackageGroup.X11.files) File(rootfs, path).apply { parentFile!!.mkdirs() }.writeText("")
        assertTrue(PackageGroup.X11.addedIn(rootfs))
        // Packages the group gained since (Mesa's DRI driver links) come with the next update.
        assertEquals(AlpineSetup.PACKAGES + listOf("tigervnc", "openbox", "mesa-dri-gallium"), AlpineSetup.updateList(rootfs, androidMonospace = true))
        File(rootfs, "usr/bin/foot").writeText("")
        assertEquals(
            AlpineSetup.PACKAGES + listOf("tigervnc", "openbox", "mesa-dri-gallium", "foot", PackageGroup.DEJAVU),
            AlpineSetup.updateList(rootfs, androidMonospace = false),
        )
    }
}
