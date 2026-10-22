package com.garathon.flatdroid.storage

import java.io.File

/**
 * The folders a desktop gives every user, which apps find through `user-dirs.dirs` (Kamoso asks
 * for Pictures). English names, as `xdg-user-dirs` makes them without a locale.
 */
object HomeFolders {
    /** XDG name to folder, in `user-dirs.dirs` order. */
    val STANDARD = linkedMapOf(
        "DESKTOP" to "Desktop",
        "DOWNLOAD" to "Downloads",
        "DOCUMENTS" to "Documents",
        "MUSIC" to "Music",
        "PICTURES" to "Pictures",
        "VIDEOS" to "Videos",
    )

    /** Names apps also ask for, given the home itself (what `xdg-user-dirs` does for a missing folder). */
    val HOME_ONLY = listOf("TEMPLATES", "PUBLICSHARE")

    /**
     * Creates [home] with the standard folders in it; false if it already existed or can't be
     * created. Only for a home Flatdroid creates itself, so a folder the user deletes afterwards
     * stays deleted.
     */
    fun create(home: File): Boolean {
        if (home.exists() || !home.mkdirs()) return false
        for (name in STANDARD.values) File(home, name).mkdir()
        return true
    }

    /**
     * `user-dirs.dirs` for a home whose folders are checked with [isFolder] (by name); a missing
     * one is given the home itself, as `xdg-user-dirs` does.
     */
    fun userDirs(isFolder: (String) -> Boolean): String = buildString {
        append("# Written by Flatdroid at every launch.\n")
        for ((key, name) in STANDARD) {
            append("XDG_${key}_DIR=\"\$HOME/${if (isFolder(name)) name else ""}\"\n")
        }
        for (key in HOME_ONLY) append("XDG_${key}_DIR=\"\$HOME/\"\n")
    }

    /** Where imported files go: the home's Downloads folder, or the home itself without one. */
    fun importDir(home: File): File = File(home, STANDARD.getValue("DOWNLOAD")).takeIf { it.isDirectory } ?: home
}
