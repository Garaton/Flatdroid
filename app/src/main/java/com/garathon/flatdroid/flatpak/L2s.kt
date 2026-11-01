package com.garathon.flatdroid.flatpak

import java.io.File

/**
 * proot's link2symlink store (`<rootfs>/.l2s`). Under it a hard link is a symlink to an
 * intermediate link in the store, `<PREFIX><first name><NNNN>`, which points to the file holding
 * the content, the intermediate's name plus `.NNNN` (the link count). `<first name>` is the name
 * the content had before the first hard link was made: for a Flatpak file, its repository object.
 */
object L2s {
    const val PREFIX = ".l2s."

    /**
     * The intermediate in [store] that a link's [target] names, or null for any other link.
     * Matched by the folder's name rather than its whole path: Android reaches app data as both
     * `/data/data/<package>` and `/data/user/0/<package>`, and links made at different times use
     * either, for the same store.
     */
    fun storedName(target: String, store: File): String? {
        val file = File(target)
        return file.name.takeIf { file.parentFile?.name == store.name && it.startsWith(PREFIX) }
    }

    /** The repository object name an intermediate was first linked from (without its `xx/` folder). */
    fun firstName(intermediate: String): String = intermediate.removePrefix(PREFIX).dropLast(UNIQUIFIER_LENGTH)

    private const val UNIQUIFIER_LENGTH = 4
}
