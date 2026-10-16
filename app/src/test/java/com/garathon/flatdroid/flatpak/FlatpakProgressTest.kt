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
        assertFalse(p.feed("Installation complete."))
    }

    @Test
    fun progressWithoutATableHasNoRef() {
        val p = FlatpakProgress()
        p.feed("Updating 3/5…")
        assertEquals(FlatpakProgress.Step("Updating", 3, 5, null, null), p.current)
    }

    @Test
    fun repeatedProgressOfOneStepCanReplaceTheLastLine() {
        assertTrue(FlatpakProgress.sameStep("Installing 1/2…", "Installing 1/2… █ 5%"))
        assertFalse(FlatpakProgress.sameStep("Installing 1/2… 100%", "Installing 2/2…"))
        assertFalse(FlatpakProgress.sameStep("Looking for matches…", "Installing 1/2…"))
    }
}
