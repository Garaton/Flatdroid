package com.garathon.flatdroid.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.garathon.flatdroid.R
import com.garathon.flatdroid.camera.AndroidCameras
import com.garathon.flatdroid.settings.HomeMode
import com.garathon.flatdroid.settings.SecurityLevel
import com.garathon.flatdroid.settings.SecuritySettings
import com.garathon.flatdroid.ui.DropdownRow
import com.garathon.flatdroid.ui.SwitchRow

// Settings that show in more than one place (the settings pages and the setup choices, or the global
// pages and an app's), written once. Everything else is in the pages themselves.

/**
 * The security level (shown as chosen, or Custom when the settings match no level), what it means,
 * and that no level is a sandbox. Each setting a level sets is changed on its own, on the settings
 * pages. On the Overview and in setup.
 */
@Composable
fun SecurityLevelRow(security: SecuritySettings, onChange: (SecuritySettings) -> Unit, enabled: Boolean = true) {
    val level = SecurityLevel.of(security)
    // Each level carries its own label and hint, so adding a preset needs no change here.
    val names = SecurityLevel.entries.associateWith { stringResource(it.labelRes) }
    val custom = stringResource(R.string.security_level_custom)
    DropdownRow(
        selected = level,
        // Custom is shown only while it is what the settings are; choosing it changes nothing.
        options = SecurityLevel.entries.toList<SecurityLevel?>() + if (level == null) listOf(null) else emptyList(),
        onSelect = { it?.let { chosen -> onChange(chosen.settings) } },
        label = { it?.let(names::getValue) ?: custom },
        title = stringResource(R.string.security_level),
        hint = stringResource(level?.hintRes ?: R.string.security_level_custom_hint),
        enabled = enabled,
    )
    Text(stringResource(R.string.security_not_sandboxed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/** Only download translations for the device's languages: on the Flatpak page and in setup. */
@Composable
fun LanguagesRow(checked: Boolean, onChange: (Boolean) -> Unit, deviceLanguages: String) {
    SwitchRow(
        checked, onChange,
        stringResource(R.string.settings_languages, deviceLanguages.replace(";", ", ")), stringResource(R.string.settings_languages_hint),
    )
}

/** Install vendor GPU runtimes (a developer setting): on the Flatpak page and in setup. */
@Composable
fun GpuRuntimesRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    SwitchRow(
        checked, onChange,
        stringResource(R.string.settings_gpu_runtimes), stringResource(R.string.settings_gpu_runtimes_hint),
        developer = true,
    )
}

/** The names of the home modes, for the default home and an app's. */
@Composable
fun homeModeNames(): Map<HomeMode, String> = mapOf(
    HomeMode.SHARED to stringResource(R.string.app_settings_home_shared),
    HomeMode.PRIVATE to stringResource(R.string.app_settings_home_private),
    HomeMode.TEMPORARY to stringResource(R.string.app_settings_home_temporary),
    HomeMode.ANDROID to stringResource(R.string.app_settings_home_android),
)

/**
 * The camera's frame rate and size, which Flatdroid sets for every app (the Audio and camera page)
 * and an app can override (its Overrides page, with [onResetRate] and [onResetResolution] to remove an
 * override). [resolutions] are the sizes the cameras give at each rate; choosing a rate that doesn't
 * give the current size moves it to the largest that it does below it.
 */
@Composable
fun CameraRows(
    rate: Int,
    resolution: Int,
    resolutions: Map<Int, List<AndroidCameras.Resolution>>,
    enabled: Boolean,
    rateHint: String,
    resolutionHint: String,
    onRate: (rate: Int, resolution: Int) -> Unit,
    onResolution: (Int) -> Unit,
    developer: Boolean = false,
    onResetRate: (() -> Unit)? = null,
    onResetResolution: (() -> Unit)? = null,
    resettable: Boolean = false,
) {
    val resources = LocalResources.current
    DropdownRow(
        selected = rate,
        options = resolutions.keys.sortedDescending().ifEmpty { listOf(rate) },
        onSelect = { chosen ->
            // A size the cameras don't give at the new rate: the largest they do below it
            // (the largest they do for full resolution).
            val sides = resolutions[chosen].orEmpty().map { it.shortSide }
            val size = if (sides.isNotEmpty() && resolution !in sides) {
                if (resolution == AndroidCameras.FULL_RESOLUTION) sides.first()
                else sides.firstOrNull { it != AndroidCameras.FULL_RESOLUTION && it < resolution } ?: sides.last()
            } else resolution
            onRate(chosen, size)
        },
        label = { resources.getString(R.string.app_settings_camera_frame_rate_value, it) },
        title = stringResource(R.string.app_settings_camera_frame_rate),
        hint = rateHint,
        enabled = enabled,
        developer = developer,
        onReset = onResetRate,
        resettable = resettable,
    )
    val sizes = resolutions[rate].orEmpty()
    DropdownRow(
        selected = resolution,
        options = sizes.map { it.shortSide }.ifEmpty { listOf(resolution) },
        onSelect = onResolution,
        label = { side ->
            if (side == AndroidCameras.FULL_RESOLUTION) {
                val full = sizes.firstOrNull { it.shortSide == side }?.fullSizes.orEmpty()
                resources.getString(R.string.app_settings_camera_resolution_full, full.joinToString(", ") { "${it.width}×${it.height}" })
            } else {
                resources.getString(R.string.app_settings_camera_resolution_value, side)
            }
        },
        title = stringResource(R.string.app_settings_camera_resolution),
        hint = resolutionHint,
        enabled = enabled,
        developer = developer,
        onReset = onResetResolution,
        resettable = resettable,
    )
}

/** Keeps [SecuritySettings] across configuration changes (rememberSaveable needs plain values). */
val SecuritySaver: Saver<SecuritySettings, Any> = listSaver(
    save = {
        listOf(
            it.extraSyscallFilter, it.hideHelperProcesses, it.permissionsFromMetadata, it.defaultHomeMode.name,
            it.limitDevices, it.hideScreen, it.checkFilesBeforeLaunch,
            it.androidFonts, it.shareClipboard, it.shareLocale,
        )
    },
    restore = {
        SecuritySettings(
            extraSyscallFilter = it[0] as Boolean,
            hideHelperProcesses = it[1] as Boolean,
            permissionsFromMetadata = it[2] as Boolean,
            defaultHomeMode = HomeMode.valueOf(it[3] as String),
            limitDevices = it[4] as Boolean,
            hideScreen = it[5] as Boolean,
            checkFilesBeforeLaunch = it[6] as Boolean,
            androidFonts = it[7] as Boolean,
            shareClipboard = it[8] as Boolean,
            shareLocale = it[9] as Boolean,
        )
    },
)
