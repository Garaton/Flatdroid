package com.garathon.flatdroid.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.garathon.flatdroid.alpine.GuestFiles
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.ui.LocalDeveloperOptions
import com.garathon.flatdroid.ui.settings.GpuRuntimesRow
import com.garathon.flatdroid.ui.settings.LanguagesRow
import com.garathon.flatdroid.ui.settings.SecurityLevelRow
import com.garathon.flatdroid.ui.settings.SecuritySaver
import com.garathon.flatdroid.ui.theme.FlatdroidTheme

/**
 * The first screen of setup, before the installation: what setup does and needs, and the choices
 * it needs from the user, then Continue, which starts setup (its last step applies them). Shown
 * again after a reset, pre-filled with the saved [settings]. [deviceLanguages] is Flatpak's
 * value for the device's languages (e.g. "de;en").
 */
@Composable
fun SetupSettingsScreen(
    settings: UserSettings,
    deviceLanguages: String,
    onContinue: (UserSettings) -> Unit,
    modifier: Modifier = Modifier,
    /** What went wrong with a reset that ended here, if anything. */
    error: String? = null,
) {
    var onlyMyLanguages by rememberSaveable { mutableStateOf(settings.onlyMyLanguages) }
    var security by rememberSaveable(stateSaver = SecuritySaver) { mutableStateOf(settings.security) }
    var gpuRuntimes by rememberSaveable { mutableStateOf(settings.installGpuRuntimes) }

    SetupFrame(
        title = stringResource(R.string.setup_settings_title),
        screen = 1,
        modifier = modifier,
        actions = {
            Button(
                onClick = { onContinue(settings.copy(flatpakLanguages = if (onlyMyLanguages) deviceLanguages else GuestFiles.ALL_LANGUAGES, security = security, installGpuRuntimes = gpuRuntimes)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.setup_settings_continue)) }
            Text(
                stringResource(R.string.setup_settings_later),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        error?.let { SetupError(stringResource(R.string.setup_failed, it)) }
        Text(stringResource(R.string.setup_settings_intro), style = MaterialTheme.typography.bodyMedium)
        SetupNote(stringResource(R.string.setup_estimate))
        LanguagesRow(onlyMyLanguages, { onlyMyLanguages = it }, deviceLanguages)
        // Only with developer options, which a reset keeps: a first setup has none yet.
        CompositionLocalProvider(LocalDeveloperOptions provides settings.developerOptions) {
            GpuRuntimesRow(gpuRuntimes, { gpuRuntimes = it })
        }
        SecurityLevelRow(security, { security = it })
        Text(stringResource(R.string.security_change_later), style = MaterialTheme.typography.bodySmall)
    }
}

/** What went wrong, in a card; selectable. */
@Composable
internal fun SetupError(text: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer) {
        SelectionContainer {
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
        }
    }
}

/** A short note on a tinted background, with an info icon. */
@Composable
internal fun SetupNote(text: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
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
