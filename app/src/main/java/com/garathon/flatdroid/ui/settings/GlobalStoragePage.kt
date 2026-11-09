package com.garathon.flatdroid.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.FlatdroidStorage
import com.garathon.flatdroid.R
import com.garathon.flatdroid.ui.IconAction
import com.garathon.flatdroid.ui.SectionTitle
import com.garathon.flatdroid.ui.StorageAction
import com.garathon.flatdroid.ui.SwitchRow
import com.garathon.flatdroid.ui.list.ListFilter
import com.garathon.flatdroid.ui.list.ListSort
import com.garathon.flatdroid.ui.list.ListSpec

/** A row of the apps and runtimes list. */
private sealed interface FlatpakEntry {
    val id: String
    val name: String
    val bytes: Long

    data class App(override val id: String, override val name: String, override val bytes: Long) : FlatpakEntry
    data class Runtime(override val id: String, override val bytes: Long, val unused: Boolean) : FlatpakEntry {
        override val name get() = id
    }
}

/** A row of the app homes and app data lists: an app, installed or with files left. */
private data class AppFilesEntry(val id: String, val name: String, val installed: Boolean, val bytes: Long)

private val flatpakSpec = ListSpec<FlatpakEntry>(
    searchText = { listOf(it.name, it.id) },
    filters = listOf(
        ListFilter("apps", R.string.storage_filter_apps, group = "kind") { it is FlatpakEntry.App },
        ListFilter("runtimes", R.string.storage_filter_runtimes, group = "kind") { it is FlatpakEntry.Runtime },
        // Only runtimes can be unused: an app doesn't match.
        ListFilter("unused", R.string.storage_filter_unused) { it is FlatpakEntry.Runtime && it.unused },
    ),
    sorts = listOf(
        ListSort("size", R.string.storage_sort_size, defaultDescending = true, comparator = compareBy { it.bytes }),
        ListSort("name", R.string.storage_sort_name, comparator = compareBy { it.name.lowercase() }),
    ),
    defaultSort = "size",
)

private val appFilesSpec = ListSpec<AppFilesEntry>(
    searchText = { listOf(it.name, it.id) },
    filters = listOf(
        ListFilter("notInstalled", R.string.storage_filter_not_installed) { !it.installed },
        ListFilter("hasFiles", R.string.storage_filter_has_files) { it.bytes > 0 },
    ),
    sorts = listOf(
        ListSort("size", R.string.storage_sort_size, defaultDescending = true, comparator = compareBy { it.bytes }),
        ListSort("name", R.string.storage_sort_name, comparator = compareBy { it.name.lowercase() }),
    ),
    defaultSort = "size",
)

/**
 * Flatdroid's storage: the total by part, the shared home, lists of what each app and runtime
 * takes (with each app's home and app data, which can be imported, exported and deleted one by
 * one), clean-up, backup of everything and resetting it. The lists' actions take effect at once.
 */
