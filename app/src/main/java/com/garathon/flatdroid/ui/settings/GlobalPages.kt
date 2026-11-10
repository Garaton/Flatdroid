package com.garathon.flatdroid.ui.settings

import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.camera.AndroidCameras
import com.garathon.flatdroid.flatpak.FlathubLinkApproval
import com.garathon.flatdroid.settings.AppDefaults
import com.garathon.flatdroid.settings.AppSettings
import com.garathon.flatdroid.settings.AppTime
import com.garathon.flatdroid.settings.Duration
import com.garathon.flatdroid.settings.FlatpakTheme
import com.garathon.flatdroid.settings.HomeMode
import com.garathon.flatdroid.settings.InterfaceTheme
import com.garathon.flatdroid.settings.MobileData
import com.garathon.flatdroid.settings.UsageStats
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.ui.ConfirmDialog
import com.garathon.flatdroid.ui.DropdownRow
import com.garathon.flatdroid.ui.FlatdroidIcon
import com.garathon.flatdroid.ui.ResetDialog
import com.garathon.flatdroid.ui.SettingsCard
import com.garathon.flatdroid.ui.SwitchRow
import com.garathon.flatdroid.ui.TextRow
import com.garathon.flatdroid.ui.rememberFlathubLinksAllowed
import com.garathon.flatdroid.ui.theme.Spacing

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
    SettingsCard {
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
    
    }
    SettingsCard {
        SecurityLevelRow(s.settings.security, { v -> s.editSettings { copy(security = v) } })
    }

    SettingsCard(title = stringResource(R.string.settings_flatdroid_settings)) {
        Text(stringResource(R.string.settings_flatdroid_settings_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            OutlinedButton(onClick = s.env.actions.onImportSettings, enabled = s.env.canReset) {
                Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.settings_backup_import), modifier = Modifier.padding(start = Spacing.small))
            }
            OutlinedButton(onClick = s.env.actions.onExportSettings) {
                Icon(Icons.Filled.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.settings_backup_export), modifier = Modifier.padding(start = Spacing.small))
            }
        }
    }

    SettingsCard(title = stringResource(R.string.settings_section_danger), danger = true) {
        OutlinedButton(onClick = { confirmResetSettings = true }, enabled = s.env.canReset) {
            Text(stringResource(R.string.storage_reset_settings), color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(onClick = { confirmReset = true }, enabled = s.env.canReset) {
            Text(stringResource(R.string.reset_button), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun GlobalDefaultsPage(s: GlobalPageState) = SettingsPageColumn(R.string.settings_page_defaults) {
    SettingsCard {
        val security = s.settings.security
        // These match the per-app switches of the same name; the value also reaches every app still on it.
        val appliesToDefault = " " + stringResource(R.string.security_applies_to_default)
        val homes = homeModeNames()
        DropdownRow(
            selected = security.defaultHomeMode,
            options = HomeMode.entries.toList(),
            onSelect = { v -> s.editSettings { copy(security = security.copy(defaultHomeMode = v)) } },
            label = homes::getValue,
            title = stringResource(R.string.security_default_home),
            hint = stringResource(R.string.security_default_home_hint),
        )
        SwitchRow(
            security.permissionsFromMetadata, { v -> s.editSettings { copy(security = security.copy(permissionsFromMetadata = v)) } },
            stringResource(R.string.security_permissions_from_metadata), stringResource(R.string.security_permissions_from_metadata_hint) + appliesToDefault,
        )
        SwitchRow(
            security.shareClipboard, { v -> s.editSettings { copy(security = security.copy(shareClipboard = v)) } },
            stringResource(R.string.security_share_clipboard), stringResource(R.string.app_settings_clipboard_hint) + appliesToDefault,
        )
        val percentLabel = stringResource(R.string.app_settings_resolution_percent)
        DropdownRow(
            selected = s.settings.appResolution,
            options = AppDefaults.DEFAULT_RESOLUTIONS,
            onSelect = { v -> s.editSettings { copy(appResolution = v) } },
            label = { percentLabel.format(it.percent) },
            title = stringResource(R.string.app_settings_resolution),
            hint = stringResource(R.string.app_settings_resolution_hint) + appliesToDefault,
        )
        DropdownRow(
            selected = s.settings.appInterfaceSize,
            options = AppSettings.INTERFACE_SIZES,
            onSelect = { v -> s.editSettings { copy(appInterfaceSize = v) } },
            label = { percentLabel.format(it) },
            title = stringResource(R.string.app_settings_interface_size),
            hint = stringResource(R.string.app_settings_interface_size_hint) + appliesToDefault,
        )
    }
}

@Composable
fun GlobalDisplayPage(s: GlobalPageState) = SettingsPageColumn(R.string.settings_page_display) {
    SettingsCard {
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
    }
    SettingsCard {
        SwitchRow(
            s.settings.frameRate60, { v -> s.editSettings { copy(frameRate60 = v) } },
            stringResource(R.string.settings_frame_rate_60), stringResource(R.string.settings_frame_rate_60_hint),
        )
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
    SettingsCard {
        SwitchRow(
            s.settings.lowLatencyAudio, { v -> s.editSettings { copy(lowLatencyAudio = v) } },
            stringResource(R.string.settings_low_latency_audio), stringResource(R.string.settings_low_latency_audio_hint),
        )
        SwitchRow(
            s.settings.highQualityAudio, { v -> s.editSettings { copy(highQualityAudio = v) } },
            stringResource(R.string.settings_high_quality_audio), stringResource(R.string.settings_high_quality_audio_hint),
        )
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
    SettingsCard {
        CameraRows(
            rate = s.settings.cameraFrameRate,
            resolution = s.settings.cameraResolution,
            resolutions = s.env.cameraResolutions,
            enabled = s.env.hasCamera,
            rateHint = stringResource(if (s.env.hasCamera) R.string.app_settings_camera_frame_rate_hint else R.string.app_settings_camera_none) +
                if (s.settings.cameraFrameRate > AndroidCameras.COMMON_MAX_FRAME_RATE) " " + stringResource(R.string.app_settings_camera_frame_rate_high) else "",
            resolutionHint = stringResource(R.string.app_settings_camera_resolution_hint),
            onRate = { rate, size -> s.editSettings { copy(cameraFrameRate = rate, cameraResolution = size) } },
            onResolution = { v -> s.editSettings { copy(cameraResolution = v) } },
        )
    }
}

@Composable
fun GlobalSecurityPage(s: GlobalPageState) = SettingsPageColumn(R.string.settings_page_security) {
    val security = s.settings.security
    SettingsCard {
        SwitchRow(
            security.hideHelperProcesses, { v -> s.editSettings { copy(security = security.copy(hideHelperProcesses = v)) } },
            stringResource(R.string.security_hide_helpers), stringResource(R.string.security_hide_helpers_hint),
            developer = true,
        )
        SwitchRow(
            security.hideScreen, { v -> s.editSettings { copy(security = security.copy(hideScreen = v)) } },
            stringResource(R.string.security_hide_screen), stringResource(R.string.security_hide_screen_hint),
        )
        SwitchRow(
            security.checkFilesBeforeLaunch, { v -> s.editSettings { copy(security = security.copy(checkFilesBeforeLaunch = v)) } },
            stringResource(R.string.security_check_files), stringResource(R.string.security_check_files_hint),
        )
    }
    SettingsCard {
        SwitchRow(
            security.androidFonts, { v -> s.editSettings { copy(security = security.copy(androidFonts = v)) } },
            stringResource(R.string.security_android_fonts), stringResource(R.string.security_android_fonts_hint),
        )
        SwitchRow(
            security.shareLocale, { v -> s.editSettings { copy(security = security.copy(shareLocale = v)) } },
            stringResource(R.string.security_share_locale), stringResource(R.string.security_share_locale_hint),
        )
        SwitchRow(
            security.limitDevices, { v -> s.editSettings { copy(security = security.copy(limitDevices = v)) } },
            stringResource(R.string.security_limit_devices), stringResource(R.string.security_limit_devices_hint),
        )
    }
}

@Composable
fun GlobalFlatpakPage(s: GlobalPageState) = SettingsPageColumn(R.string.settings_page_flatpak) {
    SettingsCard {
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
    }
    SettingsCard {
        LanguagesRow(s.draft.onlyMyLanguages, { v -> s.edit { copy(onlyMyLanguages = v) } }, s.env.deviceLanguages)
        GpuRuntimesRow(s.settings.installGpuRuntimes, { v -> s.editSettings { copy(installGpuRuntimes = v) } })
    }
    // Flathub's install pages open Flatdroid only once Android allows it (Android 12 and later: the
    // domain can't be verified, so the user adds it, and an app can't do that for itself).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        val allowed = rememberFlathubLinksAllowed()
        SettingsCard(title = stringResource(R.string.settings_flathub_links)) {
            val on = allowed == true
            Text(
                stringResource(if (on) R.string.settings_flathub_links_allowed else R.string.settings_flathub_links_not_allowed),
                style = MaterialTheme.typography.titleSmall,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Text(
                stringResource(if (on) R.string.settings_flathub_links_hint_allowed else R.string.settings_flathub_links_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { FlathubLinkApproval.openSettings(context) }) { Text(stringResource(R.string.settings_flathub_links_open)) }
        }
    }
}

@Composable
fun GlobalDeveloperPage(s: GlobalPageState) = SettingsPageColumn(R.string.settings_page_developer) {
    SettingsCard {
        SwitchRow(
            s.settings.developerOptions, { v -> s.editSettings { copy(developerOptions = v) } },
            stringResource(R.string.settings_developer_options), stringResource(R.string.settings_developer_options_hint),
        )
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
    SettingsCard(title = stringResource(R.string.settings_section_tools)) {
        OutlinedButton(onClick = s.env.onTerminal, enabled = s.env.canOpenTerminal) { Text(stringResource(R.string.terminal_title)) }
    }
}

/** The viewer list's entry for Flatdroid's own viewer (viewer apps are their package names). */
private const val OWN_VIEWER = ""
