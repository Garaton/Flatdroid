package com.garathon.flatdroid.proot

/**
 * Flatdroid's seccomp helper (`native/fdsec.c`, shipped as [LIBRARY]), the first program every
 * proot runs: it installs the filter, checks that it is in effect, and only then executes the
 * command, which inherits the filter and can't remove it. When the filter isn't in effect it
 * executes nothing and prints a line starting with [REFUSED_PREFIX].
 *
 * The filter narrows what a process can reach from inside proot; it doesn't make proot a sandbox.
 */
object Fdsec {
    const val LIBRARY = "libfdsec.so"

    /**
     * Where fdsec is bound in a Flatpak's root, and in Alpine's. It is the only file Flatdroid
     * binds into a Flatpak's root that isn't the app's own data or regenerated every launch, and
     * its source is read-only to the app (nativeLibraryDir, owned by system), so a Flatpak can't
     * modify it. It has to run inside the root, before the app, because the seccomp filter lives
     * on the process that becomes the app; proot itself can't carry the filter (it needs ptrace
     * and process_vm_*, which the filter blocks). `/.flatdroid/` holds nothing else.
     */
    const val APP_PATH = "/.flatdroid/fdsec"
    const val ALPINE_PATH = "/run/flatdroid/fdsec"

    const val REFUSED_PREFIX = "fdsec: refused"

    /** What the filter refuses beyond its fixed list. */
    data class Filter(
        /** pidfd_open, process_madvise, kcmp, keyctl, add_key and request_key (ENOSYS). */
        val extraSyscalls: Boolean = false,
        /** Internet sockets (AF_INET, AF_INET6; EACCES). Unix and netlink sockets stay. */
        val noInternet: Boolean = false,
        /** Outgoing connections (`connect`, EACCES), for a server that only accepts them. */
        val noConnect: Boolean = false,
    )

    /** [command] behind the filter, with fdsec at [guestPath]. */
    fun wrap(guestPath: String, filter: Filter, command: List<String>): List<String> =
        listOf(guestPath) + flags(filter) + "--" + command

    fun flags(filter: Filter): List<String> =
        listOfNotNull("--extra".takeIf { filter.extraSyscalls }, "--no-inet".takeIf { filter.noInternet }, "--no-connect".takeIf { filter.noConnect })

    /** Whether [line] of a proot's output is fdsec refusing to start its command. */
    fun refused(line: String) = line.startsWith(REFUSED_PREFIX)
}
