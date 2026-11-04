package com.garathon.flatdroid.settings

import com.garathon.flatdroid.R

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
    /** Only CPU and memory information from /sys, and `devices=all` gives no device group. */
    val limitDevices: Boolean = false,
    /** The VNC password screen can't be captured, the password shows only on tap, and the viewer must be chosen in Settings. */
    val hideScreen: Boolean = false,
    /** Exports go through Android's "save as" picker instead of straight into Download. */
    val exportWithPicker: Boolean = false,
    /** Each app's and its runtime's deployed files are checked against a baseline before launch. */
    val checkFilesBeforeLaunch: Boolean = false,
    /** Apps get Android's fonts (their list tells apps and websites the device's Android version and maker) until changed per app. */
    val androidFonts: Boolean = true,
    /** Apps share the clipboard with Android (through the VNC viewer) until changed per app. */
    val shareClipboard: Boolean = true,
    /** Apps get Android's time zone (otherwise UTC, which hints at no location) until changed per app. */
    val shareTimeZone: Boolean = true,
    /** Apps get Android's language and region as their locale (otherwise the generic C.UTF-8) until changed per app. */
    val shareLanguage: Boolean = true,
) {
    val appDefaults: AppDefaults get() =
        AppDefaults(defaultHomeMode, permissionsFromMetadata, androidFonts, shareClipboard, shareTimeZone, shareLanguage)
}

/**
 * Presets for [SecuritySettings], asked for before setup. Performant trusts every installed
 * Flatpak; Default assumes some are malicious without knowing Flatdroid; Hardened assumes one
 * knows exactly how Flatdroid works, and gives up speed and features for it.
 */
enum class SecurityLevel(
    override val settings: SecuritySettings,
    override val labelRes: Int,
    override val hintRes: Int,
) : Preset<SecuritySettings> {
    PERFORMANT(
        SecuritySettings(extraSyscallFilter = false, hideHelperProcesses = false),
        R.string.security_level_performant, R.string.security_level_performant_hint,
    ),
    DEFAULT(SecuritySettings(), R.string.security_level_default, R.string.security_level_default_hint),
    HARDENED(
        SecuritySettings(
            permissionsFromMetadata = false,
            defaultHomeMode = HomeMode.PRIVATE,
            limitDevices = true,
            hideScreen = true,
            exportWithPicker = true,
            checkFilesBeforeLaunch = true,
            androidFonts = false,
            shareClipboard = false,
            shareTimeZone = false,
            shareLanguage = false,
        ),
        R.string.security_level_hardened, R.string.security_level_hardened_hint,
    ),
    ;

    companion object {
        /** The level whose settings these are; null for Custom. */
        fun of(settings: SecuritySettings): SecurityLevel? = entries.presetFor(settings)
    }
}
