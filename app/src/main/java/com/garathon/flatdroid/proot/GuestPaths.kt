package com.garathon.flatdroid.proot

/** Guest paths shared by the helpers' Alpine root and every Flatpak's root. */
object GuestPaths {
    /** Xvnc's socket directory. */
    const val X11_DIR = "/tmp/.X11-unix"

    /** PulseAudio's socket directory. */
    const val PULSE_DIR = "/run/pulse"

    /** A Flatpak's home. */
    const val HOME = "/home/user"
}
