package com.garathon.flatdroid.setup

import android.content.Context
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.flatpak.AppData
import com.garathon.flatdroid.launcher.ElectronSwap
import com.garathon.flatdroid.settings.UserSettingsStore
import com.garathon.flatdroid.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.garathon.flatdroid.storage.TempHomes
import java.io.File

/** Setup as the UI shows it. */
data class SetupUi(
    val running: Boolean = false,
    val completed: Set<SetupStep> = emptySet(),
    val current: SetupStep? = null,
    val failed: SetupStep? = null,
    val error: String? = null,
    val fraction: Float? = null,
    val detail: String = "",
    val log: List<String> = emptyList(),
    /** The user chose their settings for this setup; until then the settings screen shows. */
    val settingsChosen: Boolean = false,
) {
    val isComplete: Boolean get() = completed.containsAll(SetupStep.entries)
}

/** Process-wide setup state: written by [SetupService], read by the UI. */
object SetupController {
    private const val MAX_LOG_LINES = 400
    const val SETTINGS_FILE = "settings.properties"

    private val _state = MutableStateFlow(SetupUi())
    val state: StateFlow<SetupUi> = _state.asStateFlow()

    fun store(context: Context) = SetupStateStore(File(context.filesDir, "setup.properties"))
    fun settings(context: Context) = UserSettingsStore(File(context.filesDir, SETTINGS_FILE))

    fun reset(context: Context, env: AlpineEnvironment = AlpineEnvironment.get(context)) = AlpineReset(
        pendingFile = File(context.filesDir, "alpine/reset-pending"),
        installer = env.installer,
        state = store(context),
        temporary = env.temporaryFiles() + listOf(AppData.rootsDir(context), AppData.appTmpDir(context), AppData.sessionDir(context), TempHomes.root(context)) +
            ElectronSwap.temporaryFiles(env.cacheDir),
        appCaches = listOf(AppData.cacheRoot(context)),
        flatpakFiles = listOf(AppData.electronDir(context)),
        userData = listOf(
            AppData.root(context), AppData.launcherDir(context), AppData.settingsDir(context), File(context.filesDir, SETTINGS_FILE),
        ),
        fs = AndroidTarFs,
    )

    /**
     * Loads finished steps from disk, unless setup is running in this process. A pending reset
     * counts as nothing finished.
     */
    fun refresh(context: Context) {
        val saved = if (reset(context).isPending()) SetupState() else store(context).load()
        _state.update { if (it.running) it else it.copy(completed = saved.completed, settingsChosen = saved.settingsChosen) }
    }

    /** Records that setup started with the user's chosen settings. */
    fun settingsChosen(context: Context) {
        val store = store(context)
        store.save(store.load().copy(settingsChosen = true))
        _state.update { it.copy(settingsChosen = true) }
    }

    /** After a reset: nothing finished, but keep the log and the running flag. */
    fun cleared() = _state.update {
        it.copy(completed = emptySet(), settingsChosen = false, failed = null, error = null, current = null, fraction = null, detail = "")
    }

    /** Marks setup as running; false when it already was. */
    fun begin(): Boolean {
        var started = false
        _state.update {
            if (it.running) it else {
                started = true
                it.copy(running = true, failed = null, error = null, fraction = null, detail = "", log = emptyList())
            }
        }
        return started
    }

    fun end() = _state.update { it.copy(running = false, current = null, fraction = null) }

    /** An error outside any setup step, e.g. while resetting. */
    fun error(message: String) {
        DebugLog.w(message)
        _state.update { it.copy(error = message) }
    }

    val reporter = object : SetupReporter {
        override fun stepStarted(step: SetupStep) =
            _state.update { it.copy(current = step, fraction = null, detail = "") }

        override fun progress(fraction: Float?, detail: String) =
            _state.update { it.copy(fraction = fraction, detail = detail) }

        override fun log(line: String) {
            DebugLog.i(line)
            _state.update { it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) }
        }

        override fun stepFinished(step: SetupStep) =
            _state.update { it.copy(completed = it.completed + step, current = null, fraction = null, detail = "") }

        override fun failed(step: SetupStep, message: String) {
            DebugLog.w("Setup step $step failed: $message")
            _state.update { it.copy(failed = step, error = message, current = null, fraction = null) }
        }
    }
}
