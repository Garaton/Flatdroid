package com.garathon.flatdroid.setup

import com.garathon.flatdroid.alpine.programs.AlpinePrograms
import com.garathon.flatdroid.setup.rootfs.RootfsInstaller
import com.garathon.flatdroid.setup.rootfs.TarFs
import com.garathon.flatdroid.setup.rootfs.deleteTree
import com.garathon.flatdroid.util.io.writeTextAtomically
import java.io.File

/** What a reset keeps besides Flatdroid's own setup state (which every reset clears). */
enum class ResetMode(internal val key: String) {
    /** The Flatpak installation comes back with the new rootfs; app data and settings stay. */
    KEEP_FLATPAKS("keep-flatpaks"),
    /** Flatpaks (and every cache) are deleted; each app's data and settings, and Flatdroid's settings stay. */
    REMOVE_FLATPAKS("remove-flatpaks"),
    /** Everything is deleted, Flatdroid's settings included. */
    WIPE("wipe"),
}

/**
 * Takes the Alpine environment back to before setup, in one of the [ResetMode]s.
 *
 * [request] records the reset first; [finish] does the work and can be repeated, so a reset
 * interrupted at any point completes the next time [finish] runs.
 */
class AlpineReset(
    private val pendingFile: File,
    private val installer: RootfsInstaller,
    private val state: SetupStateStore,
    /** Temporary files (guest /tmp, downloads, package cache, roots, the session, apps' caches): always removed. */
    private val temporary: List<File>,
    /** Files made from installed Flatpaks (prepared Electron builds, baselines, which extra data ran): removed with them. */
    private val flatpakFiles: List<File>,
    /** Each Flatpak's settings and data, the launcher's files and Flatdroid's settings: removed only by [ResetMode.WIPE]. */
    private val userData: List<File>,
    private val fs: TarFs,
    /**
     * Where a reset that keeps Flatpaks notes the Alpine packages the user added, for setup to add
     * them back ([AlpineSetup]); the other modes drop it. Null: not noted.
     */
    private val userPackages: File? = null,
) {
    fun isPending(): Boolean = pendingFile.isFile

    fun request(mode: ResetMode) {
        pendingFile.writeTextAtomically(mode.key)
    }

    /** Completes a requested reset; does nothing when none is pending. */
    fun finish() {
        if (!isPending()) return
        // An unreadable request keeps the most.
        val mode = ResetMode.entries.firstOrNull { it.key == pendingFile.readText().trim() } ?: ResetMode.KEEP_FLATPAKS
        userPackages?.let { file ->
            // Before the rootfs goes; a repeated finish finds the note already there (or nothing to note).
            if (mode != ResetMode.KEEP_FLATPAKS) file.delete()
            else if (!file.exists()) AlpinePrograms.userPackages(installer.rootfs).takeIf { it.isNotEmpty() }?.let { file.writeTextAtomically(it.joinToString("\n") + "\n") }
        }
        state.clear()
        if (mode == ResetMode.KEEP_FLATPAKS) installer.setAside() else installer.discardKept()
        if (mode != ResetMode.KEEP_FLATPAKS) flatpakFiles.forEach { fs.deleteTree(it) }
        if (mode == ResetMode.WIPE) userData.forEach { fs.deleteTree(it) }
        installer.wipe()
        temporary.forEach { fs.deleteTree(it) }
        pendingFile.delete()
    }
}
