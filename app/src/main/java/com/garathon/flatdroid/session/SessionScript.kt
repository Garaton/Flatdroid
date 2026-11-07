package com.garathon.flatdroid.session

import com.garathon.flatdroid.camera.CameraScript
import com.garathon.flatdroid.proot.Fdsec
import com.garathon.flatdroid.proot.Fdshm
import com.garathon.flatdroid.proot.GuestPaths
import com.garathon.flatdroid.settings.Resampler

/**
 * The display and audio helpers for one Flatpak session, run in Alpine (never in the Flatpak's
 * root, and with nothing from shared storage bound). Started fresh for every launch.
 */
object SessionScript {
    /** VNC port, on localhost only, for an external viewer; not the default 5901. */
    const val VNC_PORT = 14400
    /** Flatdroid's own viewer connects here, in [GUEST_VNC_DIR] (private storage, mode 0600): no port, no password. */
    const val VNC_SOCKET = "vnc.sock"
    const val X_SOCKET = "X1"
    /** Printed once every helper of the script has started. */
    const val DISPLAY_READY = "flatdroid: display ready"

    // Guest paths of the binds Flatdroid adds to the helpers' proot.
    const val GUEST_SCRIPT = "/run/flatdroid/session.sh"
    const val GUEST_PULSE_SCRIPT = "/run/flatdroid/pulse.pa"
    const val GUEST_VNC_DIR = "/run/flatdroid/vnc"
    const val GUEST_OPENBOX_RC = "/root/.config/openbox/rc.xml"
    /** Openbox's theme ([com.garathon.flatdroid.alpine.GuestFiles.OPENBOX_THEMERC]), where Openbox looks for the user's themes. */
    const val GUEST_OPENBOX_THEMERC = "/root/.themes/${com.garathon.flatdroid.alpine.GuestFiles.OPENBOX_THEME}/openbox-3/themerc"

    /** In front of a helper's command: fdsec again, adding no Internet sockets to the filter it already has. */
    private val NO_INTERNET = (listOf(Fdsec.ALPINE_PATH) + Fdsec.flags(Fdsec.Filter(noInternet = true)) + "--").joinToString(" ")

    /**
     * In front of a display server that external viewers reach on [VNC_PORT]: no outgoing
     * connections, so an app can't have it connect out (TigerVNC's reverse connections, which any
     * X client can request) where the app itself may have no network.
     */
    private val NO_CONNECT = (listOf(Fdsec.ALPINE_PATH) + Fdsec.flags(Fdsec.Filter(noConnect = true)) + "--").joinToString(" ")

    /**
     * The helpers' command: [TEXT] at [GUEST_SCRIPT] with its arguments. Without [sound],
     * PulseAudio doesn't start, and so neither does the microphone (whose source [PulseScript]
     * sets up). With [sessionBus], the app's
     * bus and its proxy ([SessionBus]) start too, and with [camera] PipeWire, for the camera's
     * own helpers ([CameraScript]). Without [clipboard], Xvnc neither takes the viewer's clipboard
     * nor sends its own.
     */
    fun command(
        geometry: String, frameRate: Int, sound: Boolean, resampler: Resampler, sessionBus: Boolean,
        camera: Boolean = false, clipboard: Boolean = true, wayland: Boolean = false, externalViewer: Boolean = false,
        interfaceSize: Int = 100,
    ): List<String> =
        listOf(
            "/bin/sh", GUEST_SCRIPT, geometry, VNC_PORT.toString(), frameRate.toString(),
            onOff(sound), resampler.pulseName, onOff(sessionBus), onOff(sessionBus && camera),
            onOff(clipboard), onOff(wayland), onOff(externalViewer), scale(interfaceSize),
        )

    /** A percent as the output's scale ("1.5" for 150). */
    fun scale(percent: Int): String = "${percent / 100}.${(percent % 100).toString().padStart(2, '0')}"

    /** wayvnc's configuration ([WaylandVnc.config]), next to Xvnc's password file. */
    const val GUEST_WAYVNC_CONFIG = "$GUEST_VNC_DIR/wayvnc.conf"
    /** labwc's configuration directory ([com.garathon.flatdroid.alpine.GuestFiles.labwcRc]). */
    const val GUEST_LABWC_DIR = "$GUEST_VNC_DIR/labwc"
    /** Above this frame rate (uncapped) the output keeps the headless backend's refresh. */
    const val MAX_OUTPUT_REFRESH = 240

