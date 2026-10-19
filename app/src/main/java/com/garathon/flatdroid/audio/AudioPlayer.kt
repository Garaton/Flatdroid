package com.garathon.flatdroid.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import com.garathon.flatdroid.settings.AudioOutputMode
import com.garathon.flatdroid.util.DebugLog
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

/**
 * Plays a session's sound: raw 16-bit stereo PCM at [rate], read from PulseAudio's
 * simple-protocol socket, written to an [AudioTrack] holding about [latencyMs] of audio, on the
 * Android output path [outputMode] asks for (whose own latency adds to it). It asks for no audio
 * focus, so it mixes with other apps' sound. PulseAudio sends silence while nothing
 * plays; after [SILENCE_PAUSE_MS] of it the track is paused, so Android's audio output can idle.
 */
class AudioPlayer(
    private val socket: File,
    private val rate: Int,
    private val latencyMs: Int,
    private val outputMode: AudioOutputMode,
) {
    @Volatile private var running = false
    @Volatile private var connection: LocalSocket? = null
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread(::run, "audio-player").apply {
            isDaemon = true
            start()
        }
    }

    /** Stops playing and waits briefly for the thread to end. */
    fun stop() {
        running = false
        runCatching { connection?.shutdownInput() }
        thread?.interrupt()
        thread?.join(STOP_WAIT_MS)
    }

    private fun run() {
        val sock = connect(socket) { running } ?: return
        connection = sock
        val track = try {
            buildTrack()
        } catch (e: RuntimeException) {
            // An output Android won't open: the session goes on without sound rather than Flatdroid stopping.
            DebugLog.w("Audio player: ${e.message}")
            runCatching { sock.close() }
            connection = null
            return
        }
        var underruns = 0
        try {
            val input = DataInputStream(sock.inputStream)
            val buffer = ByteArray(Pcm.bytesFor(rate, CHANNELS, CHUNK_MS))
            val pauseAfter = Pcm.bytesFor(rate, CHANNELS, SILENCE_PAUSE_MS)
            var silentBytes = 0
            var playing = false
            while (running) {
                input.readFully(buffer)
                if (Pcm.isSilent(buffer, buffer.size)) {
                    silentBytes = (silentBytes + buffer.size).coerceAtMost(pauseAfter)
                    if (playing && silentBytes >= pauseAfter) {
                        track.pause()
                        track.flush()
                        playing = false
                    }
                    if (!playing) continue
                } else {
                    silentBytes = 0
                }
                if (!playing) {
                    track.play()
                    playing = true
                }
                track.write(buffer, 0, buffer.size)
            }
        } catch (_: EOFException) {
            // PulseAudio has gone: the session is ending.
        } catch (e: IOException) {
            if (running) DebugLog.w("Audio player: ${e.message}")
        } catch (e: RuntimeException) {
            DebugLog.w("Audio player: ${e.message}")
        } finally {
            underruns = track.underrunCount
            track.release()
            runCatching { sock.close() }
            connection = null
        }
        DebugLog.i("Audio player stopped ($underruns underruns)")
    }

    private fun buildTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimum, Pcm.bytesFor(rate, CHANNELS, latencyMs)))
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (outputMode) {
                AudioOutputMode.LOW_LATENCY -> builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                AudioOutputMode.POWER_SAVING -> builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
            }
        }
        return builder.build()
    }

    companion object {
        private const val CHANNELS = 2
        private const val CHUNK_MS = 10
        const val SILENCE_PAUSE_MS = 1000
        private const val STOP_WAIT_MS = 1000L
        private const val CONNECT_RETRY_MS = 100L
        private const val CONNECT_TIMEOUT_MS = 10_000L

        /**
         * Connects to the Unix socket [file], retrying while PulseAudio creates it; null on
         * timeout or once [keepTrying] turns false.
         */
        fun connect(file: File, keepTrying: () -> Boolean): LocalSocket? {
            val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
            while (keepTrying() && System.currentTimeMillis() < deadline) {
                val sock = LocalSocket()
                try {
                    sock.connect(LocalSocketAddress(file.path, LocalSocketAddress.Namespace.FILESYSTEM))
                    return sock
                } catch (_: IOException) {
                    runCatching { sock.close() }
                }
                try {
                    Thread.sleep(CONNECT_RETRY_MS)
                } catch (_: InterruptedException) {
                    return null
                }
            }
            DebugLog.w("Could not connect to ${file.name}")
            return null
        }
    }
}
