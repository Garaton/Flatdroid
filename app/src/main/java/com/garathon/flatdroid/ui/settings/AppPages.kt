package com.garathon.flatdroid.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.audio.MicStreamer
import com.garathon.flatdroid.camera.AndroidCameras
import com.garathon.flatdroid.settings.AppSettings
import com.garathon.flatdroid.settings.HomeMode
import com.garathon.flatdroid.settings.Resolution
import com.garathon.flatdroid.ui.AppIcon
import com.garathon.flatdroid.ui.CheckboxRow
import com.garathon.flatdroid.ui.ConfirmDialog
import com.garathon.flatdroid.ui.DropdownRow
import com.garathon.flatdroid.ui.SectionTitle
import com.garathon.flatdroid.ui.StorageAction
import com.garathon.flatdroid.ui.SwitchRow
import com.garathon.flatdroid.ui.TextRow

@Composable
fun AppOverviewPage(s: AppPageState) = SettingsPageColumn(R.string.app_page_overview) {
    var confirmUninstall by rememberSaveable { mutableStateOf(false) }
    if (confirmUninstall) {
        UninstallDialog(
            s.app.name,
            onConfirm = { data, runtimes -> confirmUninstall = false; s.env.onUninstall(data, runtimes) },
            onDismiss = { confirmUninstall = false },
        )
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        AppIcon(s.app.icon, Modifier.size(96.dp))
        Text(s.app.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Text(s.app.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
    if (s.env.showsWebContent) {
        Text(stringResource(R.string.app_settings_web_content), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (s.env.developerOptions) {
        TextRow(
            content = s.draft.environment,
            onChange = { v -> s.edit { copy(environment = v) } },
            title = stringResource(R.string.app_settings_environment),
            hint = stringResource(if (!s.draft.environmentValid) R.string.app_settings_environment_invalid else R.string.app_settings_environment_hint),
            isError = !s.draft.environmentValid,
            developer = true,
            singleLine = false,
            keyboardType = KeyboardType.Ascii,
        )
        TextRow(
            content = s.draft.launchArgs,
            onChange = { v -> s.edit { copy(launchArgs = v) } },
            title = stringResource(R.string.app_settings_launch_args),
            hint = stringResource(R.string.app_settings_launch_args_hint),
            isError = false,
            developer = true,
            singleLine = false,
            minLines = 1,
            keyboardType = KeyboardType.Ascii,
        )
    }
    SectionTitle(stringResource(R.string.settings_section_danger), danger = true)
    OutlinedButton(onClick = { confirmUninstall = true }, enabled = s.env.canUninstall) {
        Text(stringResource(R.string.uninstall_button), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun AppHomePage(s: AppPageState) = SettingsPageColumn(R.string.app_page_home) {
    val defaults = s.settings.defaults
    val names = mapOf(
        HomeMode.SHARED to stringResource(R.string.app_settings_home_shared),
        HomeMode.PRIVATE to stringResource(R.string.app_settings_home_private),
        HomeMode.TEMPORARY to stringResource(R.string.app_settings_home_temporary),
    )
    DropdownRow(
        selected = s.settings.homeMode,
        options = HomeMode.entries.toList(),
        onSelect = { v -> s.editSettings { copy(homeModeChoice = v.takeIf { it != defaults.homeMode }) } },
        label = names::getValue,
        title = stringResource(R.string.app_settings_files),
        hint = when (s.settings.homeMode) {
            HomeMode.SHARED -> stringResource(R.string.app_settings_home_shared_hint, s.env.sharedHome)
            HomeMode.PRIVATE -> stringResource(R.string.app_settings_home_private_hint)
            HomeMode.TEMPORARY -> stringResource(R.string.app_settings_home_temporary_hint)
        },
    )
    if (s.env.broadAccess) {
        SwitchRow(
            s.settings.sdcard, { v -> s.editSettings { copy(sdcard = v) } },
            stringResource(R.string.app_settings_sdcard), stringResource(R.string.app_settings_sdcard_hint),
        )
    }
    if (s.env.developerOptions) {
        SwitchRow(
            s.settings.shareHiddenFiles, { v -> s.editSettings { copy(shareHiddenFiles = v) } },
            stringResource(R.string.app_settings_share_hidden), stringResource(R.string.app_settings_share_hidden_hint),
            developer = true,
        )
    }
}

@Composable
fun AppDisplayPage(s: AppPageState) = SettingsPageColumn(R.string.app_page_display) {
    val app = s.app
    val customLabel = stringResource(R.string.app_settings_custom)
    val percentLabel = stringResource(R.string.app_settings_resolution_percent)
    DropdownRow(
        selected = s.settings.resolution,
        options = Resolution.entries,
        onSelect = { v -> s.editSettings { copy(resolution = v) } },
        label = { it.percent?.let { p -> percentLabel.format(p) } ?: customLabel },
        title = stringResource(R.string.app_settings_resolution),
        hint = stringResource(R.string.app_settings_resolution_hint),
    )
    if (s.settings.resolution == Resolution.CUSTOM) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SizeField(s.draft.width, stringResource(R.string.app_settings_width), !s.draft.widthValid, Modifier.width(120.dp)) { v -> s.edit { copy(width = v) } }
            Text("×")
            SizeField(s.draft.height, stringResource(R.string.app_settings_height), !s.draft.heightValid, Modifier.width(120.dp)) { v -> s.edit { copy(height = v) } }
        }
        if (!s.draft.widthValid || !s.draft.heightValid) {
            Text(
                stringResource(R.string.app_settings_custom_invalid, AppSettings.MIN_SIZE, AppSettings.MAX_SIZE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    // The compositor scales; Xvnc can't.
    if (app.wayland) DropdownRow(
        selected = s.settings.interfaceSize,
        options = AppSettings.INTERFACE_SIZES,
        onSelect = { v -> s.editSettings { copy(interfaceSize = v) } },
        label = { percentLabel.format(it) },
        title = stringResource(R.string.app_settings_interface_size),
        hint = stringResource(R.string.app_settings_interface_size_hint),
    )
    // Only for apps that ask for Wayland as well as X11.
    if (app.waylandAndX11) SwitchRow(
        s.settings.forceX11, { v -> s.editSettings { copy(forceX11 = v) } },
        stringResource(R.string.app_settings_force_x11), stringResource(R.string.app_settings_force_x11_hint),
    )
}

@Composable
fun AppPermissionsPage(s: AppPageState) = SettingsPageColumn(R.string.app_page_permissions) {
    val app = s.app
    val defaults = s.settings.defaults
    val context = LocalContext.current
    // Configuration-aware (unlike context.getString from LocalContext): used in the label lambdas
    // below, which run per item and so can't call the composable stringResource.
    val resources = LocalResources.current
    var micRefused by rememberSaveable { mutableStateOf(false) }
    var cameraRefused by rememberSaveable { mutableStateOf(false) }
    val fromMetadata = defaults.permissionsFromMetadata
    val audioDefault = fromMetadata && app.requestsAudio
    val networkDefault = fromMetadata && app.requestsNetwork
    val audio = s.settings.audio ?: audioDefault
    val network = s.settings.network ?: networkDefault

    SwitchRow(
        network, { v -> s.editSettings { copy(network = v.takeIf { it != networkDefault }) } },
        stringResource(R.string.app_settings_network),
        stringResource(
            when {
                !app.requestsNetwork -> R.string.app_settings_network_not_requested
                fromMetadata -> R.string.app_settings_network_requested
                else -> R.string.app_settings_network_requested_off
            }
        ),
    )
    SwitchRow(
        audio, { v -> s.editSettings { copy(audio = v.takeIf { it != audioDefault }) } },
        stringResource(R.string.app_settings_audio),
        stringResource(
            when {
                !app.requestsAudio -> R.string.app_settings_audio_not_requested
                fromMetadata -> R.string.app_settings_audio_requested
                else -> R.string.app_settings_audio_requested_off
            }
        ),
    )
    SwitchRow(
        s.settings.microphone,
        { on ->
            micRefused = false
            if (!on || MicStreamer.hasPermission(context)) {
                s.editSettings { copy(microphone = on) }
            } else {
                s.env.onRequestMicrophone { granted -> if (granted) s.editSettings { copy(microphone = true) } else micRefused = true }
            }
        },
        stringResource(R.string.app_settings_microphone),
        stringResource(if (micRefused) R.string.app_settings_microphone_android_denied else R.string.app_settings_microphone_hint),
        enabled = audio,
    )
    SwitchRow(
        s.settings.alwaysAllowMicrophone, { v -> s.editSettings { copy(alwaysAllowMicrophone = v) } },
        stringResource(R.string.app_settings_always_allow_microphone), stringResource(R.string.app_settings_always_allow_microphone_hint),
        enabled = audio && s.settings.microphone,
    )
    SwitchRow(
        s.settings.camera,
        { on ->
            cameraRefused = false
            if (!on || AndroidCameras.hasPermission(context)) {
                s.editSettings { copy(camera = on) }
            } else {
                s.env.onRequestCamera { granted -> if (granted) s.editSettings { copy(camera = true) } else cameraRefused = true }
            }
        },
        stringResource(R.string.app_settings_camera),
        stringResource(
            when {
                !s.env.hasCamera -> R.string.app_settings_camera_none
                cameraRefused -> R.string.app_settings_camera_android_denied
                else -> R.string.app_settings_camera_hint
            }
        ),
        enabled = s.env.hasCamera,
    )
    if (s.settings.camera) {
        val cameraResolutions = s.env.cameraResolutions
        DropdownRow(
            selected = s.settings.cameraFrameRate,
            options = cameraResolutions.keys.sortedDescending().ifEmpty { listOf(s.settings.cameraFrameRate) },
            onSelect = { rate ->
                s.editSettings {
                    // A size the cameras don't give at the new rate: the largest they do below it
                    // (the largest they do for full resolution).
                    val sides = cameraResolutions[rate].orEmpty().map { it.shortSide }
                    val size = if (sides.isNotEmpty() && cameraResolution !in sides) {
                        if (cameraResolution == AndroidCameras.FULL_RESOLUTION) sides.first()
                        else sides.firstOrNull { it != AndroidCameras.FULL_RESOLUTION && it < cameraResolution } ?: sides.last()
                    } else cameraResolution
                    copy(cameraFrameRate = rate, cameraResolution = size)
                }
            },
            label = { resources.getString(R.string.app_settings_camera_frame_rate_value, it) },
            title = stringResource(R.string.app_settings_camera_frame_rate),
            hint = stringResource(R.string.app_settings_camera_frame_rate_hint) +
                if (s.settings.cameraFrameRate > AndroidCameras.COMMON_MAX_FRAME_RATE) " " + stringResource(R.string.app_settings_camera_frame_rate_high) else "",
        )
        val resolutions = cameraResolutions[s.settings.cameraFrameRate].orEmpty()
        DropdownRow(
            selected = s.settings.cameraResolution,
            options = resolutions.map { it.shortSide }.ifEmpty { listOf(s.settings.cameraResolution) },
            onSelect = { v -> s.editSettings { copy(cameraResolution = v) } },
            label = { side ->
                if (side == AndroidCameras.FULL_RESOLUTION) {
                    val sizes = resolutions.firstOrNull { it.shortSide == side }?.fullSizes.orEmpty()
                    resources.getString(R.string.app_settings_camera_resolution_full, sizes.joinToString(", ") { "${it.width}×${it.height}" })
                } else {
                    resources.getString(R.string.app_settings_camera_resolution_value, side)
                }
            },
            title = stringResource(R.string.app_settings_camera_resolution),
            hint = stringResource(R.string.app_settings_camera_resolution_hint),
        )
    }
    SwitchRow(
        s.settings.sharesClipboard, { v -> s.editSettings { copy(clipboard = v.takeIf { it != defaults.clipboard }) } },
        stringResource(R.string.app_settings_clipboard), stringResource(R.string.app_settings_clipboard_hint),
    )
    SwitchRow(
        s.settings.usesAndroidFonts, { v -> s.editSettings { copy(androidFonts = v.takeIf { it != defaults.androidFonts }) } },
        stringResource(R.string.app_settings_android_fonts), stringResource(R.string.app_settings_android_fonts_hint),
    )
    SwitchRow(
        s.settings.sharesTimeZone, { v -> s.editSettings { copy(timeZone = v.takeIf { it != defaults.timeZone }) } },
        stringResource(R.string.app_settings_time_zone), stringResource(R.string.app_settings_time_zone_hint),
    )
    SwitchRow(
        s.settings.sharesLanguage, { v -> s.editSettings { copy(language = v.takeIf { it != defaults.language }) } },
        stringResource(R.string.app_settings_language), stringResource(R.string.app_settings_language_hint),
    )
    if (s.env.developerOptions && app.requestsAllDevices) {
        SwitchRow(
            s.settings.wholeDev, { v -> s.editSettings { copy(wholeDev = v) } },
            stringResource(R.string.app_settings_whole_dev), stringResource(R.string.app_settings_whole_dev_hint),
            developer = true,
        )
    }
}

@Composable
fun AppOverridesPage(s: AppPageState) = SettingsPageColumn(R.string.app_page_overrides) {
    SwitchRow(
        s.settings.allowExtraSyscalls, { v -> s.editSettings { copy(allowExtraSyscalls = v) } },
        stringResource(R.string.app_settings_extra_syscalls), stringResource(R.string.app_settings_extra_syscalls_hint),
        developer = true,
    )
}

@Composable
fun AppStoragePage(s: AppPageState) = SettingsPageColumn(R.string.app_page_storage) {
    val env = s.env
    var confirm by rememberSaveable { mutableStateOf<StorageAction?>(null) }
    confirm?.let { action ->
        val (title, text, button) = when (action) {
            StorageAction.IMPORT_HOME -> Triple(R.string.app_settings_import_home_title, R.string.app_settings_import_home_text, R.string.storage_import_home)
            StorageAction.RESET_HOME -> Triple(R.string.app_settings_reset_home_title, R.string.app_settings_reset_home_text, R.string.storage_reset_home)
            StorageAction.IMPORT_SETTINGS -> Triple(R.string.app_settings_import_settings_title, R.string.app_settings_import_settings_text, R.string.storage_import_settings)
            else -> Triple(R.string.app_settings_reset_app_settings_title, R.string.app_settings_reset_app_settings_text, R.string.storage_reset_settings)
        }
        ConfirmDialog(
            stringResource(title, s.app.name), stringResource(text), stringResource(button),
            onConfirm = { confirm = null; env.onStorage(action) }, onDismiss = { confirm = null },
        )
    }
    val storage = env.storage
    val context = LocalContext.current
    val measuring = stringResource(R.string.app_settings_measuring)
    val measuringSize = stringResource(R.string.storage_measuring_size)
    // The last measured size stays shown while a new measurement runs.
    fun size(bytes: Long?) = bytes?.let { Formatter.formatShortFileSize(context, it) }
        ?.let { if (storage?.measuring == true) measuringSize.format(it) else it } ?: measuring
    val hasHome = (storage?.privateHome ?: 0L) > 0L
    Text(stringResource(R.string.app_settings_app_storage, size(storage?.total)), style = MaterialTheme.typography.titleMedium)

    Text(stringResource(R.string.app_settings_cache, size(storage?.cache)))
    OutlinedButton(onClick = { env.onStorage(StorageAction.CLEAR_CACHE) }, enabled = env.canChangeFiles && (storage?.cache ?: 0L) > 0L) {
        Text(stringResource(R.string.app_settings_clear_cache))
    }

    Text(stringResource(R.string.app_settings_private_home, size(storage?.privateHome)))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { env.onStorage(StorageAction.COPY_INTO_HOME) }, enabled = env.canChangeFiles) { Text(stringResource(R.string.storage_copy_into_home)) }
        OutlinedButton(onClick = { env.onStorage(StorageAction.DOWNLOAD_FROM_HOME) }, enabled = hasHome) { Text(stringResource(R.string.storage_download_from_home)) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { confirm = StorageAction.IMPORT_HOME }, enabled = env.canChangeFiles) { Text(stringResource(R.string.storage_import_home)) }
        OutlinedButton(onClick = { env.onStorage(StorageAction.EXPORT_HOME) }, enabled = hasHome) { Text(stringResource(R.string.storage_export_home)) }
        OutlinedButton(onClick = { confirm = StorageAction.RESET_HOME }, enabled = env.canChangeFiles && hasHome) {
            Text(stringResource(R.string.storage_reset_home), color = MaterialTheme.colorScheme.error)
        }
    }

    Text(stringResource(R.string.app_settings_settings, size(storage?.settings)))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { confirm = StorageAction.IMPORT_SETTINGS }, enabled = env.canChangeFiles) { Text(stringResource(R.string.storage_import_settings)) }
        OutlinedButton(onClick = { env.onStorage(StorageAction.EXPORT_SETTINGS) }, enabled = (storage?.settings ?: 0L) > 0L) { Text(stringResource(R.string.storage_export_settings)) }
        OutlinedButton(onClick = { confirm = StorageAction.RESET_SETTINGS }, enabled = env.canChangeFiles) {
            Text(stringResource(R.string.storage_reset_settings), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SizeField(value: String, label: String, isError: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(4)) },
        label = { Text(label) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun UninstallDialog(name: String, onConfirm: (deleteData: Boolean, keepRuntimes: Boolean) -> Unit, onDismiss: () -> Unit) {
    var deleteData by rememberSaveable { mutableStateOf(false) }
    var keepRuntimes by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.uninstall_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.uninstall_text))
                CheckboxRow(deleteData, { deleteData = it }, stringResource(R.string.uninstall_delete_data))
                CheckboxRow(keepRuntimes, { keepRuntimes = it }, stringResource(R.string.uninstall_keep_runtimes))
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(deleteData, keepRuntimes) }) { Text(stringResource(R.string.uninstall_confirm), color = MaterialTheme.colorScheme.onPrimary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reset_cancel)) } },
    )
}
