package com.garathon.flatdroid

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.garathon.flatdroid.alpine.AlpineCommands
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.flatpak.AppData
import com.garathon.flatdroid.proot.Fdsec
import com.garathon.flatdroid.proot.ProotProcess
import com.garathon.flatdroid.session.SessionController
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.setup.AndroidTarFs
import com.garathon.flatdroid.setup.SetupController
import com.garathon.flatdroid.setup.deleteTree
import com.garathon.flatdroid.storage.SavedSizes
import com.garathon.flatdroid.util.DebugLog
import com.garathon.flatdroid.util.LineBuffer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Work in Alpine started from the screens: the single command field (one command, its output, no
 * session; [running]) and applying settings ([applying]). Each runs one at a time.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val outputLines = LineBuffer(maxLines = MAX_OUTPUT_LINES, scope = viewModelScope, intervalMs = OUTPUT_INTERVAL_MS)
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _applying = MutableStateFlow(false)
    val applying: StateFlow<Boolean> = _applying.asStateFlow()

    /**
     * The command's output. While it runs, only its last [LIVE_OUTPUT_LINES] (laying out
     * thousands of lines ten times a second is what slows the screen); once it has finished, the
     * last [MAX_OUTPUT_LINES].
     */
    val output: StateFlow<String> = combine(outputLines.lines, _running) { lines, running ->
        (if (running) lines.takeLast(LIVE_OUTPUT_LINES) else lines).joinToString("\n")
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, "")
    @Volatile private var command: ProotProcess? = null
    @Volatile private var interrupted = false
    private val _settingsError = MutableStateFlow<String?>(null)
    val settingsError: StateFlow<String?> = _settingsError.asStateFlow()

    /**
     * ✕ on the running command. The first press is Ctrl-C (SIGINT), and the command may take as
     * long as it needs to stop; a second press stops it the hard way (SIGQUIT to proot, then
     * SIGKILL), which can leave files a command was changing inconsistent (see ProcessStopper).
     */
    fun cancel() {
        val proc = command ?: return
        if (!interrupted) {
            interrupted = true
            end(text(R.string.terminal_ctrl_c))
            viewModelScope.launch(Dispatchers.IO) { proc.interrupt() }
        } else {
            end(text(R.string.terminal_force_stopping))
            viewModelScope.launch(Dispatchers.IO) { proc.kill() }
        }
    }

    fun clearSettingsError() {
        _settingsError.value = null
    }

    /**
     * Applies [settings] in Alpine, then saves them and calls [onApplied] (on the main thread).
     * Only commands that differ from the saved settings' run, and they run first, so settings whose
     * commands failed aren't saved and a second save tries them again.
     */
    fun applySettings(settings: UserSettings, onApplied: () -> Unit) {
        if (_applying.value) return
        _applying.value = true
        _settingsError.value = null
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val before = SetupController.settings(app).load()
                val commands = settings.commands().takeIf { before == null || it != before.commands() }.orEmpty()
                // Never during a session: the app could take the commands over (see AlpineCommands).
                if (commands.isNotEmpty() && SessionController.isBusy) throw IOException(text(R.string.settings_app_running))
                runInterruptible {
                    val env = AlpineEnvironment.get(app)
                    for (command in commands) {
                        DebugLog.i("$ ${command.joinToString(" ")}")
                        val result = env.run(command, timeoutSeconds = UserSettings.APPLY_TIMEOUT_SECONDS, onLine = DebugLog::i)
                        if (result.exitCode != 0) {
                            throw IOException(result.output.lines().lastOrNull { it.isNotBlank() } ?: "exit ${result.exitCode}")
                        }
                    }
                }
                SetupController.settings(app).save(settings)
                // Off, the file check's baselines aren't kept up (installs and updates record none), so
                // they are dropped; turning it on again asks how to record them.
                if (before?.security?.checkFilesBeforeLaunch == true && !settings.security.checkFilesBeforeLaunch) {
                    AndroidTarFs.deleteTree(AppData.integrityDir(app))
                }
                withContext(Dispatchers.Main) { onApplied() }
            } catch (e: Exception) {
                _settingsError.value = e.message ?: e.toString()
            } finally {
                _applying.value = false
            }
        }
    }

    fun run(command: String) {
        if (command.isBlank() || _running.value) return
        outputLines.clear()
        // A command started during a session isn't hidden from the app (see AlpineCommands).
        if (!AlpineCommands.terminalMayRun(getApplication())) {
            log("$ $command")
            end(text(R.string.terminal_app_running))
            return
        }
        _running.value = true
        AlpineCommands.terminalRunning = true
        DebugLog.i("$ $command")
        log("$ $command")
        interrupted = false
        viewModelScope.launch(Dispatchers.IO) {
            var proc: ProotProcess? = null
            var timedOut = false
            // A command may change anything in Alpine, Flatpak's installation included.
            SavedSizes.forgetAll(getApplication())
            try {
                // The user's text reaches the guest shell as one argument; the proot invocation
                // itself is still an argument list. Behind fdsec's filter unless the developer
                // setting turns it off for the terminal.
                val filtered = SetupController.settings(getApplication()).load()?.filterTerminal ?: true
                proc = AlpineEnvironment.get(getApplication())
                    .start(listOf("/bin/sh", "-c", command), filter = Fdsec.Filter().takeIf { filtered }, onLine = ::log)
                this@MainViewModel.command = proc
                val started = proc
                val watchdog = launch {
                    delay(COMMAND_TIMEOUT_SECONDS * 1000)
                    timedOut = true
                    runInterruptible { started.stop() }
                }
                val code = runInterruptible { started.waitFor() }
                watchdog.cancel()
                end(
                    when {
                        timedOut -> text(R.string.terminal_timed_out)
                        interrupted -> text(R.string.terminal_stopped, code)
                        else -> text(R.string.terminal_exit, code)
                    }
                )
            } catch (e: CancellationException) {
                // The screen is gone for good: stop the command without asking.
                proc?.let { withContext(NonCancellable) { runInterruptible { it.stop() } } }
                end(text(R.string.terminal_cancelled))
                throw e
            } catch (e: Exception) {
                end(text(R.string.terminal_error, e))
            } finally {
                SavedSizes.changedAll(getApplication(), measureNow = false)
                this@MainViewModel.command = null
                AlpineCommands.terminalRunning = false
                _running.value = false
            }
        }
    }

    /** Output lines go to the screen only: logging each one costs as much as showing it. */
    private fun log(line: String) {
        outputLines.add(line)
    }

    private fun text(id: Int, vararg args: Any?): String = getApplication<Application>().getString(id, *args)

    private fun end(line: String) {
        DebugLog.i(line)
        outputLines.add(line)
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 30 * 60L
        const val MAX_OUTPUT_LINES = 2_000
        const val LIVE_OUTPUT_LINES = 300
        const val OUTPUT_INTERVAL_MS = 100L
    }
}