@Composable
fun GlobalStoragePage(s: GlobalPageState) = SettingsPageColumn(R.string.settings_page_storage) {
    val env = s.env
    val actions = env.actions
    val storage = env.storage
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    fun sizeOf(bytes: Long?) = bytes?.let(::size) ?: context.getString(R.string.app_settings_measuring)
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    var uninstall by rememberSaveable { mutableStateOf<String?>(null) }
    val names = remember(env.apps) { env.apps.associate { it.id to it.name } }
    val nameOf = { id: String -> names[id] ?: id }
    val canChange = env.canReset && !env.storageBusy

    confirm?.let { key ->
        StorageConfirmDialog(
            key, nameOf, storage?.unusedAppData, storage?.unusedHomes,
            confirmed = StorageConfirmed(
                onAppAction = actions.onAppStorage,
                onGlobal = { k ->
                    when (k) {
                        StorageConfirm.CLEAR_APP_DATA -> actions.onClearUnusedAppData()
                        StorageConfirm.CLEAR_HOMES -> actions.onClearUnusedHomes()
                        StorageConfirm.IMPORT_ALL_HOMES -> actions.onImportAllHomes()
                        StorageConfirm.IMPORT_ALL_APP_DATA -> actions.onImportAllAppFiles()
                        StorageConfirm.IMPORT_DOTFILES -> actions.onImportSharedDotfiles()
                        StorageConfirm.RESET_DOTFILES -> actions.onResetSharedDotfiles()
                        StorageConfirm.RESET_ALL_HOMES -> actions.onResetAllHomes()
                        StorageConfirm.RESET_ALL_APP_DATA -> actions.onResetAllAppData()
                    }
                },
            ),
            onDismiss = { confirm = null },
        )
    }
    uninstall?.let { id ->
        UninstallDialog(
            nameOf(id),
            onConfirm = { data, runtimes -> uninstall = null; actions.onUninstall(id, data, runtimes) },
            onDismiss = { uninstall = null },
        )
    }

    env.taskText?.let { text ->
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 3.dp)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }

    // The total by part.
    val colors = storageColors()
    Text(
        stringResource(R.string.settings_storage_total, sizeOf(storage?.total)),
        style = MaterialTheme.typography.titleMedium,
    )
    StorageBar(
        listOf(
            StorageSegment(R.string.storage_part_runtimes, storage?.runtimes, colors[0]),
            StorageSegment(R.string.storage_part_apps, storage?.apps, colors[1]),
            StorageSegment(R.string.storage_part_app_data, storage?.appData, colors[2]),
            StorageSegment(R.string.storage_part_homes, storage?.privateHomes, colors[3]),
            StorageSegment(R.string.storage_part_cache, storage?.cache, colors[4]),
            StorageSegment(R.string.storage_part_other, storage?.other, colors[5]),
        ),
    )

    // The shared home: where it is, what is visible in it, and how to add files.
    SectionTitle(stringResource(R.string.storage_section_shared_home))
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(env.sharedHome)
            Text(
                stringResource(if (env.allFilesAccess == true) R.string.storage_shared_all else R.string.storage_shared_only_own),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconAction(Icons.Filled.Edit, R.string.settings_change_shared_home, actions.onChangeSharedHome)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = actions.onAddFiles) { Text(stringResource(R.string.storage_add_files)) }
        OutlinedButton(onClick = actions.onAddZip) { Text(stringResource(R.string.storage_add_zip)) }
    }
    if (env.allFilesAccess != null) {
        SwitchRow(
            env.allFilesAccess, { actions.onAllFilesAccess() },
            stringResource(R.string.settings_all_files_access), stringResource(R.string.settings_all_files_access_hint),
        )
    }

    // What each app and runtime takes.
    SectionTitle(stringResource(R.string.storage_section_flatpaks))
    val entries = remember(env.apps, storage) {
        env.apps.map { FlatpakEntry.App(it.id, it.name, storage?.perApp?.get(it.id)?.app ?: 0) } +
            storage?.runtimeSizes.orEmpty().map { (id, bytes) -> FlatpakEntry.Runtime(id, bytes, id in storage!!.unusedRuntimes) }
    }
    if (storage?.runtimeSizes == null) {
        Text(stringResource(R.string.storage_measuring_runtimes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    StorageList(flatpakSpec, entries, key = { it.id }, emptyText = R.string.storage_list_empty) { entry ->
        when (entry) {
            is FlatpakEntry.App -> StorageRow(entry.name, size(entry.bytes), subtitle = entry.id) {
                IconAction(Icons.Filled.Delete, R.string.uninstall_button, { uninstall = entry.id }, enabled = env.canReset, danger = true)
            }
            is FlatpakEntry.Runtime -> StorageRow(
                entry.name, size(entry.bytes),
                subtitle = stringResource(if (entry.unused) R.string.storage_runtime_unused else R.string.storage_runtime),
                dimmed = entry.unused,
            )
        }
    }

    // Each app's home and app data: installed apps and those whose files are left.
    val files = remember(env.apps, storage) {
        val perApp = storage?.perApp.orEmpty()
        val installed = env.apps.map { it.id }.toSet()
        (installed + perApp.keys).map { id -> id to perApp[id] }
    }
    SectionTitle(stringResource(R.string.storage_section_homes))
    val homes = remember(files, names) { files.map { (id, sizes) -> AppFilesEntry(id, names[id] ?: id, id in names, sizes?.privateHome ?: 0) } }
    StorageList(appFilesSpec, homes, key = { it.id }, emptyText = R.string.storage_list_empty) { entry ->
        StorageRow(entry.name, size(entry.bytes), subtitle = if (entry.installed) entry.id else stringResource(R.string.storage_not_installed), dimmed = !entry.installed, actionsBelow = true) {
            IconAction(Icons.Filled.FolderOpen, R.string.storage_open_in_files, { actions.onOpenHome(entry.id) }, enabled = entry.bytes > 0)
            IconAction(Icons.Filled.UploadFile, R.string.storage_import, { confirm = StorageConfirm.app(entry.id, StorageAction.IMPORT_HOME) }, enabled = canChange)
            IconAction(Icons.Filled.SaveAlt, R.string.storage_export, { actions.onAppStorage(entry.id, StorageAction.EXPORT_HOME) }, enabled = entry.bytes > 0)
            IconAction(Icons.Filled.Delete, R.string.storage_delete, { confirm = StorageConfirm.app(entry.id, StorageAction.RESET_HOME) }, enabled = canChange && entry.bytes > 0, danger = true)
        }
    }
    SectionTitle(stringResource(R.string.storage_section_app_data))
    val data = remember(files, names) { files.map { (id, sizes) -> AppFilesEntry(id, names[id] ?: id, id in names, sizes?.settings ?: 0) } }
    StorageList(appFilesSpec, data, key = { it.id }, emptyText = R.string.storage_list_empty) { entry ->
        StorageRow(entry.name, size(entry.bytes), subtitle = if (entry.installed) entry.id else stringResource(R.string.storage_not_installed), dimmed = !entry.installed, actionsBelow = true) {
            IconAction(Icons.Filled.UploadFile, R.string.storage_import, { confirm = StorageConfirm.app(entry.id, StorageAction.IMPORT_SETTINGS) }, enabled = canChange)
            IconAction(Icons.Filled.SaveAlt, R.string.storage_export, { actions.onAppStorage(entry.id, StorageAction.EXPORT_SETTINGS) }, enabled = entry.bytes > 0)
            IconAction(Icons.Filled.Delete, R.string.storage_delete, { confirm = StorageConfirm.app(entry.id, StorageAction.RESET_SETTINGS) }, enabled = canChange && entry.bytes > 0, danger = true)
        }
    }

    // Clean up.
    SectionTitle(stringResource(R.string.storage_section_clean_up))
    var cachesCleared by rememberSaveable { mutableStateOf(false) }
    val cache = storage?.cache ?: 0
    BorderedRows {
        StorageRow(stringResource(R.string.settings_clear_all_caches), sizeOf(storage?.cache)) {
            IconAction(Icons.Filled.DeleteSweep, R.string.settings_clear_all_caches, { actions.onClearAllCaches(); cachesCleared = true }, enabled = env.canReset && !cachesCleared && cache > 0)
        }
        StorageRow(stringResource(R.string.settings_clear_unused_app_data), sizeOf(storage?.unusedAppData?.bytes)) {
            IconAction(Icons.Filled.DeleteSweep, R.string.settings_clear_unused_app_data, { confirm = StorageConfirm.CLEAR_APP_DATA }, enabled = canChange && (storage?.unusedAppData?.apps ?: 0) > 0)
        }
        StorageRow(stringResource(R.string.settings_clear_unused_homes), sizeOf(storage?.unusedHomes?.bytes)) {
            IconAction(Icons.Filled.DeleteSweep, R.string.settings_clear_unused_homes, { confirm = StorageConfirm.CLEAR_HOMES }, enabled = canChange && (storage?.unusedHomes?.apps ?: 0) > 0)
        }
    }

    // Backup of everything.
    SectionTitle(stringResource(R.string.storage_section_backup))
    val dotfiles = storage?.sharedDotfiles ?: 0L
    BorderedRows {
        StorageRow(stringResource(R.string.storage_part_homes), sizeOf(storage?.privateHomes)) {
            IconAction(Icons.Filled.UploadFile, R.string.storage_import, { confirm = StorageConfirm.IMPORT_ALL_HOMES }, enabled = canChange)
            IconAction(Icons.Filled.SaveAlt, R.string.storage_export, actions.onExportAllHomes, enabled = canChange)
        }
        StorageRow(stringResource(R.string.storage_part_app_data), sizeOf(storage?.appData)) {
            IconAction(Icons.Filled.UploadFile, R.string.storage_import, { confirm = StorageConfirm.IMPORT_ALL_APP_DATA }, enabled = canChange)
            IconAction(Icons.Filled.SaveAlt, R.string.storage_export, actions.onExportAllAppFiles, enabled = canChange)
        }
        // Used only by apps that opt in (a developer setting), so shown with developer options or once it holds something.
        if (s.settings.developerOptions || dotfiles > 0) {
            StorageRow(stringResource(R.string.settings_shared_dotfiles), size(dotfiles), actionsBelow = true) {
                IconAction(Icons.Filled.UploadFile, R.string.storage_import, { confirm = StorageConfirm.IMPORT_DOTFILES }, enabled = canChange)
                IconAction(Icons.Filled.SaveAlt, R.string.storage_export, actions.onExportSharedDotfiles, enabled = canChange && dotfiles > 0)
                IconAction(Icons.Filled.Delete, R.string.storage_delete, { confirm = StorageConfirm.RESET_DOTFILES }, enabled = canChange && dotfiles > 0, danger = true)
            }
        }
    }

    // Resetting everything.
    SectionTitle(stringResource(R.string.settings_section_danger), danger = true)
    OutlinedButton(onClick = { confirm = StorageConfirm.RESET_ALL_HOMES }, enabled = canChange) {
        Text(stringResource(R.string.storage_reset_all_homes), color = MaterialTheme.colorScheme.error)
    }
    OutlinedButton(onClick = { confirm = StorageConfirm.RESET_ALL_APP_DATA }, enabled = canChange) {
        Text(stringResource(R.string.storage_reset_all_app_data), color = MaterialTheme.colorScheme.error)
    }
}

/** Rows in one outlined box, as the lists are. */
@Composable
fun BorderedRows(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)).padding(vertical = 4.dp),
    ) { content() }
}
