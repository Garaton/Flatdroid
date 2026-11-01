package com.garathon.flatdroid.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.FlatdroidStorage
import com.garathon.flatdroid.Unused
import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.IntegrityGuard
import com.garathon.flatdroid.settings.FlatpakTheme
import com.garathon.flatdroid.setup.ResetMode
import com.garathon.flatdroid.alpine.GuestFiles
import com.garathon.flatdroid.session.Viewer
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.ui.theme.FlatdroidTheme

/**
 * Flatdroid's Settings, opened from the home screen: every setting by section, with Save and
 * Back, the Alpine terminal, and a danger section at the end (resetting the Alpine environment,
 * not while an app or a Flatpak task runs: [canReset]). The Storage section's actions take
 * effect at once, without Save: changing the shared home ([sharedHome], shown as the user knows
 * it) and All files access ([allFilesAccess], null where Android has no such setting), which
 * opens Android's setting; Flatdroid's storage ([storage], null while measured) with clearing
 * every app's cache and what apps that aren't installed left; export and import of every app's
 * private home or settings (not while a storage task runs: [storageBusy]); and export, import
 * and reset of Flatdroid's own settings.
 * [deviceLanguages] is Flatpak's value for the device's languages (e.g. "de;en"); [viewers] are
 * the installed VNC viewers to choose from.
 */
