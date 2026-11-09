package com.garathon.flatdroid.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PermCameraMic
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.FlatdroidStorage
import com.garathon.flatdroid.R
import com.garathon.flatdroid.camera.AndroidCameras
import com.garathon.flatdroid.flatpak.InstalledApp
import com.garathon.flatdroid.Unused
import com.garathon.flatdroid.flatpak.IntegrityGuard
import com.garathon.flatdroid.session.Viewer
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.settings.UserSettingsDraft
import com.garathon.flatdroid.setup.ResetMode
import com.garathon.flatdroid.ui.BaselineChoiceDialog
import com.garathon.flatdroid.ui.ConfirmDialog
import com.garathon.flatdroid.ui.StorageAction
import com.garathon.flatdroid.ui.FlatdroidIcon
import com.garathon.flatdroid.ui.theme.FlatdroidTheme

/** What the Storage page can ask for; each is an action of the activity. */
class GlobalStorageActions(
    val onChangeSharedHome: () -> Unit,
    val onAllFilesAccess: () -> Unit,
    val onClearAllCaches: () -> Unit,
    val onClearUnusedAppData: () -> Unit,
    val onClearUnusedHomes: () -> Unit,
    val onExportAllHomes: () -> Unit,
    val onImportAllHomes: () -> Unit,
    val onExportAllAppFiles: () -> Unit,
    val onImportAllAppFiles: () -> Unit,
    val onExportSharedDotfiles: () -> Unit,
    val onImportSharedDotfiles: () -> Unit,
    val onResetSharedDotfiles: () -> Unit,
    val onExportSettings: () -> Unit,
    val onImportSettings: () -> Unit,
    val onAddFiles: () -> Unit,
    val onAddZip: () -> Unit,
    val onResetAllHomes: () -> Unit,
    val onResetAllAppData: () -> Unit,
    /** One app's storage action from the lists (its home or app data: import, export, reset). */
    val onAppStorage: (appId: String, StorageAction) -> Unit,
    val onOpenHome: (appId: String) -> Unit,
    val onUninstall: (appId: String, deleteData: Boolean, keepRuntimes: Boolean) -> Unit,
)

/**
 * Everything the pages read besides the draft. [deviceLanguages] is Flatpak's value for the
 * device's languages (e.g. "de;en"); [viewers] are the installed VNC viewers; [allFilesAccess] is
 * null where Android has no such setting; [storage] is null while measured. Resetting and the
 * terminal are not offered while an app or a Flatpak task runs ([canReset], [canOpenTerminal]).
 */
class GlobalSettingsEnv(
    val deviceLanguages: String,
    val viewers: List<Viewer.App>,
    val applying: Boolean,
    val error: String?,
    val sharedHome: String,
    val allFilesAccess: Boolean?,
    val storage: FlatdroidStorage?,
    val storageBusy: Boolean,
    val canReset: Boolean,
    val canOpenTerminal: Boolean,
    val actions: GlobalStorageActions,
    val onReset: (ResetMode) -> Unit,
    val onResetSettings: () -> Unit,
    val onTerminal: () -> Unit,
    /** The installed apps, for the Overview's usage numbers. */
    val apps: List<InstalledApp>,
    /** What the running Flatpak task is doing (an uninstall started from the Storage lists, say); null when none runs. */
    val taskText: String?,
    /** The device has a camera, and the sizes its cameras give at each rate ([AndroidCameras.resolutions]; empty while asked). */
    val hasCamera: Boolean,
    val cameraResolutions: Map<Int, List<AndroidCameras.Resolution>>,
)

/** The draft and its environment, as the pages see them. */
class GlobalPageState(
    val env: GlobalSettingsEnv,
    val draft: UserSettingsDraft,
    /** Goes to another page (the Overview's chips). */
    val onPage: (GlobalPage) -> Unit,
    private val onDraft: (UserSettingsDraft) -> Unit,
) {
    val settings: UserSettings get() = draft.settings

    fun edit(change: UserSettingsDraft.() -> UserSettingsDraft) = onDraft(draft.change())

    fun editSettings(change: UserSettings.() -> UserSettings) = edit { copy(settings = settings.change()) }
}

/**
 * Flatdroid's settings, opened from the home screen: the pages in a rail, edited as one draft.
 * The check saves it ([onSave]; the two other callbacks are for the prompt that turning on Safe
 * launch brings when apps have no file baseline), the X discards it ([onDiscard]), asking first
 * when something changed. The Storage page's actions take effect at once, without saving.
 */
