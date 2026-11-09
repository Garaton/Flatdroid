package com.garathon.flatdroid.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PermCameraMic
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.ui.graphics.vector.ImageVector
import com.garathon.flatdroid.R

// The pages of both settings screens. The order of the entries is the order of the rail, top to
// bottom; each has its icon (the rail shows icons only) and its title (shown in the page).

/** The pages of Flatdroid's settings, in the rail's order. */
enum class GlobalPage(val key: String, override val icon: ImageVector, override val titleRes: Int) : SettingsPage {
    OVERVIEW("overview", Icons.Filled.Dashboard, R.string.settings_page_overview),
    PER_APP_DEFAULTS("defaults", Icons.Filled.Apps, R.string.settings_page_defaults),
    DISPLAY("display", Icons.Filled.DisplaySettings, R.string.settings_page_display),
    DEVICES("devices", Icons.Filled.PermCameraMic, R.string.settings_page_devices),
    SECURITY("security", Icons.Filled.Shield, R.string.settings_page_security),
    FLATPAK("flatpak", Icons.Filled.Inventory2, R.string.settings_page_flatpak),
    STORAGE("storage", Icons.Filled.Storage, R.string.settings_page_storage),
    DEVELOPER("developer", Icons.Filled.Code, R.string.settings_page_developer);

    companion object {
        fun of(key: String?): GlobalPage = entries.firstOrNull { it.key == key } ?: OVERVIEW
    }
}

/** The pages of one app's settings, in the rail's order; [OVERRIDES] only with developer options. */
enum class AppPage(val key: String, override val icon: ImageVector, override val titleRes: Int) : SettingsPage {
    OVERVIEW("overview", Icons.Filled.Info, R.string.app_page_overview),
    PERMISSIONS("permissions", Icons.Filled.VerifiedUser, R.string.app_page_permissions),
    HOME("home", Icons.Filled.Folder, R.string.app_page_home),
    DISPLAY("display", Icons.Filled.DisplaySettings, R.string.app_page_display),
    STORAGE("storage", Icons.Filled.Storage, R.string.app_page_storage),
    OVERRIDES("overrides", Icons.Filled.Tune, R.string.app_page_overrides);

    companion object {
        fun of(key: String?): AppPage = entries.firstOrNull { it.key == key } ?: OVERVIEW
    }
}

