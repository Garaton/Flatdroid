package com.garathon.flatdroid.session

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.garathon.flatdroid.audio.AudioPlayer
import com.garathon.flatdroid.util.DebugLog
import com.garathon.flatdroid.util.appScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * What a running app does while Flatdroid's viewer isn't on screen, unless the app's "Continue while
 * hidden" is on: its sound is muted at once, and after [GRACE_MS] it is paused until the viewer is
 * back, so it uses no CPU meanwhile. Nothing new runs for it: the wait is a coroutine on the shared
 * threads. With an external viewer Flatdroid can't tell what is on screen, so nothing happens.
 *
 * The pause is two signals: SIGSTOP to the app's proot first, then to every process it traces. A
 * traced process that gets a signal waits for its tracer, which, stopped, can't let it go (SIGSTOP
 * to the processes alone does nothing: proot gets the signal first and lets them run on). SIGCONT to
 * proot lets every one of them go on; they get one too, in case.
 */
object HiddenPause {
    /** How long the viewer stays hidden before the app is paused: switching to another app and back for a moment doesn't pause it. */
    const val GRACE_MS = 30_000L

    private class Session(val prootPid: Int, val player: AudioPlayer?)

    private var session: Session? = null
    private var shown = false
    private var paused = false
    private var waiting: Job? = null

    /** A session started whose app follows this ([continueWhileHidden] off, Flatdroid's own viewer). Not on the main thread. */
    @Synchronized
    fun attach(prootPid: Int, player: AudioPlayer?) {
        session = Session(prootPid, player)
        paused = false
        if (!shown) hidden()
    }

    /** The session is ending: the app is let go on (a stopped process ignores the signals that end it) and the sound comes back. */
    @Synchronized
    fun detach() {
        waiting?.cancel()
        waiting = null
        session?.let { resume(it) }
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
            resume(now)
        } else {
            hidden()
        }
    }

    private fun hidden() {
        val now = session ?: return
        now.player?.muted = true
        waiting?.cancel()
        waiting = appScope.launch(Dispatchers.Default) {
            delay(GRACE_MS)
            synchronized(this@HiddenPause) {
                if (session === now && !shown && !paused) pause(now)
            }
        }
    }

    private fun pause(now: Session) {
        if (!signal(now.prootPid, OsConstants.SIGSTOP)) return
        val traced = tracedBy(now.prootPid)
        traced.forEach { signal(it, OsConstants.SIGSTOP) }
        paused = true
        DebugLog.i("Hidden: the app is paused (${traced.size} processes)")
    }

    private fun resume(now: Session) {
        now.player?.muted = false
        if (!paused) return
        paused = false
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

    /** The processes [tracer] traces, from `/proc/<pid>/status` (`TracerPid:`): the app's, however they were started. */
    private fun tracedBy(tracer: Int): List<Int> = File("/proc").list().orEmpty().mapNotNull { name ->
        val pid = name.toIntOrNull() ?: return@mapNotNull null
        val status = runCatching { File("/proc/$name/status").readText() }.getOrNull() ?: return@mapNotNull null
        val tracerPid = status.lineSequence().firstOrNull { it.startsWith("TracerPid:") }?.substringAfter(':')?.trim()?.toIntOrNull()
        pid.takeIf { tracerPid == tracer }
    }
}
