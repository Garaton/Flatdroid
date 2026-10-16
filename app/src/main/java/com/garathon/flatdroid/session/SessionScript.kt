package com.garathon.flatdroid.session

import com.garathon.flatdroid.proot.GuestPaths

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
    const val GUEST_VNC_DIR = "/run/flatdroid/vnc"
    const val GUEST_OPENBOX_RC = "/root/.config/openbox/rc.xml"

    /**
     * Arguments: geometry (WxH), VNC port. Exits when Xvnc does; Flatdroid ends the session by
     * stopping this proot. `-extension GLX` turns GLX off in Alpine's Xvnc, which still has it
     * (apps use EGL); Flatdroid's own Xvnc build won't need it. `--use-pid-file=no`: proot
     * shares the process list, so a stale PID file can match a live process.
     */
    val TEXT = """
        |#!/bin/sh
        |geometry=$1
        |port=$2
        |rm -f /tmp/.X1-lock ${GuestPaths.X11_DIR}/$X_SOCKET ${GuestPaths.PULSE_DIR}/native ${GuestPaths.PULSE_DIR}/simple
        |
        |Xvnc :1 -geometry "${'$'}geometry" -depth 24 -rfbport "${'$'}port" -localhost \
        |  -SecurityTypes VncAuth -PasswordFile $GUEST_VNC_DIR/passwd \
        |  -extension MIT-SHM -extension GLX &
        |xvnc=${'$'}!
        |i=0
        |while [ ! -S ${GuestPaths.X11_DIR}/$X_SOCKET ]; do
        |  kill -0 "${'$'}xvnc" 2>/dev/null || { echo "flatdroid: Xvnc exited"; exit 1; }
        |  i=${'$'}((i + 1))
        |  [ "${'$'}i" -gt 300 ] && { echo "flatdroid: Xvnc did not start"; exit 1; }
        |  sleep 0.1
        |done
        |
        |DISPLAY=:1 openbox &
        |pulseaudio -n --use-pid-file=no --daemonize=no --exit-idle-time=-1 --disable-shm=yes \
        |  -L "module-null-sink sink_name=out rate=48000" \
        |  -L "module-native-protocol-unix socket=${GuestPaths.PULSE_DIR}/native auth-anonymous=1" \
        |  -L "module-simple-protocol-unix socket=${GuestPaths.PULSE_DIR}/simple record=true playback=false source=out.monitor rate=48000 format=s16le channels=2" &
        |echo "flatdroid: display ready"
        |wait "${'$'}xvnc"
        |echo "flatdroid: Xvnc exited"
        |""".trimMargin()
}
