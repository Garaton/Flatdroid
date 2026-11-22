package com.garathon.flatdroid.launcher

/**
 * Stand-ins for "run on the host": scripts bound over the runtime's `/usr/bin/flatpak-spawn` and
 * an app's own `/app/bin/host-spawn`. There is no host and no other sandbox here, so both run the
 * command in the app's own root, behind the same proot and filter: nothing is widened, and only
 * what the runtime and the app ship can run. As in Flatpak, running "on the host" needs the
 * metadata's `org.freedesktop.Flatpak` (or the whole session bus); without it both fail as there.
 * Options asking for a narrower sandbox are refused: nothing can be narrowed here.
 */
object HostCommands {
    const val FLATPAK_SPAWN = "flatpak-spawn"
    const val HOST_SPAWN = "host-spawn"
    /** Where each goes in the app's root, and where the original must exist for it to be bound. */
    const val FLATPAK_SPAWN_GUEST = "/usr/bin/$FLATPAK_SPAWN"
    const val HOST_SPAWN_GUEST = "/app/bin/$HOST_SPAWN"

    const val FLATPAK_NAME = "org.freedesktop.Flatpak"
    /** `sockets=` entry for the whole session bus. */
    private const val WHOLE_BUS = "session-bus"

    /**
     * Whether the app may run commands "on the host": its metadata may talk to [FLATPAK_NAME],
     * or it has the whole session bus ([sockets] names it).
     */
    fun hostAllowed(policy: Map<String, String>, sockets: List<String>): Boolean =
        WHOLE_BUS in sockets || policy[FLATPAK_NAME] == "talk" || policy[FLATPAK_NAME] == "own"

    /** The message Flatpak's tools print when [FLATPAK_NAME] isn't reachable. */
    private const val NOT_PROVIDED =
        "GDBus.Error:org.freedesktop.DBus.Error.ServiceUnknown: The name $FLATPAK_NAME was not provided by any .service files"

    /**
     * `flatpak-spawn`: options up to the first argument not starting with `-` (or `--`), then the
     * command. `--env`, `--unset-env`, `--env-fd`, `--clear-env`, `--directory` and `--forward-fd`
     * (the descriptor is simply inherited) as flatpak-spawn has them, values only as `--opt=value`;
     * `--watch-bus`, `--expose-pids`, `--share-pids` and `--latest-version` change nothing here.
     * The command replaces the script, so its exit status is the caller's (a signal shows as the
     * signal, where flatpak-spawn returns 128 + its number).
     */
    fun flatpakSpawn(hostAllowed: Boolean): String = """
        |#!/bin/sh
        |# Flatdroid's flatpak-spawn: runs the command in this app's own root.
        |host_allowed=${if (hostAllowed) 1 else 0}
        |die() { echo "flatpak-spawn: ${'$'}1" >&2; exit 1; }
        |drop() { r=; for w in ${'$'}1; do [ "${'$'}w" = "${'$'}2" ] || r="${'$'}r ${'$'}w"; done; echo "${'$'}r"; }
        |valid() { case ${'$'}1 in ''|[0-9]*|*[!A-Za-z0-9_]*) return 1;; esac; }
        |names= unsets=
        |setenv() {
        |  case ${'$'}1 in *=*) ;; *) die "Invalid env format ${'$'}1";; esac
        |  name=${'$'}{1%%=*}
        |  valid "${'$'}name" || die "Invalid env format ${'$'}1"
        |  eval "fdv_${'$'}name=\${'$'}{1#*=}"
        |  names="${'$'}(drop "${'$'}names" "${'$'}name") ${'$'}name"
        |  unsets=${'$'}(drop "${'$'}unsets" "${'$'}name")
        |}
        |unsetenv() {
        |  valid "${'$'}1" || die "Invalid env format ${'$'}1"
        |  unsets="${'$'}(drop "${'$'}unsets" "${'$'}1") ${'$'}1"
        |  names=${'$'}(drop "${'$'}names" "${'$'}1")
        |}
        |envfd() {
        |  case ${'$'}1 in ''|*[!0-9]*) die "Not a valid file descriptor";; esac
        |  data=${'$'}(tr '\0' '\n' < "/proc/self/fd/${'$'}1") || die "Not a valid file descriptor"
        |  while IFS= read -r line; do [ -z "${'$'}line" ] || setenv "${'$'}line"; done <<EOF
        |${'$'}data
        |EOF
        |}
        |host=0 clear=0 dir=
        |while [ ${'$'}# -gt 0 ]; do
        |  a=${'$'}1
        |  case ${'$'}a in -*) ;; *) break;; esac
        |  shift
        |  case ${'$'}a in
        |    --) break;;
        |    --host) host=1;;
        |    --clear-env) clear=1;;
        |    -v|--verbose|--watch-bus|--expose-pids|--share-pids|--latest-version) ;;
        |    --directory=*) dir=${'$'}{a#--directory=};;
        |    --env=*) setenv "${'$'}{a#--env=}";;
        |    --unset-env=*) unsetenv "${'$'}{a#--unset-env=}";;
        |    --env-fd=*) envfd "${'$'}{a#--env-fd=}";;
        |    --forward-fd=*) fd=${'$'}{a#--forward-fd=}; case ${'$'}fd in ''|*[!0-9]*) die "Invalid fd '${'$'}fd'";; esac;;
        |    --sandbox|--sandbox=*|--sandbox-*|--no-network|--no-network=*|--app-path|--app-path=*|--usr-path|--usr-path=*)
        |      die "Flatdroid can't run a command in a narrower sandbox (${'$'}a)";;
        |    --directory|--env|--unset-env|--env-fd|--forward-fd) die "Missing argument for ${'$'}a";;
        |    -h|--help) echo "Usage: flatpak-spawn [OPTION…] COMMAND [ARGUMENT…]"; exit 0;;
        |    *) die "Unknown option ${'$'}a";;
        |  esac
        |done
        |[ ${'$'}# -gt 0 ] || die "No command specified"
        |if [ "${'$'}host" = 1 ] && [ "${'$'}host_allowed" != 1 ]; then
        |  echo "Portal call failed: $NOT_PROVIDED" >&2
        |  exit 1
        |fi
        |if [ -n "${'$'}dir" ]; then cd -- "${'$'}dir" || exit 1; fi
        |# A command whose name holds '=' would be taken for a variable.
        |case ${'$'}1 in *=*) set -- /bin/sh -euc 'exec "${'$'}@"' sh "${'$'}@";; esac
        |for name in ${'$'}unsets; do unset "${'$'}name"; done
        |# The variables go before the command: the command and its arguments are rotated behind them.
        |m=${'$'}#
        |for name in ${'$'}names; do eval "set -- \"\${'$'}@\" \"${'$'}name=\${'$'}{fdv_${'$'}name}\""; done
        |while [ "${'$'}m" -gt 0 ]; do a=${'$'}1; shift; set -- "${'$'}@" "${'$'}a"; m=${'$'}((m - 1)); done
        |if [ "${'$'}clear" = 1 ]; then set -- /usr/bin/env -i "${'$'}@"; else set -- /usr/bin/env "${'$'}@"; fi
        |$NEW_SESSION
        |exec "${'$'}@"
        |""".trimMargin()

