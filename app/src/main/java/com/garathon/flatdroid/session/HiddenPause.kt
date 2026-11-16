package com.garathon.flatdroid.session

import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.garathon.flatdroid.audio.AudioPlayer
import com.garathon.flatdroid.proot.processes.ProcTable
import com.garathon.flatdroid.util.debug.DebugLog
import com.garathon.flatdroid.util.appScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What a running app does while Flatdroid's viewer isn't on screen, unless the app's "Continue while
 * hidden" is on ([attach]'s pauseWhenHidden): its sound is muted at once, and after [GRACE_MS] it is
 * paused until the viewer is back, so it uses no CPU meanwhile. Nothing new runs for it: the wait is a
 * coroutine on the shared threads. With an external viewer Flatdroid can't tell what is on screen, so
 * nothing happens.
 *
 * A second reason to pause, for every app: no window is open ([windows], [NoWindowWait]). That
 * pause holds whether the viewer is on screen or not, until the user lets the app go on
 * ([resumeNoWindow]) or stops it, and the viewer coming back doesn't undo it.
 *
 * The pause is two signals: SIGSTOP to the app's proot first, then to every process it traces. A
 * traced process that gets a signal waits for its tracer, which, stopped, can't let it go (SIGSTOP
 * to the processes alone does nothing: proot gets the signal first and lets them run on). SIGCONT to
 * proot lets every one of them go on; they get one too, in case.
 */
object HiddenPause {
    /** How long the viewer stays hidden before the app is paused: switching to another app and back for a moment doesn't pause it. */
    const val GRACE_MS = 30_000L

    private class Session(val prootPid: Int, val player: AudioPlayer?, val pauseWhenHidden: Boolean, val wait: NoWindowWait)

    private var session: Session? = null
    private var shown = false
    /** The app's processes are stopped (for either reason). */
    private var stopped = false
    /** The viewer has been hidden for [GRACE_MS]. */
    private var hiddenStop = false
    /** No window was open for the wait: the user is asked ([SessionState.Running.noWindow]). */
    private var noWindowStop = false
    private var waiting: Job? = null
    private var windowWait: Job? = null

    /**
     * A session started with Flatdroid's own viewer. With [pauseWhenHidden] (the app's "Continue
     * while hidden" off) it also goes quiet and pauses while hidden. Not on the main thread.
     */
    @Synchronized
    fun attach(prootPid: Int, player: AudioPlayer?, pauseWhenHidden: Boolean) {
        session = Session(prootPid, player, pauseWhenHidden, NoWindowWait(SystemClock.elapsedRealtime()))
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

    /** Flatdroid's viewer became visible or hidden. Main thread. */
    @Synchronized
    fun viewerShown(visible: Boolean) {
        if (visible == shown) return
        shown = visible
        val now = session ?: return
        if (visible) {
            waiting?.cancel()
            waiting = null
            hiddenStop = false
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
        val deadline = now.wait.windows(count, SystemClock.elapsedRealtime())
        SessionController.opening(now.wait.empty && !now.wait.opened && !noWindowStop)
        if (count > 0 && noWindowStop) {
            // Something opened a window anyway (an app that went on by itself): nothing to ask any more.
            noWindowStop = false
            SessionController.noWindow(false)
            settle(now)
        }
        arm(now, deadline)
    }

    /** The user chose to let the app go on with no window: the next pause waits twice as long. */
    @Synchronized
    fun resumeNoWindow() {
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
                if (session !== now || noWindowStop || !now.wait.empty) return@synchronized
                if (!stop(now)) return@synchronized
                noWindowStop = true
                settle(now)
                DebugLog.i("No window: the app is paused after ${now.wait.waitMs} ms")
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
            delay(GRACE_MS)
            synchronized(this@HiddenPause) {
                if (session === now && !shown && !hiddenStop && stop(now)) hiddenStop = true
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
        if (!signal(now.prootPid, OsConstants.SIGSTOP)) return false
        val traced = tracedBy(now.prootPid)
        traced.forEach { signal(it, OsConstants.SIGSTOP) }
        stopped = true
        DebugLog.i("Paused: the app is stopped (${traced.size} processes)")
        return true
    }

    private fun go(now: Session) {
        if (!stopped) return
        stopped = false
        signal(now.prootPid, OsConstants.SIGCONT)
        tracedBy(now.prootPid).forEach { signal(it, OsConstants.SIGCONT) }
        DebugLog.i("Shown: the app goes on")
    }

    private fun signal(pid: Int, signal: Int): Boolean = try {
        Os.kill(pid, signal)
        true
    } catch (e: ErrnoException) {
        DebugLog.w("Signal $signal to $pid: $e")
        false
    }

    /** The processes [tracer] traces (`TracerPid:` in `/proc/<pid>/status`): the app's, however they were started. */
    private fun tracedBy(tracer: Int): List<Int> = ProcTable.read().filterValues { it.tracer == tracer }.keys.toList()
}
