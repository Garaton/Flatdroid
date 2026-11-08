package com.garathon.flatdroid.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.garathon.flatdroid.Unused
import com.garathon.flatdroid.settings.FlatpakTheme
import com.garathon.flatdroid.settings.TitleBars
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.ui.ConfirmDialog
import com.garathon.flatdroid.ui.DropdownRow
import com.garathon.flatdroid.ui.PerAppDefaultRows
import com.garathon.flatdroid.ui.ResetDialog
import com.garathon.flatdroid.ui.SectionTitle
import com.garathon.flatdroid.ui.SecurityPreset
import com.garathon.flatdroid.ui.SecurityPrivacyRows
import com.garathon.flatdroid.ui.SwitchRow
import com.garathon.flatdroid.ui.TextRow

@Composable
fun GlobalOverviewPage(s: GlobalPageState) = SettingsPageColumn(R.string.settings_page_overview) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var confirmResetSettings by rememberSaveable { mutableStateOf(false) }
    if (confirmReset) ResetDialog(onConfirm = { confirmReset = false; s.env.onReset(it) }, onDismiss = { confirmReset = false })
    if (confirmResetSettings) {
        ConfirmDialog(
            stringResource(R.string.settings_reset_settings_title), stringResource(R.string.settings_reset_settings_text),
            stringResource(R.string.storage_reset_settings),
            onConfirm = { confirmResetSettings = false; s.env.onResetSettings() }, onDismiss = { confirmResetSettings = false },
        )
    }
    SwitchRow(
        s.settings.pureBlack, { v -> s.editSettings { copy(pureBlack = v) } },
        stringResource(R.string.settings_pure_black), stringResource(R.string.settings_pure_black_hint), enabled = s.enabled,
    )
    SectionTitle(stringResource(R.string.settings_section_security))
    SecurityPreset(s.settings.security, { v -> s.editSettings { copy(security = v) } }, enabled = s.enabled)

    SectionTitle(stringResource(R.string.settings_section_danger), danger = true)
    OutlinedButton(onClick = { confirmResetSettings = true }, enabled = s.enabled && s.env.canReset) {
        Text(stringResource(R.string.storage_reset_settings), color = MaterialTheme.colorScheme.error)
    }
    OutlinedButton(onClick = { confirmReset = true }, enabled = s.enabled && s.env.canReset) {
        Text(stringResource(R.string.reset_button), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun GlobalDefaultsPage(s: GlobalPageState) = SettingsPageColumn(R.string.settings_page_defaults) {
    PerAppDefaultRows(s.settings.security, { v -> s.editSettings { copy(security = v) } }, enabled = s.enabled)
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
        enabled = s.enabled,
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
        enabled = s.enabled,
    )
    if (s.settings.externalViewer) {
        Text(stringResource(R.string.settings_viewer_external_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    SwitchRow(
        s.settings.frameRate60, { v -> s.editSettings { copy(frameRate60 = v) } },
        stringResource(R.string.settings_frame_rate_60), stringResource(R.string.settings_frame_rate_60_hint), enabled = s.enabled,
    )
    if (s.settings.developerOptions) {
        TextRow(
            content = s.draft.customFrameRate,
            onChange = { v -> s.edit { copy(customFrameRate = v.filter(Char::isDigit).take(4)) } },
            title = stringResource(R.string.settings_custom_frame_rate),
            hint = stringResource(R.string.settings_custom_frame_rate_hint, UserSettings.CUSTOM_FRAME_RATES.first, UserSettings.CUSTOM_FRAME_RATES.last),
            isError = !s.draft.customFrameRateValid,
            enabled = s.enabled && !s.settings.uncappedFrameRate,
            developer = true,
        )
        SwitchRow(
            s.settings.uncappedFrameRate, { v -> s.editSettings { copy(uncappedFrameRate = v) } },
            stringResource(R.string.settings_uncapped_frame_rate), stringResource(R.string.settings_uncapped_frame_rate_hint),
            enabled = s.enabled, developer = true,
        )
    }
}

@Composable
fun GlobalDevicesPage(s: GlobalPageState) = SettingsPageColumn(R.string.settings_page_devices) {
    SwitchRow(
        s.settings.lowLatencyAudio, { v -> s.editSettings { copy(lowLatencyAudio = v) } },
        stringResource(R.string.settings_low_latency_audio), stringResource(R.string.settings_low_latency_audio_hint), enabled = s.enabled,
    )
    SwitchRow(
        s.settings.highQualityAudio, { v -> s.editSettings { copy(highQualityAudio = v) } },
        stringResource(R.string.settings_high_quality_audio), stringResource(R.string.settings_high_quality_audio_hint), enabled = s.enabled,
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
            enabled = s.enabled,
            developer = true,
        )
    }
}

@Composable
fun GlobalSecurityPage(s: GlobalPageState) = SettingsPageColumn(R.string.settings_page_security) {
    SecurityPrivacyRows(
        s.settings.security, { v -> s.editSettings { copy(security = v) } },
        enabled = s.enabled, developerOptions = s.settings.developerOptions,
    )
}

@Composable
fun GlobalFlatpakPage(s: GlobalPageState) = SettingsPageColumn(R.string.settings_page_flatpak) {
    SwitchRow(
        s.draft.onlyMyLanguages, { v -> s.edit { copy(onlyMyLanguages = v) } },
        stringResource(R.string.settings_languages, s.env.deviceLanguages.replace(";", ", ")), stringResource(R.string.settings_languages_hint),
        enabled = s.enabled,
    )
    if (s.settings.developerOptions) {
        SwitchRow(
            s.settings.installGpuRuntimes, { v -> s.editSettings { copy(installGpuRuntimes = v) } },
            stringResource(R.string.settings_gpu_runtimes), stringResource(R.string.settings_gpu_runtimes_hint),
            enabled = s.enabled, developer = true,
        )
    }
}

@Composable
fun GlobalDeveloperPage(s: GlobalPageState) = SettingsPageColumn(R.string.settings_page_developer) {
    SwitchRow(
        s.settings.developerOptions, { v -> s.editSettings { copy(developerOptions = v) } },
        stringResource(R.string.settings_developer_options), stringResource(R.string.settings_developer_options_hint), enabled = s.enabled,
    )
    if (s.settings.developerOptions) {
        val titleBarNames = mapOf(
            TitleBars.AUTO to stringResource(R.string.settings_title_bars_auto),
            TitleBars.ON to stringResource(R.string.settings_title_bars_on),
            TitleBars.OFF to stringResource(R.string.settings_title_bars_off),
        )
        DropdownRow(
            selected = s.settings.titleBars,
            options = TitleBars.entries,
            onSelect = { v -> s.editSettings { copy(titleBars = v) } },
            label = { titleBarNames.getValue(it) },
            title = stringResource(R.string.settings_title_bars),
            hint = stringResource(R.string.settings_title_bars_hint),
            enabled = s.enabled,
            developer = true,
        )
        SwitchRow(
            s.settings.terminalFilter, { v -> s.editSettings { copy(terminalFilter = v) } },
            stringResource(R.string.settings_terminal_filter), stringResource(R.string.settings_terminal_filter_hint),
            enabled = s.enabled, developer = true,
        )
        SwitchRow(
            s.settings.terminalDuringSession, { v -> s.editSettings { copy(terminalDuringSession = v) } },
            stringResource(R.string.settings_terminal_during_session), stringResource(R.string.settings_terminal_during_session_hint),
            enabled = s.enabled, developer = true,
        )
    }
    SectionTitle(stringResource(R.string.settings_section_tools))
    OutlinedButton(onClick = s.env.onTerminal, enabled = s.enabled && s.env.canOpenTerminal) { Text(stringResource(R.string.terminal_title)) }
}

@Composable
fun GlobalStoragePage(s: GlobalPageState) = SettingsPageColumn(R.string.settings_page_storage) {
    val env = s.env
    val actions = env.actions
    val storage = env.storage
    var cachesCleared by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmImportAll by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDotfiles by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    storage?.let { measured ->
        val unused = (if (confirmClear == HOMES) measured.unusedHomes else measured.unusedAppData) ?: Unused(0, 0)
        if (confirmClear != null && unused.apps > 0) {
            val homes = confirmClear == HOMES
            ConfirmDialog(
                stringResource(if (homes) R.string.settings_clear_unused_homes_title else R.string.settings_clear_unused_app_data_title),
                pluralStringResource(
                    if (homes) R.plurals.settings_clear_unused_homes_text else R.plurals.settings_clear_unused_app_data_text,
                    unused.apps, unused.apps, size(unused.bytes),
                ),
                stringResource(R.string.settings_clear),
                onConfirm = { confirmClear = null; if (homes) actions.onClearUnusedHomes() else actions.onClearUnusedAppData() },
                onDismiss = { confirmClear = null },
            )
        }
    }
    confirmImportAll?.let { what ->
        val homes = what == HOMES
        ConfirmDialog(
            stringResource(if (homes) R.string.storage_import_all_homes_title else R.string.storage_import_all_settings_title),
            stringResource(if (homes) R.string.storage_import_all_homes_text else R.string.storage_import_all_settings_text),
            stringResource(R.string.storage_import),
            onConfirm = { confirmImportAll = null; if (homes) actions.onImportAllHomes() else actions.onImportAllAppFiles() },
            onDismiss = { confirmImportAll = null },
        )
    }
    confirmDotfiles?.let { what ->
        val reset = what == RESET
        ConfirmDialog(
            stringResource(if (reset) R.string.storage_reset_shared_dotfiles_title else R.string.storage_import_shared_dotfiles_title),
            stringResource(if (reset) R.string.storage_reset_shared_dotfiles_text else R.string.storage_import_shared_dotfiles_text),
            stringResource(if (reset) R.string.storage_reset else R.string.storage_import),
            onConfirm = { confirmDotfiles = null; if (reset) actions.onResetSharedDotfiles() else actions.onImportSharedDotfiles() },
            onDismiss = { confirmDotfiles = null },
        )
    }

    Text(stringResource(R.string.settings_shared_home, env.sharedHome))
    Text(stringResource(R.string.settings_shared_home_hint), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = actions.onChangeSharedHome, enabled = s.enabled) { Text(stringResource(R.string.settings_change_shared_home)) }
    if (env.allFilesAccess != null) {
        SwitchRow(
            env.allFilesAccess, { actions.onAllFilesAccess() },
            stringResource(R.string.settings_all_files_access), stringResource(R.string.settings_all_files_access_hint),
            enabled = s.enabled,
        )
    }
    if (storage == null) {
        Text(stringResource(R.string.app_settings_measuring))
    } else {
        // Each part shows as soon as it is known; one from an earlier measurement is marked until measured again.
        val measuringSize = stringResource(R.string.storage_measuring_size)
        val measuring = stringResource(R.string.app_settings_measuring)
        fun shown(bytes: Long?, again: Boolean) = bytes?.let { size(it).let { t -> if (again) measuringSize.format(t) else t } } ?: measuring
        fun shown(bytes: Long?, part: FlatdroidStorage.Part) = shown(bytes, part in storage.stale)
        Column {
            Text(stringResource(R.string.settings_storage_total, shown(storage.total, storage.stale.isNotEmpty())))
            Text(stringResource(R.string.settings_storage_runtimes, shown(storage.runtimes, FlatdroidStorage.Part.SHARED)))
            Text(stringResource(R.string.settings_storage_apps, shown(storage.apps, FlatdroidStorage.Part.APPS)))
            Text(stringResource(R.string.settings_storage_app_data, shown(storage.appData, FlatdroidStorage.Part.APPS)))
            Text(stringResource(R.string.settings_storage_homes, shown(storage.privateHomes, FlatdroidStorage.Part.APPS)))
            Text(stringResource(R.string.settings_storage_cache, shown(storage.cache, FlatdroidStorage.Part.CACHE)))
        }
    }
    val canClear = s.enabled && env.canReset && !env.storageBusy
    OutlinedButton(onClick = { actions.onClearAllCaches(); cachesCleared = true }, enabled = s.enabled && env.canReset && !cachesCleared) {
        Text(stringResource(if (cachesCleared) R.string.app_settings_cache_cleared else R.string.settings_clear_all_caches))
    }
    OutlinedButton(onClick = { confirmClear = APP_DATA }, enabled = canClear && (storage?.unusedAppData?.apps ?: 0) > 0) {
        Text(stringResource(R.string.settings_clear_unused_app_data))
    }
    OutlinedButton(onClick = { confirmClear = HOMES }, enabled = canClear && (storage?.unusedHomes?.apps ?: 0) > 0) {
        Text(stringResource(R.string.settings_clear_unused_homes))
    }
    Text(stringResource(R.string.settings_all_apps), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.settings_all_apps_hint), style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { confirmImportAll = HOMES }, enabled = canClear) { Text(stringResource(R.string.storage_import_all_homes)) }
        OutlinedButton(onClick = actions.onExportAllHomes, enabled = canClear) { Text(stringResource(R.string.storage_export_all_homes)) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { confirmImportAll = APP_DATA }, enabled = canClear) { Text(stringResource(R.string.storage_import_all_settings)) }
        OutlinedButton(onClick = actions.onExportAllAppFiles, enabled = canClear) { Text(stringResource(R.string.storage_export_all_settings)) }
    }
    // Used only by apps that opt in (a developer setting), so shown with developer options or once it holds something.
    val sharedDotfiles = storage?.sharedDotfiles ?: 0L
    if (s.settings.developerOptions || sharedDotfiles > 0) {
        Text(stringResource(R.string.settings_shared_dotfiles), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_shared_dotfiles_hint, size(sharedDotfiles)), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirmDotfiles = IMPORT }, enabled = canClear) { Text(stringResource(R.string.storage_import)) }
            OutlinedButton(onClick = actions.onExportSharedDotfiles, enabled = canClear && sharedDotfiles > 0) { Text(stringResource(R.string.storage_export)) }
            OutlinedButton(onClick = { confirmDotfiles = RESET }, enabled = canClear && sharedDotfiles > 0) {
                Text(stringResource(R.string.storage_reset), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    Text(stringResource(R.string.settings_flatdroid_settings), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.settings_flatdroid_settings_hint), style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = actions.onImportSettings, enabled = s.enabled && env.canReset) { Text(stringResource(R.string.storage_import_settings)) }
        OutlinedButton(onClick = actions.onExportSettings, enabled = s.enabled) { Text(stringResource(R.string.storage_export_settings)) }
    }
}

private const val APP_DATA = "appData"
private const val HOMES = "homes"
private const val IMPORT = "import"
private const val RESET = "reset"

/** The viewer list's entry for Flatdroid's own viewer (viewer apps are their package names). */
private const val OWN_VIEWER = ""
