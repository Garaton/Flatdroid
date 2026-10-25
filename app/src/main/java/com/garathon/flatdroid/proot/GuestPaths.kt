package com.garathon.flatdroid.proot

/** Guest paths shared by the helpers' Alpine root and every Flatpak's root. */
object GuestPaths {
    /** Xvnc's socket directory. */
    const val X11_DIR = "/tmp/.X11-unix"

    /** PulseAudio's socket directory. */
    const val PULSE_DIR = "/run/pulse"

    /**
     * Fontconfig's list of the host's font directories, which Flatpak runtimes include from
     * their fonts.conf (Fontconfig complains when it is missing). Flatdroid's lists none.
     */
    const val FONT_DIRS = "/run/host/font-dirs.xml"

    /**
     * Android's own fonts (CJK, emoji and more scripts than the runtimes have), where Flatpak puts
     * the host's: the runtimes' fonts.conf already lists it.
     */
    const val HOST_FONTS = "/run/host/fonts"

    /** Flatdroid's desktop settings, last in a Flatpak's `XDG_CONFIG_DIRS` (see DesktopConfig). */
    const val XDG_CONFIG_DIR = "/run/flatdroid/xdg"

    /** All of shared storage, when an app's setting asks for it. */
    const val SDCARD = "/sdcard"

    /** Holds [SESSION_BUS_SOCKET], the app's session bus (Flatpak's path). */
    const val SESSION_BUS_DIR = "/run/flatpak"
    const val SESSION_BUS_SOCKET = "bus"

    /** Flatdroid's stand-in for bwrap in Alpine, as `FLATPAK_BWRAP` ([com.garathon.flatdroid.alpine.GuestFiles.BWRAP_STAND_IN]). */
    const val BWRAP_STAND_IN = "/run/flatdroid/bwrap"

    /** A Flatpak's home. */
    const val HOME = "/home/user"
}
