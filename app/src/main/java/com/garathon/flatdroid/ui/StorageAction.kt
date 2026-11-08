package com.garathon.flatdroid.ui

/** What an app's Storage page asks for; the ones that replace or delete files were confirmed. */
enum class StorageAction {
    CLEAR_CACHE, COPY_INTO_HOME, DOWNLOAD_FROM_HOME, IMPORT_HOME, EXPORT_HOME, RESET_HOME,
    IMPORT_SETTINGS, EXPORT_SETTINGS, RESET_SETTINGS,
}
