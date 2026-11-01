package com.garathon.flatdroid

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.garathon.flatdroid.flatpak.AppData
import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.settings.AppSettingsStore
import com.garathon.flatdroid.settings.HomeMode
import com.garathon.flatdroid.settings.UserSettingsStore
import com.garathon.flatdroid.setup.SetupController
import com.garathon.flatdroid.storage.HomesProvider
import com.garathon.flatdroid.storage.ImportTarget
import com.garathon.flatdroid.storage.PendingTasks
import com.garathon.flatdroid.storage.SavedSizes
import com.garathon.flatdroid.storage.SettingsBackup
import com.garathon.flatdroid.storage.SharedFolderPath
import com.garathon.flatdroid.storage.SharedHome
import com.garathon.flatdroid.storage.StorageTask
import com.garathon.flatdroid.storage.StorageTaskCodec
import com.garathon.flatdroid.storage.StorageTaskService
import com.garathon.flatdroid.storage.StorageTasks
import com.garathon.flatdroid.storage.TempHomePrompt
import com.garathon.flatdroid.storage.TempHomes
import com.garathon.flatdroid.ui.ClashDialog
import com.garathon.flatdroid.ui.ConfirmDialog
import com.garathon.flatdroid.ui.MessageDialog
import com.garathon.flatdroid.ui.StorageAction
import com.garathon.flatdroid.ui.TempHomeDialog
import com.garathon.flatdroid.ui.UnfinishedDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Flatdroid's storage in its window: Android's pickers and the storage permission, the questions
 * and messages they lead to ([Dialogs]), the checks before a launch ([beforeLaunch]), and the
 * storage tasks they start. Made while [activity] is created, since it registers for activity
 * results; the view models are read only later, once the activity is attached. What a picker or
 * question is for survives Android rebuilding the activity (rotation, dark mode, memory) through
 * its saved state ([restore]); the steps that follow a permission request or a settings import
 * don't, and are then skipped.
 */
