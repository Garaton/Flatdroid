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
        val state = SetupState(setOf(SetupStep.ROOTFS, SetupStep.FLATHUB), settingsApplied = true)
        store.save(state)
        assertEquals(state, store.load())
        assertFalse(SetupState().settingsApplied)
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
    fun apkProgressLines() {
        assertEquals(12 to 186, ApkProgress.parse("( 12/186) Installing glib (2.88.1-r1)"))
        assertEquals(186 to 186, ApkProgress.parse("(186/186) Installing tigervnc (1.16.2-r0)"))
        assertNull(ApkProgress.parse("Executing busybox-1.37.0-r31.trigger"))
        assertNull(ApkProgress.parse("2 errors; 380.3 MiB in 202 packages"))
    }
}
