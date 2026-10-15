package com.garathon.flatdroid.setup

import com.garathon.flatdroid.util.writeTextAtomically
import java.io.File

/**
 * Takes the Alpine environment back to before setup, either keeping installed Flatpaks (their
 * installation is moved aside and returns with the next rootfs; each app's private settings and
 * data stay where they are) or wiping everything, those settings and data included.
 *
 * [request] records the reset first; [finish] does the work and can be repeated, so a reset
 * interrupted at any point completes the next time [finish] runs.
 */
class AlpineReset(
    private val pendingFile: File,
    private val installer: RootfsInstaller,
    private val state: SetupStateStore,
    /** Temporary files (guest /tmp, downloads, package cache, Flatpak caches), removed in both modes. */
    private val temporary: List<File>,
    /**
     * Every Flatpak's private settings and data, the launcher's files and Flatdroid's own settings;
     * removed only when wiping everything.
     */
    private val flatpakData: List<File>,
    private val fs: TarFs,
) {
    fun isPending(): Boolean = pendingFile.isFile

    fun request(keepFlatpaks: Boolean) {
        pendingFile.writeTextAtomically(if (keepFlatpaks) KEEP else WIPE)
    }

    /** Completes a requested reset; does nothing when none is pending. */
    fun finish() {
        if (!isPending()) return
        val keep = pendingFile.readText().trim() == KEEP
        state.clear()
        if (keep) {
            installer.setAside()
        } else {
            installer.discardKept()
            flatpakData.forEach { fs.deleteTree(it) }
        }
        installer.wipe()
        temporary.forEach { fs.deleteTree(it) }
        pendingFile.delete()
    }

    private companion object {
        const val KEEP = "keep-flatpaks"
        const val WIPE = "wipe"
    }
}