class StorageFlows(
    private val activity: ComponentActivity,
    private val homeModel: () -> HomeViewModel,
    private val mainModel: () -> MainViewModel,
) {
    private val home get() = homeModel()
    /** Refreshed on resume: the user may have changed it in Android's settings. */
    var broadAccess by mutableStateOf(false)
        private set
    var sharedHomeDisplay by mutableStateOf("")
        private set

    /** A message about storage, and what follows once it is closed. */
    private var message by mutableStateOf<Message?>(null)

    /** The app whose temporary home Flatdroid asks about ([TempHomePrompt.needsAsking]). */
    private var tempHomeQuestion by mutableStateOf<String?>(null)

    /** A zip for the shared home, waiting for keep-both, skip or replace. */
    private var clashQuestion by mutableStateOf<Uri?>(null)

    /** A storage task that didn't finish, asked about ([checkUnfinished]). */
    private var unfinished by mutableStateOf<PendingTasks.Record?>(null)

    /** An export of apps' files, waiting for the user to read what it may hold ([export]). */
    private var exportQuestion by mutableStateOf<StorageTask?>(null)

    /** The export whose file the "save as" picker is choosing. */
    private var exportTarget: StorageTask? = null

    private val pending by lazy { StorageTaskService.pendingTasks(activity) }

    /** Android 10 and older: what waits for the storage permission (it goes ahead either way). */
    private var afterStoragePermission: (() -> Unit)? = null

    /** Where the files being picked go. */
    private var importTarget: ImportTarget? = null

    /** What the zip being picked is for. */
    private var zipTarget: ZipTarget? = null

    /** What follows a successful import of Flatdroid's settings. */
    private var afterSettingsImport: (() -> Unit)? = null

    init {
        activity.savedStateRegistry.registerSavedStateProvider(SAVED_STATE) {
            Bundle().apply {
                importTarget?.let { putString(KEY_IMPORT, it.key) }
                zipTarget?.let { putString(KEY_ZIP, it.key) }
                clashQuestion?.let { putString(KEY_CLASH, it.toString()) }
                exportQuestion?.let { task -> StorageTaskCodec.encode(task).forEach { (k, v) -> putString(KEY_EXPORT_QUESTION + k, v) } }
                exportTarget?.let { task -> StorageTaskCodec.encode(task).forEach { (k, v) -> putString(KEY_EXPORT_TARGET + k, v) } }
            }
        }
    }

    /** In the activity's onCreate, after super.onCreate: what was pending when it was rebuilt. */
    fun restore() {
        val state = activity.savedStateRegistry.consumeRestoredStateForKey(SAVED_STATE) ?: return
        importTarget = state.getString(KEY_IMPORT)?.let(::importTargetOf)
        zipTarget = state.getString(KEY_ZIP)?.let(ZipTarget::of)
        clashQuestion = state.getString(KEY_CLASH)?.toUri()
        fun task(prefix: String) = StorageTaskCodec.decode(
            state.keySet().filter { it.startsWith(prefix) }.associate { it.removePrefix(prefix) to state.getString(it).orEmpty() }
        )
        exportQuestion = task(KEY_EXPORT_QUESTION)
        exportTarget = task(KEY_EXPORT_TARGET)
    }

    private val storagePermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterStoragePermission?.invoke()
        afterStoragePermission = null
    }

    private val importPicker = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = importTarget
        importTarget = null
        if (target != null && uris.isNotEmpty()) start(StorageTask.ImportFiles(target), uris)
    }

    /** Files picked from a private home (the picker opens on it) are saved in Download. */
    private val homeFilesPicker = activity.registerForActivityResult(OpenDocumentsAt()) { uris ->
        if (uris.isNotEmpty()) start(StorageTask.SaveToDownload, uris)
    }

    private val zipPicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = zipTarget
        zipTarget = null
        if (target != null && uri != null) zipPicked(target, uri)
    }

    private val folderPicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(::useSharedHomeFolder)
    }

    private val exportPicker = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val task = exportTarget
        exportTarget = null
        if (task != null && uri != null) {
            start(task, listOf(uri))
        } else if (task is StorageTask.ExportTempHome) {
            // No file picked: the temporary home isn't saved, so the question comes back before
            // the next launch can replace it.
            TempHomePrompt.askAgain(task.appId)
            checkTempHomes()
        }
    }

    /** The questions and messages, on every screen: they follow pickers and sessions, which can end anywhere. */
    @Composable
    fun Dialogs(nameOf: (String) -> String) {
        message?.let { shown ->
            MessageDialog(shown.title, shown.text) {
                message = null
                shown.then?.invoke()
            }
        }
        tempHomeQuestion?.let { appId ->
            TempHomeDialog(
                nameOf(appId),
                onExport = {
                    tempHomeQuestion = null
                    TempHomePrompt.answer(appId)
                    // Its question already says what the zip may hold.
                    exportConfirmed(StorageTask.ExportTempHome(appId))
                    checkTempHomes()
                },
                onDelete = {
                    tempHomeQuestion = null
                    TempHomePrompt.answer(appId)
                    activity.lifecycleScope.launch(Dispatchers.IO) { if (TempHomes.owner(activity) == appId) TempHomes.delete(activity) }
                    checkTempHomes()
                },
            )
        }
        exportQuestion?.let { task ->
            ConfirmDialog(
                activity.getString(R.string.storage_export_warning_title),
                activity.getString(if (exportWithPicker()) R.string.storage_export_warning_picker else R.string.storage_export_warning_download),
                activity.getString(R.string.storage_export),
                onConfirm = { exportQuestion = null; exportConfirmed(task) },
                onDismiss = { exportQuestion = null },
            )
        }
        clashQuestion?.let { uri ->
            ClashDialog(
                onChoice = { clash -> clashQuestion = null; start(StorageTask.ImportSharedZip(clash), listOf(uri)) },
                onDismiss = { clashQuestion = null },
            )
        }
        unfinished?.let { record ->
            val goesOn = record.task is StorageTask.ImportFiles || record.task == StorageTask.SaveToDownload || record.task is StorageTask.ImportSharedZip
            UnfinishedDialog(
                title = activity.getString(R.string.storage_unfinished_title, StorageTaskService.describe(activity, record.task)),
                text = activity.getString(
                    when {
                        !record.resumable -> R.string.storage_unfinished_lost
                        goesOn -> R.string.storage_unfinished_text
                        else -> R.string.storage_unfinished_restart
                    },
                ),
                canContinue = record.resumable,
                onContinue = {
                    unfinished = null
                    StorageTaskService.resume(activity, record)
                },
                onCancel = {
                    unfinished = null
                    activity.lifecycleScope.launch(Dispatchers.IO) {
                        pending.remove(record.id)
                        StorageTaskService.releaseAccess(activity, record.uris, pending.all())
                    }
                    checkUnfinished()
                },
            )
        }
    }

    /**
     * Asks about a storage task that stopped before it finished (Flatdroid was stopped or killed),
     * and gives back access to picked files that no unfinished task needs any more. Not while
     * storage tasks run, since theirs are recorded too.
     */
    fun checkUnfinished() {
        if (unfinished != null) return
        activity.lifecycleScope.launch {
            val first = withContext(Dispatchers.IO) {
                if (StorageTasks.ui.value.let { it.running != null || it.queued.isNotEmpty() }) return@withContext null
                val records = pending.all()
                StorageTaskService.releaseAccess(activity, null, records)
                records.firstOrNull()
            }
            if (unfinished == null && StorageTasks.ui.value.running == null) unfinished = first
        }
    }

    /**
     * Starts [task] on [uris] picked by the user, keeping read access to them (and write access,
     * where given, for files adopted in the shared home) until the task ends, so it can continue
     * after a stop; where Android won't keep it, the task still runs but can't continue.
     */
    private fun start(task: StorageTask, uris: List<Uri>) {
        val kept = uris.all { uri ->
            val resolver = activity.contentResolver
            val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
            runCatching { resolver.takePersistableUriPermission(uri, read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }.isSuccess ||
                runCatching { resolver.takePersistableUriPermission(uri, read) }.isSuccess
        }
        StorageTaskService.start(activity, task, uris, resumable = kept)
    }

    fun refresh() {
        broadAccess = SharedHome.broadAccess(activity)
        sharedHomeDisplay = SharedHome.display(SharedHome.dir(activity))
    }

    /** Asks about the temporary home left by the last session, if there is one to ask about. */
    fun checkTempHomes() {
        if (tempHomeQuestion != null) return
        activity.lifecycleScope.launch {
            val next = withContext(Dispatchers.IO) { TempHomePrompt.needsAsking(activity) }
            if (tempHomeQuestion == null) tempHomeQuestion = next
        }
    }

    /**
     * The storage checks before [appId] launches, then [onReady]: the temporary home this launch
     * would replace is asked about first; not while the app's files, or the temporary home, are
     * being copied; the storage permission (Android 10 and older) for an app using shared
     * storage; the warning about files hidden in the shared home.
     */
    fun beforeLaunch(appId: String, onReady: () -> Unit, storageAsked: Boolean = false) {
        // An unfinished task is settled first: it may be the temporary home's export.
        if (unfinished != null) return
        val settings = AppData.settings(activity, appId).load()
        val temporary = settings.homeMode == HomeMode.TEMPORARY
        val leftOver = TempHomePrompt.needsAsking(activity)
        if (leftOver != null && (leftOver == appId || temporary)) {
            tempHomeQuestion = leftOver
            return
        }
        if (StorageTasks.busyWith(appId) || (temporary && StorageTasks.exportingTempHome(pending))) {
            Toast.makeText(activity, R.string.launch_storage_busy, Toast.LENGTH_LONG).show()
            return
        }
        val shared = settings.homeMode == HomeMode.SHARED
        if ((shared || settings.sdcard) && !storageAsked && SharedHome.needsLegacyPermission(activity)) {
            afterStoragePermission = { beforeLaunch(appId, onReady, storageAsked = true) }
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        if (shared && SharedHome.needsWarning(activity)) {
            message = hiddenFilesWarning(SharedHome.dir(activity)) { beforeLaunch(appId, onReady, storageAsked) }
            return
        }
        onReady()
    }

    fun importFiles(target: ImportTarget) = withStorage(target == ImportTarget.Shared) {
        importTarget = target
        importPicker.launch(arrayOf("*/*"))
    }

    fun importZipIntoSharedHome() = pickZip(ZipTarget.SharedHome)

    /** An action from an app's Storage section; the ones that replace or delete files were confirmed. */
    fun storageAction(appId: String, action: StorageAction) {
        when (action) {
            StorageAction.CLEAR_CACHE -> home.clearCache(appId)
            StorageAction.COPY_INTO_HOME -> importFiles(ImportTarget.Private(appId))
            StorageAction.DOWNLOAD_FROM_HOME -> withStorage(LEGACY_DOWNLOAD) {
                homeFilesPicker.launch(DocumentsContract.buildDocumentUri(HomesProvider.AUTHORITY, appId))
            }
            StorageAction.IMPORT_HOME -> pickZip(ZipTarget.Home(appId))
            StorageAction.EXPORT_HOME -> export(StorageTask.ExportHome(appId))
            StorageAction.RESET_HOME -> home.resetPrivateHome(appId)
            StorageAction.IMPORT_SETTINGS -> pickZip(ZipTarget.AppFiles(appId))
            StorageAction.EXPORT_SETTINGS -> export(StorageTask.ExportAppFiles(appId))
            StorageAction.RESET_SETTINGS -> home.resetAppFiles(appId)
        }
    }

    fun exportFlatdroidSettings() = export(StorageTask.ExportFlatdroidSettings)

    fun exportAllHomes() = export(StorageTask.ExportAllHomes)

    fun exportAllAppFiles() = export(StorageTask.ExportAllAppFiles)

    fun exportSharedDotfiles() = export(StorageTask.ExportSharedDotfiles)

    /** Confirmed: replaces the shared hidden files with the picked zip's. */
    fun importSharedDotfiles() = pickZip(ZipTarget.SharedDotfiles)

    /** Confirmed: replaces the private homes of the apps in the picked zip. */
    fun importAllHomes() = pickZip(ZipTarget.AllHomes)

    /** Confirmed: replaces the settings and other files of the apps in the picked zip. */
    fun importAllAppFiles() = pickZip(ZipTarget.AllAppFiles)

    /** Picks a settings zip and applies it as Save does; [then] once applied. */
    fun importFlatdroidSettings(then: () -> Unit) {
        afterSettingsImport = then
        pickZip(ZipTarget.FlatdroidSettings)
    }

    fun changeSharedHome() = withStorage(true) { folderPicker.launch(null) }

    /** Android's All files access page for Flatdroid (Android 11 and newer). */
    fun openAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${activity.packageName}".toUri()))
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    /**
     * An export. Zips of apps' files first say what they may hold (logins, cookies, keys); then
     * it goes to Download, or with the security setting wherever the user picks in Android's
     * "save as" picker.
     */
    private fun export(task: StorageTask) {
        if (task == StorageTask.ExportFlatdroidSettings) exportConfirmed(task) else exportQuestion = task
    }

    /** Download by path needs the storage permission before Android 10; a picked file doesn't. */
    private fun exportConfirmed(task: StorageTask) {
        if (exportWithPicker()) {
            exportTarget = task
            exportPicker.launch(StorageTaskService.exportName(activity, task))
        } else {
            withStorage(LEGACY_DOWNLOAD) { StorageTaskService.start(activity, task) }
        }
    }

    private fun exportWithPicker() = SetupController.settings(activity).load()?.security?.exportWithPicker == true

    /** Runs [then], first asking for the storage permission on Android 10 and older when [needed]. */
    private fun withStorage(needed: Boolean, then: () -> Unit) {
        if (needed && SharedHome.needsLegacyPermission(activity)) {
            afterStoragePermission = then
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            then()
        }
    }

    // Only a zip for the shared home writes there; the others are read through the picker.
    private fun pickZip(target: ZipTarget) = withStorage(target == ZipTarget.SharedHome) {
        zipTarget = target
        zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
    }

    private fun zipPicked(target: ZipTarget, uri: Uri) {
        when (target) {
            is ZipTarget.Home -> start(StorageTask.ImportHome(target.appId), listOf(uri))
            is ZipTarget.AppFiles -> start(StorageTask.ImportAppFiles(target.appId), listOf(uri))
            ZipTarget.SharedHome -> clashQuestion = uri
            ZipTarget.FlatdroidSettings -> applyFlatdroidSettings(uri)
            ZipTarget.AllHomes -> start(StorageTask.ImportAllHomes, listOf(uri))
            ZipTarget.AllAppFiles -> start(StorageTask.ImportAllAppFiles, listOf(uri))
            ZipTarget.SharedDotfiles -> start(StorageTask.ImportSharedDotfiles, listOf(uri))
        }
    }

    /**
     * Replaces Flatdroid's settings and every app's launcher settings with a zip's, then applies
     * them as Save does (running their commands in Alpine). A zip without valid settings changes
     * nothing.
     */
    private fun applyFlatdroidSettings(uri: Uri) {
        val then = afterSettingsImport
        afterSettingsImport = null
        activity.lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) {
                try {
                    val files = (activity.contentResolver.openInputStream(uri) ?: throw IOException(activity.getString(R.string.storage_unreadable)))
                        .use(SettingsBackup::read)
                    val check = File(activity.cacheDir, "import-settings.properties").apply { writeBytes(files.getValue(SettingsBackup.SETTINGS)) }
                    val settings = UserSettingsStore(check).load().also { check.delete() }
                        ?: throw IOException(activity.getString(R.string.storage_settings_invalid))
                    val appDir = AppData.settingsDir(activity)
                    val local = HashMap<String, ByteArray>()
                    // They count in each app's settings size.
                    appDir.listFiles().orEmpty().forEach { file ->
                        SettingsBackup.appId(file.name)?.let {
                            SavedSizes.forget(activity, it)
                            runCatching { file.readBytes() }.onSuccess { bytes -> local[file.name] = bytes }
                            file.delete()
                            SavedSizes.changed(activity, it)
                        }
                    }
                    for ((name, bytes) in files) {
                        if (name.startsWith(SettingsBackup.APP_SETTINGS)) {
                            val fileName = name.removePrefix(SettingsBackup.APP_SETTINGS)
                            File(appDir, fileName).apply { parentFile?.mkdirs() }.writeBytes(AppSettingsStore.imported(bytes, local[fileName]))
                            SettingsBackup.appId(fileName)?.let { SavedSizes.changed(activity, it) }
                        }
                    }
                    // The viewer stays this device's choice: it gets every session's VNC password. So do
                    // the security level and the terminal's filter, which decide how far apps reach.
                    val device = SetupController.settings(activity).load()
                    settings.copy(
                        vncViewer = device?.vncViewer,
                        security = device?.security ?: settings.security,
                        terminalFilter = device?.terminalFilter ?: settings.terminalFilter,
                    )
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, activity.getString(R.string.storage_failed, e.message ?: e.toString()), Toast.LENGTH_LONG).show()
                    }
                    null
                }
            }
            if (imported != null) mainModel().applySettings(imported) { refresh(); then?.invoke() }
        }
    }

    /** The folder picked in "Change shared home": used from the next launch, if Flatdroid can use it. */
    private fun useSharedHomeFolder(uri: Uri) {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return
        when (val result = SharedFolderPath.of(uri.authority, documentId, SharedHome.storageRoot().path, SharedHome.broadAccess(activity))) {
            is SharedFolderPath.Result.Ok -> {
                val dir = File(result.path)
                val store = SetupController.settings(activity)
                store.load()?.let { store.save(it.copy(sharedHome = result.path.takeIf { path -> path != SharedHome.defaultDir().path })) }
                refresh()
                if (SharedHome.needsWarning(activity, dir)) message = hiddenFilesWarning(dir)
            }
            SharedFolderPath.Result.NotAllowed ->
                message = Message(activity.getString(R.string.shared_home_not_allowed_title), activity.getString(R.string.shared_home_not_allowed))
            SharedFolderPath.Result.Unknown ->
                message = Message(activity.getString(R.string.shared_home_not_allowed_title), activity.getString(R.string.shared_home_unknown))
        }
    }

    /**
     * The shared home may hold files Flatdroid can't see. Says where to change that, without
     * doing it here; shown once per folder.
     */
    private fun hiddenFilesWarning(dir: File, then: (() -> Unit)? = null): Message {
        val name = SharedHome.display(dir)
        return Message(activity.getString(R.string.shared_home_hidden_title, name), activity.getString(R.string.shared_home_hidden, name)) {
            SharedHome.markWarned(activity, dir)
            then?.invoke()
        }
    }

    /** A message whose OK runs [then]. */
    private class Message(val title: String, val text: String, val then: (() -> Unit)? = null)

    /** What a picked zip is for; [key] keeps it in saved state. */
    private sealed interface ZipTarget {
        val key: String

        data class Home(val appId: String) : ZipTarget {
            override val key get() = "home:$appId"
        }

        data class AppFiles(val appId: String) : ZipTarget {
            override val key get() = "settings:$appId"
        }

        data object SharedHome : ZipTarget {
            override val key = "shared"
        }

        data object FlatdroidSettings : ZipTarget {
            override val key = "flatdroid"
        }

        data object AllHomes : ZipTarget {
            override val key = "allHomes"
        }

        data object AllAppFiles : ZipTarget {
            override val key = "allSettings"
        }

        data object SharedDotfiles : ZipTarget {
            override val key = "sharedDotfiles"
        }

        companion object {
            fun of(key: String): ZipTarget? {
                val appId = key.substringAfter(':', "").takeIf(InstalledApps::isValidId)
                return when (key.substringBefore(':')) {
                    "home" -> appId?.let(::Home)
                    "settings" -> appId?.let(::AppFiles)
                    SharedHome.key -> SharedHome
                    FlatdroidSettings.key -> FlatdroidSettings
                    AllHomes.key -> AllHomes
                    AllAppFiles.key -> AllAppFiles
                    SharedDotfiles.key -> SharedDotfiles
                    else -> null
                }
            }
        }
    }

    private val ImportTarget.key: String
        get() = when (this) {
            ImportTarget.Shared -> "shared"
            is ImportTarget.Private -> "private:$appId"
        }

    private fun importTargetOf(key: String): ImportTarget? = when {
        key == "shared" -> ImportTarget.Shared
        key.startsWith("private:") -> key.removePrefix("private:").takeIf(InstalledApps::isValidId)?.let(ImportTarget::Private)
        else -> null
    }

    /** Android's picker for several files, opening on [Uri] where Android allows it (8.0 and newer). */
    private class OpenDocumentsAt : ActivityResultContract<Uri?, List<Uri>>() {
        override fun createIntent(context: Context, input: Uri?): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .apply { if (input != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) putExtra(DocumentsContract.EXTRA_INITIAL_URI, input) }

        override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
            if (resultCode != android.app.Activity.RESULT_OK || intent == null) return emptyList()
            val clip = intent.clipData
            return if (clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } else listOfNotNull(intent.data)
        }
    }

    private companion object {
        /** Download by path, before Android 10, needs the storage permission. */
        val LEGACY_DOWNLOAD = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        const val KEY_EXPORT_QUESTION = "exportQuestion."
        const val KEY_EXPORT_TARGET = "exportTarget."
        const val SAVED_STATE = "storage"
        const val KEY_IMPORT = "import"
        const val KEY_ZIP = "zip"
        const val KEY_CLASH = "clash"
    }
}
