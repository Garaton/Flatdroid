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

    /**
     * Everything that is the app's own in `files/appdata/<ID>/` (its settings size, reset with them):
     * [layout]'s directories and [DOT_FILES_DIR].
     */
    fun dirs(appData: File, appId: String): List<File> = layout(appId).map { File(appData, it.first) } + File(appData, DOT_FILES_DIR)

    /**
     * The app's own copies of the dot-files a Shared Home would otherwise share between apps and
     * with other Android apps that can read shared storage: shell start-up files (which run
     * code), Git's settings (which can name commands), and keys and logins. Bound over the shared
     * home's, only in Shared Home mode (a private or temporary home is the app's already). Not
     * exported: on a desktop they belong to the whole home, not to one app.
     */
    const val DOT_FILES_DIR = "dotfiles"
    val PRIVATE_DOT_FILES = listOf(
        // Shell start-up (run code), Git (can name commands), network-tool rc files (curl/wget
        // read options such as an output path from them), credentials and history.
        ".bash_history", ".bash_profile", ".bashrc", ".curlrc", ".gitconfig", ".inputrc", ".netrc", ".profile", ".wgetrc", ".zshrc",
    )
    val PRIVATE_DOT_DIRS = listOf(".gnupg", ".ssh")

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
