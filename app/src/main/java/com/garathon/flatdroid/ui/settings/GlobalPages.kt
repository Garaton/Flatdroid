package com.garathon.flatdroid.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.UploadFile
import com.garathon.flatdroid.settings.UsageStats
import com.garathon.flatdroid.settings.Duration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.FlatdroidStorage
import com.garathon.flatdroid.R
import com.garathon.flatdroid.ui.IconAction
import com.garathon.flatdroid.Unused
import com.garathon.flatdroid.settings.FlatpakTheme
import com.garathon.flatdroid.settings.InterfaceTheme
import com.garathon.flatdroid.settings.AppTime
import com.garathon.flatdroid.settings.MobileData
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.camera.AndroidCameras
import com.garathon.flatdroid.ui.ConfirmDialog
import com.garathon.flatdroid.ui.FlatdroidIcon
import com.garathon.flatdroid.ui.DropdownRow
import com.garathon.flatdroid.ui.PerAppDefaultRows
import com.garathon.flatdroid.ui.ResetDialog
import com.garathon.flatdroid.ui.SectionTitle
import com.garathon.flatdroid.ui.SecurityPreset
import com.garathon.flatdroid.ui.SecurityPrivacyRows
import com.garathon.flatdroid.ui.SwitchRow
import com.garathon.flatdroid.ui.TextRow

/** A number or name with what it is below it. */
@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** [millis] as "45 min", "3 h 20 min" or "2 d 4 h". */
@Composable
fun durationText(millis: Long): String {
    val d = Duration.of(millis)
    return when (d.largest) {
        Duration.Kind.DAYS_HOURS -> stringResource(R.string.duration_days_hours, d.days, d.hours)
        Duration.Kind.HOURS_MINUTES -> stringResource(R.string.duration_hours_minutes, d.hours, d.minutes)
        Duration.Kind.MINUTES -> stringResource(R.string.duration_minutes, d.minutes)
    }
}

