package com.garathon.flatdroid.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.res.stringResource
import com.garathon.flatdroid.R
import com.garathon.flatdroid.settings.HomeMode
import com.garathon.flatdroid.settings.SecurityLevel
import com.garathon.flatdroid.settings.SecuritySettings

/**
 * The security level, what it means, and that no level is a sandbox; with [details], every
 * setting the levels set, each changeable on its own (a change that matches no level is Custom).
 * The system call filter's settings are developer settings ([developerOptions]); the levels set
 * them either way.
 */
@Composable
fun SecuritySection(
    security: SecuritySettings,
    onChange: (SecuritySettings) -> Unit,
    details: Boolean,
    enabled: Boolean = true,
    developerOptions: Boolean = false,
) {
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
    if (!details) {
        Text(stringResource(R.string.security_change_later), style = MaterialTheme.typography.bodySmall)
        return
    }
    if (developerOptions) {
        SwitchRow(
            security.extraSyscallFilter, { onChange(security.copy(extraSyscallFilter = it)) },
            stringResource(R.string.security_extra_syscalls), stringResource(R.string.security_extra_syscalls_hint),
            enabled = enabled, developer = true,
        )
        SwitchRow(
            security.hideHelperProcesses, { onChange(security.copy(hideHelperProcesses = it)) },
            stringResource(R.string.security_hide_helpers), stringResource(R.string.security_hide_helpers_hint),
            enabled = enabled, developer = true,
        )
    }
    // These match the per-app switches of the same name; the level's value also reaches every app still on it.
    val appliesToDefault = " " + stringResource(R.string.security_applies_to_default)
    SwitchRow(
        security.permissionsFromMetadata, { onChange(security.copy(permissionsFromMetadata = it)) },
        stringResource(R.string.security_permissions_from_metadata), stringResource(R.string.security_permissions_from_metadata_hint) + appliesToDefault, enabled = enabled,
    )
    val homes = mapOf(
        HomeMode.SHARED to stringResource(R.string.app_settings_home_shared),
        HomeMode.PRIVATE to stringResource(R.string.app_settings_home_private),
        HomeMode.TEMPORARY to stringResource(R.string.app_settings_home_temporary),
    )
    DropdownRow(
        selected = security.defaultHomeMode,
        options = HomeMode.entries.toList(),
        onSelect = { onChange(security.copy(defaultHomeMode = it)) },
        label = homes::getValue,
        title = stringResource(R.string.security_default_home),
        hint = stringResource(R.string.security_default_home_hint),
        enabled = enabled,
    )
    SwitchRow(
        security.androidFonts, { onChange(security.copy(androidFonts = it)) },
        stringResource(R.string.security_android_fonts), stringResource(R.string.app_settings_android_fonts_hint) + appliesToDefault, enabled = enabled,
    )
    SwitchRow(
        security.shareClipboard, { onChange(security.copy(shareClipboard = it)) },
        stringResource(R.string.security_share_clipboard), stringResource(R.string.app_settings_clipboard_hint) + appliesToDefault, enabled = enabled,
    )
    SwitchRow(
        security.shareTimeZone, { onChange(security.copy(shareTimeZone = it)) },
        stringResource(R.string.security_share_time_zone), stringResource(R.string.app_settings_time_zone_hint) + appliesToDefault, enabled = enabled,
    )
    SwitchRow(
        security.shareLanguage, { onChange(security.copy(shareLanguage = it)) },
        stringResource(R.string.security_share_language), stringResource(R.string.app_settings_language_hint) + appliesToDefault, enabled = enabled,
    )
    SwitchRow(
        security.limitDevices, { onChange(security.copy(limitDevices = it)) },
        stringResource(R.string.security_limit_devices), stringResource(R.string.security_limit_devices_hint), enabled = enabled,
    )
    SwitchRow(
        security.hideScreen, { onChange(security.copy(hideScreen = it)) },
        stringResource(R.string.security_hide_screen), stringResource(R.string.security_hide_screen_hint), enabled = enabled,
    )
    SwitchRow(
        security.exportWithPicker, { onChange(security.copy(exportWithPicker = it)) },
        stringResource(R.string.security_export_picker), stringResource(R.string.security_export_picker_hint), enabled = enabled,
    )
    SwitchRow(
        security.checkFilesBeforeLaunch, { onChange(security.copy(checkFilesBeforeLaunch = it)) },
        stringResource(R.string.security_check_files), stringResource(R.string.security_check_files_hint), enabled = enabled,
    )
}

/** Keeps [SecuritySettings] across configuration changes (rememberSaveable needs plain values). */
val SecuritySaver: Saver<SecuritySettings, Any> = listSaver(
    save = {
        listOf(
            it.extraSyscallFilter, it.hideHelperProcesses, it.permissionsFromMetadata, it.defaultHomeMode.name,
            it.limitDevices, it.hideScreen, it.exportWithPicker, it.checkFilesBeforeLaunch,
            it.androidFonts, it.shareClipboard, it.shareTimeZone, it.shareLanguage,
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
            exportWithPicker = it[6] as Boolean,
            checkFilesBeforeLaunch = it[7] as Boolean,
            androidFonts = it[8] as Boolean,
            shareClipboard = it[9] as Boolean,
            shareTimeZone = it[10] as Boolean,
            shareLanguage = it[11] as Boolean,
        )
    },
)
