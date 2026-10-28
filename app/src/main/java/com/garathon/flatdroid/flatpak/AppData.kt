package com.garathon.flatdroid.flatpak

import android.content.Context
import android.os.Process
import android.system.Os
import com.garathon.flatdroid.settings.AppDefaults
import com.garathon.flatdroid.settings.AppSettingsStore
import com.garathon.flatdroid.setup.SetupController
import java.io.File

/**
 * Where each Flatpak's private files live, in Flatdroid's private storage rather than the shared
 * home. Settings and data persist and are backed up (end-to-end encrypted only); caches go to the
 * cache directory, which Android may clear and never backs up.
 */
object AppData {
    /**
     * Settings and data: `files/appdata/<ID>/`, bound over the app's .config, .local, .pki and
     * .var/app/<ID> (whose config and data are also /var/config and /var/data), and its private
     * home (`home`, used in Private Home mode). Kept by a reset that keeps
     * Flatpaks, deleted by one that wipes everything.
     */
    fun root(context: Context): File = File(context.filesDir.canonicalFile, "appdata")

    /** The app's private home (Private Home mode), shown in the Files app (see HomesProvider). */
    fun privateHome(context: Context, appId: String): File = File(root(context), "$appId/home")

    /**
     * Hidden config and key files (.ssh, .gnupg, shell start-up, .gitconfig, …) shared by the apps
     * that opt in, in Shared Home mode (the developer setting [AppSettings.shareHiddenFiles]). A
     * leading dot keeps it from colliding with an app ID directory.
     */
    fun sharedDotfilesDir(context: Context): File = File(root(context), ".shared-dotfiles")

    /**
     * Caches: `cache/appcache/<ID>/`, bound over the app's .cache, its XDG_CACHE_HOME
     * (.var/app/<ID>/cache) and /var/cache, with /var/tmp in it. Deleted by every reset.
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

    fun settingsFile(context: Context, appId: String): File = File(settingsDir(context), "$appId.properties")

    /** The app's settings, with what those the user hasn't changed follow (the security level's defaults). */
    fun settings(context: Context, appId: String) = AppSettingsStore(
        settingsFile(context, appId),
        SetupController.settings(context).load()?.security?.appDefaults ?: AppDefaults(),
    )

    /** Electron builds prepared for apps that ship another architecture's (`files/electron/<ID>`). */
    fun electronDir(context: Context): File = File(context.filesDir.canonicalFile, "electron")

    /** Rebuilt for every launch, so they live in the cache: each app's root and private /tmp. */
    fun rootsDir(context: Context): File = File(context.cacheDir.canonicalFile, "roots")
    fun appTmpDir(context: Context): File = File(context.cacheDir.canonicalFile, "apptmp")

    /**
     * Present while an update, uninstall or repair runs; removed when it ends. Still there at the
     * next start, it means the process was killed in the middle (a force-stop), so the repository
     * is cleaned up before anything else (see FlatpakTask.Repair).
     */
    fun taskMarker(context: Context): File = File(context.filesDir.canonicalFile, "flatpak-task-running")

    /** The running session's sockets, VNC password file and generated /etc files. */
    fun sessionDir(context: Context): File = File(context.cacheDir.canonicalFile, "session")

    /** File-integrity baselines, one manifest per deployment commit (see FileIntegrityCheck). */
    fun integrityDir(context: Context): File = File(context.filesDir.canonicalFile, "integrity")
}