@Composable
fun GlobalOverviewPage(s: GlobalPageState) = SettingsPageColumn(null) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var confirmResetSettings by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    if (confirmReset) ResetDialog(onConfirm = { confirmReset = false; s.env.onReset(it) }, onDismiss = { confirmReset = false })
    if (confirmResetSettings) {
        ConfirmDialog(
            stringResource(R.string.settings_reset_settings_title), stringResource(R.string.settings_reset_settings_text),
            stringResource(R.string.storage_reset_settings),
            onConfirm = { confirmResetSettings = false; s.env.onResetSettings() }, onDismiss = { confirmResetSettings = false },
        )
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        FlatdroidIcon(size = 88.dp)
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        Text(
            stringResource(R.string.settings_logline),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
    // How Flatdroid is used: storage (a way to its page), and the time and launches of all apps.
    val usage = remember(s.env.apps) { UsageStats.of(s.env.apps) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Stat(
            s.env.storage?.total?.let { Formatter.formatShortFileSize(context, it) } ?: stringResource(R.string.app_settings_measuring),
            stringResource(R.string.settings_page_storage), Modifier.clickable { s.onPage(GlobalPage.STORAGE) },
        )
        Stat(durationText(usage.usedMillis), stringResource(R.string.overview_time_used))
        Stat(usage.launches.toString(), stringResource(R.string.overview_launches))
        usage.mostUsed?.let { Stat(it.name, stringResource(R.string.overview_most_used)) }
    }
    val themeNames = mapOf(
        InterfaceTheme.SYSTEM to stringResource(R.string.settings_theme_system),
        InterfaceTheme.LIGHT to stringResource(R.string.settings_theme_light),
        InterfaceTheme.DARK to stringResource(R.string.settings_theme_dark),
        InterfaceTheme.AMOLED to stringResource(R.string.settings_theme_amoled),
    )
    DropdownRow(
        selected = s.settings.theme,
        options = InterfaceTheme.entries,
        onSelect = { v -> s.editSettings { copy(theme = v) } },
        label = { themeNames.getValue(it) },
        title = stringResource(R.string.settings_interface_theme),
        hint = stringResource(R.string.settings_interface_theme_hint),
    )
    
    val appTimeNames = mapOf(
        AppTime.TIME_USED to stringResource(R.string.settings_app_time_used),
        AppTime.LAST_OPENED to stringResource(R.string.settings_app_time_last_opened),
    )
    DropdownRow(
        selected = s.settings.appTime,
        options = AppTime.entries,
        onSelect = { v -> s.editSettings { copy(appTime = v) } },
        label = { appTimeNames.getValue(it) },
        title = stringResource(R.string.settings_app_time),
        hint = stringResource(R.string.settings_app_time_hint),
    )
    
    SecurityPreset(s.settings.security, { v -> s.editSettings { copy(security = v) } })

    SectionTitle(stringResource(R.string.settings_flatdroid_settings))
    Text(stringResource(R.string.settings_flatdroid_settings_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = s.env.actions.onImportSettings, enabled = s.env.canReset) {
            Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.settings_backup_import), modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = s.env.actions.onExportSettings) {
            Icon(Icons.Filled.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.settings_backup_export), modifier = Modifier.padding(start = 8.dp))
        }
    }

    SectionTitle(stringResource(R.string.settings_section_danger), danger = true)
    OutlinedButton(onClick = { confirmResetSettings = true }, enabled = s.env.canReset) {
        Text(stringResource(R.string.storage_reset_settings), color = MaterialTheme.colorScheme.error)
    }
    OutlinedButton(onClick = { confirmReset = true }, enabled = s.env.canReset) {
        Text(stringResource(R.string.reset_button), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun GlobalDefaultsPage(s: GlobalPageState) = SettingsPageColumn(R.string.settings_page_defaults) {
    PerAppDefaultRows(s.settings.security, { v -> s.editSettings { copy(security = v) } })
}

@Composable
fun GlobalDisplayPage(s: GlobalPageState) = SettingsPageColumn(R.string.settings_page_display) {
    val themeNames = mapOf(
        FlatpakTheme.SYSTEM to stringResource(R.string.settings_theme_system),
        FlatpakTheme.DARK to stringResource(R.string.settings_theme_dark),
        FlatpakTheme.LIGHT to stringResource(R.string.settings_theme_light),
    )
    DropdownRow(
        selected = s.settings.flatpakTheme,
        options = FlatpakTheme.entries,
        onSelect = { v -> s.editSettings { copy(flatpakTheme = v) } },
        label = { themeNames.getValue(it) },
        title = stringResource(R.string.settings_theme),
        hint = stringResource(R.string.settings_theme_hint),
    )

    val noViewer = stringResource(R.string.settings_viewer_none)
    val ownViewer = stringResource(R.string.settings_viewer_flatdroid)
    // Only the saved choice can be missing: the list holds installed viewers.
    val missingViewer = stringResource(R.string.settings_viewer_missing, s.settings.vncViewer.orEmpty())
    // Flatdroid's own viewer ("") or a viewer app (its package, which turns the external viewer on).
    DropdownRow(
        selected = if (s.settings.externalViewer) s.settings.vncViewer else OWN_VIEWER,
        options = listOf(OWN_VIEWER) + s.env.viewers.map { it.packageName },
        onSelect = { choice ->
            s.editSettings { copy(externalViewer = choice != OWN_VIEWER, vncViewer = if (choice != OWN_VIEWER) choice else vncViewer) }
        },
        label = { id ->
            when (id) {
                OWN_VIEWER -> ownViewer
                null -> noViewer
                else -> s.env.viewers.firstOrNull { it.packageName == id }?.label ?: missingViewer
            }
        },
        title = stringResource(R.string.settings_viewer),
        hint = stringResource(R.string.settings_viewer_hint),
    )
    if (s.settings.externalViewer) {
        Text(stringResource(R.string.settings_viewer_external_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    SwitchRow(
        s.settings.frameRate60, { v -> s.editSettings { copy(frameRate60 = v) } },
        stringResource(R.string.settings_frame_rate_60), stringResource(R.string.settings_frame_rate_60_hint),
    )
    if (s.settings.developerOptions) {
        TextRow(
            content = s.draft.customFrameRate,
            onChange = { v -> s.edit { copy(customFrameRate = v.filter(Char::isDigit).take(4)) } },
            title = stringResource(R.string.settings_custom_frame_rate),
            hint = stringResource(R.string.settings_custom_frame_rate_hint, UserSettings.CUSTOM_FRAME_RATES.first, UserSettings.CUSTOM_FRAME_RATES.last),
            isError = !s.draft.customFrameRateValid,
            enabled = !s.settings.uncappedFrameRate,
            developer = true,
        )
        SwitchRow(
            s.settings.uncappedFrameRate, { v -> s.editSettings { copy(uncappedFrameRate = v) } },
            stringResource(R.string.settings_uncapped_frame_rate), stringResource(R.string.settings_uncapped_frame_rate_hint),
            developer = true,
        )
    }
}

@Composable
fun GlobalDevicesPage(s: GlobalPageState) = SettingsPageColumn(R.string.settings_page_devices) {
    SwitchRow(
        s.settings.lowLatencyAudio, { v -> s.editSettings { copy(lowLatencyAudio = v) } },
        stringResource(R.string.settings_low_latency_audio), stringResource(R.string.settings_low_latency_audio_hint),
    )
    SwitchRow(
        s.settings.highQualityAudio, { v -> s.editSettings { copy(highQualityAudio = v) } },
        stringResource(R.string.settings_high_quality_audio), stringResource(R.string.settings_high_quality_audio_hint),
    )
    if (s.settings.developerOptions) {
        TextRow(
            content = s.draft.audioLatency,
            onChange = { v -> s.edit { copy(audioLatency = v.filter(Char::isDigit).take(4)) } },
            title = stringResource(R.string.settings_audio_latency),
            hint = stringResource(
                R.string.settings_audio_latency_hint,
                UserSettings.AUDIO_LATENCIES_MS.first, UserSettings.AUDIO_LATENCIES_MS.last, UserSettings.DEFAULT_AUDIO_LATENCY_MS,
            ),
            isError = !s.draft.audioLatencyValid,
            developer = true,
        )
    }
    CameraDefaultRows(s)
}

/** The camera's frame rate and size for every app; an app can override them in its developer settings. */
@Composable
private fun CameraDefaultRows(s: GlobalPageState) {
    val resources = LocalResources.current
    val rate = s.settings.cameraFrameRate
    val resolution = s.settings.cameraResolution
    val cameraResolutions = s.env.cameraResolutions
    DropdownRow(
        selected = rate,
        options = cameraResolutions.keys.sortedDescending().ifEmpty { listOf(rate) },
        onSelect = { chosen ->
            s.editSettings {
                // A size the cameras don't give at the new rate: the largest they do below it
                // (the largest they do for full resolution).
                val sides = cameraResolutions[chosen].orEmpty().map { it.shortSide }
                val size = if (sides.isNotEmpty() && resolution !in sides) {
                    if (resolution == AndroidCameras.FULL_RESOLUTION) sides.first()
                    else sides.firstOrNull { it != AndroidCameras.FULL_RESOLUTION && it < resolution } ?: sides.last()
                } else resolution
                copy(cameraFrameRate = chosen, cameraResolution = size)
            }
        },
        label = { resources.getString(R.string.app_settings_camera_frame_rate_value, it) },
        title = stringResource(R.string.app_settings_camera_frame_rate),
        hint = stringResource(if (s.env.hasCamera) R.string.app_settings_camera_frame_rate_hint else R.string.app_settings_camera_none) +
            if (rate > AndroidCameras.COMMON_MAX_FRAME_RATE) " " + stringResource(R.string.app_settings_camera_frame_rate_high) else "",
        enabled = s.env.hasCamera,
    )
    val resolutions = cameraResolutions[rate].orEmpty()
    DropdownRow(
        selected = resolution,
        options = resolutions.map { it.shortSide }.ifEmpty { listOf(resolution) },
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
        enabled = s.env.hasCamera,
    )
}

@Composable
fun GlobalSecurityPage(s: GlobalPageState) = SettingsPageColumn(R.string.settings_page_security) {
    SecurityPrivacyRows(
        s.settings.security, { v -> s.editSettings { copy(security = v) } },
        developerOptions = s.settings.developerOptions,
    )
}

@Composable
fun GlobalFlatpakPage(s: GlobalPageState) = SettingsPageColumn(R.string.settings_page_flatpak) {
    val mobileNames = mapOf(
        MobileData.ASK to stringResource(R.string.settings_mobile_data_ask),
        MobileData.ALWAYS to stringResource(R.string.settings_mobile_data_always),
        MobileData.NEVER to stringResource(R.string.settings_mobile_data_never),
    )
    DropdownRow(
        selected = s.settings.mobileData,
        options = MobileData.entries,
        onSelect = { v -> s.editSettings { copy(mobileData = v) } },
        label = { mobileNames.getValue(it) },
        title = stringResource(R.string.settings_mobile_data),
        hint = stringResource(R.string.settings_mobile_data_hint),
    )
    SwitchRow(
        s.draft.onlyMyLanguages, { v -> s.edit { copy(onlyMyLanguages = v) } },
        stringResource(R.string.settings_languages, s.env.deviceLanguages.replace(";", ", ")), stringResource(R.string.settings_languages_hint),
    )
    if (s.settings.developerOptions) {
        SwitchRow(
            s.settings.installGpuRuntimes, { v -> s.editSettings { copy(installGpuRuntimes = v) } },
            stringResource(R.string.settings_gpu_runtimes), stringResource(R.string.settings_gpu_runtimes_hint),
            developer = true,
        )
    }
}

@Composable
fun GlobalDeveloperPage(s: GlobalPageState) = SettingsPageColumn(R.string.settings_page_developer) {
    SwitchRow(
        s.settings.developerOptions, { v -> s.editSettings { copy(developerOptions = v) } },
        stringResource(R.string.settings_developer_options), stringResource(R.string.settings_developer_options_hint),
    )
    if (s.settings.developerOptions) {
        SwitchRow(
            s.settings.terminalFilter, { v -> s.editSettings { copy(terminalFilter = v) } },
            stringResource(R.string.settings_terminal_filter), stringResource(R.string.settings_terminal_filter_hint),
            developer = true,
        )
        SwitchRow(
            s.settings.terminalDuringSession, { v -> s.editSettings { copy(terminalDuringSession = v) } },
            stringResource(R.string.settings_terminal_during_session), stringResource(R.string.settings_terminal_during_session_hint),
            developer = true,
        )
    }
    SectionTitle(stringResource(R.string.settings_section_tools))
    OutlinedButton(onClick = s.env.onTerminal, enabled = s.env.canOpenTerminal) { Text(stringResource(R.string.terminal_title)) }
}

/** The viewer list's entry for Flatdroid's own viewer (viewer apps are their package names). */
private const val OWN_VIEWER = ""
