package com.garathon.flatdroid.shortcuts

import com.garathon.flatdroid.session.SessionState
import com.garathon.flatdroid.shortcuts.ShortcutLaunch.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutLaunchTest {
    private val app = "org.xfce.mousepad"
    private val other = "org.gnome.Calculator"

    @Test
    fun nothingRunsSoItStarts() {
        assertEquals(Decision.Open, ShortcutLaunch.decide(app, SessionState.Idle, busy = false))
    }

    @Test
    fun theSameAppIsShown() {
        assertEquals(Decision.Open, ShortcutLaunch.decide(app, SessionState.Running(app), busy = false))
        assertEquals(Decision.Open, ShortcutLaunch.decide(app, SessionState.Starting(app, ""), busy = false))
        assertEquals(Decision.Open, ShortcutLaunch.decide(app, SessionState.FilesChanged(app, ""), busy = false))
    }

    @Test
    fun anotherAppIsRefused() {
        assertEquals(Decision.AppBusy(other, asking = false), ShortcutLaunch.decide(app, SessionState.Running(other), busy = false))
        assertEquals(Decision.AppBusy(other, asking = false), ShortcutLaunch.decide(app, SessionState.Stopping(other), busy = true))
        assertEquals(Decision.AppBusy(other, asking = true), ShortcutLaunch.decide(app, SessionState.ElectronOffer(other, "30", "x86_64", null), busy = false))
    }

    @Test
    fun aTaskIsRefused() {
        assertEquals(Decision.Busy, ShortcutLaunch.decide(app, SessionState.Idle, busy = true))
    }
}
