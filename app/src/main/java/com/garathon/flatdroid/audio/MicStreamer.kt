package com.garathon.flatdroid.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.LocalSocket
import androidx.core.content.ContextCompat
import com.garathon.flatdroid.util.DebugLog
import java.io.File
import java.io.IOException

/**
 * Android's microphone into PulseAudio: 16-bit mono PCM at [rate] from an [AudioRecord],
 * written to PulseAudio's simple-protocol socket for the microphone. Android shows its
 * microphone indicator only while this runs.
 */
class MicStreamer(private val context: Context, private val socket: File, private val rate: Int) {
    /** One recording run; each has its own flag, so a run still ending can't stop the next. */
    private inner class Run : Thread("microphone") {
        @Volatile var active = true
        @Volatile var connection: LocalSocket? = null

        override fun run() {
            val sock = AudioPlayer.connect(socket) { active } ?: return
            connection = sock
            try {
                record(sock)
            } finally {
                runCatching { sock.close() }
            }
        }

        private fun record(sock: LocalSocket) {
            val chunk = Pcm.bytesFor(rate, 1, CHUNK_MS)
            val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = try {
                @Suppress("MissingPermission") // checked in start()
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, chunk * 4))
            } catch (e: Exception) {
                DebugLog.w("Microphone: ${e.message}")
                return
            }
            try {
                record.startRecording()
                val output = sock.outputStream
                val buffer = ByteArray(chunk)
                while (active) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n < 0) throw IOException("AudioRecord error $n")
                    output.write(buffer, 0, n)
                }
            } catch (e: IOException) {
                if (active) DebugLog.w("Microphone: ${e.message}")
            } catch (e: RuntimeException) {
                // Android refused to record (another app holds the microphone, the permission was
                // revoked): the app records silence rather than Flatdroid stopping.
                DebugLog.w("Microphone: ${e.message}")
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }

        fun finish() {
            active = false
            runCatching { connection?.shutdownOutput() }
            interrupt()
            join(STOP_WAIT_MS)
        }
    }

    private var run: Run? = null

    /** Starts recording, unless it already runs or Android's permission is missing (false then). */
    @Synchronized
    fun start(): Boolean {
        if (run?.isAlive == true) return true
        if (!hasPermission(context)) {
            DebugLog.w("Microphone: Android's permission is missing")
            return false
        }
        run = Run().apply {
            isDaemon = true
            start()
        }
        return true
    }

    @Synchronized
    fun stop() {
        run?.finish()
        run = null
    }

    companion object {
        private const val CHUNK_MS = 10
        private const val STOP_WAIT_MS = 1000L
        /** 48 kHz works on practically every device; 44.1 kHz is the one Android guarantees. */
        private val RATES = listOf(48_000, 44_100)

        fun hasPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** The first rate this device records mono 16-bit at. */
        fun rate(): Int = RATES.firstOrNull {
            AudioRecord.getMinBufferSize(it, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
        } ?: RATES.last()
    }
}
