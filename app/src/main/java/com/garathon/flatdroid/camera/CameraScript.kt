package com.garathon.flatdroid.camera

import com.garathon.flatdroid.session.SessionBus

/**
 * The camera's helpers for one session, in a proot of their own: proot hides a process's root
 * only from processes in the same proot, so PipeWire (among the other helpers) can tell its
 * clients apart, and the portal its callers (the session bus's proxy, also among the other
 * helpers), only across proots. Started only for an app with the camera on, once PipeWire runs:
 * the permission store (the camera allowed for the app, which the portal sees as app ID ""),
 * WirePlumber, the camera portal, and for each camera a GStreamer source that turns the JPEG
 * frames Flatdroid writes to its pipe ([frameHeader] before each) into a PipeWire camera node. Its
 * node appears with the first frame; Flatdroid writes a black one at the start, and opens Android's
 * camera only while an app is linked to the node ([USERS_PREFIX]). A source never waits for an
 * app: frames an app isn't ready for are dropped (a one-frame leaky queue, and no buffer pool, so
 * an app holding on to buffers can't stall it), as a real camera does. Frames are JPEG because
 * pipewiresink drops the timestamps of raw video frames it copies (still so in PipeWire 1.6), and
 * an app can't record frames without them; each is timestamped as it arrives.
 */
object CameraScript {
    const val GUEST_SCRIPT = "/run/flatdroid/camera.sh"
    /** PipeWire's sockets (its XDG_RUNTIME_DIR), also bound into the other helpers' proot. */
    const val GUEST_PIPEWIRE_DIR = "/run/flatdroid/pipewire"
    const val PIPEWIRE_SOCKET = "pipewire-0"
    /** The cameras' pipes, one per node, named as the node. */
    const val GUEST_PIPE_DIR = "/run/flatdroid/camera"
    /** WirePlumber's and the portal's configuration ([CONFIG_FILES]), as XDG_CONFIG_DIRS. */
    const val GUEST_CONFIG_DIR = "/run/flatdroid/camera-config"

    /** Printed once the nodes exist and the portal reports a camera. */
    const val READY = "flatdroid: camera ready"
    /** Printed with a node's name and how many links lead from it, whenever links change. */
    const val USERS_PREFIX = "flatdroid: camera users "

    /**
     * Where a camera faces, as libcamera names it (`api.libcamera.location`): apps mirror their
     * preview of a camera that doesn't face back, as a mirror shows the user.
     */
    enum class Location { FRONT, BACK, EXTERNAL }

    /**
     * A camera as a PipeWire node: its frames are [width]×[height] JPEG at [frameRate] per second,
     * from a camera facing [location].
     */
    data class Node(
        val name: String, val description: String, val width: Int, val height: Int, val frameRate: Int,
        val location: Location = Location.EXTERNAL,
    )

    fun nodeName(index: Int) = "flatdroid-camera-$index"

    /**
     * What goes before each JPEG frame of [length] bytes in a pipe: a part of a multipart stream,
     * as webcams send over HTTP, which the source splits again by its length.
     */
    fun frameHeader(length: Int): ByteArray =
        "--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: $length\r\n\r\n".toByteArray()

    /** What goes after each frame. */
    val FRAME_END = "\r\n".toByteArray()

    /** The node and its count in a [USERS_PREFIX] line; null for any other line. */
    fun users(line: String): Pair<String, Int>? {
        val rest = line.removePrefix(USERS_PREFIX).takeIf { it != line }?.trim() ?: return null
        val name = rest.substringBefore(' ')
        val count = rest.substringAfter(' ', "").toIntOrNull() ?: return null
        return name to count
    }

    /**
     * WirePlumber without its hardware monitors, and the portal with a stand-in
     * for the access dialog: the permission is set before the app starts, so
     * the dialog is never asked for.
     */
    val CONFIG_FILES = mapOf(
        "wireplumber/wireplumber.conf.d/flatdroid.conf" to """
            |wireplumber.profiles = {
            |  main = {
            |    monitor.alsa = disabled
            |    monitor.alsa-midi = disabled
            |    monitor.bluez = disabled
            |    monitor.bluez-midi = disabled
            |    monitor.v4l2 = disabled
            |    monitor.libcamera = disabled
            |  }
            |}
            |""".trimMargin(),
        // XDG_DESKTOP_PORTAL_DIR: the portal reads its backends and its configuration only from there.
        "portals/flatdroid-portals.conf" to "[preferred]\ndefault=flatdroid\n",
        "portals/flatdroid.portal" to
            "[portal]\nDBusName=org.freedesktop.impl.portal.desktop.flatdroid\nInterfaces=org.freedesktop.impl.portal.Access;\n",
    )

