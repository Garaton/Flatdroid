package com.garathon.flatdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
 * Step 2 of setup, after the installation: the choices setup needs from the user, then Finish.
 * Shown again after a reset, pre-filled with the saved [settings], so they are applied to the new
 * Alpine. [deviceLanguages] is Flatpak's value for the device's languages (e.g. "de;en").
 */
@Composable
fun SetupSettingsScreen(
    settings: UserSettings,
    deviceLanguages: String,
    applying: Boolean,
    error: String?,
    onFinish: (UserSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var onlyMyLanguages by rememberSaveable { mutableStateOf(settings.onlyMyLanguages) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.setup_settings_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.setup_settings_intro), style = MaterialTheme.typography.bodyMedium)
        LanguagesOption(onlyMyLanguages, deviceLanguages, enabled = !applying) { onlyMyLanguages = it }
        error?.let {
            SelectionContainer { Text(stringResource(R.string.settings_failed, it), color = MaterialTheme.colorScheme.error) }
        }
        Button(
            onClick = { onFinish(UserSettings(if (onlyMyLanguages) deviceLanguages else GuestFiles.ALL_LANGUAGES)) },
            enabled = !applying,
        ) {
            Text(stringResource(if (applying) R.string.settings_applying else R.string.setup_settings_finish))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SetupSettingsScreenPreview() {
    FlatdroidTheme {
        SetupSettingsScreen(UserSettings.defaults("de;en"), "de;en", applying = false, error = null, onFinish = {})
    }
}
