package com.garathon.flatdroid.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class ElectronTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun findsTheBinaryZypakRuns() {
        assertEquals("/app/obsidian", Electron.zypakTarget("#!/bin/sh\n# start\nexec zypak-wrapper /app/obsidian \"\$@\"\n"))
        assertEquals(
            "/app/main/app",
            Electron.zypakTarget("#!/bin/bash\nexport TMPDIR=x\nexec /app/bin/zypak-wrapper.sh '/app/main/app' --flag \"\$@\"\n"),
        )
        assertNull(Electron.zypakTarget("#!/bin/sh\nexec /app/bin/real \"\$@\"\n"))
        assertNull(Electron.zypakTarget("#!/bin/sh\n# zypak-wrapper /app/commented\nexec zypak-wrapper \"\$BIN\"\n"))
    }

    @Test
    fun findsTheBrowserCobaltRuns() {
        val script = "#!/usr/bin/bash\ntouch \"\${XDG_CONFIG_HOME}/x\"\nexec cobalt \"\$@\"\n"
        assertEquals("/app/extra/chrome", Electron.cobaltTarget(script, "com.google.Chrome") { it == "/app/extra/chrome" })
        assertEquals("/app/extra/edge/edge", Electron.cobaltTarget(script, "com.example.Edge") { it == "/app/extra/edge/edge" })
        assertNull(Electron.cobaltTarget(script, "com.google.Chrome") { false })
        assertNull(Electron.cobaltTarget("#!/bin/sh\n# cobalt\nexec /app/bin/real\n", "com.google.Chrome") { true })
    }

    @Test
    fun readsTheVersionFromTheBinary() {
        val binary = ByteArray(200_000) { 'x'.code.toByte() }
        val ua = "Mozilla/5.0 Chrome/83.0.4103.122 Electron/9.4.4 Safari/537.36".toByteArray()
        ua.copyInto(binary, 150_000)
        assertEquals("9.4.4", Electron.version(ByteArrayInputStream(binary)))
        assertNull(Electron.version(ByteArrayInputStream("Electron/%s and Electron/".toByteArray())))
    }

    @Test
    fun findsAVersionSplitAcrossReads() {
        val text = "....Electron/31.7.6 ...".toByteArray()
        // Hands out the bytes a few at a time, so the marker and version cross read boundaries.
        val trickle = object : InputStream() {
            var pos = 0
            override fun read(): Int = if (pos < text.size) text[pos++].toInt() else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= text.size) return -1
                val n = minOf(3, len, text.size - pos)
                text.copyInto(b, off, pos, pos + n)
                pos += n
                return n
            }
        }
        assertEquals("31.7.6", Electron.version(trickle))
    }

    @Test
    fun readsShasums() {
        val sums = "${"a".repeat(64)} *electron-v9.4.4-linux-arm64.zip\n${"B".repeat(64)}  electron-v9.4.4-linux-x64.zip\n"
        assertEquals("a".repeat(64), Electron.sha256(sums, "electron-v9.4.4-linux-arm64.zip"))
        assertEquals("b".repeat(64), Electron.sha256(sums, "electron-v9.4.4-linux-x64.zip"))
        assertNull(Electron.sha256(sums, "electron-v9.4.4-linux-armv7l.zip"))
    }

    @Test
    fun namesReleases() {
        assertEquals("electron-v9.4.4-linux-arm64.zip", Electron.zipName("9.4.4", "aarch64"))
        assertEquals("electron-v9.4.4-linux-x64.zip", Electron.zipName("9.4.4", "x86_64"))
        assertEquals(
            "https://github.com/electron/electron/releases/download/v9.4.4/SHASUMS256.txt",
            Electron.releaseUrl("9.4.4", "SHASUMS256.txt"),
        )
    }

    @Test
    fun detectsNativeModules() {
        val resources = tmp.newFolder("resources")
        File(resources, "app.asar").writeText("x")
        assertFalse(Electron.hasNativeModules(resources))
        File(resources, "app.asar.unpacked/node_modules/x").mkdirs()
        File(resources, "app.asar.unpacked/node_modules/x/x.node").writeText("x")
        assertTrue(Electron.hasNativeModules(resources))
    }
}
