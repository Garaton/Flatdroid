package com.garathon.flatdroid.flatpak

import android.content.Context
import android.os.Process
import android.system.Os
import com.garathon.flatdroid.settings.AppSettingsStore
import java.io.File

/**
 * Where each Flatpak's private files live, in Flatdroid's private storage rather than the shared
 * home. Settings and data persist and are backed up (end-to-end encrypted only); caches go to the
 * cache directory, which Android may clear and never backs up.
 */
object AppData {
    /**
     * Settings and data: `files/appdata/<ID>/`, bound over the app's .config, .local, .pki and
     * .var/app/<ID>, and (until the shared home exists) its home itself. Kept by a reset that keeps
     * Flatpaks, deleted by one that wipes everything.
     */
    fun root(context: Context): File = File(context.filesDir.canonicalFile, "appdata")

    /**
     * Caches: `cache/appcache/<ID>/`, bound over the app's .cache and its XDG_CACHE_HOME
     * (.var/app/<ID>/cache). Deleted by every reset.
     */
    fun cacheRoot(context: Context): File = File(context.cacheDir.canonicalFile, "appcache")

    /** Files the launcher shares between all Flatpaks (see [LauncherFiles]). */
    fun launcherDir(context: Context): File = File(context.filesDir.canonicalFile, "launcher")

    fun launcherFiles(context: Context) = LauncherFiles(File(launcherDir(context), "etc"), Process.myUid(), Os.getgid())

    /**
     * Flatdroid's own per-app settings (`files/appsettings/<ID>.properties`). Not in [root]: the
     * app can write there, and these choose how it is launched.
     */
    fun settingsDir(context: Context): File = File(context.filesDir.canonicalFile, "appsettings")

    fun settings(context: Context, appId: String) = AppSettingsStore(File(settingsDir(context), "$appId.properties"))

    /** Electron builds prepared for apps that ship another architecture's (`files/electron/<ID>`). */
    fun electronDir(context: Context): File = File(context.filesDir.canonicalFile, "electron")

    /** Rebuilt for every launch, so they live in the cache: each app's root and private /tmp. */
    fun rootsDir(context: Context): File = File(context.cacheDir.canonicalFile, "roots")
    fun appTmpDir(context: Context): File = File(context.cacheDir.canonicalFile, "apptmp")

    /** The running session's sockets, VNC password file and generated /etc files. */
    fun sessionDir(context: Context): File = File(context.cacheDir.canonicalFile, "session")
}
