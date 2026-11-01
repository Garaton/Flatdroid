package com.garathon.flatdroid

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.flatpak.AppData
import com.garathon.flatdroid.flatpak.FlatpakInstallation
import com.garathon.flatdroid.flatpak.FlatpakTasks
import com.garathon.flatdroid.flatpak.InstalledApp
import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.session.SessionController
import com.garathon.flatdroid.session.SessionState
import com.garathon.flatdroid.setup.SetupController
import com.garathon.flatdroid.setup.AndroidTarFs
import com.garathon.flatdroid.setup.deleteTree
import com.garathon.flatdroid.storage.AppFiles
import com.garathon.flatdroid.storage.AppStorage
import com.garathon.flatdroid.FlatdroidStorage.Part
import com.garathon.flatdroid.storage.SavedSizes
import com.garathon.flatdroid.storage.StorageSizes
import com.garathon.flatdroid.storage.StorageTasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Installed apps for the home list; reloaded after Flatpak tasks, sessions and setup. */
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val _apps = MutableStateFlow<List<InstalledApp>?>(null)
    /** Null until first loaded. */
    val apps: StateFlow<List<InstalledApp>?> = _apps.asStateFlow()

    init {
        viewModelScope.launch {
            merge(
                FlatpakTasks.ui.distinctUntilChangedBy { it.generation }.map { },
                // A launch records the last-opened time.
                SessionController.ui.distinctUntilChangedBy { it.state is SessionState.Running }.map { },
                // Setup after a reset brings kept Flatpaks back while the screen stays open.
                SetupController.state.distinctUntilChangedBy { it.isComplete && !it.running }.map { },
            ).collect { reload() }
        }
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            _apps.value = try {
                InstalledApps(getApplication()).list()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private val _storage = MutableStateFlow<Map<String, AppStorage>>(emptyMap())
    /** Measured storage by app ID; an app is missing until [measureStorage] has run for it. */
    val storage: StateFlow<Map<String, AppStorage>> = _storage.asStateFlow()

    private val _flatdroidStorage = MutableStateFlow<FlatdroidStorage?>(null)
    val flatdroidStorage: StateFlow<FlatdroidStorage?> = _flatdroidStorage.asStateFlow()

    fun measureStorage(appId: String) {
        // The last sizes stay shown, marked as measuring, until the new ones are in.
        _storage.update { m -> m[appId]?.let { m + (appId to it.copy(measuring = true)) } ?: m }
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            // Not while a Flatpak task or setup changes everything; measured again once it ends.
            val saved = SavedSizes.app(context, appId) ?: return@launch
            val measured = AppStorage(
                app = saved.app,
                cache = StorageSizes.contents(File(AppData.cacheRoot(context), appId)),
                privateHome = saved.privateHome,
                settings = saved.settings,
            )
            _storage.update { it + (appId to measured) }
        }
    }

    /**
     * Flatdroid's storage, part by part: what was measured before shows at once, marked as being
     * measured again, and each part replaces it as soon as it is in (the shared hidden files, then
     * the apps, the cache, and last the runtimes and the rest, the slowest).
     */
    fun measureFlatdroid() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val installed = installedIds()
            val ids = SavedSizes.appIds(context)
            _flatdroidStorage.update { (it ?: lastKnownStorage(context, ids, installed)).copy(stale = Part.entries.toSet()) }
            fun done(part: Part, change: (FlatdroidStorage) -> FlatdroidStorage) =
                _flatdroidStorage.update { it?.let { s -> change(s).copy(stale = s.stale - part) } }
            done(Part.SHARED_DOTFILES) { it.copy(sharedDotfiles = StorageSizes.of(AppData.sharedDotfilesDir(context))) }
            // Not while a Flatpak task changes everything: the parts left keep their earlier sizes, marked.
            val apps = ids.associateWith { SavedSizes.app(context, it) ?: return@launch }
            done(Part.APPS) { it.withApps(apps, installed) }
            done(Part.CACHE) { it.copy(cache = StorageSizes.of(context.cacheDir)) }
            val shared = SavedSizes.shared(context) ?: return@launch
            done(Part.SHARED) { it.copy(runtimes = shared.runtimes, other = shared.other) }
        }
    }

    /** The parts saved before (perhaps from before a change), for showing until measured again. */
    private fun lastKnownStorage(context: Application, ids: Set<String>, installed: Set<String>): FlatdroidStorage {
        var storage = FlatdroidStorage()
        val apps = ids.associateWith { SavedSizes.lastApp(context, it) }
        if (apps.values.none { it == null }) storage = storage.withApps(apps.mapValues { it.value!! }, installed)
        SavedSizes.lastShared(context)?.let { storage = storage.copy(runtimes = it.runtimes, other = it.other) }
        return storage
    }


    /** Deletes the app's cache; not while it runs. */
    fun clearCache(appId: String) = whileIdle(appId) {
        AndroidTarFs.deleteTree(File(AppData.cacheRoot(getApplication()), appId))
    }

    /** The hidden files apps share when they opt in; not while an app runs or a storage task uses files. */
    fun resetSharedDotfiles() {
        if (SessionController.isBusy || StorageTasks.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            AndroidTarFs.deleteTree(AppData.sharedDotfilesDir(getApplication()))
            measureFlatdroid()
        }
    }

    /** Every app's cache; not while one runs. */
    fun clearAllCaches() {
        if (SessionController.isBusy) return
        viewModelScope.launch(Dispatchers.IO) {
            AndroidTarFs.deleteTree(AppData.cacheRoot(getApplication()))
            _storage.update { m -> m.mapValues { it.value.copy(measuring = true) } }
            measureFlatdroid()
        }
    }

    /** Deletes the settings and other files ([AppFiles]) of apps that aren't installed; not while anything runs. */
    fun clearUnusedAppData() = whileAllIdle { context, appId ->
        AppFiles.dirs(File(AppData.root(context), appId), appId).forEach { AndroidTarFs.deleteTree(it) }
        AppData.settingsFile(context, appId).delete()
    }

    /** Deletes the private homes of apps that aren't installed; not while anything runs. */
    fun clearUnusedHomes() = whileAllIdle { context, appId -> AndroidTarFs.deleteTree(AppData.privateHome(context, appId)) }

    /** Deletes the app's private home; the next launch or import in Private Home mode makes a new one, with the standard folders. */
    fun resetPrivateHome(appId: String) = whileIdle(appId) {
        AndroidTarFs.deleteTree(AppData.privateHome(getApplication(), appId))
    }

    /** Deletes the app's settings and other files of its own ([AppFiles]). */
    fun resetAppFiles(appId: String) = whileIdle(appId) {
        AppFiles.dirs(File(AppData.root(getApplication()), appId), appId).forEach { AndroidTarFs.deleteTree(it) }
    }

    /** Runs [change] on the app's files unless it runs or a storage task uses them, then measures again. */
    private fun whileIdle(appId: String, change: () -> Unit) {
        if (SessionController.ui.value.state.appIdOrNull == appId || StorageTasks.busyWith(appId)) return
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            SavedSizes.forget(context, appId)
            try {
                change()
            } finally {
                SavedSizes.changed(context, appId)
            }
            measureStorage(appId)
        }
    }

    /**
     * Runs [change] on each app that isn't installed but has files here, unless an app, a Flatpak
     * task or a storage task runs, then measures again.
     */
    private fun whileAllIdle(change: (Application, String) -> Unit) {
        if (SessionController.isBusy || FlatpakTasks.ui.value.running != null || StorageTasks.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            for (appId in SavedSizes.appIds(context) - installedIds()) {
                SavedSizes.forget(context, appId)
                try {
                    change(context, appId)
                    // Nothing left of it: its folder goes too.
                    File(AppData.root(context), appId).delete()
                } finally {
                    // Measured again only if something is left (the other of its home and settings).
                    if (appId in SavedSizes.appIds(context)) SavedSizes.changed(context, appId) else SavedSizes.forget(context, appId)
                }
            }
            measureFlatdroid()
        }
    }

    private fun installedIds(): Set<String> = AlpineEnvironment.get(getApplication()).let { FlatpakInstallation(it.flatpakDir, it.arch).appIds().toSet() }
}

