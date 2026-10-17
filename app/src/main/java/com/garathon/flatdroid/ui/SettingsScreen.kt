package com.garathon.flatdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.setup.ResetMode
import com.garathon.flatdroid.alpine.GuestFiles
import com.garathon.flatdroid.session.Viewer
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.ui.theme.FlatdroidTheme

/**
 * Flatdroid's Settings, opened from the home screen: every setting by section, with Save and
 * Back, the Alpine terminal, and a danger section at the end (resetting the Alpine environment,
 * not while an app or a Flatpak task runs: [canReset]).
 * [deviceLanguages] is Flatpak's value for the device's languages (e.g. "de;en"); [viewers] are
 * the installed VNC viewers to choose from.
 */
@Composable
fun SettingsScreen(
    settings: UserSettings,
    deviceLanguages: String,
    viewers: List<Viewer.App>,
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
    var vncViewer by rememberSaveable { mutableStateOf(settings.vncViewer) }
    var frameRate60 by rememberSaveable { mutableStateOf(settings.frameRate60) }
    var customFrameRate by rememberSaveable { mutableStateOf(settings.customFrameRate?.toString() ?: "") }
    var uncappedFrameRate by rememberSaveable { mutableStateOf(settings.uncappedFrameRate) }
    var titleBars by rememberSaveable { mutableStateOf(settings.titleBars) }
    var installGpuRuntimes by rememberSaveable { mutableStateOf(settings.installGpuRuntimes) }
    // Empty means "not set"; anything else must be a rate Xvnc takes.
    val customFrameRateValue = customFrameRate.toIntOrNull()?.takeIf { it in UserSettings.CUSTOM_FRAME_RATES }
    val customFrameRateValid = customFrameRate.isEmpty() || customFrameRateValue != null
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    if (confirmReset) {
        ResetDialog(onConfirm = { confirmReset = false; onReset(it) }, onDismiss = { confirmReset = false })
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        SectionTitle(stringResource(R.string.settings_section_flatpak))
        LanguagesOption(onlyMyLanguages, deviceLanguages, enabled = !applying) { onlyMyLanguages = it }

        SectionTitle(stringResource(R.string.settings_section_viewer))
        Text(stringResource(R.string.settings_viewer_hint), style = MaterialTheme.typography.bodySmall)
        ViewerChoice(vncViewer, viewers, enabled = !applying) { vncViewer = it }
        SwitchRow(
            frameRate60, { frameRate60 = it },
            stringResource(R.string.settings_frame_rate_60), stringResource(R.string.settings_frame_rate_60_hint), enabled = !applying,
        )

        SectionTitle(stringResource(R.string.settings_section_developer))
        SwitchRow(
            developerOptions, { developerOptions = it },
            stringResource(R.string.settings_developer_options), stringResource(R.string.settings_developer_options_hint), enabled = !applying,
        )
        if (developerOptions) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DeveloperMark()
                OutlinedTextField(
                    value = customFrameRate,
                    onValueChange = { customFrameRate = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.settings_custom_frame_rate)) },
                    supportingText = { Text(stringResource(R.string.settings_custom_frame_rate_hint, UserSettings.CUSTOM_FRAME_RATES.first, UserSettings.CUSTOM_FRAME_RATES.last)) },
                    isError = !customFrameRateValid,
                    singleLine = true,
                    enabled = !applying && !uncappedFrameRate,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            SwitchRow(
                uncappedFrameRate, { uncappedFrameRate = it },
                stringResource(R.string.settings_uncapped_frame_rate), stringResource(R.string.settings_uncapped_frame_rate_hint),
                enabled = !applying, developer = true,
            )
            SwitchRow(
                titleBars, { titleBars = it },
                stringResource(R.string.settings_title_bars), stringResource(R.string.settings_title_bars_hint),
                enabled = !applying, developer = true,
            )
            SwitchRow(
                installGpuRuntimes, { installGpuRuntimes = it },
                stringResource(R.string.settings_gpu_runtimes), stringResource(R.string.settings_gpu_runtimes_hint),
                enabled = !applying, developer = true,
            )
        }

        error?.let {
            SelectionContainer { Text(stringResource(R.string.settings_failed, it), color = MaterialTheme.colorScheme.error) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(
                        UserSettings(
                            flatpakLanguages = if (onlyMyLanguages) deviceLanguages else GuestFiles.ALL_LANGUAGES,
                            developerOptions = developerOptions,
                            vncViewer = vncViewer,
                            frameRate60 = frameRate60,
                            customFrameRate = customFrameRateValue,
                            uncappedFrameRate = uncappedFrameRate,
                            installGpuRuntimes = installGpuRuntimes,
                            titleBars = titleBars,
                        )
                    )
                },
                enabled = !applying && customFrameRateValid,
            ) {
                Text(stringResource(if (applying) R.string.settings_applying else R.string.settings_save))
            }
            TextButton(onClick = onBack, enabled = !applying) { Text(stringResource(R.string.settings_back)) }
        }

        SectionTitle(stringResource(R.string.settings_section_tools))
        OutlinedButton(onClick = onTerminal, enabled = !applying) { Text(stringResource(R.string.terminal_title)) }

        SectionTitle(stringResource(R.string.settings_section_danger), danger = true)
        OutlinedButton(onClick = { confirmReset = true }, enabled = !applying && canReset) {
            Text(stringResource(R.string.reset_button), color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * The VNC viewer as a dropdown: "Ask every time" ([chosen] null) or one of [viewers]. A chosen
 * viewer that was uninstalled is shown as such until another is picked; links go to the chooser
 * meanwhile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerChoice(chosen: String?, viewers: List<Viewer.App>, enabled: Boolean, onChange: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val ask = stringResource(R.string.settings_viewer_ask)
    val label = when (chosen) {
        null -> ask
        else -> viewers.firstOrNull { it.packageName == chosen }?.label ?: stringResource(R.string.settings_viewer_missing, chosen)
    }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(ask) }, onClick = { onChange(null); expanded = false })
            for (viewer in viewers) {
                DropdownMenuItem(text = { Text(viewer.label) }, onClick = { onChange(viewer.packageName); expanded = false })
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    FlatdroidTheme {
        SettingsScreen(UserSettings.defaults("de;en"), "de;en", listOf(Viewer.App("com.gaurav.avnc", "AVNC")), applying = false, error = null, onSave = {}, onBack = {}, onReset = {}, onTerminal = {})
    }
}
