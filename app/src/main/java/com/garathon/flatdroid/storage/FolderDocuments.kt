package com.garathon.flatdroid.storage

import com.garathon.flatdroid.flatpak.InstalledApps

/**
 * A folder on the device as a document Android's Files app opens: in an app's private home,
 * [HomesProvider]'s; in shared storage, Android's own external storage provider's (verify on
 * Android before 10). Anything else (an app's own root, Flatdroid's other files) has none.
 */
object FolderDocuments {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** The provider's authority and document ID for [path], or null. [homesRoot] holds `<app ID>/home`; [storageRoot] is shared storage. */
    fun of(path: String, homesRoot: String, storageRoot: String): Pair<String, String>? {
        if (path.split('/').any { it == "." || it == ".." }) return null
        relative(path, homesRoot)?.let { rest ->
            val appId = rest.substringBefore('/')
            val inApp = rest.substringAfter('/', "")
            if (!InstalledApps.isValidId(appId)) return null
            if (inApp != "home" && !inApp.startsWith("home/")) return null
            val inHome = inApp.removePrefix("home").removePrefix("/")
            return HomesProvider.AUTHORITY to (if (inHome.isEmpty()) appId else HomeDocuments.child(appId, inHome))
        }
        relative(path, storageRoot)?.let { rest -> return EXTERNAL_STORAGE_AUTHORITY to "primary:$rest" }
        return null
    }

    /** [path] below [root] ("" for the root itself), or null when it isn't. */
    private fun relative(path: String, root: String): String? {
        val base = root.trimEnd('/')
        if (path == base) return ""
        return path.removePrefix("$base/").takeIf { it != path }?.trimEnd('/')
    }
}
