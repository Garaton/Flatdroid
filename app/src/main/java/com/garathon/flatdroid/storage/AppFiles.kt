package com.garathon.flatdroid.storage

import java.io.File

/**
 * An app's settings and other files of its own (`files/appdata/<ID>/` without the home), as a
 * zip lays them out: the paths they have in the app's home, which is also desktop Flatpak's
 * layout, so an export can be copied to a desktop's home and back.
 */
object AppFiles {
    /** Directory name in `files/appdata/<ID>/` to its path in the home (ending in "/"). */
    fun layout(appId: String): List<Pair<String, String>> = listOf(
        "config" to ".config/",
        "local" to ".local/",
        "pki" to ".pki/",
        "var" to ".var/app/$appId/",
    )

    fun dirs(appData: File, appId: String): List<File> = layout(appId).map { File(appData, it.first) }

    /**
     * What an export holds: without Flatdroid's `user-dirs.dirs` (written at every launch) and the
     * cache's mount point.
     */
    fun parts(appData: File, appId: String): List<HomeZip.Part> = layout(appId).map { (dir, prefix) ->
        HomeZip.Part(File(appData, dir), prefix, EXCLUDE[dir].orEmpty())
    }

    private val EXCLUDE = mapOf(
        "config" to setOf("user-dirs.dirs"),
        "var" to setOf("cache", "config/user-dirs.dirs"),
    )

    /** Where a zip entry (a safe relative path) goes under [appData], or null for anything outside these directories. */
    fun destination(appData: File, appId: String, path: String): File? {
        for ((dir, prefix) in layout(appId)) {
            if (path == prefix.trimEnd('/')) return File(appData, dir)
            if (path.startsWith(prefix)) return File(File(appData, dir), path.removePrefix(prefix))
        }
        return null
    }
}
