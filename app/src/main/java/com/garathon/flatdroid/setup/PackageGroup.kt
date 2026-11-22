package com.garathon.flatdroid.setup

import java.io.File

/**
 * Alpine packages that setup leaves out, added the first time an app needs them (the user is asked
 * at that launch). A group counts as added when its [files] are in the rootfs, so an Alpine that
 * already has them needs nothing; from then on Alpine's updates keep it ([AlpineSetup.updateList]).
 */
enum class PackageGroup(private val packages: List<String>, val files: List<String>, private val downloadMb: Int) {
    /** Xvnc and Openbox, for an app that only takes X11 (or is set to Force X11): 31 packages, 54 MiB installed. */
    X11(listOf("tigervnc", "openbox"), listOf("usr/bin/Xvnc", "usr/bin/openbox"), downloadMb = 14),

    /**
     * foot, the terminal of an app whose desktop file says `Terminal=true`. Its font is Android's
     * monospace one ([ANDROID_MONOSPACE], with Android's other fonts as fallbacks); a device without
     * it gets DejaVu.
     */
    TERMINAL(listOf("foot"), listOf("usr/bin/foot"), downloadMb = 1);

    /** The packages to add, on a device that has [androidMonospace] or not. */
    fun packages(androidMonospace: Boolean): List<String> =
        if (this == TERMINAL && !androidMonospace) packages + DEJAVU else packages

    /** About how much [packages] download beyond setup's, in MB (Alpine 3.24's index, either architecture). */
    fun downloadMb(androidMonospace: Boolean): Int = if (this == TERMINAL && !androidMonospace) DEJAVU_DOWNLOAD_MB else downloadMb

    /** Whether the group is in the Alpine at [rootfs]. */
    fun addedIn(rootfs: File): Boolean = files.all { File(rootfs, it).isFile }

    companion object {
        /** Android's monospace font, which foot uses where the device has it. */
        val ANDROID_MONOSPACE = File("/system/fonts/DroidSansMono.ttf")
        /** foot's font where Android has no [ANDROID_MONOSPACE]. */
        const val DEJAVU = "font-dejavu"
        /** The terminal's download with [DEJAVU]. */
        private const val DEJAVU_DOWNLOAD_MB = 7

        /** The groups a session needs: X11 unless it is Wayland, the terminal for a terminal app. */
        fun needed(waylandSession: Boolean, terminal: Boolean): List<PackageGroup> =
            listOfNotNull(X11.takeIf { !waylandSession }, TERMINAL.takeIf { terminal })
    }
}
