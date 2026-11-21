package com.garathon.flatdroid.shortcuts

import com.garathon.flatdroid.session.SessionState

/** What a home-screen shortcut's tap does, as a tap in Flatdroid's list would: one app at a time. */
object ShortcutLaunch {
    sealed interface Decision {
        /** Flatdroid's window opens: it starts the app, or shows it when it is already there. */
        data object Open : Decision
        /** Another app runs or starts ([asking]: Flatdroid waits for an answer about it). */
        data class AppBusy(val appId: String, val asking: Boolean) : Decision
        /** A Flatpak task (or a terminal command) runs. */
        data object Busy : Decision
    }

    fun decide(appId: String, session: SessionState, busy: Boolean): Decision {
        val other = session.appIdOrNull
        return when {
            other == appId -> Decision.Open
            other != null -> Decision.AppBusy(other, asking = session is SessionState.Question)
            busy -> Decision.Busy
            else -> Decision.Open
        }
    }
}
