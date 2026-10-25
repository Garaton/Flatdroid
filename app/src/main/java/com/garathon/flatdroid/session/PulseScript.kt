package com.garathon.flatdroid.session

import com.garathon.flatdroid.proot.GuestPaths

/**
 * PulseAudio's start-up script for one session (`pulseaudio -n -F`). Apps play into a null sink;
 * its monitor is streamed as raw PCM to Flatdroid's player through [SIMPLE_SOCKET]. The
 * microphone is the source [MIC_SOURCE]: with [microphone], a pipe source reading what Flatdroid
 * writes to [MIC_PIPE] (silence, or Android's microphone while it streams), else a silent source.
 * The pipe adds no buffer of its own (a null sink fed through the simple protocol holds the sound
 * back about 0.45 s, and its monitor starts up to 2 s late). Either way it is the default
 * source, so an app never records the sink's monitor, its own sound, by default. The app's
 * socket, [NATIVE_SOCKET], comes last, once the defaults are set.
 */
object PulseScript {
    const val NATIVE_SOCKET = "native"
    const val SIMPLE_SOCKET = "simple"
    const val MIC_PIPE = "mic"
    const val MIC_SOURCE = "mic"

    /** [outputRate] is the device's output rate; [micRate] the rate Flatdroid records at. */
    fun text(outputRate: Int, microphone: Boolean, micRate: Int): String {
        val dir = GuestPaths.PULSE_DIR
        val mic = if (microphone) {
            "load-module module-pipe-source source_name=$MIC_SOURCE file=$dir/$MIC_PIPE format=s16le rate=$micRate channels=1 " +
                "source_properties=device.description=Microphone"
        } else {
            "load-module module-null-source source_name=$MIC_SOURCE description=Microphone"
        }
        return (
            listOf(
                "load-module module-null-sink sink_name=out rate=$outputRate sink_properties=device.description=Android",
                "load-module module-simple-protocol-unix socket=$dir/$SIMPLE_SOCKET record=true playback=false source=out.monitor " +
                    "rate=$outputRate format=s16le channels=2",
            ) + mic + listOf(
                "set-default-sink out",
                "set-default-source $MIC_SOURCE",
                "load-module module-native-protocol-unix socket=$dir/$NATIVE_SOCKET auth-anonymous=1",
            )
        ).joinToString("\n", postfix = "\n")
    }
}
