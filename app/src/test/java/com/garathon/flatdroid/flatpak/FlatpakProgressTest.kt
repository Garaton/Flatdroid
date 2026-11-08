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

    @Test
    fun alpinePackagesAreSteps() {
        val progress = FlatpakProgress()
        assertTrue(progress.feed("(  3/186) Upgrading glib (2.88.1-r1 -> 2.88.2-r0)"))
        assertEquals(FlatpakProgress.Step("Upgrading", 3, 186, "glib", 1), progress.current)
        assertTrue(progress.feed("(186/186) Installing wayvnc (0.10.0-r0)"))
        assertEquals(99, progress.current?.percent)
        assertFalse(progress.current!!.deploying)
        assertTrue(progress.feed("(1/1) Purging libfoo (1.0-r0)"))
        assertEquals(0, progress.current?.percent)
        assertFalse(progress.feed("Executing busybox-1.37.0-r30.trigger"))
        assertFalse(progress.feed("OK: 521.1 MiB in 329 packages"))
    }

    @Test
    fun onlyStatusLinesReachTheStatus() {
        for (line in listOf("Looking for updates…", "Looking for matches…", "Nothing to do.", "Nothing unused to uninstall",
                "Installation complete.", "Changes complete.", "Skipping: org.xfce.mousepad/aarch64/stable is already installed")) {
            assertTrue(line, FlatpakProgress.isStatus(line))
        }
        for (line in listOf(
            "Info: runtime org.freedesktop.Platform branch 24.08 is end-of-life, with reason:",
            "   org.freedesktop.Platform 24.08 is no longer receiving fixes and security updates. Please update to a supported runtime version.",
            "Info: applications using this runtime:",
            "   dev.bambosh.UnofficialHomestuckCollection",
            "Warning: Commit metadata for runtime/org.freedesktop.Platform/aarch64/24.08 not matching expected metadata",
            "These runtimes in installation 'user' are pinned and won't be removed; see flatpak-pin(1):",
            "  runtime/org.freedesktop.Platform/aarch64/26.08",
            " 1.           org.xfce.mousepad.Locale    stable    i    flathub    < 2.3 MB (partial)",
            "(1/1) Upgrading libexpat (2.9.0-r0 -> 2.10.0-r0)",
            "OK: 521.1 MiB in 329 packages",
            "",
        )) {
            assertFalse(line, FlatpakProgress.isStatus(line))
        }
    }
}
