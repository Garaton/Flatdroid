package com.garathon.flatdroid.session.bus

import com.garathon.flatdroid.proot.Fdsec
import java.io.FileDescriptor

/**
 * Flatpak's "run on the host" over D-Bus: `org.freedesktop.Flatpak.Development` under
 * `org.freedesktop.Flatpak`, which some terminals call themselves with their terminal's
 * descriptors instead of the `flatpak-spawn` tool. As the tool's stand-ins
 * ([com.garathon.flatdroid.launcher.HostCommands]), there is no host: [commands] runs each command
 * in the app's own root, behind the same proot and filter, so nothing is widened. Served only to
 * apps that may ask for the host (the stand-ins' rule); the app's bus proxy keeps the others away.
 *
 * Standard input, output and error are taken: when all three are one pseudo-terminal it becomes
 * the command's controlling terminal, as Flatpak's helper makes it; otherwise each given one is
 * the command's as it is (pipes for a query's output, say), and a missing one `/dev/null`. Other
 * descriptor numbers are refused (NotSupported): a new proot can't be handed them (verify which
 * apps pass them). At most [MAX_RUNNING] run or start at once. A command starts on a thread of its
 * own ([Commands.start]), so the bus goes on meanwhile; its reply follows from there once it runs
 * (or couldn't start), and `HostCommandExited` after it, to the caller alone ([send]). With the
 * watch-bus flag a command ends when its caller leaves the bus, also while it was starting.
 */