@Composable
fun SettingsScreen(
    settings: UserSettings,
    deviceLanguages: String,
    viewers: List<Viewer.App>,
    applying: Boolean,
    error: String?,
    onSave: (UserSettings) -> Unit,
    /** Saves, then reinstalls every app for a baseline from known-good files (the "no baseline" prompt). */
    onSaveAndReinstallAll: (UserSettings) -> Unit = onSave,
    /** Saves, then records the file baseline of every installed app from its files as they are. */
    onSaveAndBaselineAll: (UserSettings) -> Unit = onSave,
    onBack: () -> Unit,
    onReset: (ResetMode) -> Unit,
    onTerminal: () -> Unit,
    sharedHome: String,
    allFilesAccess: Boolean?,
    onChangeSharedHome: () -> Unit,
    onAllFilesAccess: () -> Unit,
    storage: FlatdroidStorage?,
    onClearAllCaches: () -> Unit,
    onClearUnusedAppData: () -> Unit,
    onClearUnusedHomes: () -> Unit,
    onExportAllHomes: () -> Unit,
    onImportAllHomes: () -> Unit,
    onExportAllAppFiles: () -> Unit,
    onImportAllAppFiles: () -> Unit,
    onExportSharedDotfiles: () -> Unit,
    onImportSharedDotfiles: () -> Unit,
    onResetSharedDotfiles: () -> Unit,
    onExportSettings: () -> Unit,
    onImportSettings: () -> Unit,
    onResetSettings: () -> Unit,
    modifier: Modifier = Modifier,
    canReset: Boolean = true,
    storageBusy: Boolean = false,
) {
    var onlyMyLanguages by rememberSaveable { mutableStateOf(settings.onlyMyLanguages) }
    var developerOptions by rememberSaveable { mutableStateOf(settings.developerOptions) }
    var vncViewer by rememberSaveable { mutableStateOf(settings.vncViewer) }
    var frameRate60 by rememberSaveable { mutableStateOf(settings.frameRate60) }
    var customFrameRate by rememberSaveable { mutableStateOf(settings.customFrameRate?.toString() ?: "") }
    var uncappedFrameRate by rememberSaveable { mutableStateOf(settings.uncappedFrameRate) }
    var titleBars by rememberSaveable { mutableStateOf(settings.titleBars) }
    var installGpuRuntimes by rememberSaveable { mutableStateOf(settings.installGpuRuntimes) }
    var audioLatency by rememberSaveable { mutableStateOf(settings.customAudioLatencyMs?.toString() ?: "") }
    var lowLatencyAudio by rememberSaveable { mutableStateOf(settings.lowLatencyAudio) }
    var highQualityAudio by rememberSaveable { mutableStateOf(settings.highQualityAudio) }
    var flatpakTheme by rememberSaveable { mutableStateOf(settings.flatpakTheme) }
    var security by rememberSaveable(stateSaver = SecuritySaver) { mutableStateOf(settings.security) }
    var terminalFilter by rememberSaveable { mutableStateOf(settings.terminalFilter) }
    var confirmResetSettings by rememberSaveable { mutableStateOf(false) }
    var baselineChoice by rememberSaveable { mutableStateOf(false) }
    var cachesCleared by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmImportAll by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDotfiles by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    if (confirmResetSettings) {
        ConfirmDialog(
            stringResource(R.string.settings_reset_settings_title), stringResource(R.string.settings_reset_settings_text),
            stringResource(R.string.storage_reset_settings),
            onConfirm = { confirmResetSettings = false; onResetSettings() }, onDismiss = { confirmResetSettings = false },
        )
    }
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
                onConfirm = { confirmClear = null; if (homes) onClearUnusedHomes() else onClearUnusedAppData() },
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
            onConfirm = { confirmImportAll = null; if (homes) onImportAllHomes() else onImportAllAppFiles() },
            onDismiss = { confirmImportAll = null },
        )
    }
    confirmDotfiles?.let { what ->
        val reset = what == RESET
        ConfirmDialog(
            stringResource(if (reset) R.string.storage_reset_shared_dotfiles_title else R.string.storage_import_shared_dotfiles_title),
            stringResource(if (reset) R.string.storage_reset_shared_dotfiles_text else R.string.storage_import_shared_dotfiles_text),
            stringResource(if (reset) R.string.storage_reset else R.string.storage_import),
            onConfirm = { confirmDotfiles = null; if (reset) onResetSharedDotfiles() else onImportSharedDotfiles() },
            onDismiss = { confirmDotfiles = null },
        )
    }
    // Empty means "not set"; anything else must be a rate Xvnc takes.
    val customFrameRateValue = customFrameRate.toIntOrNull()?.takeIf { it in UserSettings.CUSTOM_FRAME_RATES }
    val customFrameRateValid = customFrameRate.isEmpty() || customFrameRateValue != null
    val audioLatencyValue = audioLatency.toIntOrNull()?.takeIf { it in UserSettings.AUDIO_LATENCIES_MS }
    val audioLatencyValid = audioLatency.isEmpty() || audioLatencyValue != null
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    if (confirmReset) {
        ResetDialog(onConfirm = { confirmReset = false; onReset(it) }, onDismiss = { confirmReset = false })
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        SectionTitle(stringResource(R.string.settings_section_flatpak))
        
        SwitchRow(
        	onlyMyLanguages, { onlyMyLanguages = it },
        	stringResource(R.string.settings_languages, deviceLanguages.replace(";", ", ")), stringResource(R.string.settings_languages_hint),
        	enabled = !applying
        )
        val themeNames = mapOf(
            FlatpakTheme.SYSTEM to stringResource(R.string.settings_theme_system),
            FlatpakTheme.DARK to stringResource(R.string.settings_theme_dark),
            FlatpakTheme.LIGHT to stringResource(R.string.settings_theme_light),
        )
        DropdownRow(
            selected = flatpakTheme,
            options = FlatpakTheme.entries,
            onSelect = { flatpakTheme = it },
            label = { themeNames.getValue(it) },
            title = stringResource(R.string.settings_theme),
            hint = stringResource(R.string.settings_theme_hint),
            enabled = !applying,
        )

        SectionTitle(stringResource(R.string.settings_section_security))
        SecuritySection(security, { security = it }, details = true, enabled = !applying, developerOptions = developerOptions)

        SectionTitle(stringResource(R.string.settings_section_viewer))
        val askViewer = stringResource(R.string.settings_viewer_ask)
        // Only the saved choice can be missing: the list holds installed viewers.
        val missingViewer = stringResource(R.string.settings_viewer_missing, vncViewer.orEmpty())
        DropdownRow(
            selected = vncViewer,
            options = listOf(null) + viewers.map { it.packageName },
            onSelect = { vncViewer = it },
            label = { id -> if (id == null) askViewer else viewers.firstOrNull { it.packageName == id }?.label ?: missingViewer },
            title = stringResource(R.string.settings_viewer),
            hint = stringResource(R.string.settings_viewer_hint),
            enabled = !applying,
        )
        SwitchRow(
            frameRate60, { frameRate60 = it },
            stringResource(R.string.settings_frame_rate_60), stringResource(R.string.settings_frame_rate_60_hint), enabled = !applying,
        )
        if (developerOptions) {
            TextRow(
                content = customFrameRate,
                onChange = { customFrameRate = it.filter(Char::isDigit).take(4) },
                title = stringResource(R.string.settings_custom_frame_rate),
                hint = stringResource(R.string.settings_custom_frame_rate_hint, UserSettings.CUSTOM_FRAME_RATES.first, UserSettings.CUSTOM_FRAME_RATES.last),
                isError = !customFrameRateValid,
                enabled = !applying && !uncappedFrameRate,
                developer = true
            )
            SwitchRow(
                uncappedFrameRate, { uncappedFrameRate = it },
                stringResource(R.string.settings_uncapped_frame_rate), stringResource(R.string.settings_uncapped_frame_rate_hint),
                enabled = !applying, developer = true,
            )
        }

        SectionTitle(stringResource(R.string.settings_section_audio))
        SwitchRow(
            lowLatencyAudio, { lowLatencyAudio = it },
            stringResource(R.string.settings_low_latency_audio), stringResource(R.string.settings_low_latency_audio_hint), enabled = !applying,
        )
        SwitchRow(
            highQualityAudio, { highQualityAudio = it },
            stringResource(R.string.settings_high_quality_audio), stringResource(R.string.settings_high_quality_audio_hint), enabled = !applying,
        )
        if (developerOptions) {
            TextRow(
                content = audioLatency,
                onChange = { audioLatency = it.filter(Char::isDigit).take(4) },
                title = stringResource(R.string.settings_audio_latency),
                hint = stringResource(
                    R.string.settings_audio_latency_hint,
                    UserSettings.AUDIO_LATENCIES_MS.first, UserSettings.AUDIO_LATENCIES_MS.last, UserSettings.DEFAULT_AUDIO_LATENCY_MS,
                ),
                isError = !audioLatencyValid,
                enabled = !applying,
                developer = true,
            )
        }

        SectionTitle(stringResource(R.string.settings_section_storage))
        Text(stringResource(R.string.settings_shared_home, sharedHome))
        Text(stringResource(R.string.settings_shared_home_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onChangeSharedHome, enabled = !applying) { Text(stringResource(R.string.settings_change_shared_home)) }
        if (allFilesAccess != null) {
            SwitchRow(
                allFilesAccess, { onAllFilesAccess() },
                stringResource(R.string.settings_all_files_access), stringResource(R.string.settings_all_files_access_hint),
                enabled = !applying,
            )
        }
        if (storage == null) {
            Text(stringResource(R.string.app_settings_measuring))
        } else {
            // Each part shows as soon as it is known; one from an earlier measurement is marked until measured again.
            val measuringSize = stringResource(R.string.storage_measuring_size)
            val measuring = stringResource(R.string.app_settings_measuring)
            fun shown(bytes: Long?, again: Boolean) = bytes?.let { size(it).let { s -> if (again) measuringSize.format(s) else s } } ?: measuring
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
        val canClear = !applying && canReset && !storageBusy
        OutlinedButton(onClick = { onClearAllCaches(); cachesCleared = true }, enabled = !applying && canReset && !cachesCleared) {
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
            OutlinedButton(onClick = onExportAllHomes, enabled = canClear) { Text(stringResource(R.string.storage_export_all_homes)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirmImportAll = APP_DATA }, enabled = canClear) { Text(stringResource(R.string.storage_import_all_settings)) }
            OutlinedButton(onClick = onExportAllAppFiles, enabled = canClear) { Text(stringResource(R.string.storage_export_all_settings)) }
        }
        // Used only by apps that opt in (a developer setting), so shown with developer options or once it holds something.
        val sharedDotfiles = storage?.sharedDotfiles ?: 0L
        if (developerOptions || sharedDotfiles > 0) {
            Text(stringResource(R.string.settings_shared_dotfiles), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_shared_dotfiles_hint, size(sharedDotfiles)), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirmDotfiles = IMPORT }, enabled = canClear) { Text(stringResource(R.string.storage_import)) }
                OutlinedButton(onClick = onExportSharedDotfiles, enabled = canClear && sharedDotfiles > 0) { Text(stringResource(R.string.storage_export)) }
                OutlinedButton(onClick = { confirmDotfiles = RESET }, enabled = canClear && sharedDotfiles > 0) {
                    Text(stringResource(R.string.storage_reset), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Text(stringResource(R.string.settings_flatdroid_settings), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_flatdroid_settings_hint), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImportSettings, enabled = !applying && canReset) { Text(stringResource(R.string.storage_import_settings)) }
            OutlinedButton(onClick = onExportSettings, enabled = !applying) { Text(stringResource(R.string.storage_export_settings)) }
            OutlinedButton(onClick = { confirmResetSettings = true }, enabled = !applying && canReset) {
                Text(stringResource(R.string.storage_reset_settings), color = MaterialTheme.colorScheme.error)
            }
        }

		// Developer Section
        SectionTitle(stringResource(R.string.settings_section_developer))
        SwitchRow(
            developerOptions, { developerOptions = it },
            stringResource(R.string.settings_developer_options), stringResource(R.string.settings_developer_options_hint), enabled = !applying,
        )
        if (developerOptions) {
            SwitchRow(
                titleBars, { titleBars = it },
                stringResource(R.string.settings_title_bars), stringResource(R.string.settings_title_bars_hint),
                enabled = !applying, developer = true,
            )
            SwitchRow(
                installGpuRuntimes, { installGpuRuntimes = it },
                stringResource(R.string.settings_gpu_runtimes), stringResource(R.string.settings_gpu_runtimes_hint),
                enabled = !applying, developer = true,
            )
            SwitchRow(
                terminalFilter, { terminalFilter = it },
                stringResource(R.string.settings_terminal_filter), stringResource(R.string.settings_terminal_filter_hint),
                enabled = !applying, developer = true,
            )
        }

        error?.let {
            SelectionContainer { Text(stringResource(R.string.settings_failed, it), color = MaterialTheme.colorScheme.error) }
        }
        val collectSettings = {
            UserSettings(
                flatpakLanguages = if (onlyMyLanguages) deviceLanguages else GuestFiles.ALL_LANGUAGES,
                developerOptions = developerOptions,
                vncViewer = vncViewer,
                frameRate60 = frameRate60,
                customFrameRate = customFrameRateValue,
                uncappedFrameRate = uncappedFrameRate,
                installGpuRuntimes = installGpuRuntimes,
                titleBars = titleBars,
                customAudioLatencyMs = audioLatencyValue,
                lowLatencyAudio = lowLatencyAudio,
                highQualityAudio = highQualityAudio,
                sharedHome = settings.sharedHome,
                flatpakTheme = flatpakTheme,
                security = security,
                terminalFilter = terminalFilter,
            )
        }
        if (baselineChoice) {
            BaselineChoiceDialog(
                canStartTask = canReset,
                onReinstall = { baselineChoice = false; onSaveAndReinstallAll(collectSettings()) },
                onRecordNow = { baselineChoice = false; onSaveAndBaselineAll(collectSettings()) },
                onAtFirstLaunch = { baselineChoice = false; onSave(collectSettings()) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    // Turning the check on: its baselines were dropped when it was turned off (and installs or
                    // updates meanwhile recorded none), so ask how to record them.
                    val enablingCheck = security.checkFilesBeforeLaunch && !settings.security.checkFilesBeforeLaunch
                    if (enablingCheck && !IntegrityGuard(context).allBaselined()) baselineChoice = true
                    else onSave(collectSettings())
                },
                enabled = !applying && customFrameRateValid && audioLatencyValid,
            ) {
                Text(stringResource(if (applying) R.string.settings_applying else R.string.settings_save))
            }
            TextButton(onClick = onBack, enabled = !applying) { Text(stringResource(R.string.settings_back)) }
        }

        SectionTitle(stringResource(R.string.settings_section_tools))
        OutlinedButton(onClick = onTerminal, enabled = !applying) { Text(stringResource(R.string.terminal_title)) }

        SectionTitle(stringResource(R.string.settings_section_danger), danger = true)
        OutlinedButton(onClick = { confirmReset = true }, enabled = !applying && canReset) {
            Text(stringResource(R.string.reset_button), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    FlatdroidTheme {
        SettingsScreen(
            UserSettings.defaults("de;en"), "de;en", listOf(Viewer.App("com.gaurav.avnc", "AVNC")), applying = false, error = null,
            onSave = {}, onBack = {}, onReset = {}, onTerminal = {},
            sharedHome = "Documents/Flatdroid", allFilesAccess = false, onChangeSharedHome = {}, onAllFilesAccess = {},
            storage = FlatdroidStorage(
                runtimes = 800L shl 20, apps = 200L shl 20, appData = 5L shl 20, privateHomes = 30L shl 20, cache = 3L shl 20,
                other = 150L shl 20, unusedAppData = Unused(1, 1L shl 20), unusedHomes = Unused(0, 0), sharedDotfiles = 0,
            ),
            onClearAllCaches = {}, onClearUnusedAppData = {}, onClearUnusedHomes = {},
            onExportAllHomes = {}, onImportAllHomes = {}, onExportAllAppFiles = {}, onImportAllAppFiles = {},
            onExportSharedDotfiles = {}, onImportSharedDotfiles = {}, onResetSharedDotfiles = {},
            onExportSettings = {}, onImportSettings = {}, onResetSettings = {},
        )
    }
}

private const val APP_DATA = "appData"
private const val HOMES = "homes"
private const val IMPORT = "import"
private const val RESET = "reset"
