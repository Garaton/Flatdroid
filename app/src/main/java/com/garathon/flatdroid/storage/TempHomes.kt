package com.garathon.flatdroid.storage

import android.content.Context
import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.setup.AndroidTarFs
import com.garathon.flatdroid.setup.deleteTree
import java.io.File

/**
 * The temporary home: one `cache/temphome/`, since one app runs at a time. It holds the app's
 * home, its settings directories and its cache for one launch, and is deleted and made again at
 * every launch in Temporary Home mode, whatever was left. When the app closes, the user is asked
 * whether to export the home (a zip in Download), and it is deleted either way (TempHomePrompt).
 */
object TempHomes {
    /** Also the base of the app's settings directories in this mode. */
    fun root(context: Context): File = File(context.cacheDir.canonicalFile, "temphome")

    fun home(context: Context): File = File(root(context), "home")

    /** Which app the temporary home belongs to. */
    private fun ownerFile(context: Context) = File(root(context), "app")

    /** Deletes what is left and makes a new home with the standard folders for [appId]. */
    fun recreate(context: Context, appId: String): File {
        delete(context)
        root(context).mkdirs()
        ownerFile(context).writeText(appId)
        val home = home(context)
        HomeFolders.create(home)
        return home
    }

    fun delete(context: Context) = AndroidTarFs.deleteTree(root(context))

    /** The app the temporary home left over belongs to, or null when there is none. */
    fun owner(context: Context): String? =
        ownerFile(context).takeIf { it.isFile }?.readText()?.trim()?.takeIf(InstalledApps::isValidId)

    /** A temporary home whose owner can't be read (a launch stopped while making it). */
    fun orphaned(context: Context): Boolean = root(context).exists() && owner(context) == null

    /**
     * Whether [home] holds anything the app saved: a file anywhere, or a folder other than the
     * empty standard ones. Links count as files.
     */
    fun hasContent(home: File, isLink: (File) -> Boolean = AndroidTarFs::isSymlink): Boolean {
        val entries = home.listFiles() ?: return false
        return entries.any { entry ->
            !(entry.isDirectory && !isLink(entry) && entry.name in HomeFolders.STANDARD.values && entry.list().isNullOrEmpty())
        }
    }
}
