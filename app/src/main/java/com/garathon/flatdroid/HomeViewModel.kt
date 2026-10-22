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
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val saved = SavedSizes.app(context, appId)
            val measured = AppStorage(
                app = saved.app,
                cache = StorageSizes.contents(File(AppData.cacheRoot(context), appId)),
                privateHome = saved.privateHome,
                settings = saved.settings,
            )
            _storage.update { it + (appId to measured) }
        }
    }

    fun measureFlatdroid() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val installed = installedIds()
            val apps = SavedSizes.appIds(context).associateWith { SavedSizes.app(context, it) }
            val unused = apps.filterKeys { it !in installed }.values
            val shared = SavedSizes.shared(context)
            _flatdroidStorage.value = FlatdroidStorage(
                runtimes = shared.runtimes,
                apps = apps.values.sumOf { it.app },
                appData = apps.values.sumOf { it.settings },
                privateHomes = apps.values.sumOf { it.privateHome },
                cache = StorageSizes.of(context.cacheDir),
                other = shared.other,
                unusedAppData = Unused(unused.count { it.settings > 0 }, unused.sumOf { it.settings }),
                unusedHomes = Unused(unused.count { it.privateHome > 0 }, unused.sumOf { it.privateHome }),
            )
        }
    }

    /** Deletes the app's cache; not while it runs. */
    fun clearCache(appId: String) = whileIdle(appId) {
        AndroidTarFs.deleteTree(File(AppData.cacheRoot(getApplication()), appId))
    }

    /** Every app's cache; not while one runs. */
    fun clearAllCaches() {
        if (SessionController.isBusy) return
        viewModelScope.launch(Dispatchers.IO) {
            AndroidTarFs.deleteTree(AppData.cacheRoot(getApplication()))
            _storage.value = emptyMap()
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
    val runtimes: Long,
    /** Installed apps' own Flatpak files. */
    val apps: Long,
    /** Apps' settings and other files of their own, installed or not. */
    val appData: Long,
    val privateHomes: Long,
    val cache: Long,
    /** Alpine and Flatdroid's own files: counted in [total], not shown on its own. */
    val other: Long,
    /** Of [appData], what apps that aren't installed have. */
    val unusedAppData: Unused,
    /** Of [privateHomes], what apps that aren't installed have. */
    val unusedHomes: Unused,
) {
    val total: Long get() = runtimes + apps + appData + privateHomes + cache + other
}

/** Files of [apps] apps that aren't installed, [bytes] in all. */
data class Unused(val apps: Int, val bytes: Long)
