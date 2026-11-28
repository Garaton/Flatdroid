package com.garathon.flatdroid.proot

/**
 * Flatdroid's `syscall()` for Alpine's commands (`native/fdstatx.c`, shipped as [LIBRARY]) on kernels
 * before 5.8, whose `statx` has no mount ID: Flatpak resolves paths through libglnx, which asks
 * `statx` for one and fails without it ("statx didn't return all required fields"), so nothing
 * could be installed or updated. The library adds the ID from `/proc/self/fdinfo`. Its `fstatfs()`
 * also answers for `O_PATH` descriptors, which kernels before 3.12 refuse and libglnx's fallback for
 * `open_tree` (kernels before 5.2) checks. Preloaded only where [needed]: newer kernels need neither.
 */
object Fdstatx {
    const val LIBRARY = "libfdstatx.so"

    /** Where it is bound in Alpine's root (read-only source: nativeLibraryDir). */
    const val ALPINE_PATH = "/run/flatdroid/fdstatx.so"

    /** [command] with the library preloaded, through Alpine's `env`: not in proot's own environment, where Android's loader would look for it too. */
    fun preloaded(command: List<String>): List<String> = listOf("/usr/bin/env", "LD_PRELOAD=$ALPINE_PATH") + command

    /** Whether the kernel [release] (`uname -r`, e.g. `4.19.191-g1234`) is older than 5.8; an unreadable one counts as old. */
    fun needed(release: String): Boolean {
        val parts = Regex("""^(\d+)\.(\d+)""").find(release)?.groupValues ?: return true
        val major = parts[1].toInt()
        val minor = parts[2].toInt()
        return major < 5 || (major == 5 && minor < 8)
    }
}
