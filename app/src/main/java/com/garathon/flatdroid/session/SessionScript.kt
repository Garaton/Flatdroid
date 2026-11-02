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
    /** VNC port, on localhost only; not the default 5901. */
    const val VNC_PORT = 14400
    const val X_SOCKET = "X1"

    // Guest paths of the binds Flatdroid adds to the helpers' proot.
    const val GUEST_SCRIPT = "/run/flatdroid/session.sh"
    const val GUEST_PULSE_SCRIPT = "/run/flatdroid/pulse.pa"
    const val GUEST_VNC_DIR = "/run/flatdroid/vnc"
    const val GUEST_OPENBOX_RC = "/root/.config/openbox/rc.xml"

    /** In front of a helper's command: fdsec again, adding no Internet sockets to the filter it already has. */
    private val NO_INTERNET = (listOf(Fdsec.ALPINE_PATH) + Fdsec.flags(Fdsec.Filter(noInternet = true)) + "--").joinToString(" ")

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
        camera: Boolean = false, clipboard: Boolean = true, wayland: Boolean = false,
    ): List<String> =
        listOf(
            "/bin/sh", GUEST_SCRIPT, geometry, VNC_PORT.toString(), frameRate.toString(),
            onOff(sound), resampler.pulseName, onOff(sessionBus), onOff(sessionBus && camera),
            onOff(clipboard), onOff(wayland),
        )

    /** wayvnc's configuration ([WaylandVnc.config]), next to Xvnc's password file. */
    const val GUEST_WAYVNC_CONFIG = "$GUEST_VNC_DIR/wayvnc.conf"

    private fun onOff(value: Boolean) = if (value) "on" else "off"

    /**
     * Arguments: geometry (WxH), VNC port, frame rate, "on" when the app gets sound (PulseAudio
     * starts only then), PulseAudio's resample method, "on" for the session bus, "on" for PipeWire, "on" when the clipboard is shared ([command]
     * builds them; off adds `-AcceptCutText=0 -SendCutText=0` to Xvnc), and "on" for a Wayland session (cage and wayvnc instead of
     * Xvnc and Openbox). Exits when the display does; Flatdroid ends the session by stopping this proot.
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
        |clipboard_args=
        |[ "${'$'}clipboard" = on ] || clipboard_args="-AcceptCutText=0 -SendCutText=0"
        |rm -f /tmp/.X1-lock ${GuestPaths.X11_DIR}/$X_SOCKET ${GuestPaths.PULSE_DIR}/*
        |
        |if [ "${'$'}wayland" = on ]; then
        |  # An app that asks only for Wayland: cage (headless, drawn in software) with wayvnc as its
        |  # one client, on the VNC port. Their buffers come from fdshm (Android has no /dev/shm).
        |  # wayvnc's control socket stays here, out of the app's reach. No Openbox: cage shows one
        |  # app full screen.
        |  rm -f ${GuestPaths.WAYLAND_DIR}/*
        |  LD_PRELOAD=${Fdshm.ALPINE_PATH} XDG_RUNTIME_DIR=${GuestPaths.WAYLAND_DIR} WLR_BACKENDS=headless WLR_RENDERER=pixman \
        |    WLR_LIBINPUT_NO_DEVICES=1 WLR_HEADLESS_OUTPUTS=1 /usr/bin/cage -- /bin/sh -c \
        |    '/usr/bin/wlr-randr --output HEADLESS-1 --custom-mode "${'$'}1"; exec /usr/bin/wayvnc -C "${'$'}2" -S "${'$'}3" -f "${'$'}4" 127.0.0.1 "${'$'}5"' \
        |    sh "${'$'}geometry" $GUEST_WAYVNC_CONFIG $GUEST_VNC_DIR/wayvncctl "${'$'}frame_rate" "${'$'}port" &
        |  display=${'$'}!
        |  i=0
        |  while [ ! -S ${GuestPaths.WAYLAND_DIR}/${GuestPaths.WAYLAND_SOCKET} ]; do
        |    kill -0 "${'$'}display" 2>/dev/null || { echo "flatdroid: the Wayland compositor exited"; exit 1; }
        |    i=${'$'}((i + 1))
        |    [ "${'$'}i" -gt 300 ] && { echo "flatdroid: the Wayland compositor did not start"; exit 1; }
        |    sleep 0.1
        |  done
        |else
        |  Xvnc :1 -geometry "${'$'}geometry" -depth 24 -rfbport "${'$'}port" -localhost \
        |    -SecurityTypes VncAuth -PasswordFile $GUEST_VNC_DIR/passwd -NeverShared -DisconnectClients=0 -FrameRate "${'$'}frame_rate" \
        |    -nolisten local -extension MIT-SHM -extension GLX ${'$'}clipboard_args &
        |  display=${'$'}!
        |  i=0
        |  while [ ! -S ${GuestPaths.X11_DIR}/$X_SOCKET ]; do
        |    kill -0 "${'$'}display" 2>/dev/null || { echo "flatdroid: Xvnc exited"; exit 1; }
        |    i=${'$'}((i + 1))
        |    [ "${'$'}i" -gt 300 ] && { echo "flatdroid: Xvnc did not start"; exit 1; }
        |    sleep 0.1
        |  done
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
        |echo "flatdroid: display ready"
        |wait "${'$'}display"
        |echo "flatdroid: the display exited"
        |""".trimMargin()
}
