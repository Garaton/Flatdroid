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
 */
class Portal(
    private val role: Role,
    /** The settings by namespace and key, read at each call. */
    private val settings: () -> Map<String, Map<String, Variant>>,
    /** The network as the app may use it, read at each call. */
    private val network: () -> NetworkStatus,
    private val links: Links,
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
    }

    /**
     * GLib's connectivity levels: 1 local only, 2 limited, 3 behind a captive portal, 4 full.
     */
    data class NetworkStatus(val available: Boolean, val metered: Boolean, val connectivity: UInt)

    /** The names Flatdroid owns in this [role]. */
    val names: List<String> = when (role) {
        Role.PORTAL -> listOf(PORTAL_NAME)
        Role.BACKEND -> listOf(BACKEND_NAME)
    }

    /**
     * What to send for [call], whose file descriptors are [fds]: its reply (none when it asks for
     * none), then any signal it causes (a request's `Response` goes after the reply that names
     * the request, as the real portal sends it).
     */
    fun handle(call: Message, fds: List<FileDescriptor> = emptyList()): List<Message> {
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
        // Requests answer at once, so closing one has nothing left to stop.
        if (call.path?.startsWith("$PATH/request/") == true && (iface == REQUEST || iface == IMPL_REQUEST) && member == "Close") {
            return call.args("").let { call.reply() }
        }
        if (call.path != PATH) return call.error(UNKNOWN_OBJECT, "No object at ${call.path}")
        val interfaces = interfaces()
        return when (iface) {
            INTROSPECTABLE -> if (member == "Introspect") call.args("").let { call.reply("s", introspection()) } else unknown(call)
            PROPERTIES -> properties(call, interfaces)
            SETTINGS, IMPL_SETTINGS -> if (iface in interfaces) settings(call) else unknownInterface(call)
            NETWORK_MONITOR -> if (iface in interfaces) networkMonitor(call) else unknownInterface(call)
            PROXY_RESOLVER -> if (iface in interfaces) proxyResolver(call) else unknownInterface(call)
            OPEN_URI -> if (iface in interfaces) openUri(call, fds, signals) else unknownInterface(call)
            IMPL_APP_CHOOSER -> if (iface in interfaces) appChooser(call) else unknownInterface(call)
            LINKS -> if (iface in interfaces) handlerLink(call) else unknownInterface(call)
            null -> call.error(UNKNOWN_METHOD, "No interface")
            else -> unknownInterface(call)
        }
    }

    /** The `SettingChanged` signal for [key] in [namespace], now [value]. */
    fun settingChanged(namespace: String, key: String, value: Variant): Message =
        Message.signal(PATH, if (role == Role.PORTAL) SETTINGS else IMPL_SETTINGS, "SettingChanged", "ssv", namespace, key, value)

    /** The network monitor's `changed` signal, after which GLib reads the status again; null when this role doesn't serve it. */
    fun networkChanged(): Message? = if (role == Role.PORTAL) Message.signal(PATH, NETWORK_MONITOR, "changed") else null

    /** The interfaces at [PATH] with their `version` property. */
    private fun interfaces(): Map<String, UInt> = when (role) {
        Role.PORTAL -> mapOf(SETTINGS to 2u, NETWORK_MONITOR to 3u, PROXY_RESOLVER to 1u, OPEN_URI to 5u)
        Role.BACKEND -> mapOf(IMPL_SETTINGS to 1u, IMPL_APP_CHOOSER to 2u, LINKS to 1u)
    }

    private fun properties(call: Message, interfaces: Map<String, UInt>): Message = when (call.member) {
        "Get" -> {
            val (iface, name) = call.strings("ss")
            val version = interfaces[iface] ?: return call.error(UNKNOWN_INTERFACE, "No interface $iface")
            if (name == "version") call.reply("v", Variant("u", version)) else call.error(UNKNOWN_PROPERTY, "No property $name")
        }
        "GetAll" -> {
            val (iface) = call.strings("s")
            val version = interfaces[iface] ?: return call.error(UNKNOWN_INTERFACE, "No interface $iface")
            call.reply("a{sv}", mapOf("version" to Variant("u", version)))
        }
        "Set" -> call.error(PROPERTY_READ_ONLY, "Properties here are read-only")
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
        val token = ((options["handle_token"] as? Variant)?.value as? String)?.takeIf { TOKEN.matches(it) } ?: "flatdroid${++requests}"
        val handle = requestPath(call.sender ?: "", token)
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

    private fun introspection(): String = buildString {
        append("<!DOCTYPE node PUBLIC \"-//freedesktop//DTD D-BUS Object Introspection 1.0//EN\"\n")
        append(" \"http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd\">\n<node>\n")
        append(INTROSPECTION[INTROSPECTABLE])
        append(INTROSPECTION[PROPERTIES])
        append(INTROSPECTION[PEER])
        for (iface in interfaces().keys) append(INTROSPECTION[iface])
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

        const val DIRECT = "direct://"

        private const val UNKNOWN_METHOD = "org.freedesktop.DBus.Error.UnknownMethod"
        private const val UNKNOWN_OBJECT = "org.freedesktop.DBus.Error.UnknownObject"
        private const val UNKNOWN_INTERFACE = "org.freedesktop.DBus.Error.UnknownInterface"
        private const val UNKNOWN_PROPERTY = "org.freedesktop.DBus.Error.UnknownProperty"
        private const val PROPERTY_READ_ONLY = "org.freedesktop.DBus.Error.PropertyReadOnly"
        private const val INVALID_ARGS = "org.freedesktop.DBus.Error.InvalidArgs"
        private const val NOT_FOUND = "org.freedesktop.portal.Error.NotFound"

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
        )
    }
}
