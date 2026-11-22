package com.garathon.flatdroid.session.helpers

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
    /** Openbox's theme ([com.garathon.flatdroid.alpine.GuestFiles.themerc]), where Openbox looks for the user's themes. */
    const val GUEST_OPENBOX_THEMERC = "/root/.themes/${com.garathon.flatdroid.alpine.GuestFiles.OPENBOX_THEME}/openbox-3/themerc"

    /** In front of a helper's command: fdsec again, adding no Internet sockets to the filter it already has. */
    private val NO_INTERNET = (listOf(Fdsec.ALPINE_PATH) + Fdsec.flags(Fdsec.Filter(noInternet = true)) + "--").joinToString(" ")

    /** foot's command: [Fdsec.holdTerminal] writing to [GUEST_TTY] (a failure to `GUEST_TTY.error`), again without Internet sockets. */
    private val HOLD_TERMINAL = Fdsec.holdTerminal(Fdsec.Filter(noInternet = true), GUEST_TTY).joinToString(" ")

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
        interfaceSize: Int = 100, memfd: Boolean = true, terminal: Boolean = false,
    ): List<String> =
        listOf(
            "/bin/sh", GUEST_SCRIPT, geometry, VNC_PORT.toString(), frameRate.toString(),
            onOff(sound), resampler.pulseName, onOff(sessionBus), onOff(sessionBus && camera),
            onOff(clipboard), onOff(wayland), onOff(externalViewer), scale(interfaceSize),
            onOff(memfd), onOff(wayland && terminal),
        )

    /**
     * Whether a kernel of this [release] ("4.19.191-g1234", say) has `memfd_create` (3.17 and later).
     * Without it PulseAudio would share memory through POSIX shared memory, which the app and the
     * helpers can't reach in each other's roots, so it runs without shared memory then. Unreadable: true (verify).
     */
    fun kernelHasMemfd(release: String): Boolean {
        val numbers = Regex("^(\\d+)\\.(\\d+)").find(release)?.groupValues ?: return true
        val major = numbers[1].toInt()
        val minor = numbers[2].toInt()
        return major > 3 || major == 3 && minor >= 17
    }

    /** A percent as the output's scale ("1.5" for 150). */
    fun scale(percent: Int): String = "${percent / 100}.${(percent % 100).toString().padStart(2, '0')}"

    /** wayvnc's configuration ([WaylandVnc.config]), next to Xvnc's password file. */
    const val GUEST_WAYVNC_CONFIG = "$GUEST_VNC_DIR/wayvnc.conf"
    /** labwc's configuration directory ([com.garathon.flatdroid.alpine.GuestFiles.labwcRc]). */
    const val GUEST_LABWC_DIR = "$GUEST_VNC_DIR/labwc"
    /** A terminal app's terminal: foot's configuration, its fonts, and where its command writes the terminal's name ([Fdsec.holdTerminal]). */
    const val GUEST_FOOT_CONFIG = "$GUEST_VNC_DIR/foot.ini"
    const val GUEST_TERMINAL_FONTS = "$GUEST_VNC_DIR/fonts.conf"
    const val GUEST_TTY = "$GUEST_VNC_DIR/tty"
    /** Android's fonts, read by the terminal ([com.garathon.flatdroid.alpine.GuestFiles.terminalFonts]). */
    const val GUEST_ANDROID_FONTS = "/run/flatdroid/android-fonts"

    /** Above this frame rate (uncapped) the output keeps the headless backend's refresh. */
    const val MAX_OUTPUT_REFRESH = 240

    private fun onOff(value: Boolean) = if (value) "on" else "off"

    /**
     * Arguments: geometry (WxH), VNC port, frame rate, "on" when the app gets sound (PulseAudio
     * starts only then), PulseAudio's resample method, "on" for the session bus, "on" for PipeWire, "on" when the clipboard is shared ([command]
     * builds them; off adds `-AcceptCutText=0 -SendCutText=0` to Xvnc), "on" for a Wayland session (labwc and wayvnc instead of
     * Xvnc and Openbox), "on" for an external viewer (the TCP port and login; else the viewer's Unix
     * socket), the Wayland output's scale ([scale]), "on" when PulseAudio may share memory through memfd ([kernelHasMemfd]), and "on"
     * for a terminal app's terminal (foot, in a Wayland session only); title bars come from Openbox's and labwc's configurations. Exits when the display does; Flatdroid ends the session by stopping this proot.
     * `-extension GLX` turns GLX off in Alpine's Xvnc, which has it (apps use EGL). `-nolisten local` drops the abstract socket
     * `@/tmp/.X11-unix/X1`: proot doesn't translate abstract names, so it would be open to other
     * users of the device, with no authentication; clients use the socket file in private storage
     * instead. `-NeverShared -DisconnectClients=1`: one viewer at a time, and a new one that logs in
     * replaces the one connected. A viewer that is closed but not yet ended (Android can freeze
     * its process) keeps its connection open, and refusing new ones would lock the user out.
     * `--use-pid-file=no`: proot shares the process list, so a stale PID file can match a live
     * process. `--disallow-module-loading` (after [PulseScript]'s modules are loaded) and
     * `--disallow-exit`: the app is PulseAudio's client, and a module it could load (the CLI
     * protocol's, say) loads any library by path, which would run the app's code among the
     * helpers. PulseAudio's modules are in [PulseScript]; whether the app records from the
     * microphone, Flatdroid tells from its pipe ([com.garathon.flatdroid.audio.MicStreamer]), so
     * nothing here starts a process during the session, which the app could see and take over.
     * The session bus's proxy reads its arguments from a file ([SessionBus.proxyArgs]), so none
     * pass through the shell.
     * The whole script runs behind fdsec's filter; every helper but Xvnc and, with an external
     * viewer, wayvnc, which serve the VNC port, also runs behind a second one without Internet
     * sockets, so exploiting one from the app gives no network the app may not have.
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
        |memfd=${'$'}{12}
        |terminal=${'$'}{13}
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
        |# The session bus and PulseAudio need nothing from the display: they start first and come up
        |# while it does. Flatdroid waits for their sockets only after the display is there.
        |if [ "${'$'}session_bus" = on ]; then
        |  rm -f ${SessionBus.GUEST_DAEMON_DIR}/${SessionBus.SOCKET} ${SessionBus.GUEST_PROXY_DIR}/${SessionBus.SOCKET}
        |  $NO_INTERNET /usr/bin/dbus-daemon --config-file=${SessionBus.GUEST_CONFIG} --nofork --nopidfile --nosyslog &
        |fi
        |# PulseAudio shares memory with the app through memfd: an app that writes many small blocks costs
        |# less than through the socket, whose every call proot traces. Where the kernel has no memfd it
        |# shares nothing: the POSIX fallback needs one /dev/shm for the app and the helpers, which they don't have.
        |if [ "${'$'}sound" = on ]; then
        |  shm_args="--disable-shm=no --enable-memfd=yes"
        |  [ "${'$'}memfd" = on ] || shm_args="--disable-shm=yes"
        |  $NO_INTERNET /usr/bin/pulseaudio -n --use-pid-file=no --daemonize=no --exit-idle-time=-1 ${'$'}shm_args \
        |    --disallow-module-loading --disallow-exit --resample-method="${'$'}resampler" -F $GUEST_PULSE_SCRIPT &
        |fi
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
        |  wl_mode="--output HEADLESS-1 --custom-mode ${'$'}mode --scale ${'$'}scale"
        |  if [ "${'$'}external" = on ]; then
        |    # wayvnc has to listen on TCP, so it can't run behind the second filter, but the compositor,
        |    # which parses everything the app sends, does. wayvnc starts here, once the output has its mode.
        |    LD_PRELOAD=${Fdshm.ALPINE_PATH} XDG_RUNTIME_DIR=${GuestPaths.WAYLAND_DIR} WLR_BACKENDS=headless WLR_RENDERER=pixman \
        |      WLR_LIBINPUT_NO_DEVICES=1 WLR_HEADLESS_OUTPUTS=1 $NO_INTERNET /usr/bin/labwc -C $GUEST_LABWC_DIR &
        |    display=${'$'}!
        |    wait_socket ${GuestPaths.WAYLAND_DIR}/${GuestPaths.WAYLAND_SOCKET} "the Wayland compositor"
        |    LD_PRELOAD=${Fdshm.ALPINE_PATH} XDG_RUNTIME_DIR=${GuestPaths.WAYLAND_DIR} WAYLAND_DISPLAY=${GuestPaths.WAYLAND_SOCKET} \
        |      /usr/bin/wlr-randr ${'$'}wl_mode
        |    LD_PRELOAD=${Fdshm.ALPINE_PATH} XDG_RUNTIME_DIR=${GuestPaths.WAYLAND_DIR} WAYLAND_DISPLAY=${GuestPaths.WAYLAND_SOCKET} \
        |      /usr/bin/wayvnc -S $GUEST_VNC_DIR/wayvncctl -f ${'$'}frame_rate -C $GUEST_WAYVNC_CONFIG 127.0.0.1 ${'$'}port &
        |  else
        |    # labwc starts wayvnc itself, once its own socket is there, behind the same second filter.
        |    LD_PRELOAD=${Fdshm.ALPINE_PATH} XDG_RUNTIME_DIR=${GuestPaths.WAYLAND_DIR} WLR_BACKENDS=headless WLR_RENDERER=pixman \
        |      WLR_LIBINPUT_NO_DEVICES=1 WLR_HEADLESS_OUTPUTS=1 $NO_INTERNET /usr/bin/labwc -C $GUEST_LABWC_DIR -S \
        |      "/bin/sh -c '/usr/bin/wlr-randr ${'$'}wl_mode; exec /usr/bin/wayvnc -S $GUEST_VNC_DIR/wayvncctl -f ${'$'}frame_rate -u $GUEST_VNC_DIR/$VNC_SOCKET'" &
        |    display=${'$'}!
        |    wait_socket ${GuestPaths.WAYLAND_DIR}/${GuestPaths.WAYLAND_SOCKET} "the Wayland compositor"
        |  fi
        |  wait_socket $GUEST_VNC_DIR/wayvncctl wayvnc
        |  # A terminal app's terminal: foot, whose command gives the terminal up and names it for the
        |  # app's root, which takes it over; --hold, since that command's first process exits to give it
        |  # up. foot parses what the app writes, so it runs behind the second filter.
        |  if [ "${'$'}terminal" = on ]; then
        |    rm -f $GUEST_TTY $GUEST_TTY.new
        |    LD_PRELOAD=${Fdshm.ALPINE_PATH} XDG_RUNTIME_DIR=${GuestPaths.WAYLAND_DIR} WAYLAND_DISPLAY=${GuestPaths.WAYLAND_SOCKET} \
        |      FONTCONFIG_FILE=$GUEST_TERMINAL_FONTS $NO_INTERNET /usr/bin/foot --hold -c $GUEST_FOOT_CONFIG -- $HOLD_TERMINAL &
        |  fi
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
        |  ${'$'}guard /usr/bin/Xvnc :1 -geometry "${'$'}geometry" -depth 24 ${'$'}listen -NeverShared -DisconnectClients=1 -FrameRate "${'$'}frame_rate" \
        |    -AllowOverride= -nolisten local -extension MIT-SHM -extension GLX -SendPrimary=0 ${'$'}clipboard_args &
        |  display=${'$'}!
        |  wait_socket ${GuestPaths.X11_DIR}/$X_SOCKET Xvnc
        |
        |  DISPLAY=:1 $NO_INTERNET /usr/bin/openbox &
        |fi
        |if [ "${'$'}session_bus" = on ]; then
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
        |echo "$DISPLAY_READY"
        |wait "${'$'}display"
        |echo "flatdroid: the display exited"
        |""".trimMargin()
}
