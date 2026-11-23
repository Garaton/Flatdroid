package com.garathon.flatdroid

import android.app.Application
import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.garathon.flatdroid.alpine.programs.AlpineChanges
import com.garathon.flatdroid.alpine.programs.AlpinePrograms
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.flatpak.catalog.CatalogImages
import com.garathon.flatdroid.storage.AppData
import com.garathon.flatdroid.flatpak.FlatpakInstallation
import com.garathon.flatdroid.launcher.electron.ElectronSwap
import com.garathon.flatdroid.launcher.Leftovers
import com.garathon.flatdroid.proot.processes.LeftoverProcesses
import com.garathon.flatdroid.setup.rootfs.AndroidTarFs
import com.garathon.flatdroid.setup.rootfs.deleteTree
import com.garathon.flatdroid.setup.SetupController
import com.garathon.flatdroid.storage.sizes.SavedSizes
import com.garathon.flatdroid.util.debug.DebugLog
import com.garathon.flatdroid.util.DoneNotice
import com.garathon.flatdroid.util.appScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Makes Flatdroid's process non-dumpable, then clears what an earlier run left behind, once per
 * process start.
 */
class FlatdroidApplication : Application() {
    override fun attachBaseContext(base: Context) {
        // Before any other code of Flatdroid's runs (content providers start before onCreate).
        // Every Flatpak runs as Flatdroid's user and sees /proc: a dumpable process (debug builds
        // are) could have its memory opened through /proc/<pid>/mem, which seccomp can't tell
        // from any other open().
        try {
            Os.prctl(OsConstants.PR_SET_DUMPABLE, 0, 0, 0, 0)
            nonDumpable = Os.prctl(OsConstants.PR_GET_DUMPABLE, 0, 0, 0, 0) == 0
        } catch (e: ErrnoException) {
            DebugLog.w("Could not make the process non-dumpable: $e")
        }
        super.attachBaseContext(base)
    }

    companion object {
        /** Whether this process is non-dumpable; launches refuse otherwise, as they do when fdsec's filter isn't in place. */
        @Volatile var nonDumpable = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(cacheDir)
        DoneNotice.track(this)
        SetupController.settings(this).loadLive()
        AlpineChanges.refresh(this)
        // Before anything of this run starts: every process of Flatdroid's that is running now is left over.
        val killed = LeftoverProcesses.kill()
        if (killed > 0) DebugLog.i("Ended $killed processes left by an earlier run")
        val trash = setAsideLeftoverWork()
        appScope.launch(Dispatchers.IO) {
            AndroidTarFs.deleteTree(trash)
            CatalogImages.clearAtStart(this@FlatdroidApplication)
            cleanLeftoverFiles()
            SavedSizes.measureMissing(this@FlatdroidApplication)
        }
    }

    /**
     * Temporary files an earlier run's commands and apps left in the work folder (the guest's /tmp,
     * proot's, the apps' roots and private /tmp, the session's sockets): moved aside at once, before
     * anything of this run can start a command, and deleted in the background. The temporary home
     * and an import's staging stay: the user is still asked about them.
     */
    private fun setAsideLeftoverWork(): File {
        val env = AlpineEnvironment.get(this)
        val trash = File(env.workDir, "trash")
        for ((i, file) in env.leftoverFiles().withIndex()) {
            if (!file.exists() && !AndroidTarFs.isSymlink(file)) continue
            trash.mkdirs()
            if (!file.renameTo(File(trash, "${System.nanoTime()}-$i"))) DebugLog.w("Could not set aside ${file.path}")
        }
        return trash
    }

    private fun cleanLeftoverFiles() {
        val env = AlpineEnvironment.get(this)
        // Without the installation every swap and cache would look orphaned; a reset handles those.
        if (!env.isInstalled() || !env.flatpakDir.isDirectory) return
        try {
            val removed = Leftovers(
                installation = FlatpakInstallation(env.flatpakDir, env.arch),
                electronDir = AppData.electronDir(this),
                perAppCacheDirs = listOf(
                    AppData.cacheRoot(this), AppData.rootsDir(this), AppData.appTmpDir(this), File(env.cacheDir, ElectronSwap.BUILD_DIR),
                ),
                fs = AndroidTarFs,
                otherApps = { AlpinePrograms(env.installer.rootfs, exists = { it.exists() || AndroidTarFs.isSymlink(it) }).list().mapTo(HashSet()) { it.id } },
            ).clean()
            SavedSizes.removeStale(this)
            for (file in removed) {
                DebugLog.i("Removed leftover ${file.path}")
                // An Electron swap counts in its app's size.
                if (file.parentFile?.canonicalPath == AppData.electronDir(this).path) SavedSizes.changed(this, file.name)
            }
        } catch (e: Exception) {
            DebugLog.w("Leftover clean-up failed: $e")
        }
    }
}