@Composable
fun GlobalSettingsScreen(
    page: GlobalPage,
    onPage: (GlobalPage) -> Unit,
    draft: SettingsDraftModel.Draft<UserSettingsDraft>,
    env: GlobalSettingsEnv,
    onSave: (UserSettings) -> Unit,
    /** Saves, then reinstalls every app for a baseline from known-good files (the "no baseline" prompt). */
    onSaveAndReinstallAll: (UserSettings) -> Unit,
    /** Saves, then records the file baseline of every installed app from its files as they are. */
    onSaveAndBaselineAll: (UserSettings) -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = draft.current
    var baselineChoice by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val toSave = current.toSettings(env.deviceLanguages)
    fun requestDiscard() {
        if (env.applying) return
        if (draft.changed) confirmDiscard = true else onDiscard()
    }
    BackHandler { requestDiscard() }
    if (confirmDiscard) {
        ConfirmDialog(
            stringResource(R.string.settings_discard_title), stringResource(R.string.settings_discard_text), stringResource(R.string.settings_discard_confirm),
            onConfirm = { confirmDiscard = false; onDiscard() }, onDismiss = { confirmDiscard = false },
        )
    }
    if (baselineChoice && toSave != null) {
        BaselineChoiceDialog(
            canStartTask = env.canReset,
            onReinstall = { baselineChoice = false; onSaveAndReinstallAll(toSave) },
            onRecordNow = { baselineChoice = false; onSaveAndBaselineAll(toSave) },
            onAtFirstLaunch = { baselineChoice = false; onSave(toSave) },
        )
    }
    val state = GlobalPageState(env, current, onPage) { draft.current = it }
    SettingsShell(
        pages = GlobalPage.entries,
        selected = page,
        onSelect = onPage,
        error = env.error,
        dimmed = env.applying,
        developerOptions = current.settings.developerOptions,
        modifier = modifier,
        topBar = {
            SettingsTopBar(
                canSave = draft.changed && toSave != null,
                busy = env.applying,
                onSave = {
                    toSave ?: return@SettingsTopBar
                    // Turning the check on: its baselines were dropped when it was turned off (and installs or
                    // updates meanwhile recorded none), so ask how to record them.
                    val enablingCheck = toSave.security.checkFilesBeforeLaunch && !draft.saved.settings.security.checkFilesBeforeLaunch
                    if (enablingCheck && !IntegrityGuard(context).allBaselined()) baselineChoice = true else onSave(toSave)
                },
                onDiscard = ::requestDiscard,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlatdroidIcon(Modifier.padding(end = 12.dp))
                    Text(stringResource(R.string.settings_top_title), style = MaterialTheme.typography.titleLarge)
                }
            }
        },
    ) { shown ->
        when (shown) {
            GlobalPage.OVERVIEW -> GlobalOverviewPage(state)
            GlobalPage.PER_APP_DEFAULTS -> GlobalDefaultsPage(state)
            GlobalPage.DISPLAY -> GlobalDisplayPage(state)
            GlobalPage.DEVICES -> GlobalDevicesPage(state)
            GlobalPage.SECURITY -> GlobalSecurityPage(state)
            GlobalPage.FLATPAK -> GlobalFlatpakPage(state)
            GlobalPage.STORAGE -> GlobalStoragePage(state)
            GlobalPage.DEVELOPER -> GlobalDeveloperPage(state)
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun GlobalSettingsScreenPreview() {
    FlatdroidTheme {
        val draft = SettingsDraftModel.Draft(UserSettingsDraft.of(UserSettings.defaults("de;en")))
        GlobalSettingsScreen(
            page = GlobalPage.STORAGE, onPage = {}, draft = draft,
            env = GlobalSettingsEnv(
                deviceLanguages = "de;en", viewers = listOf(Viewer.App("com.gaurav.avnc", "AVNC")), applying = false, error = null,
                sharedHome = "Documents/Flatdroid", allFilesAccess = false,
                storage = FlatdroidStorage(
                    runtimes = 800L shl 20, apps = 200L shl 20, appData = 5L shl 20, privateHomes = 30L shl 20, cache = 3L shl 20,
                    other = 150L shl 20, unusedAppData = Unused(1, 1L shl 20), unusedHomes = Unused(0, 0), sharedDotfiles = 0,
                ),
                storageBusy = false, canReset = true, canOpenTerminal = true,
                actions = GlobalStorageActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, { _, _, _ -> }),
                onReset = {}, onResetSettings = {}, onTerminal = {},
                apps = emptyList(), taskText = null, hasCamera = true, cameraResolutions = emptyMap(),
            ),
            onSave = {}, onSaveAndReinstallAll = {}, onSaveAndBaselineAll = {}, onDiscard = {},
        )
    }
}
