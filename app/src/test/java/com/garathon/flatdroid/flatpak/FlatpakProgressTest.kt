package com.garathon.flatdroid.flatpak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlatpakProgressTest {
    /** Captured from flatpak 1.16.6 (Alpine 3.24) writing to a pipe. */
    private val install = listOf(
        "Looking for matches…",
        "",
        " 1.\t   \torg.freedesktop.Platform.openh264\t2.0\ti\tflathub\t< 1.5 MB",
        " 2.\t   \torg.freedesktop.Platform.openh264\t2.2.0\ti\tflathub\t< 944.3 kB",
        "",
        "Installing 1/2…",
        "Installing 1/2…                        0%  0 bytes/s",
        "Installing 1/2… ██▌                    13%  42.6 kB/s",
    )

    @Test
    fun followsTheTableAndProgressLines() {
        val p = FlatpakProgress()
        assertNull(p.current)
        install.forEach { p.feed(it) }
        assertEquals(FlatpakProgress.Step("Installing", 1, 2, "org.freedesktop.Platform.openh264 2.0", 13), p.current)
        assertTrue(p.feed("Installing 2/2… ████████████████████ 100%  490.5 kB/s"))
        assertEquals(FlatpakProgress.Step("Installing", 2, 2, "org.freedesktop.Platform.openh264 2.2.0", 100), p.current)
        assertTrue(p.current!!.deploying)
        assertFalse(p.finishing)
        assertFalse(p.feed("Installation complete."))
        // Flatpak cleans up after its last step, with no further output.
        assertNull(p.current)
        assertTrue(p.finishing)
    }

    @Test
    fun deployingOnlyOnceDownloaded() {
        val p = FlatpakProgress()
        p.feed("Updating 5/10… ▌ 3%")
        assertFalse(p.current!!.deploying)
        p.feed("Changes complete.")
        assertTrue(p.finishing)
    }

    @Test
    fun progressWithoutATableHasNoRef() {
        val p = FlatpakProgress()
        p.feed("Updating 3/5…")
        assertEquals(FlatpakProgress.Step("Updating", 3, 5, null, null), p.current)
    }

    @Test
    fun aSingleOperationHasNoCount() {
        // flatpak prints "Installing…" rather than "Installing 1/1…" when there is one operation.
        val p = FlatpakProgress()
        p.feed("Looking for matches…")
        assertNull(p.current)
        p.feed(" 1.\t   \tcom.google.Chrome\tstable\ti\tflathub\t< 120 MB")
        assertTrue(p.feed("Installing… ███▌                 18%  2.1 MB/s"))
        assertEquals(FlatpakProgress.Step("Installing", 1, 1, "com.google.Chrome stable", 18), p.current)
        assertTrue(FlatpakProgress.sameStep("Installing…", "Installing… █ 5%"))
    }

    @Test
    fun repeatedProgressOfOneStepCanReplaceTheLastLine() {
        assertTrue(FlatpakProgress.sameStep("Installing 1/2…", "Installing 1/2… █ 5%"))
        assertFalse(FlatpakProgress.sameStep("Installing 1/2… 100%", "Installing 2/2…"))
        assertFalse(FlatpakProgress.sameStep("Looking for matches…", "Installing 1/2…"))
    }
}
