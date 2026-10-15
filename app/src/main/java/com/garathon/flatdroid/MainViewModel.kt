package com.garathon.flatdroid

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.setup.SetupController
import com.garathon.flatdroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * session) and applying settings. One at a time, sharing [running].
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private var commandJob: Job? = null
    private val _settingsError = MutableStateFlow<String?>(null)
    val settingsError: StateFlow<String?> = _settingsError.asStateFlow()

    /**
     * Cancels the running command, like Ctrl-C: the processes in Alpine get SIGINT first, and are
     * killed if they haven't stopped a few seconds later (see ProcessStopper).
     */
    fun cancel() {
        commandJob?.cancel()
    }

    fun clearSettingsError() {
        _settingsError.value = null
    }

    /** Saves [settings], applies them in Alpine, then calls [onApplied] (on the main thread). */
    fun applySettings(settings: UserSettings, onApplied: () -> Unit) {
        if (_running.value) return
        _running.value = true
        _settingsError.value = null
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                SetupController.settings(app).save(settings)
                runInterruptible {
                    val env = AlpineEnvironment(app)
                    for (command in settings.commands()) {
                        DebugLog.i("$ ${command.joinToString(" ")}")
                        val result = env.run(command, onLine = DebugLog::i)
                        if (result.exitCode != 0) {
                            throw IOException(result.output.lines().lastOrNull { it.isNotBlank() } ?: "exit ${result.exitCode}")
                        }
                    }
                }
                SetupController.settingsApplied(app)
                withContext(Dispatchers.Main) { onApplied() }
            } catch (e: Exception) {
                _settingsError.value = e.message ?: e.toString()
            } finally {
                _running.value = false
            }
        }
    }

    fun run(command: String) {
        if (command.isBlank() || _running.value) return
        _running.value = true
        _output.value = ""
        log("$ $command")
        commandJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // The user's text reaches the guest shell as one argument; the proot invocation
                // itself is still an argument list.
                val result = runInterruptible {
                    AlpineEnvironment(getApplication()).run(
                        listOf("/bin/sh", "-c", command),
                        timeoutSeconds = COMMAND_TIMEOUT_SECONDS,
                        onLine = ::log,
                    )
                }
                log(if (result.timedOut) "(timed out)" else "(exit ${result.exitCode})")
            } catch (e: CancellationException) {
                log("(cancelled)")
                throw e
            } catch (e: Exception) {
                log("ERROR: $e")
            } finally {
                _running.value = false
            }
        }
    }

    private fun log(line: String) {
        DebugLog.i(line)
        _output.update { "$it$line\n" }
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 30 * 60L
    }
}
