package com.garathon.flatdroid.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DevNodesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val everything: (String) -> Boolean = { true }

    @Test
    fun noDevicesGivesTheBaseSet() {
        assertEquals(DevNodes.BASE, DevNodes.forApp(emptyList(), everything))
    }

    @Test
    fun namedGroupsAreAddedAndUnknownNamesIgnored() {
        assertEquals(DevNodes.BASE + listOf("/dev/dri", "/dev/bus/usb"), DevNodes.forApp(listOf("dri", "usb", "shm", "bogus"), everything))
    }

    @Test
    fun allMeansEveryGroupNotTheWholeOfDev() {
        val all = DevNodes.forApp(listOf("all"), everything)
        assertEquals(DevNodes.BASE + DevNodes.GROUPS.values.flatten(), all)
        assertTrue(all.none { it.contains("binder") || it == "/dev" })
    }

    @Test
    fun limitedAllMeansTheGpuOnlyAndNamedGroupsStay() {
        assertEquals(DevNodes.BASE + "/dev/dri", DevNodes.forApp(listOf("all"), everything, limited = true))
        assertEquals(DevNodes.BASE + listOf("/dev/dri", "/dev/input"), DevNodes.forApp(listOf("all", "input"), everything, limited = true))
        assertEquals(DevNodes.BASE + "/dev/bus/usb", DevNodes.forApp(listOf("usb"), everything, limited = true))
    }

    @Test
    fun missingNodesAreLeftOut() {
        val forApp = DevNodes.forApp(listOf("all"), { it != "/dev/kvm" && it != "/dev/tty" })
        assertFalse("/dev/kvm" in forApp)
        assertFalse("/dev/tty" in forApp)
    }

    @Test
    fun alpineGetsRandomFromUrandomInstead() {
        val alpine = DevNodes.forAlpine(everything)
        assertFalse("/dev/random" in alpine)
        assertTrue("/dev/urandom" in alpine)
    }

    @Test
    fun deniedAndOddPathsAreNotAllowed() {
        for (path in listOf("/dev/binder", "/dev/hwbinder", "/dev/vndbinder", "/dev/__properties__/x", "/dev/socket/logdw", "/dev/../etc", "/etc/passwd", "/dev/a:b", "/dev/")) {
            assertFalse(path, DevNodes.isAllowed(path))
        }
        assertTrue(DevNodes.isAllowed("/dev/bus/usb"))
    }

    @Test
    fun mountPointsFollowTheHostsKindAndKeepWhatExists() {
        val root = tmp.newFolder("root")
        File(root, "dev").mkdirs()
        File(root, "dev/null").writeText("kept")
        DevNodes.createMountPoints(root, listOf("/dev/null", "/dev/zero", "/proc/self/fd"))
        assertEquals("kept", File(root, "dev/null").readText())
        assertTrue(File(root, "dev/zero").isFile)
        assertTrue(File(root, "proc/self/fd").isDirectory)
    }
}
