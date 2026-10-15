package com.garathon.flatdroid

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.garathon.flatdroid.alpine.GuestFiles
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.setup.SetupController
import com.garathon.flatdroid.setup.SetupService
import com.garathon.flatdroid.setup.SetupStep
import com.garathon.flatdroid.ui.CommandState
import com.garathon.flatdroid.ui.SettingsScreen
import com.garathon.flatdroid.ui.SetupSettingsScreen
import com.garathon.flatdroid.ui.TerminalScreen
import com.garathon.flatdroid.ui.SetupScreen
import com.garathon.flatdroid.ui.theme.FlatdroidTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    /** Setup starts whatever the answer; without the permission its notification is just hidden. */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { SetupService.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val languages = GuestFiles.flatpakLanguages(deviceLanguageTags())
        setContent {
            FlatdroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val setup by SetupController.state.collectAsState()
                    val modifier = Modifier.padding(innerPadding)
                    val output by viewModel.output.collectAsState()
                    val running by viewModel.running.collectAsState()
                    val settingsError by viewModel.settingsError.collectAsState()
                    var showSettings by rememberSaveable { mutableStateOf(false) }
                    when {
                        // Screen 1: setup, the same for everyone.
                        !setup.isComplete || setup.running -> SetupScreen(
                            ui = setup,
                            onStart = ::startSetup,
                            onReset = ::resetAlpine,
                            modifier = modifier,
                            // Once Alpine is extracted, commands can look into a failed setup.
                            command = if (SetupStep.ROOTFS in setup.completed) CommandState(output, running, viewModel::run, viewModel::cancel) else null,
                        )
                        // Screen 2: the user's settings, asked once setup is done (again after a reset,
                        // pre-filled, so they are applied to the new Alpine).
                        !setup.settingsApplied -> SetupSettingsScreen(
                            settings = remember { savedSettings(languages) },
                            deviceLanguages = languages,
                            applying = running,
                            error = settingsError,
                            onFinish = { viewModel.applySettings(it) {} },
                            modifier = modifier,
                        )
                        showSettings -> SettingsScreen(
                            settings = remember { savedSettings(languages) },
                            deviceLanguages = languages,
                            applying = running,
                            error = settingsError,
                            onSave = { viewModel.applySettings(it) { showSettings = false } },
                            onBack = { showSettings = false; viewModel.clearSettingsError() },
                            onReset = { keep -> showSettings = false; resetAlpine(keep) },
                            modifier = modifier,
                        )
                        else -> TerminalScreen(
                            output, running, viewModel::run, viewModel::cancel,
                            onOpenSettings = { showSettings = true },
                            modifier = modifier,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SetupController.refresh(this)
    }

    private fun startSetup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            SetupService.start(this)
        }
    }

    private fun savedSettings(deviceLanguages: String) =
        SetupController.settings(this).load() ?: UserSettings.defaults(deviceLanguages)

    private fun resetAlpine(keepFlatpaks: Boolean) = SetupService.reset(this, keepFlatpaks)

    private fun deviceLanguageTags(): List<String> {
        val locales = LocaleList.getDefault()
        return (0 until locales.size()).map { locales[it].toLanguageTag() }
    }
}