    /**
     * `host-spawn`: Go-style flags (`-x` or `--x`, values as `-x value` or `-x=value`) up to the
     * first other argument or `--`; `-cwd`; `-env`, `-pty` and `-no-pty` change nothing here (the
     * command keeps the app's environment and the caller's terminal). No command: the user's shell.
     * Called under another name (a link to it standing in for a host tool such as `podman`), it
     * refuses: that tool doesn't exist here, and running the name again would loop.
     */
    fun hostSpawn(hostAllowed: Boolean): String = """
        |#!/bin/sh
        |# Flatdroid's host-spawn: runs the command in this app's own root.
        |host_allowed=${if (hostAllowed) 1 else 0}
        |me=${'$'}{0##*/}
        |case ${'$'}me in
        |  host-spawn*) ;;
        |  *) echo "${'$'}me: commands outside the app aren't available in Flatdroid" >&2; exit 127;;
        |esac
        |usage() { echo "Usage: ${'$'}me [options] [ COMMAND [ arguments... ] ]" >&2; exit 0; }
        |cwd=
        |while [ ${'$'}# -gt 0 ]; do
        |  a=${'$'}1
        |  case ${'$'}a in
        |    --) shift; break;;
        |    -) break;;
        |    -pty|--pty|-pty=*|--pty=*|-no-pty|--no-pty|-no-pty=*|--no-pty=*) ;;
        |    -version|--version) echo flatdroid; exit 0;;
        |    -env=*|--env=*) ;;
        |    -cwd=*|--cwd=*) cwd=${'$'}{a#*=};;
        |    -env|--env|-cwd|--cwd)
        |      [ ${'$'}# -ge 2 ] || usage
        |      case ${'$'}a in *cwd) cwd=${'$'}2;; esac
        |      shift;;
        |    -*) usage;;
        |    *) break;;
        |  esac
        |  shift
        |done
        |if [ "${'$'}host_allowed" != 1 ]; then
        |  echo "$NOT_PROVIDED" >&2
        |  exit 127
        |fi
        |if [ -n "${'$'}cwd" ]; then cd -- "${'$'}cwd" || exit 127; fi
        |[ ${'$'}# -gt 0 ] || set -- "${'$'}{SHELL:-/bin/sh}"
        |$NEW_SESSION
        |exec "${'$'}@"
        |""".trimMargin()

    /**
     * As Flatpak's host helper starts a command: with the terminal on its standard input (if any)
     * as its controlling terminal, so a shell has job control. A caller without a controlling
     * terminal gets a session of its own with it (`-w` waits when setsid has to fork, as for a
     * process group leader, and passes the exit status on). A caller that has one keeps it (the
     * seventh field of `/proc/<pid>/stat`, after the name, is not 0): a terminal belongs to one
     * session, and a new one couldn't take it (setsid would fail and run nothing), as for a
     * terminal emulator running the stand-in as its shell, or a shell running it as a command.
     */
    private val NEW_SESSION = "if [ -t 0 ] && [ -x /usr/bin/setsid ]; then fd_ctty() { fd_ctty=${'$'}5; }; fd_ctty=0; " +
        "read -r fd_stat < /proc/${'$'}${'$'}/stat && fd_ctty ${'$'}{fd_stat##*) }; " +
        "[ \"${'$'}fd_ctty\" = 0 ] && set -- /usr/bin/setsid -w -c \"${'$'}@\"; fi"
}
