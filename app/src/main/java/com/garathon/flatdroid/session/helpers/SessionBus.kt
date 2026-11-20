package com.garathon.flatdroid.session.helpers

import com.garathon.flatdroid.proot.GuestPaths
import com.garathon.flatdroid.session.bus.Portal

/**
 * The app's session bus: a `dbus-daemon` among the helpers, which the app reaches only through
 * `xdg-dbus-proxy`'s filter, as in Flatpak. The app may own its ID and the names below it and use
 * what its metadata's `[Session Bus Policy]` names (nothing else is on this bus unless Flatdroid
 * starts it or joins it itself, [com.garathon.flatdroid.session.bus.SessionPortal]), but can't watch other connections or change the bus's environment. The bus starts
 * no services of its own.
 */
object SessionBus {
    const val SOCKET = GuestPaths.SESSION_BUS_SOCKET

    // Guest paths in the helpers' proot. The daemon's socket is never bound into the app's root.
    const val GUEST_DAEMON_DIR = "/run/flatdroid/dbus"
    const val GUEST_PROXY_DIR = "/run/flatdroid/bus-proxy"
    const val GUEST_CONFIG = "/run/flatdroid/dbus.conf"
    const val GUEST_PROXY_ARGS = "/run/flatdroid/proxy.args"

    /**
     * The daemon's configuration. EXTERNAL authentication for clients that offer nothing else
     * (zbus, so ashpd and the apps using it), and anonymous for the rest, as for PulseAudio: under
     * the helpers' fake root a client claims a user ID the socket doesn't confirm. Every process
     * here is the same Android user either way. Only a socket path in private storage, so no
     * abstract socket other users of the device could reach; no service directories. Receiving
     * needs its own rule (without one the daemon drops every reply); it leaves eavesdropping
     * off, which nothing here needs.
     */
    val CONFIG = """
        |<!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
        | "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
        |<busconfig>
        |  <type>session</type>
        |  <listen>unix:path=$GUEST_DAEMON_DIR/$SOCKET</listen>
        |  <auth>EXTERNAL</auth>
        |  <auth>ANONYMOUS</auth>
        |  <allow_anonymous/>
        |  <policy context="default">
        |    <allow send_destination="*"/>
        |    <allow own="*"/>
        |    <allow receive_sender="*"/>
        |  </policy>
        |</busconfig>
        |""".trimMargin()

    /**
     * `xdg-dbus-proxy`'s arguments for [appId]: Flatpak's own rules (its ID and MPRIS names below
     * it) and the valid entries of the metadata's [policy], without those naming or covering the
     * services Flatdroid runs on this bus ([RESERVED]). Always the portal's interfaces Flatdroid
     * serves ([Portal]: settings, network monitor, proxy resolver), and with [camera] the camera
     * portal too: its methods and the replies to its requests. The portal's other interfaces stay
     * closed, whoever owns its name.
     */
    fun proxyArgs(appId: String, policy: Map<String, String>, camera: Boolean): List<String> {
        val args = mutableListOf(
            "unix:path=$GUEST_DAEMON_DIR/$SOCKET",
            "$GUEST_PROXY_DIR/$SOCKET",
            "--filter",
            "--own=$appId.*",
            "--own=org.mpris.MediaPlayer2.$appId.*",
        )
        for ((name, level) in policy) {
            if (level in LEVELS && isPolicyName(name) && !isReserved(name)) args += "--$level=$name"
        }
        args += PORTAL_RULES
        if (camera) args += CAMERA_RULES
        return args
    }

    /** [args] as `--args` reads them: each followed by a NUL. */
    fun argsFile(args: List<String>): ByteArray = args.joinToString("") { "$it\u0000" }.toByteArray()

    /** A well-known bus name, optionally ending in `.*`. */
    fun isPolicyName(name: String): Boolean {
        val base = name.removeSuffix(".*")
        val elements = base.split('.')
        return base.length <= 255 && elements.size >= 2 && elements.all { it.isNotEmpty() && ELEMENT.matches(it) && !it[0].isDigit() }
    }

    /**
     * Whether [name] (optionally ending in `.*`) is in, or with its `.*` covers, a [RESERVED]
     * namespace: the metadata could otherwise reach every portal, the permission store, or take
     * the portal's own names.
     */
    fun isReserved(name: String): Boolean {
        val wildcard = name.endsWith(".*")
        val base = name.removeSuffix(".*")
        return RESERVED.any { base == it || base.startsWith("$it.") || (wildcard && it.startsWith("$base.")) }
    }

    /** The portal, its backends and the permission store (the camera's helpers), and the bus itself. */
    private val RESERVED = listOf("org.freedesktop.portal", "org.freedesktop.impl.portal", "org.freedesktop.DBus")
    private val LEVELS = setOf("see", "talk", "own")
    private val ELEMENT = Regex("[A-Za-z0-9_-]+")

    private const val PORTAL = Portal.PORTAL_NAME
    private const val PORTAL_PATH = Portal.PATH
    private val PORTAL_RULES = listOf(
        "--call=$PORTAL=${Portal.SETTINGS}.*@$PORTAL_PATH",
        "--call=$PORTAL=${Portal.NETWORK_MONITOR}.*@$PORTAL_PATH",
        "--call=$PORTAL=${Portal.PROXY_RESOLVER}.*@$PORTAL_PATH",
        "--call=$PORTAL=${Portal.PROPERTIES}.Get@$PORTAL_PATH",
        "--call=$PORTAL=${Portal.PROPERTIES}.GetAll@$PORTAL_PATH",
        "--call=$PORTAL=${Portal.INTROSPECTABLE}.Introspect@$PORTAL_PATH",
        "--broadcast=$PORTAL=${Portal.SETTINGS}.SettingChanged@$PORTAL_PATH",
        "--broadcast=$PORTAL=${Portal.NETWORK_MONITOR}.changed@$PORTAL_PATH",
        "--broadcast=$PORTAL=${Portal.PROPERTIES}.PropertiesChanged@$PORTAL_PATH",
    )
    private val CAMERA_RULES = listOf(
        "--call=$PORTAL=org.freedesktop.portal.Camera.*@$PORTAL_PATH",
        "--call=$PORTAL=org.freedesktop.portal.Request.Close@$PORTAL_PATH/request/*",
        "--broadcast=$PORTAL=org.freedesktop.portal.Request.Response@$PORTAL_PATH/request/*",
    )
}
