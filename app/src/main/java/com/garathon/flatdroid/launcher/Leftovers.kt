package com.garathon.flatdroid.launcher

import com.garathon.flatdroid.flatpak.FlatpakInstallation
import com.garathon.flatdroid.launcher.electron.Electron
import com.garathon.flatdroid.launcher.electron.ElectronSwap
import com.garathon.flatdroid.setup.rootfs.TarFs
import com.garathon.flatdroid.setup.rootfs.deleteTree
import java.io.File

/**
 * Per-app files that can outlive their app, removed at start-up: an app uninstalled from the
 * Alpine shell, or updated so that its Electron swap no longer applies, leaves them behind.
 */
class Leftovers(
    private val installation: FlatpakInstallation,
    /** `files/electron`: one swap per app ID. */
    private val electronDir: File,
    /** Per-app directories in the cache and the work folder, one entry per app ID (caches, roots, private /tmp, swaps being built). */
    private val perAppCacheDirs: List<File>,
    private val fs: TarFs,
    /** Installed apps that aren't Flatpaks (Alpine programs), whose caches stay too. */
    private val otherApps: () -> Set<String> = { emptySet() },
) {
    /**
     * Removes Electron swaps not made for an installed app's current deployment, and per-app cache
     * directories of apps no longer installed. App data is kept, as `flatpak uninstall` keeps it.
     * Returns what was removed.
     */
    fun clean(): List<File> {
        val removed = mutableListOf<File>()
        fun remove(file: File) {
            fs.deleteTree(file)
            removed += file
        }
        electronDir.listFiles()?.forEach { swap ->
            val app = installation.app(swap.name)
            if (app == null || !ElectronSwap.isPreparedFor(swap, app, fs)) remove(swap)
        }
        val installed = installation.appIds().toSet() + otherApps()
        for (dir in perAppCacheDirs) dir.listFiles()?.filter { it.name !in installed }?.forEach(::remove)
        return removed
    }
}
