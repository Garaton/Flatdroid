package com.garathon.flatdroid.proot

/**
 * Flatdroid's `shm_open` for the Wayland helpers (`native/fdshm.c`, shipped as [LIBRARY]), preloaded
 * into the compositor and its VNC server: they allocate their buffers with `shm_open`, which needs
 * `/dev/shm`, and Android has none. Backed by memfd instead, so frames stay in memory.
 */
object Fdshm {
    const val LIBRARY = "libfdshm.so"

    /** Where it is bound in Alpine's root (read-only source: nativeLibraryDir). */
    const val ALPINE_PATH = "/run/flatdroid/fdshm.so"
}
