package com.garathon.flatdroid.alpine

import com.garathon.flatdroid.flatpak.tasks.FlatpakTasks

/**
 * When Alpine may run commands besides a session's helpers. Every process of Flatdroid's runs as
 * one user, so a running app sees them all in /proc, and proot keeps them dumpable: only the
 * helpers that run when the app starts are hidden from it. A command started during a session
 * (a Flatpak task, applying settings) could have its memory opened by the app and run the app's
 * code in Alpine, with Alpine's files and network. So none starts while an app runs, and no app
 * starts while one runs.
 */
object AlpineCommands {
    /** Whether an app may start now: no Flatpak task runs. */
    fun sessionMayStart(): Boolean = FlatpakTasks.ui.value.running == null
}
