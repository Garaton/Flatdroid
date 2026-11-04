package com.garathon.flatdroid.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.audio.MicStreamer
import com.garathon.flatdroid.camera.AndroidCameras
import com.garathon.flatdroid.flatpak.InstalledApp
import com.garathon.flatdroid.settings.AppSettings
import com.garathon.flatdroid.settings.EnvironmentText
import com.garathon.flatdroid.settings.HomeMode
import com.garathon.flatdroid.settings.LaunchArgs
import com.garathon.flatdroid.settings.Resolution
import com.garathon.flatdroid.storage.AppStorage
import com.garathon.flatdroid.ui.theme.FlatdroidTheme

/**
 * One app's settings: its display size, its permissions (sound, network and microphone), its cache, and
 * uninstalling; with [developerOptions], for apps asking for all devices, also the host's whole
 * /dev, and the security level's extra system calls. Sound, network and the home left at their
 * defaults are saved as unset, so they follow the security level and the app's metadata.
 * [onRequestMicrophone] asks Android for its microphone permission and reports whether it was
 * granted: the microphone turns on only with it, so a notification can later allow it for a
 * session, unless it is always allowed. The camera likewise ([onRequestCamera]), when the device
 * has one ([hasCamera]), at a frame rate its cameras can run at and a size they give at that
 * rate ([cameraResolutions], by rate; empty while still asked). A setting that
 * needs another one is shown only while that one is on. With [developerOptions], the app's
 * environment variables can be set. Its home is the shared one ([sharedHome], as the user knows it), a
 * private one or a temporary one; with access to all of shared storage ([broadAccess]) it can
 * also get /sdcard. Its Storage section shows its sizes ([storage]) and asks for copies, exports,
 * imports and resets of its cache, private home and settings ([onStorage]), whatever its home
 * mode.
 */
