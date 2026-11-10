package com.garathon.flatdroid.ui.settings

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.garathon.flatdroid.R
import com.garathon.flatdroid.Unused
import com.garathon.flatdroid.ui.ConfirmDialog
import com.garathon.flatdroid.ui.StorageAction

/**
 * The storage actions that ask first. What is waiting is kept as a string, so it survives
 * rotation: `app:<ID>:<ACTION>` for one app's, or one of the names below.
 */
object StorageConfirm {
    fun app(appId: String, action: StorageAction) = "app:$appId:${action.name}"

    const val CLEAR_APP_DATA = "clearAppData"
    const val CLEAR_HOMES = "clearHomes"
    const val IMPORT_ALL_HOMES = "importAllHomes"
    const val IMPORT_ALL_APP_DATA = "importAllAppData"
    const val IMPORT_DOTFILES = "importDotfiles"
    const val RESET_DOTFILES = "resetDotfiles"
    const val RESET_ALL_HOMES = "resetAllHomes"
    const val RESET_ALL_APP_DATA = "resetAllAppData"
}

/** What [StorageConfirmDialog] runs once the user says yes. */
class StorageConfirmed(
    val onAppAction: (appId: String, StorageAction) -> Unit,
    val onGlobal: (key: String) -> Unit,
)

/**
 * The question for [key] ([StorageConfirm]); [nameOf] names an app, and [unusedAppData] and
 * [unusedHomes] say what clearing would remove.
 */
@Composable
fun StorageConfirmDialog(
    key: String,
    nameOf: (String) -> String,
    unusedAppData: Unused?,
    unusedHomes: Unused?,
    confirmed: StorageConfirmed,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    if (key.startsWith("app:")) {
        val parts = key.removePrefix("app:").split(":")
        val appId = parts.getOrNull(0) ?: return
        val action = StorageAction.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: return
        val (title, text, button) = when (action) {
            StorageAction.IMPORT_HOME -> Triple(R.string.app_settings_import_home_title, R.string.app_settings_import_home_text, R.string.storage_import)
            StorageAction.RESET_HOME -> Triple(R.string.app_settings_reset_home_title, R.string.app_settings_reset_home_text, R.string.storage_reset)
            StorageAction.IMPORT_SETTINGS -> Triple(R.string.app_settings_import_settings_title, R.string.app_settings_import_settings_text, R.string.storage_import)
            StorageAction.DELETE_HOME -> Triple(R.string.storage_delete_home_title, R.string.storage_delete_home_text, R.string.storage_delete)
            StorageAction.DELETE_SETTINGS -> Triple(R.string.storage_delete_app_data_title, R.string.storage_delete_app_data_text, R.string.storage_delete)
            else -> Triple(R.string.app_settings_reset_app_settings_title, R.string.app_settings_reset_app_settings_text, R.string.storage_reset)
        }
        ConfirmDialog(
            stringResource(title, nameOf(appId)), stringResource(text), stringResource(button),
            onConfirm = { onDismiss(); confirmed.onAppAction(appId, action) }, onDismiss = onDismiss,
        )
        return
    }
    val unused = when (key) {
        StorageConfirm.CLEAR_APP_DATA -> unusedAppData
        StorageConfirm.CLEAR_HOMES -> unusedHomes
        else -> null
    }
    val (title, text, button) = when (key) {
        StorageConfirm.CLEAR_APP_DATA, StorageConfirm.CLEAR_HOMES -> {
            val homes = key == StorageConfirm.CLEAR_HOMES
            val found = unused ?: return
            if (found.apps == 0) return
            Triple(
                stringResource(if (homes) R.string.settings_clear_unused_homes_title else R.string.settings_clear_unused_app_data_title),
                pluralStringResource(
                    if (homes) R.plurals.settings_clear_unused_homes_text else R.plurals.settings_clear_unused_app_data_text,
                    found.apps, found.apps, size(found.bytes),
                ),
                stringResource(R.string.settings_clear),
            )
        }
        StorageConfirm.IMPORT_ALL_HOMES -> Triple(stringResource(R.string.storage_import_all_homes_title), stringResource(R.string.storage_import_all_homes_text), stringResource(R.string.storage_import))
        StorageConfirm.IMPORT_ALL_APP_DATA -> Triple(stringResource(R.string.storage_import_all_settings_title), stringResource(R.string.storage_import_all_settings_text), stringResource(R.string.storage_import))
        StorageConfirm.IMPORT_DOTFILES -> Triple(stringResource(R.string.storage_import_shared_dotfiles_title), stringResource(R.string.storage_import_shared_dotfiles_text), stringResource(R.string.storage_import))
        StorageConfirm.RESET_DOTFILES -> Triple(stringResource(R.string.storage_reset_shared_dotfiles_title), stringResource(R.string.storage_reset_shared_dotfiles_text), stringResource(R.string.storage_reset))
        StorageConfirm.RESET_ALL_HOMES -> Triple(stringResource(R.string.storage_reset_all_homes_title), stringResource(R.string.storage_reset_all_homes_text), stringResource(R.string.storage_reset))
        StorageConfirm.RESET_ALL_APP_DATA -> Triple(stringResource(R.string.storage_reset_all_app_data_title), stringResource(R.string.storage_reset_all_app_data_text), stringResource(R.string.storage_reset))
        else -> return
    }
    ConfirmDialog(title, text, button, onConfirm = { onDismiss(); confirmed.onGlobal(key) }, onDismiss = onDismiss)
}
