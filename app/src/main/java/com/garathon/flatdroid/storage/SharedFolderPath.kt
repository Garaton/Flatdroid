package com.garathon.flatdroid.storage

/**
 * Turns a folder chosen in Android's folder picker into a path the launcher can bind. The
 * picker's grant isn't used: Flatdroid reaches shared storage by path, where without All files
 * access it may create files only under `Documents/` and `Download/` of internal storage.
 */
object SharedFolderPath {
    sealed interface Result {
        data class Ok(val path: String) : Result
        /** Outside `Documents/` and `Download/`, and Flatdroid has no access to all files. */
        data object NotAllowed : Result
        /** A folder that isn't on a storage volume Flatdroid can reach by path. */
        data object Unknown : Result
    }

    const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    const val DOWNLOADS = "com.android.providers.downloads.documents"

    /** Top-level folders of internal storage where an app may create any file without permission. */
    private val OPEN_FOLDERS = listOf("Documents", "Download")

    /**
     * [authority] and [documentId] are the picked tree's; [storageRoot] is internal storage's path
     * (e.g. /storage/emulated/0); [broadAccess] is All files access, or the storage permission on
     * Android 10 and older.
     */
    fun of(authority: String?, documentId: String, storageRoot: String, broadAccess: Boolean): Result {
        val path = when (authority) {
            EXTERNAL_STORAGE -> {
                val volume = documentId.substringBefore(':', missingDelimiterValue = "")
                val relative = documentId.substringAfter(':', missingDelimiterValue = "").trim('/')
                when {
                    volume == "primary" -> if (relative.isEmpty()) storageRoot else "$storageRoot/$relative"
                    // Another volume (an SD card): reachable by path only with access to all files.
                    volume.isNotEmpty() && broadAccess && '/' !in volume -> if (relative.isEmpty()) "/storage/$volume" else "/storage/$volume/$relative"
                    else -> return Result.Unknown
                }
            }
            // Its folders are named by path ("raw:/storage/…") only on some devices.
            DOWNLOADS -> documentId.removePrefix("raw:").takeIf { documentId.startsWith("raw:/") } ?: return Result.Unknown
            else -> return Result.Unknown
        }.trimEnd('/')
        return check(path, storageRoot, broadAccess)
    }

    /**
     * Whether [path] may be the shared home: a folder on shared storage (internal storage, or with
     * [broadAccess] another volume), and without [broadAccess] only under `Documents/` or
     * `Download/`. A saved path is checked again when used, since settings can be imported:
     * anywhere else, such as Flatdroid's own storage, would become apps' home.
     */
    fun check(path: String, storageRoot: String, broadAccess: Boolean): Result {
        val segments = path.split('/').drop(1)
        if (!path.startsWith("/") || segments.any { it.isEmpty() || it == "." || it == ".." } || ':' in path) return Result.Unknown
        val internal = path == storageRoot || path.startsWith("$storageRoot/")
        if (!internal && !otherVolume(segments)) return Result.Unknown
        if (broadAccess) return Result.Ok(path)
        if (!path.startsWith("$storageRoot/")) return Result.NotAllowed
        val top = path.removePrefix("$storageRoot/").substringBefore('/')
        // Shared storage ignores case.
        return if (OPEN_FOLDERS.any { it.equals(top, ignoreCase = true) }) Result.Ok(path) else Result.NotAllowed
    }

    /** `/storage/<volume>/…` for a volume other than internal storage (an SD card). */
    private fun otherVolume(segments: List<String>) =
        segments.size >= 2 && segments[0] == "storage" && segments[1] !in setOf("emulated", "self")

    /** [path] as the user knows it: relative to internal storage when it is there. */
    fun display(path: String, storageRoot: String): String = path.removePrefix("$storageRoot/").takeIf { it != path } ?: path
}
