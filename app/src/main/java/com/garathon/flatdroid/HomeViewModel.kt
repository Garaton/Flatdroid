package com.garathon.flatdroid

import android.app.Application
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.garathon.flatdroid.flatpak.AppData
import com.garathon.flatdroid.flatpak.FlatpakTasks
import com.garathon.flatdroid.flatpak.InstalledApp
import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.session.SessionController
import com.garathon.flatdroid.session.SessionState
import com.garathon.flatdroid.setup.AndroidTarFs
import com.garathon.flatdroid.setup.deleteTree
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

/** Installed apps for the home list; reloaded after Flatpak tasks and sessions. */
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

    private val _cacheBytes = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Measured cache sizes by app ID; an app is missing until [measureCache] has run for it. */
    val cacheBytes: StateFlow<Map<String, Long>> = _cacheBytes.asStateFlow()

    fun measureCache(appId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = sizeOf(File(AppData.cacheRoot(getApplication()), appId))
            _cacheBytes.update { it + (appId to bytes) }
        }
    }

    /** Deletes the app's cache; not while it runs. */
    fun clearCache(appId: String) {
        if (SessionController.ui.value.state.appIdOrNull == appId) return
        viewModelScope.launch(Dispatchers.IO) {
            AndroidTarFs.deleteTree(File(AppData.cacheRoot(getApplication()), appId))
            _cacheBytes.update { it + (appId to 0L) }
        }
    }

    /**
     * Disk space used under [file], as `du` counts it: allocated blocks of every entry, directories
     * included (an empty cache still takes a few KiB). Links aren't followed, so the content of
     * links into .l2s isn't counted.
     */
    private fun sizeOf(file: File): Long {
        val stat = try {
            Os.lstat(file.path)
        } catch (_: ErrnoException) {
            return 0L
        }
        val own = stat.st_blocks * 512
        return if (OsConstants.S_ISDIR(stat.st_mode)) own + (file.listFiles()?.sumOf(::sizeOf) ?: 0L) else own
    }
}
