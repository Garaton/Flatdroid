package com.garathon.flatdroid.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.audio.MicStreamer
import com.garathon.flatdroid.flatpak.InstalledApp
import com.garathon.flatdroid.settings.AppSettings
import com.garathon.flatdroid.settings.DisplaySize
import com.garathon.flatdroid.ui.theme.FlatdroidTheme

/**
 * One app's settings: its display size, its permissions (sound and microphone), its cache, and
 * uninstalling; with [developerOptions], for apps asking for all devices, also the host's whole
 * /dev. Sound left at its default is saved as unset, so it follows the app's metadata.
 * [onRequestMicrophone] asks Android for its microphone permission and reports whether it was
 * granted: the microphone turns on only with it, so a notification can later allow it for a
 * session. With [developerOptions], the microphone can also be always allowed.
 */
@Composable
fun AppSettingsScreen(
    app: InstalledApp,
    settings: AppSettings,
    /** Size of the app's cache in bytes; null while it is being measured. */
    cacheBytes: Long?,
    /** False while the app runs. */
    canClearCache: Boolean,
    /** False while any app or a Flatpak task runs. */
    canUninstall: Boolean,
    developerOptions: Boolean,
    onRequestMicrophone: (onResult: (Boolean) -> Unit) -> Unit,
    onSave: (AppSettings) -> Unit,
    onBack: () -> Unit,
    onClearCache: () -> Unit,
    onUninstall: (deleteData: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var choice by rememberSaveable { mutableStateOf(settings.displaySize) }
    var width by rememberSaveable { mutableStateOf(settings.customWidth.toString()) }
    var height by rememberSaveable { mutableStateOf(settings.customHeight.toString()) }
    var wholeDev by rememberSaveable { mutableStateOf(settings.wholeDev) }
    val audioDefault = app.requestsAudio
    var audio by rememberSaveable { mutableStateOf(settings.audio ?: audioDefault) }
    var microphone by rememberSaveable { mutableStateOf(settings.microphone) }
    var alwaysAllowMicrophone by rememberSaveable { mutableStateOf(settings.alwaysAllowMicrophone) }
    var micRefused by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var confirmUninstall by rememberSaveable { mutableStateOf(false) }
    var cacheCleared by rememberSaveable { mutableStateOf(false) }

    fun size(text: String) = text.toIntOrNull()?.takeIf { it in AppSettings.MIN_SIZE..AppSettings.MAX_SIZE }
    val customValid = size(width) != null && size(height) != null

    if (confirmUninstall) {
        UninstallDialog(app.name, onConfirm = { confirmUninstall = false; onUninstall(it) }, onDismiss = { confirmUninstall = false })
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIcon(app.icon, Modifier.size(56.dp))
            Column {
                Text(app.name, style = MaterialTheme.typography.headlineSmall)
                Text(app.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SectionTitle(stringResource(R.string.app_settings_display))
        Text(stringResource(R.string.app_settings_display_hint), style = MaterialTheme.typography.bodySmall)
        Column(Modifier.selectableGroup()) {
            RadioRow(choice == DisplaySize.PORTRAIT, { choice = DisplaySize.PORTRAIT }, stringResource(R.string.app_settings_portrait, AppSettings.PORTRAIT_WIDTH, AppSettings.PORTRAIT_HEIGHT))
            RadioRow(choice == DisplaySize.LANDSCAPE, { choice = DisplaySize.LANDSCAPE }, stringResource(R.string.app_settings_landscape, AppSettings.LANDSCAPE_WIDTH, AppSettings.LANDSCAPE_HEIGHT))
            RadioRow(choice == DisplaySize.CUSTOM, { choice = DisplaySize.CUSTOM }, stringResource(R.string.app_settings_custom))
        }
        if (choice == DisplaySize.CUSTOM) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SizeField(width, stringResource(R.string.app_settings_width), Modifier.width(120.dp)) { width = it }
                Text("×")
                SizeField(height, stringResource(R.string.app_settings_height), Modifier.width(120.dp)) { height = it }
            }
        }

        SectionTitle(stringResource(R.string.app_settings_permissions))
        SwitchRow(
            audio, { audio = it },
            stringResource(R.string.app_settings_audio),
            stringResource(if (app.requestsAudio) R.string.app_settings_audio_requested else R.string.app_settings_audio_not_requested),
        )
        SwitchRow(
            microphone,
            { on ->
                micRefused = false
                if (!on || MicStreamer.hasPermission(context)) {
                    microphone = on
                } else {
                    onRequestMicrophone { granted -> if (granted) microphone = true else micRefused = true }
                }
            },
            stringResource(R.string.app_settings_microphone),
            stringResource(if (micRefused) R.string.app_settings_microphone_android_denied else R.string.app_settings_microphone_hint),
            enabled = audio,
        )
        if (developerOptions) {
            SwitchRow(
                alwaysAllowMicrophone, { alwaysAllowMicrophone = it },
                stringResource(R.string.app_settings_always_allow_microphone), stringResource(R.string.app_settings_always_allow_microphone_hint),
                enabled = audio && microphone, developer = true,
            )
        }

        if (developerOptions && app.requestsAllDevices) {
            SectionTitle(stringResource(R.string.app_settings_devices))
            SwitchRow(
                wholeDev, { wholeDev = it },
                stringResource(R.string.app_settings_whole_dev), stringResource(R.string.app_settings_whole_dev_hint),
                developer = true,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val custom = choice == DisplaySize.CUSTOM
                    onSave(
                        settings.copy(
                            displaySize = choice,
                            customWidth = if (custom) size(width)!! else settings.customWidth,
                            customHeight = if (custom) size(height)!! else settings.customHeight,
                            wholeDev = wholeDev,
                            audio = audio.takeIf { it != audioDefault },
                            microphone = microphone,
                            alwaysAllowMicrophone = if (developerOptions) alwaysAllowMicrophone else settings.alwaysAllowMicrophone,
                        )
                    )
                },
                enabled = choice != DisplaySize.CUSTOM || customValid,
            ) { Text(stringResource(R.string.settings_save)) }
            TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
        }

        SectionTitle(stringResource(R.string.app_settings_storage))
        Text(
            cacheBytes?.let { stringResource(R.string.app_settings_cache_size, Formatter.formatShortFileSize(LocalContext.current, it)) }
                ?: stringResource(R.string.app_settings_cache_measuring),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { onClearCache(); cacheCleared = true }, enabled = canClearCache && !cacheCleared) {
            Text(stringResource(if (cacheCleared) R.string.app_settings_cache_cleared else R.string.app_settings_clear_cache))
        }

        SectionTitle(stringResource(R.string.settings_section_danger), danger = true)
        OutlinedButton(onClick = { confirmUninstall = true }, enabled = canUninstall) {
            Text(stringResource(R.string.uninstall_button), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SizeField(value: String, label: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(4)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun UninstallDialog(name: String, onConfirm: (deleteData: Boolean) -> Unit, onDismiss: () -> Unit) {
    var deleteData by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.uninstall_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.uninstall_text))
                CheckboxRow(deleteData, { deleteData = it }, stringResource(R.string.uninstall_delete_data))
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(deleteData) }) { Text(stringResource(R.string.uninstall_confirm), color = MaterialTheme.colorScheme.onPrimary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reset_cancel)) } },
    )
}

@Preview(showBackground = true)
@Composable
private fun AppSettingsScreenPreview() {
    FlatdroidTheme {
        AppSettingsScreen(
            InstalledApp("org.xfce.mousepad", "Mousepad", null, null), AppSettings(), cacheBytes = 12_345_678, canClearCache = true, canUninstall = true, developerOptions = true,
            onRequestMicrophone = {}, onSave = {}, onBack = {}, onClearCache = {}, onUninstall = {},
        )
    }
}