    /**
     * The script for [nodes]. Their names come from [nodeName], their sizes are numbers and their
     * descriptions are reduced to letters, digits and spaces, so none can break out of the quoting.
     */
    fun text(nodes: List<Node>): String {
        require(nodes.all { NAME.matches(it.name) && it.width > 0 && it.height > 0 && it.frameRate > 0 })
        val sources = nodes.joinToString("\n") { node ->
            val description = node.description.filter { it.isLetterOrDigit() || it == ' ' }.trim().ifEmpty { node.name }
            val properties = "props,media.class=Video/Source,media.role=Camera,media.type=Video,media.category=Capture," +
                "api.libcamera.location=${node.location.name.lowercase()},node.description=\\\"$description\\\""
            "( exec 3<>$GUEST_PIPE_DIR/${node.name}; exec gst-launch-1.0 -q fdsrc fd=3 do-timestamp=true blocksize=$PIPE_BYTES " +
                "! multipartdemux boundary=$BOUNDARY single-stream=true " +
                "! image/jpeg,width=${node.width},height=${node.height},framerate=${node.frameRate}/1 " +
                "! queue leaky=downstream max-size-buffers=1 max-size-bytes=0 max-size-time=0 " +
                "! pipewiresink mode=provide use-bufferpool=false 'stream-properties=\"$properties\"' client-name=${node.name} ) &"
        }
        val names = nodes.joinToString(" ") { it.name }
        return """
            |#!/bin/sh
            |export XDG_RUNTIME_DIR=$GUEST_PIPEWIRE_DIR DBUS_SESSION_BUS_ADDRESS=unix:path=${SessionBus.GUEST_DAEMON_DIR}/${SessionBus.SOCKET}
            |export XDG_CURRENT_DESKTOP=flatdroid XDG_CONFIG_DIRS=$GUEST_CONFIG_DIR XDG_DESKTOP_PORTAL_DIR=$GUEST_CONFIG_DIR/portals
            |/usr/libexec/xdg-permission-store &
            |i=0
            |until gdbus call --session --dest org.freedesktop.impl.portal.PermissionStore \
            |    --object-path /org/freedesktop/impl/portal/PermissionStore \
            |    --method org.freedesktop.impl.portal.PermissionStore.SetPermission devices true camera '' "['yes']" >/dev/null 2>&1; do
            |  i=${'$'}((i + 1))
            |  [ "${'$'}i" -gt 100 ] && { echo "flatdroid: the camera permission couldn't be set"; break; }
            |  sleep 0.1
            |done
            |wireplumber &
            |/usr/libexec/xdg-desktop-portal &
            |$sources
            |i=0
            |for node in $names; do
            |  until pw-link -o 2>/dev/null | grep -q "^${'$'}node:"; do
            |    i=${'$'}((i + 1))
            |    [ "${'$'}i" -gt 150 ] && break 2
            |    sleep 0.1
            |  done
            |done
            |# Apps ask once at start whether there is a camera, so not before the portal has seen the nodes.
            |i=0
            |until gdbus call --session --dest org.freedesktop.portal.Desktop --object-path /org/freedesktop/portal/desktop \
            |    --method org.freedesktop.DBus.Properties.Get org.freedesktop.portal.Camera IsCameraPresent 2>/dev/null | grep -q true; do
            |  i=${'$'}((i + 1))
            |  [ "${'$'}i" -gt 100 ] && break
            |  sleep 0.1
            |done
            |# One process for the users, started now: one started later (for each change) would be
            |# visible to the app. pw-link prints each output port's name ("*" when it changed), then
            |# each of its links as "  |-> <input port>", with "+", "-" or " " in front.
            |pw-link -m -l 2>/dev/null | awk -v nodes="$names" -v prefix="$USERS_PREFIX" '
            |  function report(   i, k, c) {
            |    for (i = 1; i <= n; i++) {
            |      c = 0
            |      for (k in links) if (index(k, list[i] ":") == 1) c++
            |      if (c != shown[list[i]]) { shown[list[i]] = c; print prefix list[i] " " c; fflush() }
            |    }
            |  }
            |  BEGIN { n = split(nodes, list, " "); for (i = 1; i <= n; i++) shown[list[i]] = -1; report() }
            |  substr(${'$'}0, 2, 6) == "  |-> " {
            |    key = port "|" substr(${'$'}0, 8)
            |    if (substr(${'$'}0, 1, 1) == "-") delete links[key]; else links[key] = 1
            |    report()
            |    next
            |  }
            |  substr(${'$'}0, 2, 6) == "  |<- " { next }
            |  { port = substr(${'$'}0, 2) }
            |' &
            |echo "$READY"
            |wait
            |""".trimMargin()
    }

    private val NAME = Regex("flatdroid-camera-\\d+")
    private const val BOUNDARY = "flatdroid-frame"
    /** The pipe's size ([CameraStreamer] asks for it), so a frame is read at once. */
    const val PIPE_BYTES = 1 shl 20
}
