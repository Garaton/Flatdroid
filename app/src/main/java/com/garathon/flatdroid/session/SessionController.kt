package com.garathon.flatdroid.session

import com.garathon.flatdroid.flatpak.tasks.FlatpakTasks
import com.garathon.flatdroid.session.helpers.SessionScript
import com.garathon.flatdroid.session.helpers.WaylandVnc
import com.garathon.flatdroid.util.debug.DebugLog
import com.garathon.flatdroid.util.io.LineBuffer
import com.garathon.flatdroid.util.appScope
import com.garathon.flatdroid.viewer.ViewerConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the one Flatpak session is doing. */
sealed interface SessionState {
    data object Idle : SessionState
    /** [fraction]: how far the file check is in the deployment it is on, when it can tell. */
    data class Starting(val appId: String, val detail: String, val skippable: Boolean = false, val fraction: Float? = null) : SessionState
    /**
     * The app runs. Flatdroid's own viewer connects to [socket] (a host path); with the external
     * viewer setting there is none, and a viewer connects to localhost:[SessionScript.VNC_PORT]
     * with [password] (only then made), and [username] when the session is Wayland ([WaylandVnc]). [wayland]: the
     * display is labwc rather than Xvnc and Openbox. [followPercent]:
     * the screen is that share of the viewer's size and follows it (null: a fixed size).
     */
    data class Running(
        val appId: String,
        val password: String? = null,
        val username: String? = null,
        val socket: String? = null,
        val wayland: Boolean = false,
        val followPercent: Int? = null,
        /** The compositor's socket for [com.garathon.flatdroid.viewer.WaylandClient]; null on X11. */
        val waylandSocket: String? = null,
        val shareClipboard: Boolean = true,
        /** What the Wayland output refreshes at, per second; null to leave it. */
        val outputRefresh: Int? = null,
        /** No window is open and the app was paused for it ([HiddenPause.windows]): the user may let it go on or stop it. */
        val noWindow: Boolean = false,
        /** [noWindow], and no window was ever open: the app never started one. */
        val noWindowNeverOpened: Boolean = false,
        /** The app is still opening its first window (known only where the windows are counted, once the viewer connected). */
        val opening: Boolean = false,
    ) : SessionState
    data class Stopping(val appId: String) : SessionState
    /**
     * The app ships Electron [version] for [fromArch]; the user is asked before Flatdroid
     * downloads one for this device. [problem] is set when a swap can't work.
     */
    data class ElectronOffer(val appId: String, val version: String?, val fromArch: String, val problem: String?) : SessionState, Question
    /** The agreed Electron swap is being downloaded and put in place. */
    data class PreparingElectron(val appId: String, val detail: String, val fraction: Float?) : SessionState
    /**
     * The file check found changed files before launch; [detail] lists the affected packages and
     * the launch was refused. The reinstall path restores them by [appId] (see FlatpakTask.Reinstall).
     */
    data class FilesChanged(val appId: String, val detail: String) : SessionState, Question

    /** No session runs: a launch stopped to ask the user something, which stays up until answered. */
    sealed interface Question

    val appIdOrNull: String?
        get() = when (this) {
            Idle -> null
            is Starting -> appId
            is Running -> appId
            is Stopping -> appId
            is ElectronOffer -> appId
            is PreparingElectron -> appId
            is FilesChanged -> appId
        }
}

data class SessionUi(
    val state: SessionState = SessionState.Idle,
    /** How the last session ended, or why it couldn't start. */
    val message: String? = null,
    /** [message] is an error. */
    val failed: Boolean = false,
    /** The app that wants the microphone and waits for the user's answer, if any. */
    val micRequest: String? = null,
)

/** Process-wide session state: written by [SessionService], read by the UI. */
object SessionController {
    private val _ui = MutableStateFlow(SessionUi())
    val ui: StateFlow<SessionUi> = _ui.asStateFlow()
    private val logBuffer = LineBuffer(maxLines = 2_000, scope = appScope)
    /** The last session's output: the app's, and the display helpers' (prefixed). */
    val log: StateFlow<List<String>> = logBuffer.lines

