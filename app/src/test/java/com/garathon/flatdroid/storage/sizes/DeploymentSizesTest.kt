package com.garathon.flatdroid.storage.sizes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeploymentSizesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val commit = "a".repeat(64)
    private val other = "b".repeat(64)
    private val fingerprint = longArrayOf(10, 20, 30)
    private val size = DeploymentSize(own = 4096, inodes = longArrayOf(1, 2, 3), bytes = longArrayOf(512, 8192, 0))

    @Test
    fun aSavedSizeIsReadBackWhileItsDirectoryIsTheSame() {
        val sizes = DeploymentSizes(tmp.newFolder())
        var walked = 0
        val measure = { walked++; size }
        sizes.get(commit, fingerprint, measure = measure)
        val again = sizes.get(commit, fingerprint, measure = measure)
        assertEquals(1, walked)
        assertEquals(4096L, again.own)
        assertArrayEquals(longArrayOf(1, 2, 3), again.inodes)
        assertArrayEquals(longArrayOf(512, 8192, 0), again.bytes)
        assertEquals(4096L + 512 + 8192, again.following)
    }

    @Test
    fun aDeploymentPutInItsPlaceIsWalkedAgain() {
        val sizes = DeploymentSizes(tmp.newFolder())
        var walked = 0
        sizes.get(commit, fingerprint) { walked++; size }
        // A reinstall: the same commit, a new directory.
        sizes.get(commit, longArrayOf(11, 20, 30)) { walked++; size }
        assertEquals(2, walked)
        sizes.forget(commit)
        sizes.get(commit, longArrayOf(11, 20, 30)) { walked++; size }
        assertEquals(3, walked)
    }

    @Test
    fun aWalkAChangeOvertookIsNotSaved() {
        val dir = tmp.newFolder()
        DeploymentSizes(dir).get(commit, fingerprint, keep = { false }) { size }
        assertFalse(File(dir, commit).exists())
    }

    @Test
    fun aFileCutOffIsWalkedAgain() {
        val dir = tmp.newFolder()
        val sizes = DeploymentSizes(dir)
        sizes.get(commit, fingerprint) { size }
        val file = File(dir, commit)
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() - 2))
        var walked = 0
        sizes.get(commit, fingerprint) { walked++; size }
        assertEquals(1, walked)
    }

    @Test
    fun goneDeploymentsAndUnfinishedWritesAreRemoved() {
        val dir = tmp.newFolder()
        val sizes = DeploymentSizes(dir)
        sizes.get(commit, fingerprint) { size }
        sizes.get(other, fingerprint) { size }
        File(dir, ".$commit.123.part").writeText("x")
        sizes.removeExcept(setOf(other))
        assertEquals(listOf(other), dir.list()!!.toList())
        assertTrue(File(dir, other).isFile)
    }
}
