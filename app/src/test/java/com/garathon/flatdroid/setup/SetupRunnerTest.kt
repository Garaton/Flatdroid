package com.garathon.flatdroid.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SetupRunnerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Recorder : SetupReporter {
        val events = mutableListOf<String>()
        override fun stepStarted(step: SetupStep) { events += "start $step" }
        override fun progress(fraction: Float?, detail: String) {}
        override fun log(line: String) {}
        override fun stepFinished(step: SetupStep) { events += "done $step" }
        override fun failed(step: SetupStep, message: String) { events += "failed $step: $message" }
    }

    private fun store() = SetupStateStore(File(tmp.root, "setup.properties"))

    @Test
    fun runsAllStepsInOrderAndSavesEach() {
        val ran = mutableListOf<SetupStep>()
        val store = store()
        assertTrue(SetupRunner(store) { step, _ -> ran += step }.run(Recorder()))
        assertEquals(SetupStep.entries, ran)
        assertTrue(store.load().isComplete)
    }

    @Test
    fun stopsAtFailureAndResumesThere() {
        val store = store()
        var failPackages = true
        val ran = mutableListOf<SetupStep>()
        val runner = SetupRunner(store) { step, _ ->
            ran += step
            if (step == SetupStep.PACKAGES && failPackages) error("no network")
        }

        val first = Recorder()
        assertFalse(runner.run(first))
        assertEquals(listOf(SetupStep.ROOTFS, SetupStep.PACKAGES), ran)
        assertEquals("failed PACKAGES: no network", first.events.last())
        assertEquals(setOf(SetupStep.ROOTFS), store.load().completed)
        assertEquals(SetupStep.PACKAGES, store.load().next)

        ran.clear()
        failPackages = false
        assertTrue(runner.run(Recorder()))
        assertEquals(SetupStep.entries.drop(1), ran)
    }

    @Test(expected = InterruptedException::class)
    fun cancellationIsNotAFailure() {
        SetupRunner(store()) { _, _ -> throw InterruptedException() }.run(Recorder())
    }

    @Test
    fun stateRoundTrips() {
        val store = store()
        assertEquals(SetupState(), store.load())
        val state = SetupState(setOf(SetupStep.ROOTFS, SetupStep.FLATHUB), settingsChosen = true)
        store.save(state)
        assertEquals(state, store.load())
        assertFalse(SetupState().settingsChosen)
        assertEquals(SetupStep.PACKAGES, state.next)
    }

    @Test
    fun apkSummaryAndScriptErrors() {
        val ok = ApkSummary.parse(listOf("(186/186) Installing tigervnc (1.16.2-r0)", "Executing dbus-1.16.2-r2.trigger", "OK: 375.8 MiB in 202 packages"))
        assertTrue(ok.ok)
        assertTrue(ok.errors.isEmpty())

        val failed = ApkSummary.parse(listOf(
            "ERROR: flatpak-1.16.6-r2.post-install: fork: Function not implemented",
            "( 70/186) Installing libgpg-error (1.61-r0)",
            "ERROR: glib-2.88.1-r1.trigger: fork: Function not implemented",
            "15 errors; 380.3 MiB in 202 packages",
        ))
        assertFalse(failed.ok)
        assertEquals("15 errors; 380.3 MiB in 202 packages", failed.line)
        assertEquals(2, failed.errors.size)

        assertFalse(ApkSummary.parse(listOf("ERROR: unable to select packages:")).ok)
    }

    @Test
    fun anOfflineRunIsNotMistakenForASuccessfulOne() {
        // Offline with every package installed already, apk can still end with OK; these lines tell.
        val offline = ApkSummary.parse(listOf(
            "WARNING: updating and opening https://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64/APKINDEX.tar.gz: DNS: transient error (try again later)",
            "ERROR: Not continuing due to stale/unavailable repositories. Use --force-missing-repositories to continue.",
            "OK: 375.8 MiB in 202 packages",
        ))
        assertTrue(offline.ok)
        assertEquals(1, offline.networkErrors.size)
        assertTrue(offline.errors.isEmpty())

        // A line alone tells, so a run can stop at the first one.
        assertTrue(ApkSummary.isNetworkError("WARNING: updating and opening https://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64/APKINDEX.tar.gz: DNS: transient error (try again later)"))
        assertFalse(ApkSummary.isNetworkError("( 12/186) Installing glib (2.88.1-r1)"))
        // Any other apk error stops a run too, but not a package's script error, which `apk fix` runs again.
        assertTrue(ApkSummary.isFatalError("ERROR: No space left on device"))
        assertTrue(ApkSummary.isFatalError("ERROR: flatpak-1.16.6-r2: BAD signature"))
        assertFalse(ApkSummary.isFatalError("ERROR: flatpak-1.16.6-r2.post-install: fork: Function not implemented"))
        assertFalse(ApkSummary.isFatalError("ERROR: glib-2.88.1-r1.trigger: exit 1"))
        assertFalse(ApkSummary.isFatalError("WARNING: x: temporary error"))
        assertFalse(ApkSummary.isFatalError("( 12/186) Installing glib (2.88.1-r1)"))
        assertEquals(1, ApkSummary.parse(listOf("ERROR: unable to select packages:")).networkErrors.size)
        assertEquals(1, ApkSummary.parse(listOf("fetch https://example.org/APKINDEX.tar.gz", "WARNING: x: temporary error (try again later)")).networkErrors.size)
        // A package's own script error is not a network error (apk fix handles it).
        assertTrue(ApkSummary.parse(listOf("ERROR: glib-2.88.1-r1.trigger: fork: Function not implemented", "1 error; 380.3 MiB in 202 packages")).networkErrors.isEmpty())
        assertTrue(ApkSummary.parse(listOf("fetch https://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64/APKINDEX.tar.gz", "OK: 1 MiB in 2 packages")).networkErrors.isEmpty())
    }

    @Test
    fun apkProgressLines() {
        assertEquals(12 to 186, ApkProgress.parse("( 12/186) Installing glib (2.88.1-r1)"))
        assertEquals(186 to 186, ApkProgress.parse("(186/186) Installing tigervnc (1.16.2-r0)"))
        assertNull(ApkProgress.parse("Executing busybox-1.37.0-r31.trigger"))
        assertNull(ApkProgress.parse("2 errors; 380.3 MiB in 202 packages"))
    }
}
