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
    val names = mapOf(
        SecurityLevel.PERFORMANT to stringResource(R.string.security_level_performant),
        SecurityLevel.DEFAULT to stringResource(R.string.security_level_default),
        SecurityLevel.HARDENED to stringResource(R.string.security_level_hardened),
    )
    val custom = stringResource(R.string.security_level_custom)
    DropdownRow(
        selected = level,
        // Custom is shown only while it is what the settings are; choosing it changes nothing.
        options = SecurityLevel.entries.toList<SecurityLevel?>() + if (level == null) listOf(null) else emptyList(),
        onSelect = { it?.let { chosen -> onChange(chosen.settings) } },
        label = { it?.let(names::getValue) ?: custom },
        title = stringResource(R.string.security_level),
        hint = stringResource(
            when (level) {
                SecurityLevel.PERFORMANT -> R.string.security_level_performant_hint
                SecurityLevel.DEFAULT -> R.string.security_level_default_hint
                SecurityLevel.HARDENED -> R.string.security_level_hardened_hint
                null -> R.string.security_level_custom_hint
            }
        ),
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
    SwitchRow(
        security.permissionsFromMetadata, { onChange(security.copy(permissionsFromMetadata = it)) },
        stringResource(R.string.security_permissions_from_metadata), stringResource(R.string.security_permissions_from_metadata_hint), enabled = enabled,
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
        security.limitDevices, { onChange(security.copy(limitDevices = it)) },
        stringResource(R.string.security_limit_devices), stringResource(R.string.security_limit_devices_hint), enabled = enabled,
    )
    SwitchRow(
        security.protectPassword, { onChange(security.copy(protectPassword = it)) },
        stringResource(R.string.security_protect_password), stringResource(R.string.security_protect_password_hint), enabled = enabled,
    )
    SwitchRow(
        security.exportWithPicker, { onChange(security.copy(exportWithPicker = it)) },
        stringResource(R.string.security_export_picker), stringResource(R.string.security_export_picker_hint), enabled = enabled,
    )
}

/** Keeps [SecuritySettings] across configuration changes (rememberSaveable needs plain values). */
val SecuritySaver: Saver<SecuritySettings, Any> = listSaver(
    save = {
        listOf(
            it.extraSyscallFilter, it.hideHelperProcesses, it.permissionsFromMetadata, it.defaultHomeMode.name,
            it.limitDevices, it.protectPassword, it.exportWithPicker,
        )
    },
    restore = {
        SecuritySettings(
            extraSyscallFilter = it[0] as Boolean,
            hideHelperProcesses = it[1] as Boolean,
            permissionsFromMetadata = it[2] as Boolean,
            defaultHomeMode = HomeMode.valueOf(it[3] as String),
            limitDevices = it[4] as Boolean,
            protectPassword = it[5] as Boolean,
            exportWithPicker = it[6] as Boolean,
        )
    },
)