@Composable
fun AppSettingsScreen(
    app: InstalledApp,
    settings: AppSettings,
    /** The app's storage; null while it is being measured. */
    storage: AppStorage?,
    /** False while the app runs or its files are being copied. */
    canChangeFiles: Boolean,
    /** False while any app or a Flatpak task runs. */
    canUninstall: Boolean,
    developerOptions: Boolean,
    onRequestMicrophone: (onResult: (Boolean) -> Unit) -> Unit,
    hasCamera: Boolean,
    cameraResolutions: Map<Int, List<AndroidCameras.Resolution>>,
    onRequestCamera: (onResult: (Boolean) -> Unit) -> Unit,
    onSave: (AppSettings) -> Unit,
    onBack: () -> Unit,
    onUninstall: (deleteData: Boolean, keepRuntimes: Boolean) -> Unit,
    sharedHome: String,
    broadAccess: Boolean,
    onStorage: (StorageAction) -> Unit,
    modifier: Modifier = Modifier,
    /** An Electron app or a Chromium browser ([com.garathon.flatdroid.flatpak.InstalledApps.showsWebContent]). */
    showsWebContent: Boolean = false,
) {
    var choice by rememberSaveable { mutableStateOf(settings.resolution) }
    var width by rememberSaveable { mutableStateOf(settings.customWidth.toString()) }
    var height by rememberSaveable { mutableStateOf(settings.customHeight.toString()) }
    var wholeDev by rememberSaveable { mutableStateOf(settings.wholeDev) }
    val fromMetadata = settings.defaults.permissionsFromMetadata
    val audioDefault = fromMetadata && app.requestsAudio
    var audio by rememberSaveable { mutableStateOf(settings.audio ?: audioDefault) }
    val networkDefault = fromMetadata && app.requestsNetwork
    var network by rememberSaveable { mutableStateOf(settings.network ?: networkDefault) }
    var clipboard by rememberSaveable { mutableStateOf(settings.sharesClipboard) }
    var androidFonts by rememberSaveable { mutableStateOf(settings.usesAndroidFonts) }
    var timeZone by rememberSaveable { mutableStateOf(settings.sharesTimeZone) }
    var language by rememberSaveable { mutableStateOf(settings.sharesLanguage) }
    var allowExtraSyscalls by rememberSaveable { mutableStateOf(settings.allowExtraSyscalls) }
    var shareHiddenFiles by rememberSaveable { mutableStateOf(settings.shareHiddenFiles) }
    var microphone by rememberSaveable { mutableStateOf(settings.microphone) }
    var alwaysAllowMicrophone by rememberSaveable { mutableStateOf(settings.alwaysAllowMicrophone) }
    var camera by rememberSaveable { mutableStateOf(settings.camera) }
    var cameraResolution by rememberSaveable { mutableStateOf(settings.cameraResolution) }
    var cameraFrameRate by rememberSaveable { mutableStateOf(settings.cameraFrameRate) }
    var cameraRefused by rememberSaveable { mutableStateOf(false) }
    var environment by rememberSaveable { mutableStateOf(EnvironmentText.format(settings.environment)) }
    var launchArgs by rememberSaveable { mutableStateOf(LaunchArgs.format(settings.launchArgs)) }
    var forceX11 by rememberSaveable { mutableStateOf(settings.forceX11) }
    var homeMode by rememberSaveable { mutableStateOf(settings.homeMode) }
    var sdcard by rememberSaveable { mutableStateOf(settings.sdcard) }
    val environmentValue = EnvironmentText.parse(environment)
    var micRefused by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // Configuration-aware (unlike context.getString from LocalContext): used in the label lambdas
    // below, which run per item and so can't call the composable stringResource.
    val resources = LocalResources.current
    var confirmUninstall by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf<StorageAction?>(null) }

    fun size(text: String) = text.toIntOrNull()?.takeIf { it in AppSettings.MIN_SIZE..AppSettings.MAX_SIZE }
    val customValid = size(width) != null && size(height) != null

    confirm?.let { action ->
        val (title, text, button) = when (action) {
            StorageAction.IMPORT_HOME -> Triple(R.string.app_settings_import_home_title, R.string.app_settings_import_home_text, R.string.storage_import_home)
            StorageAction.RESET_HOME -> Triple(R.string.app_settings_reset_home_title, R.string.app_settings_reset_home_text, R.string.storage_reset_home)
            StorageAction.IMPORT_SETTINGS -> Triple(R.string.app_settings_import_settings_title, R.string.app_settings_import_settings_text, R.string.storage_import_settings)
            else -> Triple(R.string.app_settings_reset_app_settings_title, R.string.app_settings_reset_app_settings_text, R.string.storage_reset_settings)
        }
        ConfirmDialog(
            stringResource(title, app.name), stringResource(text), stringResource(button),
            onConfirm = { confirm = null; onStorage(action) }, onDismiss = { confirm = null },
        )
    }
    if (confirmUninstall) {
        UninstallDialog(app.name, onConfirm = { data, runtimes -> confirmUninstall = false; onUninstall(data, runtimes) }, onDismiss = { confirmUninstall = false })
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
        if (showsWebContent) {
            Text(stringResource(R.string.app_settings_web_content), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        SectionTitle(stringResource(R.string.app_settings_display))
        val customLabel = stringResource(R.string.app_settings_custom)
        val percentLabel = stringResource(R.string.app_settings_resolution_percent)
        DropdownRow(
            selected = choice,
            options = Resolution.entries,
            onSelect = { choice = it },
            label = { it.percent?.let { p -> percentLabel.format(p) } ?: customLabel },
            title = stringResource(R.string.app_settings_resolution),
            hint = stringResource(R.string.app_settings_resolution_hint),
        )
        // Only for apps that ask for Wayland as well as X11.
        if (app.waylandAndX11) SwitchRow(
            forceX11, { forceX11 = it },
            stringResource(R.string.app_settings_force_x11), stringResource(R.string.app_settings_force_x11_hint),
        )
        if (choice == Resolution.CUSTOM) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SizeField(width, stringResource(R.string.app_settings_width), size(width) == null, Modifier.width(120.dp)) { width = it }
                Text("×")
                SizeField(height, stringResource(R.string.app_settings_height), size(height) == null, Modifier.width(120.dp)) { height = it }
            }
            if (!customValid) {
                Text(
                    stringResource(R.string.app_settings_custom_invalid, AppSettings.MIN_SIZE, AppSettings.MAX_SIZE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SectionTitle(stringResource(R.string.app_settings_permissions))
        SwitchRow(
            network, { network = it },
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
            audio, { audio = it },
            stringResource(R.string.app_settings_audio),
            stringResource(
                when {
                    !app.requestsAudio -> R.string.app_settings_audio_not_requested
                    fromMetadata -> R.string.app_settings_audio_requested
                    else -> R.string.app_settings_audio_requested_off
                }
            ),
        )
        if (audio) SwitchRow(
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
        )
        if (audio && microphone) {
            SwitchRow(
                alwaysAllowMicrophone, { alwaysAllowMicrophone = it },
                stringResource(R.string.app_settings_always_allow_microphone), stringResource(R.string.app_settings_always_allow_microphone_hint),
            )
        }
        SwitchRow(
            camera,
            { on ->
                cameraRefused = false
                if (!on || AndroidCameras.hasPermission(context)) {
                    camera = on
                } else {
                    onRequestCamera { granted -> if (granted) camera = true else cameraRefused = true }
                }
            },
            stringResource(R.string.app_settings_camera),
            stringResource(
                when {
                    !hasCamera -> R.string.app_settings_camera_none
                    cameraRefused -> R.string.app_settings_camera_android_denied
                    else -> R.string.app_settings_camera_hint
                }
            ),
            enabled = hasCamera,
        )
        if (camera) {
            DropdownRow(
                selected = cameraFrameRate,
                options = cameraResolutions.keys.sortedDescending().ifEmpty { listOf(cameraFrameRate) },
                onSelect = { rate ->
                    cameraFrameRate = rate
                    // A size the cameras don't give at the new rate: the largest they do below it
                    // (the largest they do for full resolution).
                    val sides = cameraResolutions[rate].orEmpty().map { it.shortSide }
                    if (sides.isNotEmpty() && cameraResolution !in sides) {
                        cameraResolution = if (cameraResolution == AndroidCameras.FULL_RESOLUTION) sides.first()
                        else sides.firstOrNull { it != AndroidCameras.FULL_RESOLUTION && it < cameraResolution } ?: sides.last()
                    }
                },
                label = { resources.getString(R.string.app_settings_camera_frame_rate_value, it) },
                title = stringResource(R.string.app_settings_camera_frame_rate),
                hint = stringResource(R.string.app_settings_camera_frame_rate_hint) +
                    if (cameraFrameRate > AndroidCameras.COMMON_MAX_FRAME_RATE) " " + stringResource(R.string.app_settings_camera_frame_rate_high) else "",
            )
            val resolutions = cameraResolutions[cameraFrameRate].orEmpty()
            DropdownRow(
                selected = cameraResolution,
                options = resolutions.map { it.shortSide }.ifEmpty { listOf(cameraResolution) },
                onSelect = { cameraResolution = it },
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
            clipboard, { clipboard = it },
            stringResource(R.string.app_settings_clipboard), stringResource(R.string.app_settings_clipboard_hint),
        )
        SwitchRow(
            androidFonts, { androidFonts = it },
            stringResource(R.string.app_settings_android_fonts), stringResource(R.string.app_settings_android_fonts_hint),
        )
        SwitchRow(
            timeZone, { timeZone = it },
            stringResource(R.string.app_settings_time_zone), stringResource(R.string.app_settings_time_zone_hint),
        )
        SwitchRow(
            language, { language = it },
            stringResource(R.string.app_settings_language), stringResource(R.string.app_settings_language_hint),
        )

        SectionTitle(stringResource(R.string.app_settings_files))
        Column(Modifier.selectableGroup()) {
            HomeModeRow(homeMode == HomeMode.SHARED, { homeMode = HomeMode.SHARED },
                stringResource(R.string.app_settings_home_shared), stringResource(R.string.app_settings_home_shared_hint, sharedHome))
            HomeModeRow(homeMode == HomeMode.PRIVATE, { homeMode = HomeMode.PRIVATE },
                stringResource(R.string.app_settings_home_private), stringResource(R.string.app_settings_home_private_hint))
            HomeModeRow(homeMode == HomeMode.TEMPORARY, { homeMode = HomeMode.TEMPORARY },
                stringResource(R.string.app_settings_home_temporary), stringResource(R.string.app_settings_home_temporary_hint))
        }
        if (broadAccess) {
            SwitchRow(
                sdcard, { sdcard = it },
                stringResource(R.string.app_settings_sdcard), stringResource(R.string.app_settings_sdcard_hint),
            )
        }
        if (developerOptions) {
            SwitchRow(
                shareHiddenFiles, { shareHiddenFiles = it },
                stringResource(R.string.app_settings_share_hidden), stringResource(R.string.app_settings_share_hidden_hint),
                developer = true,
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

        if (developerOptions) {
            SectionTitle(stringResource(R.string.app_settings_launch))
            SwitchRow(
                allowExtraSyscalls, { allowExtraSyscalls = it },
                stringResource(R.string.app_settings_extra_syscalls), stringResource(R.string.app_settings_extra_syscalls_hint),
                developer = true,
            )
            TextRow(
                content = environment,
                onChange = { environment = it },
                title = stringResource(R.string.app_settings_environment),
                hint = stringResource(if (environmentValue == null) R.string.app_settings_environment_invalid else R.string.app_settings_environment_hint),
                isError = environmentValue == null,
                developer = true,
                singleLine = false,
                keyboardType = KeyboardType.Ascii,
            )
            TextRow(
                content = launchArgs,
                onChange = { launchArgs = it },
                title = stringResource(R.string.app_settings_launch_args),
                hint = stringResource(R.string.app_settings_launch_args_hint),
                isError = false,
                developer = true,
                keyboardType = KeyboardType.Ascii,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(
                        settings.copy(
                            resolution = choice,
                            // Valid typed values are kept even while another size is chosen.
                            customWidth = size(width) ?: settings.customWidth,
                            customHeight = size(height) ?: settings.customHeight,
                            wholeDev = wholeDev,
                            audio = audio.takeIf { it != audioDefault },
                            network = network.takeIf { it != networkDefault },
                            clipboard = clipboard.takeIf { it != settings.defaults.clipboard },
                            androidFonts = androidFonts.takeIf { it != settings.defaults.androidFonts },
                            timeZone = timeZone.takeIf { it != settings.defaults.timeZone },
                            language = language.takeIf { it != settings.defaults.language },
                            allowExtraSyscalls = if (developerOptions) allowExtraSyscalls else settings.allowExtraSyscalls,
                            shareHiddenFiles = if (developerOptions) shareHiddenFiles else settings.shareHiddenFiles,
                            microphone = microphone,
                            alwaysAllowMicrophone = alwaysAllowMicrophone,
                            camera = camera,
                            cameraResolution = cameraResolution,
                            cameraFrameRate = cameraFrameRate,
                            environment = if (developerOptions) environmentValue.orEmpty() else settings.environment,
                            launchArgs = if (developerOptions) LaunchArgs.parse(launchArgs) else settings.launchArgs,
                            forceX11 = forceX11,
                            homeModeChoice = homeMode.takeIf { it != settings.defaults.homeMode },
                            sdcard = if (broadAccess) sdcard else settings.sdcard,
                        )
                    )
                },
                enabled = (choice != Resolution.CUSTOM || customValid) && (!developerOptions || environmentValue != null),
            ) { Text(stringResource(R.string.settings_save)) }
            TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
        }

        SectionTitle(stringResource(R.string.app_settings_storage))
        val context = LocalContext.current
        val measuring = stringResource(R.string.app_settings_measuring)
        val measuringSize = stringResource(R.string.storage_measuring_size)
        // The last measured size stays shown while a new measurement runs.
        fun size(bytes: Long?) = bytes?.let { Formatter.formatShortFileSize(context, it) }
            ?.let { if (storage?.measuring == true) measuringSize.format(it) else it } ?: measuring
        val hasHome = (storage?.privateHome ?: 0L) > 0L
        Text(stringResource(R.string.app_settings_app_storage, size(storage?.total)), style = MaterialTheme.typography.titleMedium)

        Text(stringResource(R.string.app_settings_cache, size(storage?.cache)))
        OutlinedButton(onClick = { onStorage(StorageAction.CLEAR_CACHE) }, enabled = canChangeFiles && (storage?.cache ?: 0L) > 0L) {
            Text(stringResource(R.string.app_settings_clear_cache))
        }

        Text(stringResource(R.string.app_settings_private_home, size(storage?.privateHome)))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onStorage(StorageAction.COPY_INTO_HOME) }, enabled = canChangeFiles) { Text(stringResource(R.string.storage_copy_into_home)) }
            OutlinedButton(onClick = { onStorage(StorageAction.DOWNLOAD_FROM_HOME) }, enabled = hasHome) { Text(stringResource(R.string.storage_download_from_home)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirm = StorageAction.IMPORT_HOME }, enabled = canChangeFiles) { Text(stringResource(R.string.storage_import_home)) }
            OutlinedButton(onClick = { onStorage(StorageAction.EXPORT_HOME) }, enabled = hasHome) { Text(stringResource(R.string.storage_export_home)) }
            OutlinedButton(onClick = { confirm = StorageAction.RESET_HOME }, enabled = canChangeFiles && hasHome) {
                Text(stringResource(R.string.storage_reset_home), color = MaterialTheme.colorScheme.error)
            }
        }

        Text(stringResource(R.string.app_settings_settings, size(storage?.settings)))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirm = StorageAction.IMPORT_SETTINGS }, enabled = canChangeFiles) { Text(stringResource(R.string.storage_import_settings)) }
            OutlinedButton(onClick = { onStorage(StorageAction.EXPORT_SETTINGS) }, enabled = (storage?.settings ?: 0L) > 0L) { Text(stringResource(R.string.storage_export_settings)) }
            OutlinedButton(onClick = { confirm = StorageAction.RESET_SETTINGS }, enabled = canChangeFiles) {
                Text(stringResource(R.string.storage_reset_settings), color = MaterialTheme.colorScheme.error)
            }
        }

        SectionTitle(stringResource(R.string.settings_section_danger), danger = true)
        OutlinedButton(onClick = { confirmUninstall = true }, enabled = canUninstall) {
            Text(stringResource(R.string.uninstall_button), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** What the app's Storage section asks for; the ones that replace or delete files were confirmed. */
enum class StorageAction {
    CLEAR_CACHE, COPY_INTO_HOME, DOWNLOAD_FROM_HOME, IMPORT_HOME, EXPORT_HOME, RESET_HOME,
    IMPORT_SETTINGS, EXPORT_SETTINGS, RESET_SETTINGS,
}

/** A home mode with what it means below it. */
@Composable
private fun HomeModeRow(selected: Boolean, onSelect: () -> Unit, title: String, hint: String) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(title)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Preview(showBackground = true)
@Composable
private fun AppSettingsScreenPreview() {
    FlatdroidTheme {
        AppSettingsScreen(
            InstalledApp("org.xfce.mousepad", "Mousepad", null, null), AppSettings(), storage = AppStorage(9_000_000, 12_345_678, 4_096, 65_536), canChangeFiles = true, canUninstall = true, developerOptions = true,
            onRequestMicrophone = {}, hasCamera = true, cameraResolutions = mapOf(15 to listOf(AndroidCameras.Resolution(720)), 30 to listOf(AndroidCameras.Resolution(720))), onRequestCamera = {}, onSave = {}, onBack = {}, onUninstall = { _, _ -> },
            sharedHome = "Documents/Flatdroid", broadAccess = true, onStorage = {},
        )
    }
}
