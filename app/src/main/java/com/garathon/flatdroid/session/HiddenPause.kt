package com.garathon.flatdroid.session

import android.os.SystemClock
import com.garathon.flatdroid.audio.AudioPlayer
import com.garathon.flatdroid.proot.processes.ProotFreeze
import com.garathon.flatdroid.util.debug.DebugLog
import com.garathon.flatdroid.util.appScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What a running app does while Flatdroid's viewer isn't on screen, unless the app's "Continue while
 * hidden" is on ([attach]'s pauseWhenHidden): its sound is muted at once, and after [GRACE_MS] it is
 * paused until the viewer is back, so it uses no CPU meanwhile. With the screen off there is no grace
 * ([screenOff]): nobody switches back to it in a moment then, and some devices freeze Flatdroid's own
 * process soon after the screen goes off (its children run on), before the grace would end. Nothing new runs for it: the wait is a
 * coroutine on the shared threads. With an external viewer Flatdroid can't tell what is on screen, so
 * nothing happens.
 *
 * A second reason to pause, for every app: no window is open ([windows], [NoWindowWait]). That
 * pause holds whether the viewer is on screen or not, until the user lets the app go on
 * ([resumeNoWindow]) or stops it, and the viewer coming back doesn't undo it. An app with no
 * window open is never paused for being hidden: this reason, which asks the user, decides, so an app
 * that hasn't opened its first window yet keeps running until the question comes (two minutes). Where
 * nobody counts the windows (the viewer never connected), that time passes before the hidden pause.
 *
 * The pause is [ProotFreeze]'s. The sound player stops reading meanwhile ([AudioPlayer.paused]).
 */
object HiddenPause {
    /** How long the viewer stays hidden before the app is paused: switching to another app and back for a moment doesn't pause it. */
    const val GRACE_MS = 30_000L

    private class Session(
        val prootPid: Int,
        val player: AudioPlayer?,
        val pauseWhenHidden: Boolean,
        val wait: NoWindowWait,
        val onPaused: ((Boolean) -> Unit)?,
    )

    private var session: Session? = null
    private var shown = false
    /** The app's processes are stopped (for either reason). */
    private var stopped = false
    /** The viewer has been hidden for [GRACE_MS]. */
    private var hiddenStop = false
    /** When [hiddenStop] began, on [SystemClock.elapsedRealtime]'s clock. */
    private var hiddenStopAt = 0L
    /** No window was open for the wait: the user is asked ([SessionState.Running.noWindow]). */
    private var noWindowStop = false
    private var waiting: Job? = null
    private var windowWait: Job? = null
    /** Android's screen is off ([screenOff]). */
    @Volatile private var screenIsOff = false

    /**
     * A session started with Flatdroid's own viewer. With [pauseWhenHidden] (the app's "Continue
     * while hidden" off) it also goes quiet and pauses while hidden. [onPaused] hears each pause (true)
     * and each time the app goes on (false), so what feeds the app (its cameras, its microphone) can stop meanwhile.
     * Not on the main thread.
     */
    @Synchronized
    fun attach(prootPid: Int, player: AudioPlayer?, pauseWhenHidden: Boolean, onPaused: ((Boolean) -> Unit)? = null) {
        session = Session(prootPid, player, pauseWhenHidden, NoWindowWait(SystemClock.elapsedRealtime()), onPaused)
        stopped = false
        hiddenStop = false
        noWindowStop = false
        windowWait?.cancel()
        windowWait = null
        if (!shown) hidden()
    }

    /** Whether [windows] counts for the running app. */
    @get:Synchronized
    val attached: Boolean get() = session != null

    /** The session is ending: the app is let go on (a stopped process ignores the signals that end it) and the sound comes back. */
    @Synchronized
    fun detach() {
        waiting?.cancel()
        waiting = null
        windowWait?.cancel()
        windowWait = null
        session?.let {
            hiddenStop = false
            noWindowStop = false
            settle(it)
        }
        session = null
    }

    /**
     * The screen went off ([off]) or on. Off, a hidden app pauses now instead of after [GRACE_MS] (and
     * one shown is paused as soon as the viewer hides). On changes nothing: the viewer's return does. Main thread.
     */
    @Synchronized
    fun screenOff(off: Boolean) {
        if (off == screenIsOff) return
        screenIsOff = off
        if (off && !shown) hidden()
    }

