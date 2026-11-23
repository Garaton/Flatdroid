package com.garathon.flatdroid.proot

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
    fun inStore(target: String, store: File): Boolean = Store(store).inStore(target)

    /** The intermediate in [store] that a link's [target] names, or null for any other link. */
    fun storedName(target: String, store: File): String? = Store(store).storedName(target)

    /** [storedName] and [inStore] for one store, its two paths worked out once: for walks that ask per link. */
    class Store(private val dir: File) {
        /** The paths that name the store as text: its own, and the other one Android reaches app data by. */
        val paths: Set<String> = paths(dir)
        /** Its canonical path, for a folder that names it another way; null when it can't be resolved. */
        val canonical: String? by lazy { runCatching { dir.canonicalPath }.getOrNull() }

        fun inStore(target: String): Boolean {
            val slash = target.lastIndexOf('/')
            if (slash <= 0) return false
            val parent = target.substring(0, slash)
            if (parent in paths) return true
            return runCatching { File(parent).canonicalPath == canonical }.getOrDefault(false)
        }

        fun storedName(target: String): String? {
            val name = target.substring(target.lastIndexOf('/') + 1)
            return name.takeIf { it.startsWith(PREFIX) && inStore(target) }
        }
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

    // The two paths Android reaches app data by; compared as prefixes, not built, so the
    // SdCardPath hint (use getFilesDir()) doesn't apply.
    @Suppress("SdCardPath") private const val USER_0 = "/data/user/0/"
    @Suppress("SdCardPath") private const val DATA = "/data/data/"

    /** The repository object name an intermediate was first linked from (without its `xx/` folder). */
    fun firstName(intermediate: String): String = intermediate.removePrefix(PREFIX).dropLast(UNIQUIFIER_LENGTH)

    private const val UNIQUIFIER_LENGTH = 4
}
