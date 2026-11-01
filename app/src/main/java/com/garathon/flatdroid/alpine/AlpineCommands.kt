package com.garathon.flatdroid.alpine

import android.content.Context
import com.garathon.flatdroid.flatpak.FlatpakTasks
import com.garathon.flatdroid.session.SessionController
import com.garathon.flatdroid.setup.SetupController

/**
 * When Alpine may run commands besides a session's helpers. Every process of Flatdroid's runs as
 * one user, so a running app sees them all in /proc, and proot keeps them dumpable: only the
 * helpers that run when the app starts are hidden from it. A command started during a session
 * (a Flatpak task, the terminal, applying settings) could have its memory opened by the app and
 * run the app's code in Alpine, with Alpine's files and network. So none starts while an app
 * runs, and no app starts while one runs. The terminal's developer setting
 * ([com.garathon.flatdroid.settings.UserSettings.terminalWithApps]) makes the only exception, for
 * the terminal itself.
 */
object AlpineCommands {
    /** A terminal command is running (set by the terminal's view model). */
    @Volatile var terminalRunning = false

    /** Whether the terminal and a running app may overlap now (the developer setting). */
    fun terminalWithApps(context: Context): Boolean = SetupController.settings(context).load()?.terminalWithApps == true

    /** Whether a terminal command may start now. */
    fun terminalMayRun(context: Context): Boolean = !SessionController.isBusy || terminalWithApps(context)

    /** Whether an app may start now: no Flatpak task, and no terminal command unless they may overlap. */
    fun sessionMayStart(context: Context): Boolean =
        FlatpakTasks.ui.value.running == null && (!terminalRunning || terminalWithApps(context))
}
