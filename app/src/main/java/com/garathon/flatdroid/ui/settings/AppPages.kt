package com.garathon.flatdroid.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.UploadFile
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.audio.MicStreamer
import com.garathon.flatdroid.camera.AndroidCameras
import com.garathon.flatdroid.flatpak.AppDetails
import com.garathon.flatdroid.settings.AppSettings
import com.garathon.flatdroid.settings.HomeMode
import com.garathon.flatdroid.settings.Resolution
import com.garathon.flatdroid.ui.CheckboxRow
import com.garathon.flatdroid.ui.ConfirmDialog
import com.garathon.flatdroid.ui.DropdownRow
import com.garathon.flatdroid.ui.IconAction
import com.garathon.flatdroid.ui.SettingsCard
import com.garathon.flatdroid.ui.StorageAction
import com.garathon.flatdroid.ui.SwitchRow
import com.garathon.flatdroid.ui.TextRow
import com.garathon.flatdroid.ui.home.AppIcon
import java.text.DateFormat
import java.util.Date

@Composable
fun AppOverviewPage(s: AppPageState) = SettingsPageColumn(null) {
    var confirmUninstall by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf<OverviewConfirm?>(null) }
    if (confirmUninstall) {
        UninstallDialog(
            s.app.name,
            onConfirm = { data, runtimes -> confirmUninstall = false; s.env.onUninstall(data, runtimes) },
            onDismiss = { confirmUninstall = false },
        )
    }
    confirm?.let { what ->
        val data = what == OverviewConfirm.APP_DATA
        ConfirmDialog(
            stringResource(if (data) R.string.app_settings_reset_app_settings_title else R.string.app_reset_flatdroid_title, s.app.name),
            stringResource(if (data) R.string.app_settings_reset_app_settings_text else R.string.app_reset_flatdroid_text),
            stringResource(if (data) R.string.storage_reset_app_data else R.string.app_reset_flatdroid_confirm).removeSuffix("…"),
            onConfirm = { confirm = null; if (data) s.env.onStorage(StorageAction.RESET_SETTINGS) else s.env.onResetFlatdroidSettings() },
            onDismiss = { confirm = null },
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
    PermissionChips(s)
    SettingsCard {
        SwitchRow(
            s.settings.continueWhileHidden, { v -> s.editSettings { copy(continueWhileHidden = v) } },
            stringResource(R.string.app_settings_continue_hidden), stringResource(R.string.app_settings_continue_hidden_hint),
        )
    }
    UsageAndDetails(s)
    if (s.env.developerOptions) {
        SettingsCard {
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
    }
    SettingsCard(title = stringResource(R.string.settings_section_danger), danger = true) {
        OutlinedButton(onClick = { confirm = OverviewConfirm.APP_DATA }, enabled = s.env.canChangeFiles) {
            Text(stringResource(R.string.storage_reset_app_data), color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(onClick = { confirm = OverviewConfirm.FLATDROID_SETTINGS }, enabled = s.env.canChangeFiles) {
            Text(stringResource(R.string.app_reset_flatdroid_settings), color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(onClick = { confirmUninstall = true }, enabled = s.env.canUninstall) {
            Text(stringResource(R.string.uninstall_button), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** How long the app was used, and what its metadata says about it. */
@Composable
private fun UsageAndDetails(s: AppPageState) {
    val context = LocalContext.current
    val app = s.app
    SettingsCard(title = stringResource(R.string.app_overview_used)) {
        Text(
            if (app.launches == 0) stringResource(R.string.app_overview_not_used)
            else stringResource(R.string.app_overview_used_value, durationText(app.usedMillis), app.launches),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    val details = s.env.details ?: return
    SettingsCard(title = stringResource(R.string.app_overview_details)) {
        val display = stringResource(
            when (details.display) {
                AppDetails.Display.WAYLAND_AND_X11 -> R.string.app_detail_display_both
                AppDetails.Display.WAYLAND -> R.string.app_detail_display_wayland
                AppDetails.Display.X11 -> R.string.app_detail_display_x11
                AppDetails.Display.NONE -> R.string.app_detail_display_none
            }
        )
        val rows = listOfNotNull(
            details.runtime?.let { R.string.app_detail_runtime to it },
            R.string.app_detail_display to display,
            details.installed?.let { R.string.app_detail_installed to DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) },
            details.sockets.takeIf { it.isNotEmpty() }?.let { R.string.app_detail_sockets to it.joinToString(", ") },
            details.devices.takeIf { it.isNotEmpty() }?.let { R.string.app_detail_devices to it.joinToString(", ") },
            details.filesystems.takeIf { it.isNotEmpty() }?.let { R.string.app_detail_files to it.joinToString(", ") },
            details.shared.takeIf { it.isNotEmpty() }?.let { R.string.app_detail_shared to it.joinToString(", ") },
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((label, value) in rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(context.getString(label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(84.dp))
                    Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private enum class OverviewConfirm { APP_DATA, FLATDROID_SETTINGS }

/**
 * What the app's metadata asks for and Flatdroid controls (network, sound, all devices, files),
 * and the camera and microphone when they are on. What the app is granted is filled and bold, what
 * it asks for and doesn't get is outlined.
 */
@Composable
private fun PermissionChips(s: AppPageState) {
    val app = s.app
    val settings = s.settings
    val defaults = settings.defaults
    val context = LocalContext.current
    val network = settings.network ?: (defaults.permissionsFromMetadata && app.requestsNetwork)
    val audio = settings.audio ?: (defaults.permissionsFromMetadata && app.requestsAudio)
    val files = settings.homeMode == HomeMode.SHARED || settings.homeMode == HomeMode.ANDROID || settings.sdcard
    val chips = buildList {
        if (app.requestsNetwork) add(stringResource(R.string.app_settings_network) to network)
        if (app.requestsAudio) add(stringResource(R.string.app_settings_audio) to audio)
        if (app.requestsAllDevices) add(stringResource(R.string.permission_all_devices) to (!s.env.limitDevices || settings.wholeDev))
        if (app.requestsFiles) add(stringResource(R.string.permission_files) to files)
        if (audio && (settings.microphone ?: (defaults.permissionsFromMetadata && app.requestsAudio)) && MicStreamer.hasPermission(context)) add(stringResource(R.string.app_settings_microphone) to true)
        if ((settings.camera ?: (defaults.permissionsFromMetadata && app.mayUseCamera)) && AndroidCameras.hasPermission(context)) add(stringResource(R.string.app_settings_camera) to true)
    }
    SettingsCard(title = stringResource(R.string.app_overview_asks)) {
        if (chips.isEmpty()) {
            Text(stringResource(R.string.app_overview_asks_nothing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, granted) in chips) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (granted) FontWeight.Bold else FontWeight.Normal,
                        color = if (granted) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.medium)
                            .background(if (granted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .then(if (granted) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun AppHomePage(s: AppPageState) = SettingsPageColumn(R.string.app_page_home) {
    val defaults = s.settings.defaults
    val names = homeModeNames()
    val mode = s.settings.homeMode
    SettingsCard {
        DropdownRow(
            selected = mode,
            // Android's storage as the home needs All files access; it stays listed while chosen, so the choice shows.
            options = HomeMode.entries.filter { it != HomeMode.ANDROID || s.env.broadAccess || mode == HomeMode.ANDROID },
            onSelect = { v -> s.editSettings { copy(homeModeChoice = v.takeIf { it != defaults.homeMode }) } },
            label = names::getValue,
            title = stringResource(R.string.app_page_home),
            hint = when (mode) {
                HomeMode.SHARED -> stringResource(R.string.app_settings_home_shared_hint, s.env.sharedHome)
                HomeMode.PRIVATE -> stringResource(R.string.app_settings_home_private_hint)
                HomeMode.TEMPORARY -> stringResource(R.string.app_settings_home_temporary_hint)
                HomeMode.ANDROID -> stringResource(R.string.app_settings_home_android_hint)
            },
            onReset = if (s.settings.homeModeChoice != null) ({ s.editSettings { copy(homeModeChoice = null) } }) else null,
            resettable = true,
        )
        SwitchRow(
            s.settings.sdcard && s.env.broadAccess, { v -> s.editSettings { copy(sdcard = v) } },
            stringResource(R.string.app_settings_sdcard),
            stringResource(
                when {
                    !s.env.broadAccess -> R.string.app_settings_sdcard_needs_access
                    else -> R.string.app_settings_sdcard_hint
                }
            ),
            enabled = s.env.broadAccess && mode != HomeMode.ANDROID,
        )
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
    SettingsCard {
        DropdownRow(
            selected = s.settings.resolution,
            options = Resolution.entries,
            onSelect = { v -> s.editSettings { copy(resolutionChoice = v.takeIf { it != defaults.resolution }) } },
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
        if (app.wayland) {
            DropdownRow(
                selected = s.settings.interfaceSize,
                options = AppSettings.INTERFACE_SIZES,
                onSelect = { v -> s.editSettings { copy(interfaceSizeChoice = v.takeIf { it != defaults.interfaceSize }) } },
                label = { percentLabel.format(it) },
                title = stringResource(R.string.app_settings_interface_size),
                hint = stringResource(R.string.app_settings_interface_size_hint),
                enabled = !s.settings.forceX11
            )
        }
        // Only for apps that ask for Wayland as well as X11.
        if (app.waylandAndX11) {
            SwitchRow(
                s.settings.forceX11 && app.waylandAndX11, { v -> s.editSettings { copy(forceX11 = v) } },
                stringResource(R.string.app_settings_force_x11),
                stringResource(if (app.waylandAndX11) R.string.app_settings_force_x11_hint else R.string.app_settings_force_x11_unavailable),
            )
        }
    }
}

@Composable
fun AppPermissionsPage(s: AppPageState) = SettingsPageColumn(R.string.app_page_permissions) {
    val app = s.app
    val defaults = s.settings.defaults
    val context = LocalContext.current
    var micRefused by rememberSaveable { mutableStateOf(false) }
    var cameraRefused by rememberSaveable { mutableStateOf(false) }
    val fromMetadata = defaults.permissionsFromMetadata
    val audioDefault = fromMetadata && app.requestsAudio
    val networkDefault = fromMetadata && app.requestsNetwork
    val audio = s.settings.audio ?: audioDefault
    val network = s.settings.network ?: networkDefault

    SettingsCard {
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
            onReset = if (s.settings.network != null) ({ s.editSettings { copy(network = null) } }) else null,
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
            onReset = if (s.settings.audio != null) ({ s.editSettings { copy(audio = null) } }) else null,
        )
        // On by default for an app that asks for sound, and for a hint that it uses a camera; both need Android's permission too.
        val micDefault = fromMetadata && app.requestsAudio
        val micSet = s.settings.microphone
        val micOn = (micSet ?: micDefault) && MicStreamer.hasPermission(context)
        fun setMicrophone(on: Boolean) = s.editSettings { copy(microphone = on.takeIf { it != micDefault }) }
        SwitchRow(
            micOn,
            { on ->
                micRefused = false
                if (!on || MicStreamer.hasPermission(context)) {
                    setMicrophone(on)
                } else {
                    s.env.onRequestMicrophone { granted -> if (granted) setMicrophone(true) else micRefused = true }
                }
            },
            stringResource(R.string.app_settings_microphone),
            stringResource(if (micRefused) R.string.app_settings_microphone_android_denied else R.string.app_settings_microphone_hint),
            enabled = audio,
            onReset = if (micSet != null && micSet != micDefault) ({ s.editSettings { copy(microphone = null) } }) else null,
        )
        SwitchRow(
            s.settings.alwaysAllowMicrophone, { v -> s.editSettings { copy(alwaysAllowMicrophone = v) } },
            stringResource(R.string.app_settings_always_allow_microphone), stringResource(R.string.app_settings_always_allow_microphone_hint),
            enabled = audio && micOn,
        )
        
        val cameraDefault = fromMetadata && app.mayUseCamera
        val cameraSet = s.settings.camera
        val cameraOn = (cameraSet ?: cameraDefault) && AndroidCameras.hasPermission(context)
        fun setCamera(on: Boolean) = s.editSettings { copy(camera = on.takeIf { it != cameraDefault }) }
        SwitchRow(
            cameraOn,
            { on ->
                cameraRefused = false
                if (!on || AndroidCameras.hasPermission(context)) {
                    setCamera(on)
                } else {
                    s.env.onRequestCamera { granted -> if (granted) setCamera(true) else cameraRefused = true }
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
            onReset = if (cameraSet != null && cameraSet != cameraDefault) ({ s.editSettings { copy(camera = null) } }) else null,
        )
        SwitchRow(
            s.settings.sharesClipboard, { v -> s.editSettings { copy(clipboard = v.takeIf { it != defaults.clipboard }) } },
            stringResource(R.string.app_settings_clipboard),
            stringResource(R.string.app_settings_clipboard_hint),
            onReset = if (s.settings.clipboard != null) ({ s.editSettings { copy(clipboard = null) } }) else null,
        )
        SwitchRow(
            s.settings.wholeDev && app.requestsAllDevices, { v -> s.editSettings { copy(wholeDev = v) } },
            stringResource(R.string.app_settings_whole_dev), stringResource(R.string.app_settings_whole_dev_hint),
            enabled = app.requestsAllDevices, developer = true,
        )
    }
}

/**
 * The developer overrides of what Flatdroid sets for every app: the system call filter (the
 * security level's choice) and the camera's frame rate and size (Flatdroid's camera settings).
 * Each shows the value in effect; the arrow beside an override removes it.
 */
@Composable
fun AppOverridesPage(s: AppPageState) = SettingsPageColumn(R.string.app_page_overrides) {
    val defaults = s.settings.defaults
    Text(stringResource(R.string.app_overrides_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SettingsCard {
        SwitchRow(
            s.settings.blocksExtraSyscalls, { v -> s.editSettings { copy(blockExtraSyscalls = v.takeIf { it != defaults.blockExtraSyscalls }) } },
            stringResource(R.string.app_settings_extra_syscalls),
            stringResource(R.string.app_settings_extra_syscalls_hint),
            developer = true,
            onReset = if (s.settings.blockExtraSyscalls != null) ({ s.editSettings { copy(blockExtraSyscalls = null) } }) else null,
        )
        // The camera's settings need the app's camera and one on the phone.
        val cameraOn = s.settings.camera ?: (defaults.permissionsFromMetadata && s.app.mayUseCamera)
        val cameraEnabled = cameraOn && s.env.hasCamera
        CameraRows(
            rate = s.settings.cameraFrameRate,
            resolution = s.settings.cameraResolution,
            resolutions = s.env.cameraResolutions,
            enabled = cameraEnabled,
            rateHint = stringResource(if (cameraOn) R.string.app_settings_camera_frame_rate_hint else R.string.app_overrides_camera_off),
            resolutionHint = stringResource(if (cameraOn) R.string.app_settings_camera_resolution_hint else R.string.app_overrides_camera_off),
            onRate = { rate, size ->
                s.editSettings {
                    copy(
                        cameraFrameRateOverride = rate.takeIf { it != defaults.cameraFrameRate },
                        cameraResolutionOverride = size.takeIf { it != defaults.cameraResolution },
                    )
                }
            },
            onResolution = { v -> s.editSettings { copy(cameraResolutionOverride = v.takeIf { it != defaults.cameraResolution }) } },
            developer = true,
            onResetRate = if (s.settings.cameraFrameRateOverride != null) ({ s.editSettings { copy(cameraFrameRateOverride = null) } }) else null,
            onResetResolution = if (s.settings.cameraResolutionOverride != null) ({ s.editSettings { copy(cameraResolutionOverride = null) } }) else null,
            resettable = true,
        )
    }
}

/**
 * What the app takes: the size bar, then its app home (copy files in and out, import, export,
 * open in Android's Files app), its app data (import, export) and its cache (clear); resetting
 * the home or the data is in the danger group at the end. These take effect at once.
 */
@Composable
fun AppStoragePage(s: AppPageState) = SettingsPageColumn(R.string.app_page_storage) {
    val env = s.env
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    confirm?.let { key ->
        StorageConfirmDialog(
            key, { s.app.name }, null, null,
            confirmed = StorageConfirmed(onAppAction = { _, action -> env.onStorage(action) }, onGlobal = {}),
            onDismiss = { confirm = null },
        )
    }
    val storage = env.storage
    val context = LocalContext.current
    val measuring = stringResource(R.string.app_settings_measuring)
    val measuringSize = stringResource(R.string.storage_measuring_size)
    // The last measured size stays shown while a new measurement runs.
    fun size(bytes: Long?) = bytes?.let { Formatter.formatShortFileSize(context, it) }
        ?.let { if (storage?.measuring == true) measuringSize.format(it) else it } ?: measuring
    val colors = storageColors()
    val hasHome = (storage?.privateHome ?: 0L) > 0L
    val hasData = (storage?.settings ?: 0L) > 0L
    val canChange = env.canChangeFiles

    SettingsCard {
        Text(stringResource(R.string.app_settings_app_storage, size(storage?.total)), style = MaterialTheme.typography.titleMedium)
        StorageBar(
            listOf(
                StorageSegment(R.string.storage_part_apps, storage?.app, colors[1]),
                StorageSegment(R.string.storage_part_app_data, storage?.settings, colors[2]),
                StorageSegment(R.string.storage_part_home, storage?.privateHome, colors[3]),
                StorageSegment(R.string.storage_part_cache, storage?.cache, colors[4]),
            ),
        )
        Column {
            StorageRow(stringResource(R.string.storage_part_home), size(storage?.privateHome)) {
                IconAction(Icons.Filled.FolderOpen, R.string.storage_open_in_files, { env.onOpenHome() }, enabled = hasHome)
                IconAction(Icons.Filled.UploadFile, R.string.storage_import, { confirm = StorageConfirm.app(s.app.id, StorageAction.IMPORT_HOME) }, enabled = canChange)
                IconAction(Icons.Filled.SaveAlt, R.string.storage_export, { env.onStorage(StorageAction.EXPORT_HOME) }, enabled = hasHome)
            }
            StorageRow(stringResource(R.string.storage_part_app_data), size(storage?.settings)) {
                IconAction(Icons.Filled.UploadFile, R.string.storage_import, { confirm = StorageConfirm.app(s.app.id, StorageAction.IMPORT_SETTINGS) }, enabled = canChange)
                IconAction(Icons.Filled.SaveAlt, R.string.storage_export, { env.onStorage(StorageAction.EXPORT_SETTINGS) }, enabled = hasData)
            }
            StorageRow(stringResource(R.string.storage_part_cache), size(storage?.cache)) {
                IconAction(Icons.Filled.DeleteSweep, R.string.app_settings_clear_cache, { env.onStorage(StorageAction.CLEAR_CACHE) }, enabled = canChange && (storage?.cache ?: 0L) > 0L)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { env.onStorage(StorageAction.COPY_INTO_HOME) }, enabled = canChange) { Text(stringResource(R.string.storage_copy_into_home)) }
            OutlinedButton(onClick = { env.onStorage(StorageAction.DOWNLOAD_FROM_HOME) }, enabled = hasHome) { Text(stringResource(R.string.storage_download_from_home)) }
        }
    }

    SettingsCard(title = stringResource(R.string.settings_section_danger), danger = true) {
        OutlinedButton(onClick = { confirm = StorageConfirm.app(s.app.id, StorageAction.RESET_HOME) }, enabled = canChange && hasHome) {
            Text(stringResource(R.string.storage_reset_home), color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(onClick = { confirm = StorageConfirm.app(s.app.id, StorageAction.RESET_SETTINGS) }, enabled = canChange && hasData) {
            Text(stringResource(R.string.storage_reset_app_data), color = MaterialTheme.colorScheme.error)
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
internal fun UninstallDialog(name: String, onConfirm: (deleteData: Boolean, keepRuntimes: Boolean) -> Unit, onDismiss: () -> Unit) {
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
