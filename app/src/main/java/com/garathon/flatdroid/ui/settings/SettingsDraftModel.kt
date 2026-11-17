package com.garathon.flatdroid.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.garathon.flatdroid.settings.AppSettings
import com.garathon.flatdroid.settings.AppSettingsDraft
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.settings.UserSettingsDraft

/** The permission Android was asked for from an app's Permissions page. */
enum class PermissionAsk { MICROPHONE, CAMERA }

/** Android's answer to a [PermissionAsk]; the page that asked applies it, also after a rotation. */
class PermissionAnswer(val ask: PermissionAsk, val granted: Boolean)

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

    /** What Android's permission dialog in flight was asked for (it outlives the window a rotation rebuilds). */
    var asking: PermissionAsk? = null

    /** The answer that came and wasn't applied yet. */
    var permissionAnswer by mutableStateOf<PermissionAnswer?>(null)

    fun openGlobal(saved: UserSettings) {
        if (global == null) global = Draft(UserSettingsDraft.of(saved))
    }

    fun openApp(appId: String, saved: AppSettings) {
        if (app?.appId != appId) app = AppEntry(appId, Draft(AppSettingsDraft.of(saved)))
    }

    /** Also forgets a permission question still open: its answer, when it comes, is for a page that is gone. */
    fun clear() {
        global = null
        app = null
        asking = null
        permissionAnswer = null
    }
}
