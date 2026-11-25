package com.garathon.flatdroid.session.bus

import java.io.FileDescriptor

/**
 * What Flatdroid serves on an app's session bus, answered on the bus's reader thread. As
 * [Role.PORTAL] it owns `org.freedesktop.portal.Desktop` itself and answers the app's calls. As
 * [Role.BACKEND] the real `xdg-desktop-portal` (run for the camera) owns that name and asks
 * Flatdroid, under [BACKEND_NAME], for what only Flatdroid knows (the settings), serving the rest
 * itself. Links go to Android ([links]): the app's `OpenURI` calls here, or in [Role.BACKEND] the
 * real portal's, which launches the one handler it has ([LINK_HANDLER]), whose command calls
 * [LINKS] `Open` here.
 *
 * Once the portal's name has an owner, GLib in a root with `/.flatpak-info` takes its network
 * state and proxies from it: the network monitor and proxy resolver are answered here so those
 * apps keep their network ([network], `direct://`).
 *
 * In both roles Flatdroid also owns `org.freedesktop.ScreenSaver` and `org.freedesktop.FileManager1`:
 * inhibits ([inhibitions], with the portal's or its backend's `Inhibit`), released when the app
 * asks, closes the request, or leaves the bus; and folders shown in Android's Files app
 * ([links], the app's paths mapped to the host's by [hostPath]).
 *
 * Inhibits also come through the names GNOME and KDE apps use: `org.gnome.SessionManager`'s
 * `Inhibit`, `org.freedesktop.PowerManagement` (the processor), and `org.gnome.ScreenSaver`
 * (never active); `org.gnome.Mutter.IdleMonitor` answers the time since the viewer's last input
 * ([idleMillis]).
 *
 * With [notifications], the app's notifications ([Notifications]): `org.freedesktop.Notifications`,
 * and the Notification portal (or, as [Role.BACKEND], the real portal's backend for it).
 *
 * As [Role.PORTAL], with [cameraAsked], the camera portal is answered too, only to learn that the
 * app asks for it: a camera is present, and each `AccessCamera` is refused after [cameraAsked]
 * is called (the real portal runs from the app's next launch).
 */
