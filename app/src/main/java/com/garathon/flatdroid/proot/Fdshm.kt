package com.garathon.flatdroid.proot

/**
 * Flatdroid's `shm_open` for the Wayland helpers (`native/fdshm.c`, shipped as [LIBRARY]), preloaded
 * into the compositor and its VNC server: they allocate their buffers with `shm_open`, which needs
 * `/dev/shm`, and Android has none. Backed by memfd instead, so frames stay in memory.
 *
 * On kernels without memfd (before 3.17) apps get [MEMFD_LIBRARY], its `memfd_create` fallback
 * alone, through `/etc/ld.so.preload` ([withMemfdFallback]): GNOME's image loader (glycin) passes
 * images in memfd memory and starts its loaders with an empty environment, so `LD_PRELOAD` wouldn't
 * reach them. Only glibc reads that file; musl programs (Alpine's) don't get it.
 */
object Fdshm {
    const val LIBRARY = "libfdshm.so"

    /** Where it is bound in Alpine's root (read-only source: nativeLibraryDir). */
    const val ALPINE_PATH = "/run/flatdroid/fdshm.so"

    const val MEMFD_LIBRARY = "libfdmemfd.so"

    /** Where [MEMFD_LIBRARY] is bound in an app's root. */
    const val MEMFD_APP_PATH = "/.flatdroid/fdmemfd.so"

    /** The `/etc` file glibc's loader reads the libraries to preload from. */
    const val PRELOAD_FILE = "ld.so.preload"

    /** [PRELOAD_FILE]'s text. */
    const val PRELOAD_LIST = "$MEMFD_APP_PATH\n"

    /** [binds] with [library] (the host's [MEMFD_LIBRARY]) bound at [MEMFD_APP_PATH] and [preloadList] (holding [PRELOAD_LIST]) as `/etc/ld.so.preload`. */
    fun withMemfdFallback(binds: FlatpakBinds, library: String, preloadList: String): FlatpakBinds =
        binds.copy(extra = binds.extra + (library to MEMFD_APP_PATH), etc = binds.etc + (PRELOAD_FILE to preloadList))
}
