package com.garathon.flatdroid.session

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

    /** Printed with the number of streams recording from the microphone, whenever it changes. */
    const val MIC_USERS_PREFIX = "flatdroid: microphone users "

    /** The count in a [MIC_USERS_PREFIX] line of the helpers' output; null for any other line. */
    fun micUsers(line: String): Int? = line.removePrefix(MIC_USERS_PREFIX).takeIf { it != line }?.trim()?.toIntOrNull()

    /**
     * The helpers' command: [TEXT] at [GUEST_SCRIPT] with its arguments. Without [sound],
     * PulseAudio doesn't start, and so neither does the microphone.
     */
    fun command(geometry: String, frameRate: Int, sound: Boolean, microphone: Boolean, resampler: Resampler): List<String> =
        listOf(
            "/bin/sh", GUEST_SCRIPT, geometry, VNC_PORT.toString(), frameRate.toString(),
            onOff(sound), onOff(sound && microphone), resampler.pulseName,
        )

    private fun onOff(value: Boolean) = if (value) "on" else "off"

    /**
     * Arguments: geometry (WxH), VNC port, frame rate, "on" when the app gets sound (PulseAudio
     * starts only then), "on" when it may use the microphone, and PulseAudio's resample method
     * ([command] builds them). Exits when Xvnc does; Flatdroid ends the session by stopping this proot.
     * `-extension GLX` turns GLX off in Alpine's Xvnc, which still has it (apps use EGL);
     * Flatdroid's own Xvnc build won't need it. `-nolisten local` drops the abstract socket
     * `@/tmp/.X11-unix/X1`: proot doesn't translate abstract names, so it would be open to other
     * users of the device, with no authentication; clients use the socket file in private storage
     * instead. `-NeverShared -DisconnectClients=0`: while a viewer is connected, Xvnc refuses
     * further connections rather than sharing or handing over the session.
     * `--use-pid-file=no`: proot shares the process list, so a stale PID file can match a live
     * process. PulseAudio's modules are in [PulseScript]. With the microphone on, `pactl
     * subscribe` reports how many streams record from it ([MIC_USERS_PREFIX]), so Flatdroid
     * records from Android's microphone only meanwhile.
     */
    val TEXT = """
        |#!/bin/sh
        |geometry=$1
        |port=$2
        |frame_rate=$3
        |sound=$4
        |microphone=$5
        |resampler=$6
        |rm -f /tmp/.X1-lock ${GuestPaths.X11_DIR}/$X_SOCKET ${GuestPaths.PULSE_DIR}/*
        |
        |Xvnc :1 -geometry "${'$'}geometry" -depth 24 -rfbport "${'$'}port" -localhost \
        |  -SecurityTypes VncAuth -PasswordFile $GUEST_VNC_DIR/passwd -NeverShared -DisconnectClients=0 -FrameRate "${'$'}frame_rate" \
        |  -nolisten local -extension MIT-SHM -extension GLX &
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
        |if [ "${'$'}sound" = on ]; then
        |  pulseaudio -n --use-pid-file=no --daemonize=no --exit-idle-time=-1 --disable-shm=yes \
        |    --resample-method="${'$'}resampler" -F $GUEST_PULSE_SCRIPT &
        |fi
        |if [ "${'$'}microphone" = on ]; then
        |  (
        |    i=0
        |    while [ ! -S ${GuestPaths.PULSE_DIR}/${PulseScript.NATIVE_SOCKET} ]; do
        |      i=${'$'}((i + 1))
        |      [ "${'$'}i" -gt 300 ] && exit 0
        |      sleep 0.1
        |    done
        |    export PULSE_SERVER=unix:${GuestPaths.PULSE_DIR}/${PulseScript.NATIVE_SOCKET}
        |    last=0
        |    report() {
        |      mic=${'$'}(pactl list short sources | awk '${'$'}2 == "${PulseScript.MIC_SOURCE}" { print ${'$'}1 }')
        |      users=${'$'}(pactl list short source-outputs | awk -v mic="${'$'}mic" '${'$'}2 == mic' | wc -l)
        |      [ "${'$'}users" = "${'$'}last" ] && return
        |      last=${'$'}users
        |      echo "$MIC_USERS_PREFIX${'$'}users"
        |    }
        |    report
        |    pactl subscribe | while read -r event; do
        |      case "${'$'}event" in *source-output*) report ;; esac
        |    done
        |  ) &
        |fi
        |echo "flatdroid: display ready"
        |wait "${'$'}xvnc"
        |echo "flatdroid: Xvnc exited"
        |""".trimMargin()
}
