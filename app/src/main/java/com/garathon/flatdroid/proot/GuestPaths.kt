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

    /** A Flatpak's home. */
    const val HOME = "/home/user"
}
