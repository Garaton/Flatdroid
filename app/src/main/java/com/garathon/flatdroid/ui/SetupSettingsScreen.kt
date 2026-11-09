package com.garathon.flatdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.alpine.GuestFiles
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.ui.theme.FlatdroidTheme

/**
 * Step 1 of setup, before the installation: the choices setup needs from the user, then
 * Continue, which starts setup (its last step applies them). Shown again after a reset,
 * pre-filled with the saved [settings]. [deviceLanguages] is Flatpak's value for the device's
 * languages (e.g. "de;en").
 */
@Composable
fun SetupSettingsScreen(
    settings: UserSettings,
    deviceLanguages: String,
    onContinue: (UserSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var onlyMyLanguages by rememberSaveable { mutableStateOf(settings.onlyMyLanguages) }
    var security by rememberSaveable(stateSaver = SecuritySaver) { mutableStateOf(settings.security) }
    var gpuRuntimes by rememberSaveable { mutableStateOf(settings.installGpuRuntimes) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.setup_settings_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.setup_settings_intro), style = MaterialTheme.typography.bodyMedium)
        SwitchRow(
        	onlyMyLanguages, { onlyMyLanguages = it },
        	stringResource(R.string.settings_languages, deviceLanguages.replace(";", ", ")), stringResource(R.string.settings_languages_hint),
        )
        // Only with developer options, which a reset keeps: a first setup has none yet.
        if (settings.developerOptions) {
            SwitchRow(
                gpuRuntimes, { gpuRuntimes = it },
                stringResource(R.string.settings_gpu_runtimes), stringResource(R.string.settings_gpu_runtimes_hint), developer = true,
            )
        }
        SecuritySection(security, { security = it })
        Button(onClick = { onContinue(settings.copy(flatpakLanguages = if (onlyMyLanguages) deviceLanguages else GuestFiles.ALL_LANGUAGES, security = security, installGpuRuntimes = gpuRuntimes)) }) {
            Text(stringResource(R.string.setup_settings_continue))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SetupSettingsScreenPreview() {
    FlatdroidTheme {
        SetupSettingsScreen(UserSettings.defaults("de;en"), "de;en", onContinue = {})
    }
}