class HostCommandBus(
    private val commands: Commands,
    /** What a descriptor the call brought is open on (`/dev/pts/3`, `pipe:[…]`), or null. */
    private val pathOf: (FileDescriptor) -> String?,
) {
    /** What starts and signals the commands. */
    interface Commands {
        /**
         * Starts [command] on a thread of its own, which calls [started] with its pid (null when it
         * couldn't start), then [exited] with its pid and wait status once it ends. False when it
         * can't be tried at all; then neither is called.
         */
        fun start(command: Command, started: (Int?) -> Unit, exited: (pid: Int, status: Int) -> Unit): Boolean

        /** Sends [signal] to [pid] (to its process group with [group]); whether it was sent. */
        fun signal(pid: Int, signal: Int, group: Boolean): Boolean
    }

    /**
     * A command to run in [cwd] (empty: the home), with [env] over the app's own, or alone with
     * [clearEnv]: on the terminal [tty], or else with [stdio]'s descriptors (by number, 0 to 2),
     * open only while [Commands.start] runs.
     */
    data class Command(
        val cwd: String,
        val argv: List<String>,
        val env: Map<String, String>,
        val clearEnv: Boolean,
        val tty: String?,
        val stdio: Map<Int, FileDescriptor> = emptyMap(),
    )

    /** How replies and signals reach the bus; set once the connection is open. Called from the commands' threads too. */
    @Volatile var send: (Message) -> Unit = {}

    private class Running(val owner: String, val watchBus: Boolean)
    private val lock = Any()
    private val running = HashMap<Int, Running>()
    /** Commands started and not running yet. */
    private var starting = 0
    /** Callers that left the bus while a command was starting: a watched one of theirs ends once it runs. */
    private val gone = HashSet<String>()

    /** The reply to [call]; null for a HostCommand that is starting, whose reply follows ([send]). */
    fun answer(call: Message, fds: List<FileDescriptor>): Message? = when (call.member) {
        "HostCommand" -> hostCommand(call, fds)
        "HostCommandSignal" -> {
            if (call.signature != "uub") invalid(call, "Expected (uub)")
            else {
                val pid = (call.body[0] as UInt).toInt()
                val owner = synchronized(lock) { running[pid]?.owner }
                if (owner == null || owner != call.sender) call.error(INVALID_ARGS, "No such pid $pid")
                else {
                    commands.signal(pid, (call.body[1] as UInt).toInt(), call.body[2] as Boolean)
                    call.reply()
                }
            }
        }
        else -> call.error(UNKNOWN_METHOD, "Unknown method ${call.member} on $IFACE")
    }

    private fun hostCommand(call: Message, fds: List<FileDescriptor>): Message? {
        if (call.signature != "ayaaya{uh}a{ss}u") return invalid(call, "Expected (ayaaya{uh}a{ss}u)")
        val cwd = bytes(call.body[0])
        val argv = (call.body[1] as List<*>).map(::bytes)
        if (argv.isEmpty() || argv[0].isEmpty()) return invalid(call, "No command given")
        if ('=' in argv[0]) return invalid(call, "Not a command: ${argv[0]}")
        val env = (call.body[3] as Map<*, *>).entries.associate { (k, v) -> k as String to v as String }
        if (env.keys.any { !ENV_NAME.matches(it) }) return invalid(call, "Invalid variable name")
        val flags = call.body[4] as UInt
        val given = (call.body[2] as Map<*, *>).entries.associate { (k, v) -> (k as UInt).toInt() to fds.getOrNull((v as UnixFd).index) }
        if (given.values.any { it == null }) return invalid(call, "No file descriptor")
        if (!STANDARD.containsAll(given.keys)) {
            return call.error(NOT_SUPPORTED, "Flatdroid passes only standard input, output and error to host commands")
        }
        val stdio = given.mapValues { it.value!! }
        val tty = stdio.values.map(pathOf).toSet().singleOrNull()?.takeIf { stdio.keys == STANDARD && Fdsec.isTerminal(it) }
        val owner = call.sender ?: return invalid(call, "No sender")
        val watchBus = flags and WATCH_BUS != 0u
        synchronized(lock) {
            if (running.size + starting >= MAX_RUNNING) return call.error(LIMITS_EXCEEDED, "Too many host commands")
            starting++
        }
        val command = Command(cwd, argv, env, clearEnv = flags and CLEAR_ENV != 0u, tty = tty, stdio = if (tty == null) stdio else emptyMap())
        val tried = commands.start(
            command,
            started = { pid -> started(call, owner, watchBus, pid) },
            exited = { pid, status -> send(exited(pid, owner, status)) },
        )
        if (tried) return null
        synchronized(lock) { doneStarting() }
        return call.error(FAILED, "The command could not start")
    }

    /** [call]'s command runs as [pid] (null: it couldn't start): its reply, and its end if its caller already left. */
    private fun started(call: Message, owner: String, watchBus: Boolean, pid: Int?) {
        val ownerLeft = synchronized(lock) {
            if (pid != null) running[pid] = Running(owner, watchBus)
            (owner in gone).also { doneStarting() }
        }
        if (call.expectsReply) send(if (pid == null) call.error(FAILED, "The command could not start") else call.reply("u", pid.toUInt()))
        if (pid != null && watchBus && ownerLeft) commands.signal(pid, SIGKILL, group = true)
    }

    /** Under [lock]. */
    private fun doneStarting() {
        starting--
        if (starting == 0) gone.clear()
    }

    private fun exited(pid: Int, owner: String, status: Int): Message {
        synchronized(lock) { running.remove(pid) }
        return Message.signal(PATH, IFACE, "HostCommandExited", "uu", pid.toUInt(), status.toUInt()).copy(destination = owner)
    }

    /** [name] left the bus: the commands it started to be watched end with it (Flatpak sends them SIGKILL). */
    fun left(name: String) {
        val pids = synchronized(lock) {
            if (starting > 0) gone += name
            running.filterValues { it.owner == name && it.watchBus }.keys.toList()
        }
        for (pid in pids) commands.signal(pid, SIGKILL, group = true)
    }

    /** Every command still running. */
    fun runningPids(): Set<Int> = synchronized(lock) { running.keys.toSet() }

    private fun invalid(call: Message, text: String) = call.error(INVALID_ARGS, text)

    /** A byte string as Flatpak sends paths and arguments: NUL-terminated. */
    private fun bytes(value: Any?): String =
        (value as ByteArray).decodeToString().trimEnd('\u0000')

    companion object {
        const val NAME = "org.freedesktop.Flatpak"
        const val IFACE = "org.freedesktop.Flatpak.Development"
        const val PATH = "/org/freedesktop/Flatpak/Development"
        const val VERSION = 1u
        const val MAX_RUNNING = 8

        /** HostCommand's flags. */
        const val CLEAR_ENV = 1u
        const val WATCH_BUS = 2u

        private val STANDARD = setOf(0, 1, 2)
        private const val SIGKILL = 9
        private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        private const val UNKNOWN_METHOD = "org.freedesktop.DBus.Error.UnknownMethod"
        private const val INVALID_ARGS = "org.freedesktop.DBus.Error.InvalidArgs"
        private const val NOT_SUPPORTED = "org.freedesktop.DBus.Error.NotSupported"
        private const val LIMITS_EXCEEDED = "org.freedesktop.DBus.Error.LimitsExceeded"
        private const val FAILED = "org.freedesktop.DBus.Error.Failed"

        val INTROSPECTION = """
            | <interface name="$IFACE">
            |  <method name="HostCommand"><arg type="ay" name="cwd_path" direction="in"/><arg type="aay" name="argv" direction="in"/><arg type="a{uh}" name="fds" direction="in"/><arg type="a{ss}" name="envs" direction="in"/><arg type="u" name="flags" direction="in"/><arg type="u" name="pid" direction="out"/></method>
            |  <method name="HostCommandSignal"><arg type="u" name="pid" direction="in"/><arg type="u" name="signal" direction="in"/><arg type="b" name="to_process_group" direction="in"/></method>
            |  <signal name="HostCommandExited"><arg type="u" name="pid"/><arg type="u" name="exit_status"/></signal>
            |  <property name="version" type="u" access="read"/>
            | </interface>
            |""".trimMargin()
    }
}
