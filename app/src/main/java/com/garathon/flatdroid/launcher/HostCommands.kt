package com.garathon.flatdroid.launcher

/**
 * Stand-ins for "run on the host": scripts bound over the runtime's `/usr/bin/flatpak-spawn` and
 * an app's own `/app/bin/host-spawn`. There is no host here, so both run the command in the app's
 * own root, behind the same proot and filter: nothing is widened, and only what the runtime and
 * the app ship can run. As in Flatpak, running "on the host" needs the metadata's
 * `org.freedesktop.Flatpak` (or the whole session bus); without it both fail as there. Flatpak's
 * sandbox options (`--sandbox` and the rest) are refused: Flatdroid can't start a Flatpak sandbox.
 * A host command runs without the app's `XDG_*_HOME` ([HOST_UNSET]), as on a desktop, so it
 * finds what the app wrote for the host in `~/.config`, `~/.local` and `~/.cache` (the app's
 * own folders here, in every home mode).
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

    /**
     * What Flatpak sets only inside the sandbox, pointing into `~/.var/app/<ID>`: a host command
     * goes by the defaults instead, where an app puts files meant for the host (an app menu entry
     * in `~/.local/share/applications`, found by `gtk-launch`).
     */
    val HOST_UNSET_NAMES = listOf("XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME", "XDG_STATE_HOME")
    private val HOST_UNSET = HOST_UNSET_NAMES.joinToString(" ")

    /** The message Flatpak's tools print when [FLATPAK_NAME] isn't reachable. */
    private const val NOT_PROVIDED =
        "GDBus.Error:org.freedesktop.DBus.Error.ServiceUnknown: The name $FLATPAK_NAME was not provided by any .service files"

    /**
     * `flatpak-spawn`: options up to the first argument not starting with `-` (or `--`), then the
     * command. `--env`, `--unset-env`, `--env-fd`, `--clear-env`, `--directory` and `--forward-fd`
     * (the descriptor is simply inherited) as flatpak-spawn has them, values only as `--opt=value`
     * (flatpak-spawn too: `--env A=b` ends its options at `A=b`, so `--env` misses its value);
     * `--watch-bus`, `--expose-pids`, `--share-pids` and `--latest-version` change nothing here.
     * The command replaces the script, so its exit status is the caller's (a signal shows as the
     * signal, where flatpak-spawn returns 128 + its number).
     */
    fun flatpakSpawn(hostAllowed: Boolean): String = """
        |#!/bin/sh
        |# Flatdroid's flatpak-spawn: runs the command in this app's own root.
        |# Its own names start with _fs_, so the command's environment keeps its own.
        |_fs_host_allowed=${if (hostAllowed) 1 else 0}
        |_fs_die() { echo "flatpak-spawn: ${'$'}1" >&2; exit 1; }
        |# ${'$'}1's words but ${'$'}2, into _fs_r (no subshell: each costs a fork).
        |_fs_drop() { _fs_r=; for _fs_w in ${'$'}1; do [ "${'$'}_fs_w" = "${'$'}2" ] || _fs_r="${'$'}_fs_r ${'$'}_fs_w"; done; }
        |_fs_valid() { case ${'$'}1 in ''|[0-9]*|*[!A-Za-z0-9_]*) return 1;; esac; }
        |_fs_names= _fs_unsets=
        |_fs_setenv() {
        |  case ${'$'}1 in *=*) ;; *) _fs_die "Invalid env format ${'$'}1";; esac
        |  _fs_name=${'$'}{1%%=*}
        |  _fs_valid "${'$'}_fs_name" || _fs_die "Invalid env format ${'$'}1"
        |  eval "_fs_v_${'$'}_fs_name=\${'$'}{1#*=}"
        |  _fs_drop "${'$'}_fs_names" "${'$'}_fs_name"; _fs_names="${'$'}_fs_r ${'$'}_fs_name"
        |  _fs_drop "${'$'}_fs_unsets" "${'$'}_fs_name"; _fs_unsets=${'$'}_fs_r
        |}
        |_fs_unsetenv() {
        |  _fs_valid "${'$'}1" || _fs_die "Invalid env format ${'$'}1"
        |  _fs_drop "${'$'}_fs_unsets" "${'$'}1"; _fs_unsets="${'$'}_fs_r ${'$'}1"
        |  _fs_drop "${'$'}_fs_names" "${'$'}1"; _fs_names=${'$'}_fs_r
        |}
        |# Entries end in NUL; a newline in a value travels as \001 until it is read.
        |_fs_envfd() {
        |  case ${'$'}1 in ''|*[!0-9]*) _fs_die "Not a valid file descriptor";; esac
        |  _fs_data=${'$'}(tr '\n\0' '\001\n' < "/proc/self/fd/${'$'}1") || _fs_die "Not a valid file descriptor"
        |  _fs_soh=${'$'}(printf '\001')
        |  while IFS= read -r _fs_line; do
        |    case ${'$'}_fs_line in
        |      '') continue;;
        |      *"${'$'}_fs_soh"*) _fs_line=${'$'}(printf '%sx' "${'$'}_fs_line" | tr '\001' '\n'); _fs_line=${'$'}{_fs_line%x};;
        |    esac
        |    _fs_setenv "${'$'}_fs_line"
        |  done <<EOF
        |${'$'}_fs_data
        |EOF
        |}
        |_fs_host=0 _fs_clear=0 _fs_dir=
        |while [ ${'$'}# -gt 0 ]; do
        |  _fs_a=${'$'}1
        |  case ${'$'}_fs_a in -*) ;; *) break;; esac
        |  shift
        |  case ${'$'}_fs_a in
        |    --) break;;
        |    --host) _fs_host=1;;
        |    --clear-env) _fs_clear=1;;
        |    -v|--verbose|--watch-bus|--expose-pids|--share-pids|--latest-version) ;;
        |    --directory=*) _fs_dir=${'$'}{_fs_a#--directory=};;
        |    --env=*) _fs_setenv "${'$'}{_fs_a#--env=}";;
        |    --unset-env=*) _fs_unsetenv "${'$'}{_fs_a#--unset-env=}";;
        |    --env-fd=*) _fs_envfd "${'$'}{_fs_a#--env-fd=}";;
        |    --forward-fd=*) _fs_fd=${'$'}{_fs_a#--forward-fd=}; case ${'$'}_fs_fd in ''|*[!0-9]*) _fs_die "Invalid fd '${'$'}_fs_fd'";; esac;;
        |    --sandbox|--sandbox=*|--sandbox-*|--no-network|--no-network=*|--app-path|--app-path=*|--usr-path|--usr-path=*)
        |      _fs_die "Flatdroid can't start a Flatpak sandbox (${'$'}_fs_a)";;
        |    --directory|--env|--unset-env|--env-fd|--forward-fd) _fs_die "Missing argument for ${'$'}_fs_a";;
        |    -h|--help) echo "Usage: flatpak-spawn [OPTION…] COMMAND [ARGUMENT…]"; exit 0;;
        |    *) _fs_die "Unknown option ${'$'}_fs_a";;
        |  esac
        |done
        |[ ${'$'}# -gt 0 ] || _fs_die "No command specified"
        |if [ "${'$'}_fs_host" = 1 ] && [ "${'$'}_fs_host_allowed" != 1 ]; then
        |  echo "Portal call failed: $NOT_PROVIDED" >&2
        |  exit 1
        |fi
        |# --env still sets them: it is applied after this.
        |[ "${'$'}_fs_host" = 1 ] && unset $HOST_UNSET
        |if [ -n "${'$'}_fs_dir" ]; then cd -- "${'$'}_fs_dir" || exit 1; fi
        |$PLAIN_COMMAND
        |# env's options and the variables go before the command: the command and its arguments are rotated behind them.
        |_fs_m=${'$'}#
        |for _fs_name in ${'$'}_fs_names; do eval "set -- \"\${'$'}@\" \"${'$'}_fs_name=\${'$'}{_fs_v_${'$'}_fs_name}\""; done
        |while [ "${'$'}_fs_m" -gt 0 ]; do _fs_a=${'$'}1; shift; set -- "${'$'}@" "${'$'}_fs_a"; _fs_m=${'$'}((_fs_m - 1)); done
        |for _fs_name in ${'$'}_fs_unsets; do set -- -u "${'$'}_fs_name" "${'$'}@"; done
        |if [ "${'$'}_fs_clear" = 1 ]; then set -- /usr/bin/env -i "${'$'}@"; else set -- /usr/bin/env "${'$'}@"; fi
        |$NEW_SESSION
        |exec "${'$'}@"
        |""".trimMargin()

    /**
     * `host-spawn`: Go-style flags (`-x` or `--x`, values as `-x value` or `-x=value`) up to the
     * first other argument or `--`; `-cwd`; `-env`, `-pty` and `-no-pty` change nothing here (the
     * command keeps the app's environment but [HOST_UNSET], and the caller's terminal). No command: the user's shell.
     * Called under another name (a link to it standing in for a host tool such as `podman`), it
     * refuses: that tool doesn't exist here, and running the name again would loop.
     */
    fun hostSpawn(hostAllowed: Boolean): String = """
        |#!/bin/sh
        |# Flatdroid's host-spawn: runs the command in this app's own root.
        |# Its own names start with _fs_, so the command's environment keeps its own.
        |_fs_host_allowed=${if (hostAllowed) 1 else 0}
        |_fs_me=${'$'}{0##*/}
        |case ${'$'}_fs_me in
        |  host-spawn*) ;;
        |  *) echo "${'$'}_fs_me: commands outside the app aren't available in Flatdroid" >&2; exit 127;;
        |esac
        |_fs_usage() { echo "Usage: ${'$'}_fs_me [options] [ COMMAND [ arguments... ] ]" >&2; exit 0; }
        |_fs_cwd=
        |while [ ${'$'}# -gt 0 ]; do
        |  _fs_a=${'$'}1
        |  case ${'$'}_fs_a in
        |    --) shift; break;;
        |    -) break;;
        |    -pty|--pty|-pty=*|--pty=*|-no-pty|--no-pty|-no-pty=*|--no-pty=*) ;;
        |    -version|--version) echo flatdroid; exit 0;;
        |    -env=*|--env=*) ;;
        |    -cwd=*|--cwd=*) _fs_cwd=${'$'}{_fs_a#*=};;
        |    -env|--env|-cwd|--cwd)
        |      [ ${'$'}# -ge 2 ] || _fs_usage
        |      case ${'$'}_fs_a in *cwd) _fs_cwd=${'$'}2;; esac
        |      shift;;
        |    -*) _fs_usage;;
        |    *) break;;
        |  esac
        |  shift
        |done
        |if [ "${'$'}_fs_host_allowed" != 1 ]; then
        |  echo "$NOT_PROVIDED" >&2
        |  exit 127
        |fi
        |unset $HOST_UNSET
        |if [ -n "${'$'}_fs_cwd" ]; then cd -- "${'$'}_fs_cwd" || exit 127; fi
        |[ ${'$'}# -gt 0 ] || set -- "${'$'}{SHELL:-/bin/sh}"
        |$PLAIN_COMMAND
        |$NEW_SESSION
        |exec "${'$'}@"
        |""".trimMargin()

    /**
     * A command `env`, `setsid` or `exec` would take for one of their options (its name starts with
     * `-`), or `env` for a variable (it holds `=`): given as its path instead (found as `execvp`
     * finds it), and run through `sh` when that holds `=`. Not found: exit status 127, as a shell.
     */
    private val PLAIN_COMMAND = "case ${'$'}1 in -*|*=*) " +
        "_fs_c=${'$'}(command -v -- \"${'$'}1\") || { echo \"${'$'}{0##*/}: ${'$'}1: command not found\" >&2; exit 127; }; " +
        "case ${'$'}_fs_c in /*) ;; *) _fs_c=./${'$'}_fs_c;; esac; shift; set -- \"${'$'}_fs_c\" \"${'$'}@\"; " +
        "case ${'$'}_fs_c in *=*) set -- /bin/sh -euc 'exec \"${'$'}@\"' sh \"${'$'}@\";; esac;; esac"

    /**
     * As Flatpak's host helper starts a command: with the terminal on its standard input (if any)
     * as its controlling terminal, so a shell has job control. A caller without a controlling
     * terminal gets a session of its own with it (`-w` waits when setsid has to fork, as for a
     * process group leader, and passes the exit status on). A caller that has one keeps it (the
     * seventh field of `/proc/<pid>/stat`, after the name, is not 0): a terminal belongs to one
     * session, and a new one couldn't take it (setsid would fail and run nothing), as for a
     * terminal emulator running the stand-in as its shell, or a shell running it as a command.
     */
    private val NEW_SESSION = "if [ -t 0 ] && [ -x /usr/bin/setsid ]; then _fs_ctty() { _fs_ctty=${'$'}5; }; _fs_ctty=0; " +
        "read -r _fs_stat < /proc/${'$'}${'$'}/stat && _fs_ctty ${'$'}{_fs_stat##*) }; " +
        "[ \"${'$'}_fs_ctty\" = 0 ] && set -- /usr/bin/setsid -w -c \"${'$'}@\"; fi"
}
