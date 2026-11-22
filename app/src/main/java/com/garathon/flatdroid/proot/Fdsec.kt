package com.garathon.flatdroid.proot

/**
 * Flatdroid's seccomp helper (`native/fdsec.c`, shipped as [LIBRARY]), the first program every
 * proot runs: it installs the filter, checks that it is in effect, and only then executes the
 * command, which inherits the filter and can't remove it. When the filter isn't in effect it
 * executes nothing and prints a line starting with [REFUSED_PREFIX].
 *
 * Its fixed list also lets `socket` open only Unix, netlink and Internet sockets (the families
 * Flatpak allows). The filter narrows what a process can reach from inside proot; it doesn't make
 * proot a sandbox.
 */
object Fdsec {
    const val LIBRARY = "libfdsec.so"

    /**
     * Where fdsec is bound in a Flatpak's root, and in Alpine's. With the camera's library in
     * device mode ([Fdv4l2]), it is the only file Flatdroid binds into a Flatpak's root that isn't
     * the app's own data or regenerated every launch, and its source is read-only to the app
     * (nativeLibraryDir, owned by system), so a Flatpak can't modify it. It has to run inside the
     * root, before the app, because the seccomp filter lives on the process that becomes the app;
     * proot itself can't carry the filter (it needs ptrace and process_vm_*, which the filter
     * blocks). `/.flatdroid/` holds nothing else but that library and its cameras list.
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

    /**
     * [command] behind the filter, with fdsec at [guestPath]. With [tty] (a [isTerminal] path), the
     * command runs in a session of its own with that terminal as its controlling terminal and as its
     * standard input, output and error.
     */
    fun wrap(guestPath: String, filter: Filter, command: List<String>, tty: String? = null): List<String> {
        require(tty == null || isTerminal(tty)) { "Not a pseudo-terminal: $tty" }
        return listOf(guestPath) + flags(filter) + listOfNotNull(TTY.takeIf { tty != null }, tty) + "--" + command
    }

    /**
     * A terminal emulator's command (in Alpine's root, behind [filter]): it gives up its terminal as
     * controlling terminal, so another root sharing /dev/pts can take it ([wrap]'s tty), writes the
     * terminal's name to [file] and waits until it is ended.
     */
    fun holdTerminal(filter: Filter, file: String): List<String> = listOf(ALPINE_PATH) + flags(filter) + listOf(HOLD_TTY, file)

    /** A pseudo-terminal's path as [holdTerminal] writes it: `/dev/pts/` and a number. */
    fun isTerminal(path: String): Boolean = TERMINAL.matches(path)

    /** fdsec's exit code when a terminal step failed (with a line saying which). */
    const val EXIT_TTY = 124

    private const val TTY = "--tty"
    private const val HOLD_TTY = "--hold-tty"
    private val TERMINAL = Regex("/dev/pts/[0-9]{1,9}")

    fun flags(filter: Filter): List<String> =
        listOfNotNull("--extra".takeIf { filter.extraSyscalls }, "--no-inet".takeIf { filter.noInternet }, "--no-connect".takeIf { filter.noConnect })

    /** Whether [line] of a proot's output is fdsec refusing to start its command. */
    fun refused(line: String) = line.startsWith(REFUSED_PREFIX)
}
