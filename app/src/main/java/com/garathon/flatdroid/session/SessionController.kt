package com.garathon.flatdroid.session

import com.garathon.flatdroid.flatpak.FlatpakTasks
import com.garathon.flatdroid.util.DebugLog
import com.garathon.flatdroid.util.LineBuffer
import com.garathon.flatdroid.util.appScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the one Flatpak session is doing. */
sealed interface SessionState {
    data object Idle : SessionState
    data class Starting(val appId: String, val detail: String, val skippable: Boolean = false) : SessionState
    /** The app runs; the viewer connects to localhost:[SessionScript.VNC_PORT] with [password]. */
    data class Running(val appId: String, val password: String) : SessionState
    data class Stopping(val appId: String) : SessionState
    /**
     * The app ships Electron [version] for [fromArch]; the user is asked before Flatdroid
     * downloads one for this device. [problem] is set when a swap can't work.
     */
    data class ElectronOffer(val appId: String, val version: String?, val fromArch: String, val problem: String?) : SessionState
    data class PreparingElectron(val appId: String, val detail: String, val fraction: Float?) : SessionState
    /**
     * The file check found changed files before launch; [detail] lists the affected packages and
     * the launch was refused. The reinstall path restores them by [appId] (see FlatpakTask.Reinstall).
     */
    data class FilesChanged(val appId: String, val detail: String) : SessionState

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

    val isBusy: Boolean get() = _ui.value.state !is SessionState.Idle && _ui.value.state !is SessionState.ElectronOffer && _ui.value.state !is SessionState.FilesChanged

    /** Also clears a finished Flatpak task's banner: one banner at a time. */
    fun starting(appId: String, detail: String, clearLog: Boolean = false, skippable: Boolean = false) {
        FlatpakTasks.dismiss()
        if (clearLog) logBuffer.clear()
        _ui.update { it.copy(state = SessionState.Starting(appId, detail, skippable), message = null, failed = false) }
    }

    /** Set while the file check runs; the Skip button calls [skipFileCheck]. */
    @Volatile var onSkipFileCheck: (() -> Unit)? = null
    fun skipFileCheck() { onSkipFileCheck?.invoke() }

    fun running(appId: String, password: String) = _ui.update { it.copy(state = SessionState.Running(appId, password)) }

    fun stopping(appId: String) = _ui.update { it.copy(state = SessionState.Stopping(appId)) }

    fun offerElectron(offer: SessionState.ElectronOffer) = _ui.update { it.copy(state = offer) }

    fun filesChanged(state: SessionState.FilesChanged) = _ui.update { it.copy(state = state, message = null) }

    fun preparingElectron(appId: String, detail: String, fraction: Float?) =
        _ui.update { it.copy(state = SessionState.PreparingElectron(appId, detail, fraction), message = null) }

    /** Back to idle, with [message] describing the outcome (null for a plain stop). */
    fun ended(message: String?, failed: Boolean = false) {
        message?.let { DebugLog.i("Session: $it") }
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
