package com.garathon.flatdroid

import android.app.Application
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.flatpak.AppData
import com.garathon.flatdroid.flatpak.FlatpakInstallation
import com.garathon.flatdroid.launcher.ElectronSwap
import com.garathon.flatdroid.launcher.Leftovers
import com.garathon.flatdroid.proot.LeftoverProcesses
import com.garathon.flatdroid.setup.AndroidTarFs
import com.garathon.flatdroid.storage.SavedSizes
import com.garathon.flatdroid.util.DebugLog
import com.garathon.flatdroid.util.appScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/** Clears what an earlier run left behind, once per process start. */
class FlatdroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.init(cacheDir)
        // Before anything of this run starts: every process of Flatdroid's that is running now is left over.
        val killed = LeftoverProcesses.kill()
        if (killed > 0) DebugLog.i("Ended $killed processes left by an earlier run")
        appScope.launch(Dispatchers.IO) {
            cleanLeftoverFiles()
            SavedSizes.measureMissing(this@FlatdroidApplication)
        }
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
            ).clean()
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
