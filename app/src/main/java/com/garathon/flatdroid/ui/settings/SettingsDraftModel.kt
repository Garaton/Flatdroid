package com.garathon.flatdroid.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.garathon.flatdroid.settings.AppSettings
import com.garathon.flatdroid.settings.AppSettingsDraft
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.settings.UserSettingsDraft

/**
 * The settings being edited, kept while the user moves between pages (and through rotation).
 * Dropped when the user leaves the settings screens ([clear]); nothing here is saved.
 */
class SettingsDraftModel : ViewModel() {
    /** A draft and the settings it started from, to tell whether anything changed. */
    class Draft<T>(val saved: T) {
        var current by mutableStateOf(saved)
        val changed: Boolean get() = current != saved
    }

    class AppEntry(val appId: String, val draft: Draft<AppSettingsDraft>)

    var global by mutableStateOf<Draft<UserSettingsDraft>?>(null)
        private set
    var app by mutableStateOf<AppEntry?>(null)
        private set

    fun openGlobal(saved: UserSettings) {
        if (global == null) global = Draft(UserSettingsDraft.of(saved))
    }

    fun openApp(appId: String, saved: AppSettings) {
        if (app?.appId != appId) app = AppEntry(appId, Draft(AppSettingsDraft.of(saved)))
    }

    fun clear() {
        global = null
        app = null
    }
}