    /** Where answers to [SessionUi.micRequest] go: the running session's microphone, while one has it. */
    @Volatile var onMicrophoneAnswer: ((Boolean) -> Unit)? = null

    val isBusy: Boolean get() = _ui.value.state !is SessionState.Idle && _ui.value.state !is SessionState.Question

    /** Also clears a finished Flatpak task's banner: one banner at a time. */
    fun starting(appId: String, detail: String, clearLog: Boolean = false, skippable: Boolean = false, fraction: Float? = null) {
        FlatpakTasks.dismiss()
        if (clearLog) logBuffer.clear()
        _ui.update { it.copy(state = SessionState.Starting(appId, detail, skippable, fraction), message = null, failed = false) }
    }

    /** Set while the file check runs; the Skip button calls [skipFileCheck]. */
    @Volatile var onSkipFileCheck: (() -> Unit)? = null
    /** The viewer's display took a new size (width, height), while a session with cameras runs. */
    @Volatile var onDisplayResized: ((Int, Int) -> Unit)? = null
    fun skipFileCheck() { onSkipFileCheck?.invoke() }

    fun running(state: SessionState.Running) = _ui.update { it.copy(state = state) }

    /** The running app was paused for having no window ([on]), or goes on again. */
    fun noWindow(on: Boolean, neverOpened: Boolean = false) = _ui.update {
        val state = it.state as? SessionState.Running
        val never = on && neverOpened
        if (state == null || state.noWindow == on && state.noWindowNeverOpened == never) it
        else it.copy(state = state.copy(noWindow = on, noWindowNeverOpened = never, opening = state.opening && !on))
    }

    /** The app has no window yet and never had one ([on]), or has one now. */
    fun opening(on: Boolean) = _ui.update {
        val state = it.state as? SessionState.Running
        if (state == null || state.opening == on) it else it.copy(state = state.copy(opening = on))
    }

    private val _keepScreenOn = MutableStateFlow(false)
    /** The running app holds an inhibit against the screen's timeout: Flatdroid's viewer keeps the screen on while visible. */
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()
    fun keepScreenOn(on: Boolean) { _keepScreenOn.value = on }

    private val _viewerVisible = MutableStateFlow(false)
    /** Flatdroid's viewer is on screen: the question about a window-less app is asked there, else in a notification. */
    val viewerVisible: StateFlow<Boolean> = _viewerVisible.asStateFlow()

    /** The one place that says whether the viewer is on screen: the flow above and [HiddenPause] follow it. Main thread. */
    fun viewerVisible(visible: Boolean) {
        _viewerVisible.value = visible
        HiddenPause.viewerShown(visible)
    }

    fun stopping(appId: String) {
        ViewerConnection.closeOnMain()
        _ui.update { it.copy(state = SessionState.Stopping(appId)) }
    }

    fun offerElectron(offer: SessionState.ElectronOffer) = _ui.update { it.copy(state = offer) }

    fun filesChanged(state: SessionState.FilesChanged) = _ui.update { it.copy(state = state, message = null) }

    fun preparingElectron(appId: String, detail: String, fraction: Float?) =
        _ui.update { it.copy(state = SessionState.PreparingElectron(appId, detail, fraction), message = null) }

    /** Back to idle, with [message] describing the outcome (null for a plain stop). */
    fun ended(message: String?, failed: Boolean = false) {
        ViewerConnection.closeOnMain()
        message?.let { DebugLog.i("Session: $it") }
        _keepScreenOn.value = false
        _ui.update { it.copy(state = SessionState.Idle, message = message, failed = failed && message != null, micRequest = null) }
    }

    fun requestMicrophone(appId: String?) = _ui.update { it.copy(micRequest = appId) }

    /** The user's answer to [SessionUi.micRequest]. */
    fun answerMicrophone(allow: Boolean) {
        val answer = onMicrophoneAnswer
        if (answer == null) requestMicrophone(null) else answer(allow)
    }

    fun dismissMessage() = _ui.update { it.copy(message = null, failed = false) }

    fun log(line: String) {
        DebugLog.i(line)
        logBuffer.add(line)
    }
}
