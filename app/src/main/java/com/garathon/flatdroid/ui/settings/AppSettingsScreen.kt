package com.garathon.flatdroid.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.garathon.flatdroid.R
import com.garathon.flatdroid.camera.AndroidCameras
import com.garathon.flatdroid.flatpak.AppDetails
import com.garathon.flatdroid.flatpak.InstalledApp
import com.garathon.flatdroid.settings.AppSettings
import com.garathon.flatdroid.settings.AppSettingsDraft
import com.garathon.flatdroid.storage.AppStorage
import com.garathon.flatdroid.ui.home.AppIcon
import com.garathon.flatdroid.ui.ConfirmDialog
import com.garathon.flatdroid.ui.StorageAction
import com.garathon.flatdroid.ui.theme.FlatdroidTheme

/**
 * Everything the pages read besides the draft. [onRequestMicrophone] asks Android for its
 * microphone permission and reports whether it was granted: the microphone turns on only with it,
 * so a notification can later allow it for a session, unless it is always allowed. The camera
 * likewise ([onRequestCamera]), when the device has one ([hasCamera]), at a frame rate its cameras
 * can run at and a size they give at that rate ([cameraResolutions], by rate; empty while still
 * asked). [sharedHome] is the shared home as the user knows it; with access to all of shared
 * storage ([broadAccess]) an app can also get /sdcard. [storage] is null while measured;
 * [canChangeFiles] is false while the app runs or its files are copied, [canUninstall] while any
 * app or a Flatpak task runs. [showsWebContent]: an Electron app or a Chromium browser.
 */
class AppSettingsEnv(
    val app: InstalledApp,
    /** What its metadata says; null while read. */
    val details: AppDetails?,
    val storage: AppStorage?,
    val canChangeFiles: Boolean,
    val canUninstall: Boolean,
    val developerOptions: Boolean,
    val showsWebContent: Boolean,
    val hasCamera: Boolean,
    val cameraResolutions: Map<Int, List<AndroidCameras.Resolution>>,
    val onRequestMicrophone: (onResult: (Boolean) -> Unit) -> Unit,
    val onRequestCamera: (onResult: (Boolean) -> Unit) -> Unit,
    val onUninstall: (deleteData: Boolean, keepRuntimes: Boolean) -> Unit,
    val sharedHome: String,
    val broadAccess: Boolean,
    val onStorage: (StorageAction) -> Unit,
    /** Opens this app's app home in Android's Files app. */
    val onOpenHome: () -> Unit,
    /** The security level limits devices (an app asking for all devices gets the basic ones). */
    val limitDevices: Boolean,
    /** Removes this app's Flatdroid settings file; the screen then closes. */
    val onResetFlatdroidSettings: () -> Unit,
)

/** The draft and its environment, as the pages see them. */
class AppPageState(val env: AppSettingsEnv, val draft: AppSettingsDraft, private val onDraft: (AppSettingsDraft) -> Unit) {
    val app: InstalledApp get() = env.app
    val settings: AppSettings get() = draft.settings

    fun edit(change: AppSettingsDraft.() -> AppSettingsDraft) = onDraft(draft.change())

    fun editSettings(change: AppSettings.() -> AppSettings) = edit { copy(settings = settings.change()) }
}

/**
 * One app's settings, opened from its gear on the home screen: the pages in a rail, edited as one
 * draft. The check saves it ([onSave]), the X discards it ([onDiscard]), asking first when
 * something changed. The Storage page's actions take effect at once, without saving.
 */
@Composable
fun AppSettingsScreen(
    page: AppPage,
    onPage: (AppPage) -> Unit,
    draft: SettingsDraftModel.Draft<AppSettingsDraft>,
    env: AppSettingsEnv,
    onSave: (AppSettings) -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val toSave = draft.current.toSettings(env.developerOptions)
    fun requestDiscard() = if (draft.changed) confirmDiscard = true else onDiscard()
    BackHandler { requestDiscard() }
    if (confirmDiscard) {
        ConfirmDialog(
            stringResource(R.string.settings_discard_title), stringResource(R.string.settings_discard_text), stringResource(R.string.settings_discard_confirm),
            onConfirm = { confirmDiscard = false; onDiscard() }, onDismiss = { confirmDiscard = false },
        )
    }
    val state = AppPageState(env, draft.current) { draft.current = it }
    SettingsShell(
        pages = AppPage.entries.filter { it != AppPage.OVERRIDES || env.developerOptions },
        selected = page,
        onSelect = onPage,
        modifier = modifier,
        developerOptions = env.developerOptions,
        topBar = {
            SettingsTopBar(
                canSave = draft.changed && toSave != null,
                busy = false,
                onSave = { toSave?.let(onSave) },
                onDiscard = ::requestDiscard,
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    AppIcon(env.app.icon, Modifier.size(32.dp))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(env.app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            env.app.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
    ) { shown ->
        when (shown) {
            AppPage.OVERVIEW -> AppOverviewPage(state)
            AppPage.HOME -> AppHomePage(state)
            AppPage.DISPLAY -> AppDisplayPage(state)
            AppPage.PERMISSIONS -> AppPermissionsPage(state)
            AppPage.STORAGE -> AppStoragePage(state)
            AppPage.OVERRIDES -> AppOverridesPage(state)
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun AppSettingsScreenPreview() {
    FlatdroidTheme {
        AppSettingsScreen(
            page = AppPage.PERMISSIONS, onPage = {},
            draft = SettingsDraftModel.Draft(AppSettingsDraft.of(AppSettings())),
            env = AppSettingsEnv(
                app = InstalledApp("org.xfce.mousepad", "Mousepad", null, null), details = null,
                storage = AppStorage(9_000_000, 12_345_678, 4_096, 65_536), canChangeFiles = true, canUninstall = true,
                developerOptions = true, showsWebContent = false, hasCamera = true,
                cameraResolutions = mapOf(15 to listOf(AndroidCameras.Resolution(720)), 30 to listOf(AndroidCameras.Resolution(720))),
                onRequestMicrophone = {}, onRequestCamera = {}, onUninstall = { _, _ -> },
                sharedHome = "Documents/Flatdroid", broadAccess = true, onStorage = {}, onOpenHome = {},
                limitDevices = false, onResetFlatdroidSettings = {},
            ),
            onSave = {}, onDiscard = {},
        )
    }
}
