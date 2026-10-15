package com.garathon.flatdroid.flatpak

import android.content.Context
import android.os.Process
import android.system.Os
import java.io.File

/**
 * Where each Flatpak's private files live, in Flatdroid's private storage rather than the shared
 * home. Settings and data persist and are backed up (end-to-end encrypted only); caches go to the
 * cache directory, which Android may clear and never backs up.
 */
object AppData {
    /**
     * Settings and data: `files/appdata/<ID>/`, to be bound over the app's .config, .local and
     * .var/app/<ID>. Kept by a reset that keeps Flatpaks, deleted by one that wipes everything.
     */
    fun root(context: Context): File = File(context.filesDir.canonicalFile, "appdata")

    /**
     * Caches: `cache/appcache/<ID>/`, to be bound over the app's .cache and its XDG_CACHE_HOME
     * (.var/app/<ID>/cache). Deleted by every reset.
     */
    fun cacheRoot(context: Context): File = File(context.cacheDir.canonicalFile, "appcache")

    /** Files the launcher shares between all Flatpaks (see [LauncherFiles]). */
    fun launcherDir(context: Context): File = File(context.filesDir.canonicalFile, "launcher")

    fun launcherFiles(context: Context) = LauncherFiles(File(launcherDir(context), "etc"), Process.myUid(), Os.getgid())
}