    /** Flatdroid's viewer became visible or hidden. Main thread. */
    @Synchronized
    fun viewerShown(visible: Boolean) {
        if (visible == shown) return
        shown = visible
        val now = session ?: return
        if (visible) {
            waiting?.cancel()
            waiting = null
            if (hiddenStop) {
                // The no-window wait didn't run while the app was stopped for being hidden: it goes on from here.
                hiddenStop = false
                now.wait.suspended(hiddenStopAt, SystemClock.elapsedRealtime())
                arm(now, now.wait.deadline)
            }
            settle(now)
        } else {
            hidden()
        }
    }

    /**
     * [count] windows are open now (from the compositor, on its own thread). With none for the wait,
     * the app is paused and [SessionController.noWindow] says so.
     */
    @Synchronized
    fun windows(count: Int) {
        val now = session ?: return
        val wasEmpty = now.wait.empty
        val deadline = now.wait.windows(count, SystemClock.elapsedRealtime())
        SessionController.opening(now.wait.empty && !now.wait.opened)
        if (count > 0 && noWindowStop) {
            // Something opened a window anyway (an app that went on by itself): nothing to ask any more.
            noWindowStop = false
            SessionController.noWindow(false)
            settle(now)
        }
        // A window opened while the viewer is hidden: the app has something to pause now, so its grace starts.
        if (wasEmpty && count > 0 && !shown && !hiddenStop) hidden()
        arm(now, deadline)
    }

    /**
     * The user chose to let the app go on with no window: the next pause waits twice as long. From the
     * main thread (a button): the signals and the process table go on the shared threads.
     */
    fun resumeNoWindow() {
        appScope.launch(Dispatchers.Default) { resume() }
    }

    @Synchronized
    private fun resume() {
        val now = session ?: return
        if (!noWindowStop) return
        noWindowStop = false
        SessionController.noWindow(false)
        SessionController.opening(now.wait.empty && !now.wait.opened)
        settle(now)
        arm(now, now.wait.resumed(SystemClock.elapsedRealtime()))
    }

    /** Pauses the app when [deadline] (on [SystemClock.elapsedRealtime]'s clock) comes with still no window; null: none. */
    private fun arm(now: Session, deadline: Long?) {
        windowWait?.cancel()
        windowWait = null
        if (deadline == null) return
        windowWait = appScope.launch(Dispatchers.Default) {
            delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            synchronized(this@HiddenPause) {
                // Stopped for being hidden: the wait goes on when the viewer is back ([viewerShown]).
                if (session !== now || noWindowStop || hiddenStop || !now.wait.empty) return@synchronized
                if (!stop(now)) return@synchronized
                noWindowStop = true
                settle(now)
                DebugLog.i { "No window: the app is paused after ${now.wait.waitMs} ms" }
                SessionController.noWindow(true, neverOpened = !now.wait.opened)
            }
        }
    }

    private fun hidden() {
        val now = session ?: return
        if (!now.pauseWhenHidden) return
        settle(now)
        waiting?.cancel()
        waiting = appScope.launch(Dispatchers.Default) {
            if (!screenIsOff) delay(GRACE_MS)
            // An app nobody counts the windows of (the viewer never connected) keeps running until its
            // first-window time is over: a slow start has no window for long, and stopping it stalls it.
            now.wait.firstWindowLeft(SystemClock.elapsedRealtime()).takeIf { it > 0 }?.let { delay(it) }
            synchronized(this@HiddenPause) {
                if (session !== now || shown || hiddenStop) return@synchronized
                // Windows counted and none open: the wait for a window decides (it asks the user, who can let the app
                // go on). Stopping it here as well would hide that question, and Resume would change nothing.
                if (now.wait.known && now.wait.empty) return@synchronized
                if (stop(now)) {
                    hiddenStop = true
                    hiddenStopAt = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    /** The sound and the signals follow the reasons that hold. */
    private fun settle(now: Session) {
        now.player?.muted = (!shown && now.pauseWhenHidden) || noWindowStop
        if (hiddenStop || noWindowStop) stop(now) else go(now)
    }

    private fun stop(now: Session): Boolean {
        if (stopped) return true
        val traced = ProotFreeze.stop(now.prootPid) ?: return false
        stopped = true
        now.player?.paused = true
        now.onPaused?.invoke(true)
        DebugLog.i { "Paused: the app is stopped ($traced processes)" }
        return true
    }

    private fun go(now: Session) {
        if (!stopped) return
        stopped = false
        now.player?.paused = false
        ProotFreeze.go(now.prootPid)
        now.onPaused?.invoke(false)
        DebugLog.i("Shown: the app goes on")
    }
}
