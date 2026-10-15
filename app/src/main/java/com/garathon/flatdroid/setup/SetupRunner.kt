package com.garathon.flatdroid.setup

/** Receives setup progress. Called from the setup thread. */
interface SetupReporter {
    fun stepStarted(step: SetupStep)
    /** [fraction] in 0..1, or null when the step can't tell. */
    fun progress(fraction: Float?, detail: String)
    fun log(line: String)
    fun stepFinished(step: SetupStep)
    fun failed(step: SetupStep, message: String)
}

/**
 * Runs the unfinished setup steps in order, recording each one as soon as it succeeds, so an
 * interrupted setup resumes at the step it stopped in. Stops at the first failure.
 */
class SetupRunner(
    private val store: SetupStateStore,
    private val perform: (SetupStep, SetupReporter) -> Unit,
) {
    /** True when every step is done. An [InterruptedException] (cancellation) is rethrown. */
    fun run(reporter: SetupReporter): Boolean {
        var state = store.load()
        for (step in SetupStep.entries) {
            if (step in state.completed) continue
            reporter.stepStarted(step)
            try {
                perform(step, reporter)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                reporter.failed(step, e.message ?: e.toString())
                return false
            }
            state = state.copy(completed = state.completed + step)
            store.save(state)
            reporter.stepFinished(step)
        }
        return true
    }
}
