package com.garathon.flatdroid.flatpak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LauncherFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun writesEtcFilesWithTheAndroidUid() {
        val files = LauncherFiles(File(tmp.root, "launcher/etc"), uid = 10729, gid = 10729)
        files.write()
        assertEquals("root:x:0:0:root:/root:/bin/sh\nuser:x:10729:10729:user:/home/user:/bin/sh\n", files.passwd.readText())
        assertEquals("root:x:0:\nuser:x:10729:\n", files.group.readText())
        assertTrue(files.hosts.readText().startsWith("127.0.0.1 localhost"))
        assertTrue(Regex("^[0-9a-f]{32}\n$").matches(files.machineId.readText()))
    }

    @Test
    fun machineIdIsKeptAcrossRuns() {
        val files = LauncherFiles(File(tmp.root, "etc"), uid = 1, gid = 1)
        files.write()
        val id = files.machineId.readText()
        files.write()
        assertEquals(id, files.machineId.readText())
    }
}
