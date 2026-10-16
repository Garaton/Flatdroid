package com.garathon.flatdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.setup.ResetMode
import com.garathon.flatdroid.alpine.GuestFiles
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.ui.theme.FlatdroidTheme

/**
 * Flatdroid's Settings, opened from the home screen: every setting by section, with Save and
 * Back, the Alpine terminal, and a danger section at the end (resetting the Alpine environment,
 * not while an app or a Flatpak task runs: [canReset]).
 * [deviceLanguages] is Flatpak's value for the device's languages (e.g. "de;en").
 */
@Composable
fun SettingsScreen(
    settings: UserSettings,
    deviceLanguages: String,
    applying: Boolean,
    error: String?,
    onSave: (UserSettings) -> Unit,
    onBack: () -> Unit,
    onReset: (ResetMode) -> Unit,
    onTerminal: () -> Unit,
    modifier: Modifier = Modifier,
    canReset: Boolean = true,
) {
    var onlyMyLanguages by rememberSaveable { mutableStateOf(settings.onlyMyLanguages) }
    var developerOptions by rememberSaveable { mutableStateOf(settings.developerOptions) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    if (confirmReset) {
        ResetDialog(onConfirm = { confirmReset = false; onReset(it) }, onDismiss = { confirmReset = false })
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        Text(stringResource(R.string.settings_section_flatpak), style = MaterialTheme.typography.titleMedium)
        LanguagesOption(onlyMyLanguages, deviceLanguages, enabled = !applying) { onlyMyLanguages = it }

        Text(stringResource(R.string.settings_section_developer), style = MaterialTheme.typography.titleMedium)
        SwitchRow(
            developerOptions, { developerOptions = it },
            stringResource(R.string.settings_developer_options), stringResource(R.string.settings_developer_options_hint), enabled = !applying,
        )

        error?.let {
            SelectionContainer { Text(stringResource(R.string.settings_failed, it), color = MaterialTheme.colorScheme.error) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(UserSettings(if (onlyMyLanguages) deviceLanguages else GuestFiles.ALL_LANGUAGES, developerOptions))
                },
                enabled = !applying,
            ) {
                Text(stringResource(if (applying) R.string.settings_applying else R.string.settings_save))
            }
            TextButton(onClick = onBack, enabled = !applying) { Text(stringResource(R.string.settings_back)) }
        }

        Text(stringResource(R.string.settings_section_tools), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onTerminal, enabled = !applying) { Text(stringResource(R.string.terminal_title)) }

        Text(
            stringResource(R.string.settings_section_danger),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(onClick = { confirmReset = true }, enabled = !applying && canReset) {
            Text(stringResource(R.string.reset_button), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    FlatdroidTheme {
        SettingsScreen(UserSettings.defaults("de;en"), "de;en", applying = false, error = null, onSave = {}, onBack = {}, onReset = {}, onTerminal = {})
    }
}