class Portal(
    private val role: Role,
    /** The settings by namespace and key, read at each call. */
    private val settings: () -> Map<String, Map<String, Variant>>,
    /** The network as the app may use it, read at each call. */
    private val network: () -> NetworkStatus,
    private val links: Links,
    private val inhibitions: Inhibitions = Inhibitions {},
    /** The host path behind a path in the app's root, or null. */
    private val hostPath: (String) -> String? = { null },
    /** The app asked for the camera portal; null leaves the portal's camera unanswered. */
    private val cameraAsked: (() -> Unit)? = null,
    /** The app's notifications; null serves none (the app's Notifications permission is off). */
    private val notifications: Notifications? = null,
    /** Milliseconds since the user's last input. */
    private val idleMillis: () -> Long = { 0L },
) {
    enum class Role { PORTAL, BACKEND }

    /** What opens links in Android. Each answers whether it was shown. */
    interface Links {
        /** [uri], whose scheme [LinkSchemes] allows. */
        fun openUri(uri: String): Boolean
        /** The file open at [fd] (the call's, closed after it), for writing only with [writable]. */
        fun openFile(fd: FileDescriptor, writable: Boolean): Boolean
        /** The folder open at [fd] (the call's, closed after it). */
        fun openDirectory(fd: FileDescriptor): Boolean
        /** The folder at the host's [path]. */
        fun showFolder(path: String): Boolean
    }

    /**
     * GLib's connectivity levels: 1 local only, 2 limited, 3 behind a captive portal, 4 full.
     */
    data class NetworkStatus(val available: Boolean, val metered: Boolean, val connectivity: UInt)

    /** The names Flatdroid owns in this [role]. */
    val names: List<String> = when (role) {
        Role.PORTAL -> listOf(PORTAL_NAME)
        Role.BACKEND -> listOf(BACKEND_NAME)
    } + listOf(SCREENSAVER, FILE_MANAGER) + INHIBIT_NAMES + listOfNotNull(Notifications.NAME.takeIf { notifications != null })

    /**
     * What to send for [call], whose file descriptors are [fds]: its reply (none when it asks for
     * none), then any signal it causes (a request's `Response` goes after the reply that names
     * the request, as the real portal sends it).
     */
    fun handle(call: Message, fds: List<FileDescriptor> = emptyList()): List<Message> {
        if (call.type == DBusWire.SIGNAL) {
            busSignal(call)
            return emptyList()
        }
        if (call.type != DBusWire.METHOD_CALL) return emptyList()
        val signals = ArrayList<Message>()
        val reply = try {
            answer(call, fds, signals)
        } catch (e: ArgumentException) {
            call.error(INVALID_ARGS, e.message ?: "Invalid arguments")
        }
        return listOfNotNull(reply.takeIf { call.expectsReply }) + signals
    }

    private fun answer(call: Message, fds: List<FileDescriptor>, signals: MutableList<Message>): Message {
        val iface = call.iface
        val member = call.member ?: return call.error(UNKNOWN_METHOD, "No method")
        if (iface == PEER) {
            return if (member == "Ping") call.args("").let { call.reply() } else call.error(UNKNOWN_METHOD, "Unknown method $member")
        }
        // Requests other than inhibits answer at once, so closing one has nothing left to stop.
        if (call.path?.startsWith("$PATH/request/") == true && (iface == REQUEST || iface == IMPL_REQUEST) && member == "Close") {
            return call.args("").let {
                inhibitions.remove(call.sender ?: "", call.path)
                call.reply()
            }
        }
        val interfaces = interfaces(call.path) ?: return call.error(UNKNOWN_OBJECT, "No object at ${call.path}")
        if (iface != INTROSPECTABLE && iface != PROPERTIES && iface != null && iface !in interfaces) return unknownInterface(call)
        return when (iface) {
            INTROSPECTABLE -> if (member == "Introspect") call.args("").let { call.reply("s", introspection(interfaces)) } else unknown(call)
            PROPERTIES -> properties(call, interfaces)
            SETTINGS, IMPL_SETTINGS -> settings(call)
            NETWORK_MONITOR -> networkMonitor(call)
            PROXY_RESOLVER -> proxyResolver(call)
            OPEN_URI -> openUri(call, fds, signals)
            IMPL_APP_CHOOSER -> appChooser(call)
            LINKS -> handlerLink(call)
            INHIBIT -> inhibit(call)
            IMPL_INHIBIT -> implInhibit(call)
            SCREENSAVER_IFACE -> screenSaver(call)
            GNOME_SESSION_MANAGER -> sessionManager(call)
            POWER_MANAGEMENT_IFACE -> powerManagement(call)
            GNOME_SCREENSAVER -> gnomeScreenSaver(call)
            IDLE_MONITOR -> idleMonitor(call)
            Notifications.IFACE -> freedesktopNotifications(call, signals)
            Notifications.PORTAL_IFACE, Notifications.IMPL_IFACE -> portalNotifications(call, fds)
            FILE_MANAGER_IFACE -> fileManager(call)
            CAMERA -> camera(call, signals)
            null -> call.error(UNKNOWN_METHOD, "No interface")
            else -> unknownInterface(call)
        }
    }

    /** A connection that left the bus releases what it held. Only the bus itself sends this. */
    private fun busSignal(signal: Message) {
        if (signal.sender != BUS || signal.iface != BUS || signal.member != "NameOwnerChanged" || signal.signature != "sss") return
        val (name, _, newOwner) = signal.body.map { it as String }
        // Notifications stay: a sender may exit right after one (notify-send does), as on a desktop.
        if (name.startsWith(":") && newOwner.isEmpty()) inhibitions.removeOwner(name)
    }

    /** The `SettingChanged` signal for [key] in [namespace], now [value]. */
    fun settingChanged(namespace: String, key: String, value: Variant): Message =
        Message.signal(PATH, if (role == Role.PORTAL) SETTINGS else IMPL_SETTINGS, "SettingChanged", "ssv", namespace, key, value)

    /** The network monitor's `changed` signal, after which GLib reads the status again; null when this role doesn't serve it. */
    fun networkChanged(): Message? = if (role == Role.PORTAL) Message.signal(PATH, NETWORK_MONITOR, "changed") else null

    /** The interfaces at [path] with their `version` property (0 for those without one), or null for no object there. */
    private fun interfaces(path: String?): Map<String, UInt>? = when (path) {
        PATH -> when (role) {
            Role.PORTAL -> mapOf(SETTINGS to 2u, NETWORK_MONITOR to 3u, PROXY_RESOLVER to 1u, OPEN_URI to 5u, INHIBIT to 3u) +
                (if (cameraAsked != null) mapOf(CAMERA to 1u) else emptyMap()) +
                (if (notifications != null) mapOf(Notifications.PORTAL_IFACE to 2u) else emptyMap())
            Role.BACKEND -> mapOf(IMPL_SETTINGS to 1u, IMPL_APP_CHOOSER to 2u, LINKS to 1u, IMPL_INHIBIT to 3u) +
                (if (notifications != null) mapOf(Notifications.IMPL_IFACE to 1u) else emptyMap())
        }
        in SCREENSAVER_PATHS -> mapOf(SCREENSAVER_IFACE to 0u)
        GNOME_SESSION_MANAGER_PATH -> mapOf(GNOME_SESSION_MANAGER to 0u)
        POWER_MANAGEMENT_PATH -> mapOf(POWER_MANAGEMENT_IFACE to 0u)
        GNOME_SCREENSAVER_PATH -> mapOf(GNOME_SCREENSAVER to 0u)
        IDLE_MONITOR_PATH -> mapOf(IDLE_MONITOR to 0u)
        Notifications.PATH -> if (notifications != null) mapOf(Notifications.IFACE to 0u) else null
        FILE_MANAGER_PATH -> mapOf(FILE_MANAGER_IFACE to 0u)
        else -> null
    }

    private fun properties(call: Message, interfaces: Map<String, UInt>): Message = when (call.member) {
        "Get" -> {
            val (iface, name) = call.strings("ss")
            val version = interfaces[iface] ?: return call.error(UNKNOWN_INTERFACE, "No interface $iface")
            val value = if (name == "version" && version != 0u) Variant("u", version) else extraProperties(iface)[name]
            if (value != null) call.reply("v", value) else call.error(UNKNOWN_PROPERTY, "No property $name")
        }
        "GetAll" -> {
            val (iface) = call.strings("s")
            val version = interfaces[iface] ?: return call.error(UNKNOWN_INTERFACE, "No interface $iface")
            call.reply("a{sv}", (if (version == 0u) emptyMap() else mapOf("version" to Variant("u", version))) + extraProperties(iface))
        }
        "Set" -> call.error(PROPERTY_READ_ONLY, "Properties here are read-only")
        else -> unknown(call)
    }

    /** Properties beside `version`. */
    private fun extraProperties(iface: String): Map<String, Variant> = when (iface) {
        CAMERA -> mapOf("IsCameraPresent" to Variant("b", true))
        Notifications.PORTAL_IFACE -> mapOf("SupportedOptions" to Variant("a{sv}", emptyMap<String, Variant>()))
        else -> emptyMap()
    }

    /**
     * The camera portal while it isn't run ([cameraAsked]): each `AccessCamera` gets its request
     * and a `Response` of 2 (refused), and `OpenPipeWireRemote` an error.
     */
    private fun camera(call: Message, signals: MutableList<Message>): Message = when (call.member) {
        "AccessCamera" -> {
            val options = call.args("a{sv}")[0] as Map<*, *>
            cameraAsked?.invoke()
            val handle = requestHandle(call, options)
            signals += Message.signal(handle, REQUEST, "Response", "ua{sv}", RESPONSE_OTHER, emptyMap<String, Variant>())
                .copy(destination = call.sender)
            call.reply("o", handle)
        }
        "OpenPipeWireRemote" -> call.args("a{sv}").let {
            cameraAsked?.invoke()
            call.error(NOT_ALLOWED, "No camera in this session")
        }
        else -> unknown(call)
    }

    private fun settings(call: Message): Message = when (call.member) {
        "ReadAll" -> {
            @Suppress("UNCHECKED_CAST")
            val namespaces = call.args("as")[0] as List<String>
            val all = settings()
            call.reply("a{sa{sv}}", all.filterKeys { namespace -> matches(namespaces, namespace) })
        }
        // Version 1 wrapped the value in a second variant, and the backend's Read answers the value itself.
        "Read" -> read(call) { if (call.iface == SETTINGS) Variant("v", it) else it }
        "ReadOne" -> if (call.iface == SETTINGS) read(call) { it } else unknown(call)
        else -> unknown(call)
    }

    private fun read(call: Message, wrap: (Variant) -> Variant): Message {
        val (namespace, key) = call.strings("ss")
        val value = settings()[namespace]?.get(key) ?: return call.error(NOT_FOUND, "Requested setting not found")
        return call.reply("v", wrap(value))
    }

    private fun networkMonitor(call: Message): Message {
        val status = network()
        return when (call.member) {
            "GetAvailable" -> call.args("").let { call.reply("b", status.available) }
            "GetMetered" -> call.args("").let { call.reply("b", status.metered) }
            "GetConnectivity" -> call.args("").let { call.reply("u", status.connectivity) }
            "GetStatus" -> call.args("").let {
                call.reply(
                    "a{sv}",
                    mapOf(
                        "available" to Variant("b", status.available),
                        "metered" to Variant("b", status.metered),
                        "connectivity" to Variant("u", status.connectivity),
                    ),
                )
            }
            // Flatdroid doesn't look the host up: whether there is a network at all.
            "CanReach" -> call.args("su").let { call.reply("b", status.available) }
            else -> unknown(call)
        }
    }

    private fun proxyResolver(call: Message): Message = when (call.member) {
        "Lookup" -> call.args("s").let { call.reply("as", listOf(DIRECT)) }
        else -> unknown(call)
    }

    /**
     * The app's links. Each call gets a request object at once and its `Response` right after:
     * 0 when Android showed the link, 2 when it was refused or couldn't be shown.
     */
    private fun openUri(call: Message, fds: List<FileDescriptor>, signals: MutableList<Message>): Message {
        fun options(index: Int) = call.body[index] as Map<*, *>
        fun fd(index: Int) = fds.getOrNull((call.body[index] as UnixFd).index) ?: throw ArgumentException("No file descriptor")
        val (shown, options) = when (call.member) {
            "OpenURI" -> {
                val uri = call.args("ssa{sv}")[1] as String
                (LinkSchemes.allowed(uri) && links.openUri(uri)) to options(2)
            }
            "OpenFile" -> {
                call.args("sha{sv}")
                val writable = (options(2)["writable"] as? Variant)?.value == true
                links.openFile(fd(1), writable) to options(2)
            }
            "OpenDirectory" -> {
                call.args("sha{sv}")
                links.openDirectory(fd(1)) to options(2)
            }
            "SchemeSupported" -> {
                val scheme = call.args("sa{sv}")[0] as String
                return call.reply("b", LinkSchemes.allowedScheme(scheme))
            }
            else -> return unknown(call)
        }
        val handle = requestHandle(call, options)
        signals += Message.signal(handle, REQUEST, "Response", "ua{sv}", if (shown) RESPONSE_SUCCESS else RESPONSE_OTHER, emptyMap<String, Variant>())
            .copy(destination = call.sender)
        return call.reply("o", handle)
    }

    private var requests = 0

    /**
     * The real portal asking which app opens a link: only [LINK_HANDLER] is offered here, so it
     * asks only if something else is installed beside it; that one is never chosen.
     */
    private fun appChooser(call: Message): Message = when (call.member) {
        "ChooseApplication" -> {
            @Suppress("UNCHECKED_CAST")
            val choices = call.args("ossasa{sv}")[3] as List<String>
            if (LINK_HANDLER in choices) call.reply("ua{sv}", RESPONSE_SUCCESS, mapOf("choice" to Variant("s", LINK_HANDLER)))
            else call.reply("ua{sv}", RESPONSE_CANCELLED, emptyMap<String, Variant>())
        }
        "UpdateChoices" -> call.args("oas").let { call.reply() }
        else -> unknown(call)
    }

    /** [LINK_HANDLER]'s command, run by the real portal among the camera's helpers. */
    private fun handlerLink(call: Message): Message = when (call.member) {
        "Open" -> {
            val uri = call.strings("s")[0]
            call.reply("b", LinkSchemes.allowed(uri) && links.openUri(uri))
        }
        else -> unknown(call)
    }

    /**
     * The portal's `Inhibit`: held until the app closes the request it names, or leaves the bus.
     * No `Response`: the request stands for the hold.
     */
    private fun inhibit(call: Message): Message = when (call.member) {
        "Inhibit" -> {
            val args = call.args("sua{sv}")
            val handle = requestHandle(call, args[2] as Map<*, *>)
            if (inhibitions.add(call.sender ?: "", handle, args[1] as UInt)) call.reply("o", handle) else call.error(LIMITS_EXCEEDED, "Too many inhibits")
        }
        else -> unknown(call)
    }

    /** The real portal's backend call for an app's `Inhibit`: held until it closes [handle]'s request. */
    private fun implInhibit(call: Message): Message = when (call.member) {
        "Inhibit" -> {
            val args = call.args("ossua{sv}")
            val handle = args[0] as String
            if (!handle.startsWith("$PATH/request/")) throw ArgumentException("Not a request: $handle")
            if (inhibitions.add(call.sender ?: "", handle, args[3] as UInt)) call.reply() else call.error(LIMITS_EXCEEDED, "Too many inhibits")
        }
        else -> unknown(call)
    }

    /** `org.freedesktop.ScreenSaver`: an inhibit keeps the screen on; the screen is never reported as blanked. */
    private fun screenSaver(call: Message): Message = when (call.member) {
        "Inhibit" -> {
            call.strings("ss")
            val cookie = inhibitions.nextCookie()
            if (!inhibitions.add(call.sender ?: "", "$SCREENSAVER:$cookie", Inhibitions.IDLE)) {
                call.error(LIMITS_EXCEEDED, "Too many inhibits")
            } else {
                call.reply("u", cookie)
            }
        }
        "UnInhibit" -> {
            val cookie = call.args("u")[0] as UInt
            inhibitions.remove(call.sender ?: "", "$SCREENSAVER:$cookie")
            call.reply()
        }
        "SimulateUserActivity" -> call.args("").let { call.reply() }
        "GetActive" -> call.args("").let { call.reply("b", false) }
        else -> unknown(call)
    }

    /** `org.gnome.SessionManager`'s inhibits, whose flags are the portal's. */
    private fun sessionManager(call: Message): Message = when (call.member) {
        "Inhibit" -> {
            val flags = call.args("susu")[3] as UInt
            hold(call, GNOME_SESSION_MANAGER, flags)
        }
        "Uninhibit" -> release(call, GNOME_SESSION_MANAGER)
        "IsInhibited" -> call.reply("b", inhibitions.holding(call.args("u")[0] as UInt))
        else -> unknown(call)
    }

    /** `org.freedesktop.PowerManagement.Inhibit`: the processor stays awake. */
    private fun powerManagement(call: Message): Message = when (call.member) {
        "Inhibit" -> {
            call.strings("ss")
            hold(call, POWER_MANAGEMENT_IFACE, Inhibitions.SUSPEND)
        }
        "UnInhibit" -> release(call, POWER_MANAGEMENT_IFACE)
        "HasInhibit" -> call.args("").let { call.reply("b", inhibitions.holding(Inhibitions.SUSPEND)) }
        else -> unknown(call)
    }

    /** A new cookie's hold of [flags] under [prefix], answered with the cookie. */
    private fun hold(call: Message, prefix: String, flags: UInt): Message {
        val cookie = inhibitions.nextCookie()
        return if (!inhibitions.add(call.sender ?: "", "$prefix:$cookie", flags)) call.error(LIMITS_EXCEEDED, "Too many inhibits")
        else call.reply("u", cookie)
    }

    private fun release(call: Message, prefix: String): Message {
        val cookie = call.args("u")[0] as UInt
        inhibitions.remove(call.sender ?: "", "$prefix:$cookie")
        return call.reply()
    }

    /** `org.gnome.ScreenSaver`: never active, and nothing to lock (Android's lock screen is its own). */
    private fun gnomeScreenSaver(call: Message): Message = when (call.member) {
        "GetActive" -> call.args("").let { call.reply("b", false) }
        "GetActiveTime" -> call.args("").let { call.reply("u", 0u) }
        "SetActive" -> call.args("b").let { call.reply() }
        "Lock", "SimulateUserActivity" -> call.args("").let { call.reply() }
        else -> unknown(call)
    }

    /** `org.gnome.Mutter.IdleMonitor`: the time since the viewer's last input. Watches are accepted and never fire (verify which apps need them). */
    private fun idleMonitor(call: Message): Message = when (call.member) {
        "GetIdletime" -> call.args("").let { call.reply("t", idleMillis().coerceAtLeast(0).toULong()) }
        "AddIdleWatch" -> call.args("t").let { call.reply("u", ++watches) }
        "AddUserActiveWatch" -> call.args("").let { call.reply("u", ++watches) }
        "RemoveWatch" -> call.args("u").let { call.reply() }
        else -> unknown(call)
    }

    private var watches = 0u

    /** `org.freedesktop.Notifications` ([notifications]). */
    private fun freedesktopNotifications(call: Message, signals: MutableList<Message>): Message {
        val shown = notifications ?: return unknownInterface(call)
        val owner = call.sender ?: ""
        return when (call.member) {
            "Notify" -> {
                val a = call.args("susssasa{sv}i")
                @Suppress("UNCHECKED_CAST")
                val id = shown.notify(owner, a[1] as UInt, a[2] as String, a[3] as String, a[4] as String, a[5] as List<String>, a[6] as Map<*, *>, a[7] as Int)
                if (id == null) call.error(LIMITS_EXCEEDED, "Too many notifications") else call.reply("u", id)
            }
            "CloseNotification" -> {
                signals += shown.close(owner, call.args("u")[0] as UInt)
                call.reply()
            }
            "GetCapabilities" -> call.args("").let { call.reply("as", Notifications.CAPABILITIES) }
            "GetServerInformation" -> call.args("").let { call.reply("ssss", "Flatdroid", "Flatdroid", "1", "1.2") }
            else -> unknown(call)
        }
    }

    /** The Notification portal, or as [Role.BACKEND] its backend (the app's ID first). */
    private fun portalNotifications(call: Message, fds: List<FileDescriptor>): Message {
        val shown = notifications ?: return unknownInterface(call)
        val owner = call.sender ?: ""
        val backend = call.iface == Notifications.IMPL_IFACE
        return when (call.member) {
            "AddNotification" -> {
                val a = if (backend) call.args("ssa{sv}") else call.args("sa{sv}")
                val added = if (backend) shown.add(owner, a[1] as String, a[2] as Map<*, *>, fds, appId = a[0] as String)
                else shown.add(owner, a[0] as String, a[1] as Map<*, *>, fds)
                if (added) call.reply() else call.error(LIMITS_EXCEEDED, "Too many notifications")
            }
            "RemoveNotification" -> {
                val a = if (backend) call.strings("ss").drop(1) else call.strings("s")
                shown.removeFromPortal(owner, a[0])
                call.reply()
            }
            else -> unknown(call)
        }
    }

    /**
     * `org.freedesktop.FileManager1`: Android's Files app at the first folder shown, or at the
     * folder holding the first item (it can't point at a file). Only folders the Files app can
     * open, in an app home or shared storage.
     */
    private fun fileManager(call: Message): Message = when (call.member) {
        "ShowFolders", "ShowItems", "ShowItemProperties" -> {
            @Suppress("UNCHECKED_CAST")
            val uris = call.args("ass")[0] as List<String>
            val folders = uris.asSequence().take(MAX_ITEMS).mapNotNull(::guestPath)
                .map { if (call.member == "ShowFolders") it else it.substringBeforeLast('/').ifEmpty { "/" } }
            folders.mapNotNull(hostPath).firstOrNull()?.let { links.showFolder(it) }
            call.reply()
        }
        else -> unknown(call)
    }

    /** A request's object path: the app's `handle_token` from [options] when valid, else one of Flatdroid's. */
    private fun requestHandle(call: Message, options: Map<*, *>): String {
        val token = ((options["handle_token"] as? Variant)?.value as? String)?.takeIf { TOKEN.matches(it) } ?: "flatdroid${++requests}"
        return requestPath(call.sender ?: "", token)
    }

    private fun introspection(interfaces: Map<String, UInt>): String = buildString {
        append("<!DOCTYPE node PUBLIC \"-//freedesktop//DTD D-BUS Object Introspection 1.0//EN\"\n")
        append(" \"http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd\">\n<node>\n")
        append(INTROSPECTION[INTROSPECTABLE])
        append(INTROSPECTION[PROPERTIES])
        append(INTROSPECTION[PEER])
        for (iface in interfaces.keys) append(INTROSPECTION[iface])
        append("</node>\n")
    }

    private fun unknown(call: Message) = call.error(UNKNOWN_METHOD, "Unknown method ${call.member} on ${call.iface}")
    private fun unknownInterface(call: Message) = call.error(UNKNOWN_INTERFACE, "Unknown interface ${call.iface}")

    /** The body of [this], which must be of [signature]. */
    private fun Message.args(signature: String): List<Any?> {
        if (this.signature != signature) throw ArgumentException("Expected ($signature), got (${this.signature})")
        return body
    }

    private fun Message.strings(signature: String): List<String> = args(signature).map { it as String }

    private class ArgumentException(message: String) : Exception(message)

    companion object {
        const val PORTAL_NAME = "org.freedesktop.portal.Desktop"
        /** Named in the real portal's `.portal` file for the camera ([com.garathon.flatdroid.camera.CameraScript]). */
        const val BACKEND_NAME = "org.freedesktop.impl.portal.desktop.flatdroid"
        const val PATH = "/org/freedesktop/portal/desktop"

        const val SETTINGS = "org.freedesktop.portal.Settings"
        const val IMPL_SETTINGS = "org.freedesktop.impl.portal.Settings"
        const val NETWORK_MONITOR = "org.freedesktop.portal.NetworkMonitor"
        const val PROXY_RESOLVER = "org.freedesktop.portal.ProxyResolver"
        const val OPEN_URI = "org.freedesktop.portal.OpenURI"
        const val REQUEST = "org.freedesktop.portal.Request"
        const val IMPL_REQUEST = "org.freedesktop.impl.portal.Request"
        const val IMPL_APP_CHOOSER = "org.freedesktop.impl.portal.AppChooser"
        /** Flatdroid's own interface on [BACKEND_NAME], for [LINK_HANDLER]'s command. */
        const val LINKS = "com.garathon.flatdroid.Links"
        /** The desktop file (without `.desktop`) the real portal launches for a link in camera sessions. */
        const val LINK_HANDLER = "flatdroid-links"

        const val INHIBIT = "org.freedesktop.portal.Inhibit"
        const val CAMERA = "org.freedesktop.portal.Camera"
        const val IMPL_INHIBIT = "org.freedesktop.impl.portal.Inhibit"
        const val SCREENSAVER = "org.freedesktop.ScreenSaver"
        const val SCREENSAVER_IFACE = SCREENSAVER
        /** The freedesktop path, and KDE's. */
        val SCREENSAVER_PATHS = listOf("/org/freedesktop/ScreenSaver", "/ScreenSaver")
        const val FILE_MANAGER = "org.freedesktop.FileManager1"
        const val GNOME_SESSION_MANAGER = "org.gnome.SessionManager"
        const val GNOME_SESSION_MANAGER_PATH = "/org/gnome/SessionManager"
        const val POWER_MANAGEMENT = "org.freedesktop.PowerManagement"
        const val POWER_MANAGEMENT_IFACE = "org.freedesktop.PowerManagement.Inhibit"
        const val POWER_MANAGEMENT_PATH = "/org/freedesktop/PowerManagement/Inhibit"
        const val GNOME_SCREENSAVER = "org.gnome.ScreenSaver"
        const val GNOME_SCREENSAVER_PATH = "/org/gnome/ScreenSaver"
        const val IDLE_MONITOR = "org.gnome.Mutter.IdleMonitor"
        const val IDLE_MONITOR_PATH = "/org/gnome/Mutter/IdleMonitor/Core"
        /** The other names inhibits and idle time come through. */
        val INHIBIT_NAMES = listOf(GNOME_SESSION_MANAGER, POWER_MANAGEMENT, GNOME_SCREENSAVER, IDLE_MONITOR)
        const val FILE_MANAGER_IFACE = FILE_MANAGER
        const val FILE_MANAGER_PATH = "/org/freedesktop/FileManager1"
        /** Items of one `ShowItems` looked at; the first that maps is shown. */
        private const val MAX_ITEMS = 32
        private const val BUS = "org.freedesktop.DBus"
        private const val LIMITS_EXCEEDED = "org.freedesktop.DBus.Error.LimitsExceeded"

        /** The path of a `file:` address on the local host, or null. */
        fun guestPath(uri: String): String? {
            val parsed = runCatching { java.net.URI(uri) }.getOrNull() ?: return null
            if (!parsed.scheme.equals("file", ignoreCase = true) || parsed.isOpaque) return null
            if (!parsed.authority.isNullOrEmpty() && parsed.authority != "localhost") return null
            return parsed.path?.takeIf { it.startsWith("/") && '\u0000' !in it }
        }

        const val RESPONSE_SUCCESS = 0u
        const val RESPONSE_CANCELLED = 1u
        const val RESPONSE_OTHER = 2u

        private val TOKEN = Regex("[A-Za-z0-9_]{1,64}")

        /** A request's object: the caller's unique name without its colon and with underscores for dots, then the token. */
        fun requestPath(sender: String, token: String): String =
            "$PATH/request/${sender.removePrefix(":").replace('.', '_').ifEmpty { "_" }}/$token"
        const val PROPERTIES = "org.freedesktop.DBus.Properties"
        const val INTROSPECTABLE = "org.freedesktop.DBus.Introspectable"
        const val PEER = "org.freedesktop.DBus.Peer"

        const val APPEARANCE = "org.freedesktop.appearance"
        const val COLOR_SCHEME = "color-scheme"
        /** `color-scheme`'s values. */
        const val PREFER_DARK = 1u
        const val PREFER_LIGHT = 2u
        /** `(ddd)`: red, green and blue from 0 to 1. */
        const val ACCENT_COLOR = "accent-color"
        /** 0 normal, 1 high. */
        const val CONTRAST = "contrast"
        /** 0 no preference, 1 reduced. */
        const val REDUCED_MOTION = "reduced-motion"

        const val DIRECT = "direct://"

        private const val UNKNOWN_METHOD = "org.freedesktop.DBus.Error.UnknownMethod"
        private const val UNKNOWN_OBJECT = "org.freedesktop.DBus.Error.UnknownObject"
        private const val UNKNOWN_INTERFACE = "org.freedesktop.DBus.Error.UnknownInterface"
        private const val UNKNOWN_PROPERTY = "org.freedesktop.DBus.Error.UnknownProperty"
        private const val PROPERTY_READ_ONLY = "org.freedesktop.DBus.Error.PropertyReadOnly"
        private const val INVALID_ARGS = "org.freedesktop.DBus.Error.InvalidArgs"
        private const val NOT_FOUND = "org.freedesktop.portal.Error.NotFound"
        private const val NOT_ALLOWED = "org.freedesktop.portal.Error.NotAllowed"

        /**
         * Whether [namespace] is asked for by [patterns]: none, or an empty one, asks for all; a
         * pattern ending in `*` for every namespace it starts.
         */
        fun matches(patterns: List<String>, namespace: String): Boolean =
            patterns.isEmpty() || patterns.any { pattern ->
                pattern.isEmpty() || pattern == namespace || (pattern.endsWith("*") && namespace.startsWith(pattern.dropLast(1)))
            }

        private val INTROSPECTION = mapOf(
            INTROSPECTABLE to """
                | <interface name="$INTROSPECTABLE">
                |  <method name="Introspect"><arg type="s" direction="out"/></method>
                | </interface>
                |""".trimMargin(),
            PROPERTIES to """
                | <interface name="$PROPERTIES">
                |  <method name="Get"><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="v" direction="out"/></method>
                |  <method name="GetAll"><arg type="s" direction="in"/><arg type="a{sv}" direction="out"/></method>
                |  <method name="Set"><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="v" direction="in"/></method>
                |  <signal name="PropertiesChanged"><arg type="s"/><arg type="a{sv}"/><arg type="as"/></signal>
                | </interface>
                |""".trimMargin(),
            PEER to """
                | <interface name="$PEER">
                |  <method name="Ping"/>
                | </interface>
                |""".trimMargin(),
            SETTINGS to """
                | <interface name="$SETTINGS">
                |  <method name="ReadAll"><arg type="as" name="namespaces" direction="in"/><arg type="a{sa{sv}}" direction="out"/></method>
                |  <method name="Read"><arg type="s" name="namespace" direction="in"/><arg type="s" name="key" direction="in"/><arg type="v" direction="out"/></method>
                |  <method name="ReadOne"><arg type="s" name="namespace" direction="in"/><arg type="s" name="key" direction="in"/><arg type="v" direction="out"/></method>
                |  <signal name="SettingChanged"><arg type="s"/><arg type="s"/><arg type="v"/></signal>
                |  <property name="version" type="u" access="read"/>
                | </interface>
                |""".trimMargin(),
            IMPL_SETTINGS to """
                | <interface name="$IMPL_SETTINGS">
                |  <method name="ReadAll"><arg type="as" name="namespaces" direction="in"/><arg type="a{sa{sv}}" direction="out"/></method>
                |  <method name="Read"><arg type="s" name="namespace" direction="in"/><arg type="s" name="key" direction="in"/><arg type="v" direction="out"/></method>
                |  <signal name="SettingChanged"><arg type="s"/><arg type="s"/><arg type="v"/></signal>
                |  <property name="version" type="u" access="read"/>
                | </interface>
                |""".trimMargin(),
            NETWORK_MONITOR to """
                | <interface name="$NETWORK_MONITOR">
                |  <method name="GetAvailable"><arg type="b" direction="out"/></method>
                |  <method name="GetMetered"><arg type="b" direction="out"/></method>
                |  <method name="GetConnectivity"><arg type="u" direction="out"/></method>
                |  <method name="GetStatus"><arg type="a{sv}" direction="out"/></method>
                |  <method name="CanReach"><arg type="s" name="hostname" direction="in"/><arg type="u" name="port" direction="in"/><arg type="b" direction="out"/></method>
                |  <signal name="changed"/>
                |  <property name="version" type="u" access="read"/>
                | </interface>
                |""".trimMargin(),
            OPEN_URI to """
                | <interface name="$OPEN_URI">
                |  <method name="OpenURI"><arg type="s" name="parent_window" direction="in"/><arg type="s" name="uri" direction="in"/><arg type="a{sv}" name="options" direction="in"/><arg type="o" name="handle" direction="out"/></method>
                |  <method name="OpenFile"><arg type="s" name="parent_window" direction="in"/><arg type="h" name="fd" direction="in"/><arg type="a{sv}" name="options" direction="in"/><arg type="o" name="handle" direction="out"/></method>
                |  <method name="OpenDirectory"><arg type="s" name="parent_window" direction="in"/><arg type="h" name="fd" direction="in"/><arg type="a{sv}" name="options" direction="in"/><arg type="o" name="handle" direction="out"/></method>
                |  <method name="SchemeSupported"><arg type="s" name="scheme" direction="in"/><arg type="a{sv}" name="options" direction="in"/><arg type="b" name="supported" direction="out"/></method>
                |  <property name="version" type="u" access="read"/>
                | </interface>
                |""".trimMargin(),
            IMPL_APP_CHOOSER to """
                | <interface name="$IMPL_APP_CHOOSER">
                |  <method name="ChooseApplication"><arg type="o" direction="in"/><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="as" direction="in"/><arg type="a{sv}" direction="in"/><arg type="u" direction="out"/><arg type="a{sv}" direction="out"/></method>
                |  <method name="UpdateChoices"><arg type="o" direction="in"/><arg type="as" direction="in"/></method>
                | </interface>
                |""".trimMargin(),
            LINKS to """
                | <interface name="$LINKS">
                |  <method name="Open"><arg type="s" name="uri" direction="in"/><arg type="b" direction="out"/></method>
                | </interface>
                |""".trimMargin(),
            PROXY_RESOLVER to """
                | <interface name="$PROXY_RESOLVER">
                |  <method name="Lookup"><arg type="s" name="uri" direction="in"/><arg type="as" direction="out"/></method>
                |  <property name="version" type="u" access="read"/>
                | </interface>
                |""".trimMargin(),
            CAMERA to """
                | <interface name="$CAMERA">
                |  <method name="AccessCamera"><arg type="a{sv}" name="options" direction="in"/><arg type="o" name="handle" direction="out"/></method>
                |  <method name="OpenPipeWireRemote"><arg type="a{sv}" name="options" direction="in"/><arg type="h" direction="out"/></method>
                |  <property name="IsCameraPresent" type="b" access="read"/>
                |  <property name="version" type="u" access="read"/>
                | </interface>
                |""".trimMargin(),
            INHIBIT to """
                | <interface name="$INHIBIT">
                |  <method name="Inhibit"><arg type="s" name="window" direction="in"/><arg type="u" name="flags" direction="in"/><arg type="a{sv}" name="options" direction="in"/><arg type="o" name="handle" direction="out"/></method>
                |  <property name="version" type="u" access="read"/>
                | </interface>
                |""".trimMargin(),
            IMPL_INHIBIT to """
                | <interface name="$IMPL_INHIBIT">
                |  <method name="Inhibit"><arg type="o" name="handle" direction="in"/><arg type="s" name="app_id" direction="in"/><arg type="s" name="window" direction="in"/><arg type="u" name="flags" direction="in"/><arg type="a{sv}" name="options" direction="in"/></method>
                |  <property name="version" type="u" access="read"/>
                | </interface>
                |""".trimMargin(),
            SCREENSAVER_IFACE to """
                | <interface name="$SCREENSAVER_IFACE">
                |  <method name="Inhibit"><arg type="s" name="application_name" direction="in"/><arg type="s" name="reason_for_inhibit" direction="in"/><arg type="u" name="cookie" direction="out"/></method>
                |  <method name="UnInhibit"><arg type="u" name="cookie" direction="in"/></method>
                |  <method name="SimulateUserActivity"/>
                |  <method name="GetActive"><arg type="b" direction="out"/></method>
                | </interface>
                |""".trimMargin(),
            GNOME_SESSION_MANAGER to """
                | <interface name="$GNOME_SESSION_MANAGER">
                |  <method name="Inhibit"><arg type="s" name="app_id" direction="in"/><arg type="u" name="toplevel_xid" direction="in"/><arg type="s" name="reason" direction="in"/><arg type="u" name="flags" direction="in"/><arg type="u" name="cookie" direction="out"/></method>
                |  <method name="Uninhibit"><arg type="u" name="cookie" direction="in"/></method>
                |  <method name="IsInhibited"><arg type="u" name="flags" direction="in"/><arg type="b" direction="out"/></method>
                | </interface>
                |""".trimMargin(),
            POWER_MANAGEMENT_IFACE to """
                | <interface name="$POWER_MANAGEMENT_IFACE">
                |  <method name="Inhibit"><arg type="s" name="application" direction="in"/><arg type="s" name="reason" direction="in"/><arg type="u" name="cookie" direction="out"/></method>
                |  <method name="UnInhibit"><arg type="u" name="cookie" direction="in"/></method>
                |  <method name="HasInhibit"><arg type="b" direction="out"/></method>
                | </interface>
                |""".trimMargin(),
            GNOME_SCREENSAVER to """
                | <interface name="$GNOME_SCREENSAVER">
                |  <method name="GetActive"><arg type="b" direction="out"/></method>
                |  <method name="GetActiveTime"><arg type="u" direction="out"/></method>
                |  <method name="SetActive"><arg type="b" direction="in"/></method>
                |  <method name="Lock"/>
                |  <method name="SimulateUserActivity"/>
                | </interface>
                |""".trimMargin(),
            IDLE_MONITOR to """
                | <interface name="$IDLE_MONITOR">
                |  <method name="GetIdletime"><arg type="t" direction="out"/></method>
                |  <method name="AddIdleWatch"><arg type="t" name="interval" direction="in"/><arg type="u" name="id" direction="out"/></method>
                |  <method name="AddUserActiveWatch"><arg type="u" name="id" direction="out"/></method>
                |  <method name="RemoveWatch"><arg type="u" name="id" direction="in"/></method>
                | </interface>
                |""".trimMargin(),
            Notifications.IFACE to """
                | <interface name="${Notifications.IFACE}">
                |  <method name="Notify"><arg type="s" name="app_name" direction="in"/><arg type="u" name="replaces_id" direction="in"/><arg type="s" name="app_icon" direction="in"/><arg type="s" name="summary" direction="in"/><arg type="s" name="body" direction="in"/><arg type="as" name="actions" direction="in"/><arg type="a{sv}" name="hints" direction="in"/><arg type="i" name="expire_timeout" direction="in"/><arg type="u" direction="out"/></method>
                |  <method name="CloseNotification"><arg type="u" name="id" direction="in"/></method>
                |  <method name="GetCapabilities"><arg type="as" direction="out"/></method>
                |  <method name="GetServerInformation"><arg type="s" direction="out"/><arg type="s" direction="out"/><arg type="s" direction="out"/><arg type="s" direction="out"/></method>
                |  <signal name="NotificationClosed"><arg type="u"/><arg type="u"/></signal>
                |  <signal name="ActionInvoked"><arg type="u"/><arg type="s"/></signal>
                | </interface>
                |""".trimMargin(),
            Notifications.PORTAL_IFACE to """
                | <interface name="${Notifications.PORTAL_IFACE}">
                |  <method name="AddNotification"><arg type="s" name="id" direction="in"/><arg type="a{sv}" name="notification" direction="in"/></method>
                |  <method name="RemoveNotification"><arg type="s" name="id" direction="in"/></method>
                |  <signal name="ActionInvoked"><arg type="s"/><arg type="s"/><arg type="av"/></signal>
                |  <property name="SupportedOptions" type="a{sv}" access="read"/>
                |  <property name="version" type="u" access="read"/>
                | </interface>
                |""".trimMargin(),
            Notifications.IMPL_IFACE to """
                | <interface name="${Notifications.IMPL_IFACE}">
                |  <method name="AddNotification"><arg type="s" name="app_id" direction="in"/><arg type="s" name="id" direction="in"/><arg type="a{sv}" name="notification" direction="in"/></method>
                |  <method name="RemoveNotification"><arg type="s" name="app_id" direction="in"/><arg type="s" name="id" direction="in"/></method>
                |  <signal name="ActionInvoked"><arg type="s"/><arg type="s"/><arg type="s"/><arg type="av"/></signal>
                |  <property name="version" type="u" access="read"/>
                | </interface>
                |""".trimMargin(),
            FILE_MANAGER_IFACE to """
                | <interface name="$FILE_MANAGER_IFACE">
                |  <method name="ShowFolders"><arg type="as" name="uris" direction="in"/><arg type="s" name="startup_id" direction="in"/></method>
                |  <method name="ShowItems"><arg type="as" name="uris" direction="in"/><arg type="s" name="startup_id" direction="in"/></method>
                |  <method name="ShowItemProperties"><arg type="as" name="uris" direction="in"/><arg type="s" name="startup_id" direction="in"/></method>
                | </interface>
                |""".trimMargin(),
        )
    }
}
