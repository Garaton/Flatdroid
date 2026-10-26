package com.garathon.flatdroid.settings

/**
 * The settings a security level sets ([SecurityLevel]). Each can still be changed on its own;
 * values that match no level are "Custom". None of them makes proot a sandbox: they narrow
 * what an app can reach.
 */
data class SecuritySettings(
    /** fdsec also refuses pidfd_open, process_madvise, kcmp, keyctl, add_key and request_key (apps can opt out). */
    val extraSyscallFilter: Boolean = true,
    /**
     * The helpers' /proc entries are hidden from the app's root (an empty directory over each).
     * About 2% on program starts and file lookups (proot checks every bind), so not in Performant.
     */
    val hideHelperProcesses: Boolean = true,
    /** Network and sound follow what an app's metadata asks for; otherwise they're off until allowed. */
    val permissionsFromMetadata: Boolean = true,
    /** The home an app gets until the user picks one. */
    val defaultHomeMode: HomeMode = HomeMode.SHARED,
    /** Only CPU and memory information from /sys, and `devices=all` limited to the GPU. */
    val limitDevices: Boolean = false,
    /** The VNC password screen can't be captured, the password shows only on tap, and the viewer must be chosen in Settings. */
    val protectPassword: Boolean = false,
    /** Exports go through Android's "save as" picker instead of straight into Download. */
    val exportWithPicker: Boolean = false,
) {
    val appDefaults: AppDefaults get() = AppDefaults(defaultHomeMode, permissionsFromMetadata)
}

/**
 * Presets for [SecuritySettings], asked for before setup. Performant trusts every installed
 * Flatpak; Default assumes some are malicious without knowing Flatdroid; Hardened assumes one
 * knows exactly how Flatdroid works, and gives up speed and features for it.
 */
enum class SecurityLevel(val settings: SecuritySettings) {
    PERFORMANT(SecuritySettings(extraSyscallFilter = false, hideHelperProcesses = false)),
    DEFAULT(SecuritySettings()),
    HARDENED(
        SecuritySettings(
            permissionsFromMetadata = false,
            defaultHomeMode = HomeMode.PRIVATE,
            limitDevices = true,
            protectPassword = true,
            exportWithPicker = true,
        )
    ),
    ;

    companion object {
        /** The level whose settings these are; null for Custom. */
        fun of(settings: SecuritySettings): SecurityLevel? = entries.firstOrNull { it.settings == settings }
    }
}
