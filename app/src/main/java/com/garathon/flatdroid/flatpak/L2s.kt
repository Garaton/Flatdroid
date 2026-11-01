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
     * Whether a link's [target] names a file directly in [store], by any path that reaches it:
     * Android reaches app data as both `/data/data/<package>` and `/data/user/0/<package>`, and
     * links made at different times may use either for the same store. Those two are compared as
     * text; any other folder only once resolved, which is rare and costs a lookup.
     */
    fun inStore(target: String, store: File): Boolean {
        val parent = File(target).parentFile ?: return false
        if (parent.path in paths(store)) return true
        return runCatching { parent.canonicalPath == store.canonicalPath }.getOrDefault(false)
    }

    /** The intermediate in [store] that a link's [target] names, or null for any other link. */
    fun storedName(target: String, store: File): String? {
        val file = File(target)
        return file.name.takeIf { it.startsWith(PREFIX) && inStore(target, store) }
    }

    private fun paths(store: File): Set<String> {
        val path = store.path
        return setOf(
            path,
            when {
                path.startsWith(USER_0) -> DATA + path.removePrefix(USER_0)
                path.startsWith(DATA) -> USER_0 + path.removePrefix(DATA)
                else -> path
            },
        )
    }

    private const val USER_0 = "/data/user/0/"
    private const val DATA = "/data/data/"

    /** The repository object name an intermediate was first linked from (without its `xx/` folder). */
    fun firstName(intermediate: String): String = intermediate.removePrefix(PREFIX).dropLast(UNIQUIFIER_LENGTH)

    private const val UNIQUIFIER_LENGTH = 4
}
