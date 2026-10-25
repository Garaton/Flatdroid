package com.garathon.flatdroid.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.garathon.flatdroid.setup.SetupController
import java.io.File

/**
 * The shared home: `Documents/Flatdroid` on internal storage, or the folder the user chose. Without
 * All files access (Android 11 and newer), Flatdroid and its apps see only the files Flatdroid
 * created there; files from other apps, or from an earlier installation of Flatdroid, are hidden.
 */
object SharedHome {
    fun storageRoot(): File = Environment.getExternalStorageDirectory()

    fun defaultDir(): File = File(storageRoot(), "${Environment.DIRECTORY_DOCUMENTS}/Flatdroid")

    /**
     * The chosen folder, or the default one. A chosen folder that isn't on shared storage (only
     * possible through an imported settings file) is ignored. One outside `Documents/` and
     * `Download/` stays in use when All files access is turned off.
     */
    fun dir(context: Context): File {
        val chosen = SetupController.settings(context).load()?.sharedHome ?: return defaultDir()
        return if (SharedFolderPath.check(chosen, storageRoot().path, broadAccess = true) is SharedFolderPath.Result.Ok) File(chosen) else defaultDir()
    }

    fun display(dir: File): String = SharedFolderPath.display(dir.path, storageRoot().path)

    /**
     * Whether Flatdroid can reach all of shared storage: All files access on Android 11 and newer,
     * the storage permission before (which Android 10 and older need for the shared home at all).
     */
    fun broadAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else hasLegacyPermission(context)

    /** Android 10 and older: the storage permission, without which shared storage can't be used. */
    fun needsLegacyPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !hasLegacyPermission(context)

    private fun hasLegacyPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /**
     * Creates the shared home with the standard folders when it doesn't exist, and remembers that
     * this installation created it. Returns the folder, or null if it can't be created.
     */
    fun ensure(context: Context): File? {
        val dir = dir(context)
        if (dir.isDirectory) return dir
        if (!HomeFolders.create(dir)) return null
        record(context, CREATED, dir)
        return dir
    }

    /**
     * Whether to warn that the shared home may hold files Flatdroid can't see: it exists, this
     * installation didn't create it, Flatdroid can't see all files, and the user hasn't been told
     * about this folder yet.
     */
    fun needsWarning(context: Context, dir: File = dir(context)): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !broadAccess(context) && dir.exists() &&
            !recorded(context, CREATED, dir) && !recorded(context, WARNED, dir)

    fun markWarned(context: Context, dir: File) = record(context, WARNED, dir)

    // Kept out of backups and removed with the app: a reinstall must forget what it created, since
    // Android then hides those files from it.
    private fun records(context: Context, name: String) = File(context.noBackupFilesDir, name)

    private fun recorded(context: Context, name: String, dir: File): Boolean =
        records(context, name).takeIf { it.isFile }?.readLines()?.contains(dir.path) == true

    @Synchronized
    private fun record(context: Context, name: String, dir: File) {
        if (recorded(context, name, dir)) return
        records(context, name).appendText(dir.path + "\n")
    }

    private const val CREATED = "shared-homes-created"
    private const val WARNED = "shared-homes-warned"
}