/** Flatdroid's storage, in bytes, by part. */
data class FlatdroidStorage(
    val runtimes: Long? = null,
    /** Installed apps' own Flatpak files. */
    val apps: Long? = null,
    /** Apps' settings and other files of their own, installed or not. */
    val appData: Long? = null,
    val privateHomes: Long? = null,
    val cache: Long? = null,
    /** Alpine and Flatdroid's own files: counted in [total], not shown on its own. */
    val other: Long? = null,
    /** Of [appData], what apps that aren't installed have. */
    val unusedAppData: Unused? = null,
    /** Of [privateHomes], what apps that aren't installed have. */
    val unusedHomes: Unused? = null,
    /** The hidden files apps share when they opt in (developer setting); counted in [total]. */
    val sharedDotfiles: Long? = null,
    /** Parts shown from an earlier measurement while they are measured again. A null size isn't known yet. */
    val stale: Set<Part> = emptySet(),
) {
    /** Which sizes are measured together: [APPS] is [apps], [appData], [privateHomes] and the unused ones; [SHARED] is [runtimes] and [other]. */
    enum class Part { APPS, SHARED, CACHE, SHARED_DOTFILES }

    /** Null until every part is known. */
    val total: Long? get() = listOf(runtimes, apps, appData, privateHomes, cache, other, sharedDotfiles)
        .takeIf { parts -> parts.all { it != null } }?.sumOf { it!! }

    /** The apps' part from each app's saved sizes; [installed] tells the unused ones. */
    fun withApps(apps: Map<String, SavedSizes.App>, installed: Set<String>): FlatdroidStorage {
        val unused = apps.filterKeys { it !in installed }.values
        return copy(
            apps = apps.values.sumOf { it.app },
            appData = apps.values.sumOf { it.settings },
            privateHomes = apps.values.sumOf { it.privateHome },
            unusedAppData = Unused(unused.count { it.settings > 0 }, unused.sumOf { it.settings }),
            unusedHomes = Unused(unused.count { it.privateHome > 0 }, unused.sumOf { it.privateHome }),
        )
    }
}

/** Files of [apps] apps that aren't installed, [bytes] in all. */
data class Unused(val apps: Int, val bytes: Long)