    private fun onOff(value: Boolean) = if (value) "on" else "off"

    /**
     * Arguments: geometry (WxH), VNC port, frame rate, "on" when the app gets sound (PulseAudio
     * starts only then), PulseAudio's resample method, "on" for the session bus, "on" for PipeWire, "on" when the clipboard is shared ([command]
     * builds them; off adds `-AcceptCutText=0 -SendCutText=0` to Xvnc), "on" for a Wayland session (labwc and wayvnc instead of
     * Xvnc and Openbox), "on" for an external viewer (the TCP port and login; else the viewer's Unix
     * socket), and the Wayland output's scale ([scale]); title bars come from Openbox's and labwc's configurations. Exits when the display does; Flatdroid ends the session by stopping this proot.
     * `-extension GLX` turns GLX off in Alpine's Xvnc, which has it (apps use EGL). `-nolisten local` drops the abstract socket
     * `@/tmp/.X11-unix/X1`: proot doesn't translate abstract names, so it would be open to other
     * users of the device, with no authentication; clients use the socket file in private storage
     * instead. `-NeverShared -DisconnectClients=0`: while a viewer is connected, Xvnc refuses
     * further connections rather than sharing or handing over the session.
     * `--use-pid-file=no`: proot shares the process list, so a stale PID file can match a live
     * process. `--disallow-module-loading` (after [PulseScript]'s modules are loaded) and
     * `--disallow-exit`: the app is PulseAudio's client, and a module it could load (the CLI
     * protocol's, say) loads any library by path, which would run the app's code among the
     * helpers. PulseAudio's modules are in [PulseScript]; whether the app records from the
     * microphone, Flatdroid tells from its pipe ([com.garathon.flatdroid.audio.MicStreamer]), so
     * nothing here starts a process during the session, which the app could see and take over.
     * The session bus's proxy reads its arguments from a file ([SessionBus.proxyArgs]), so none
     * pass through the shell.
     * The whole script runs behind fdsec's filter; every helper but Xvnc, which serves the VNC
     * port, also runs behind a second one without Internet sockets, so exploiting one from the
     * app gives no network the app may not have.
     */
    val TEXT = """
        |#!/bin/sh
        |geometry=$1
        |port=$2
        |frame_rate=$3
        |sound=$4
        |resampler=$5
        |session_bus=$6
        |camera=$7
        |clipboard=$8
        |wayland=$9
        |external=${'$'}{10}
        |scale=${'$'}{11}
        |clipboard_args=
        |[ "${'$'}clipboard" = on ] || clipboard_args="-AcceptCutText=0 -SendCutText=0"
        |rm -f /tmp/.X1-lock ${GuestPaths.X11_DIR}/$X_SOCKET ${GuestPaths.PULSE_DIR}/* $GUEST_VNC_DIR/$VNC_SOCKET
        |
        |# Waits for the socket $1 of $2, which the display's process stands or falls with.
        |wait_socket() {
        |  i=0
        |  while [ ! -S "${'$'}1" ]; do
        |    kill -0 "${'$'}display" 2>/dev/null || { echo "flatdroid: ${'$'}2 exited"; exit 1; }
        |    i=${'$'}((i + 1))
        |    [ "${'$'}i" -gt 300 ] && { echo "flatdroid: ${'$'}2 did not start"; exit 1; }
        |    sleep 0.1
        |  done
        |}
        |
        |if [ "${'$'}wayland" = on ]; then
        |  # labwc (headless, drawn in software) with wayvnc as a client, on the viewer's socket (or
        |  # the VNC port for an external viewer). Their buffers come from fdshm (Android has no
        |  # /dev/shm). wayvnc's control socket stays here, out of the app's reach. The output
        |  # refreshes at the frame rate sent, not the headless backend's 60: apps are told to draw,
        |  # and labwc renders, only as often as a frame can go out.
        |  rm -f ${GuestPaths.WAYLAND_DIR}/* $GUEST_VNC_DIR/wayvncctl
        |  mode=${'$'}geometry
        |  [ "${'$'}frame_rate" -le $MAX_OUTPUT_REFRESH ] && mode="${'$'}geometry@${'$'}{frame_rate}Hz"
        |  if [ "${'$'}external" = on ]; then
        |    guard= listen="-C $GUEST_WAYVNC_CONFIG 127.0.0.1 ${'$'}port"
        |  else
        |    guard="$NO_INTERNET" listen="-u $GUEST_VNC_DIR/$VNC_SOCKET"
        |  fi
        |  LD_PRELOAD=${Fdshm.ALPINE_PATH} XDG_RUNTIME_DIR=${GuestPaths.WAYLAND_DIR} WLR_BACKENDS=headless WLR_RENDERER=pixman \
        |    WLR_LIBINPUT_NO_DEVICES=1 WLR_HEADLESS_OUTPUTS=1 ${'$'}guard /usr/bin/labwc -C $GUEST_LABWC_DIR -S \
        |    "/bin/sh -c '/usr/bin/wlr-randr --output HEADLESS-1 --custom-mode ${'$'}mode --scale ${'$'}scale; exec /usr/bin/wayvnc -S $GUEST_VNC_DIR/wayvncctl -f ${'$'}frame_rate ${'$'}listen'" &
        |  display=${'$'}!
        |  wait_socket ${GuestPaths.WAYLAND_DIR}/${GuestPaths.WAYLAND_SOCKET} "the Wayland compositor"
        |  # labwc starts wayvnc itself, once its own socket is there.
        |  wait_socket $GUEST_VNC_DIR/wayvncctl wayvnc
        |else
        |  if [ "${'$'}external" = on ]; then
        |    guard="$NO_CONNECT"
        |    listen="-rfbport ${'$'}port -localhost -SecurityTypes VncAuth -PasswordFile $GUEST_VNC_DIR/passwd"
        |  else
        |    guard="$NO_INTERNET"
        |    listen="-rfbport -1 -rfbunixpath $GUEST_VNC_DIR/$VNC_SOCKET -rfbunixmode 0600 -SecurityTypes None"
        |  fi
        |  # No parameters changeable through the VNC extension: any X client could otherwise turn the clipboard back on.
        |  # Only copies reach the viewer, not every selection (PRIMARY).
        |  ${'$'}guard /usr/bin/Xvnc :1 -geometry "${'$'}geometry" -depth 24 ${'$'}listen -NeverShared -DisconnectClients=0 -FrameRate "${'$'}frame_rate" \
        |    -AllowOverride= -nolisten local -extension MIT-SHM -extension GLX -SendPrimary=0 ${'$'}clipboard_args &
        |  display=${'$'}!
        |  wait_socket ${GuestPaths.X11_DIR}/$X_SOCKET Xvnc
        |
        |  DISPLAY=:1 $NO_INTERNET /usr/bin/openbox &
        |fi
        |if [ "${'$'}session_bus" = on ]; then
        |  rm -f ${SessionBus.GUEST_DAEMON_DIR}/${SessionBus.SOCKET} ${SessionBus.GUEST_PROXY_DIR}/${SessionBus.SOCKET}
        |  $NO_INTERNET /usr/bin/dbus-daemon --config-file=${SessionBus.GUEST_CONFIG} --nofork --nopidfile --nosyslog &
        |  i=0
        |  while [ ! -S ${SessionBus.GUEST_DAEMON_DIR}/${SessionBus.SOCKET} ] && [ "${'$'}i" -le 100 ]; do
        |    i=${'$'}((i + 1))
        |    sleep 0.1
        |  done
        |  if [ -S ${SessionBus.GUEST_DAEMON_DIR}/${SessionBus.SOCKET} ]; then
        |    $NO_INTERNET /usr/bin/xdg-dbus-proxy --args=3 3< ${SessionBus.GUEST_PROXY_ARGS} &
        |    if [ "${'$'}camera" = on ]; then
        |      rm -f ${CameraScript.GUEST_PIPEWIRE_DIR}/*
        |      XDG_RUNTIME_DIR=${CameraScript.GUEST_PIPEWIRE_DIR} \
        |        DBUS_SESSION_BUS_ADDRESS=unix:path=${SessionBus.GUEST_DAEMON_DIR}/${SessionBus.SOCKET} $NO_INTERNET /usr/bin/pipewire &
        |    fi
        |  else
        |    echo "flatdroid: the session bus did not start"
        |  fi
        |fi
        |if [ "${'$'}sound" = on ]; then
        |  $NO_INTERNET /usr/bin/pulseaudio -n --use-pid-file=no --daemonize=no --exit-idle-time=-1 --disable-shm=yes \
        |    --disallow-module-loading --disallow-exit --resample-method="${'$'}resampler" -F $GUEST_PULSE_SCRIPT &
        |fi
        |echo "$DISPLAY_READY"
        |wait "${'$'}display"
        |echo "flatdroid: the display exited"
        |""".trimMargin()
}
