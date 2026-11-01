package com.garathon.flatdroid.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.core.content.ContextCompat
import com.garathon.flatdroid.util.DebugLog
import java.io.File
import java.io.FileDescriptor

/**
 * The microphone's pipe into PulseAudio for one session: 16-bit mono PCM at [rate], silence until
 * [start], Android's microphone (an [AudioRecord]) from then until [stop], until [close]. Android
 * shows its microphone indicator only while the microphone streams.
 *
 * PulseAudio reads the pipe only while an app records (measured: about 100 KB/s while recording at
 * 48 kHz, next to nothing otherwise), so an app gets sound the moment it starts recording, before
 * Flatdroid has heard of it, and the pipe itself tells whether an app records ([onRecording]):
 * more than the pipe holds written without it filling up means it is read; full for
 * [STOPPED_AFTER_MS] means it isn't any more. No process among the helpers has to watch
 * PulseAudio for it (one started during the session would be visible to the app). The pipe is
 * shrunk to one page, so what waits in it stays short (on 4 KB pages, 40 ms of silence at 48 kHz).
 * While nothing reads it, it fills and this thread sleeps in poll(); silence is written at the
 * clock's pace only while an app records without the microphone (until it is allowed, or for
 * good when denied).
 */
class MicStreamer(
    private val context: Context,
    private val pipe: File,
    private val rate: Int,
    /** Whether an app records, each time that changes (on this streamer's thread). */
    private val onRecording: (Boolean) -> Unit = {},
) {
    @Volatile private var streaming = false
    @Volatile private var closed = false
    private val thread = Thread(::run, "microphone").apply {
        isDaemon = true
        start()
    }

    /** Streams Android's microphone, unless Android's permission is missing (false then). */
    fun start(): Boolean {
        if (!hasPermission(context)) {
            DebugLog.w("Microphone: Android's permission is missing")
            return false
        }
        streaming = true
        return true
    }

    /** Back to silence. */
    fun stop() {
        streaming = false
    }

    fun close() {
        closed = true
        streaming = false
        thread.join(STOP_WAIT_MS)
    }

    private fun run() {
        val fd = open() ?: return
        val chunk = Pcm.bytesFor(rate, 1, CHUNK_MS)
        val buffer = ByteArray(chunk)
        val silence = ByteArray(chunk)
        var record: AudioRecord? = null
        var next = System.nanoTime()
        val capacity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Os.fcntlInt(fd, F_GETPIPE_SZ, 0)
            } catch (_: ErrnoException) {
                DEFAULT_PIPE_BYTES
            }
        } else {
            DEFAULT_PIPE_BYTES
        }
        val reading = Reading(capacity)
        try {
            while (!closed) {
                if (streaming && record == null) {
                    record = record(chunk)
                    if (record == null) streaming = false
                } else if (!streaming && record != null) {
                    release(record)
                    record = null
                    next = System.nanoTime()
                }
                if (record != null) {
                    val n = try {
                        record.read(buffer, 0, buffer.size)
                    } catch (e: RuntimeException) {
                        // Android stopped the recording (another app took the microphone, the
                        // permission was revoked): silence rather than Flatdroid stopping.
                        DebugLog.w("Microphone: ${e.message}")
                        -1
                    }
                    if (n < 0) {
                        release(record)
                        record = null
                        streaming = false
                        next = System.nanoTime()
                    } else if (n == 0) {
                        // Nothing yet (read() may return none even when blocking): no busy loop.
                        Thread.sleep(CHUNK_MS.toLong())
                    } else if (write(fd, buffer, n)) {
                        reading.wrote(n)
                    } else {
                        // Nobody reads (the app paused or stopped recording): dropped, never queued.
                        reading.full()
                    }
                } else if (write(fd, silence, silence.size)) {
                    reading.wrote(silence.size)
                    next += CHUNK_MS * 1_000_000L
                    val ahead = (next - System.nanoTime()) / 1_000_000L
                    if (ahead > 0) Thread.sleep(ahead) else if (ahead < -CHUNK_MS) next = System.nanoTime()
                } else {
                    reading.full()
                    // While an app reads, it gets STOPPED_AFTER_MS to drain the pipe; otherwise
                    // the wait is long, but [closed] and [streaming] are checked between waits.
                    waitWritable(fd, if (reading.now) STOPPED_AFTER_MS.toInt() else IDLE_CHECK_MS)
                    next = System.nanoTime()
                }
            }
        } catch (e: ErrnoException) {
            // PulseAudio is gone (the session ends).
            if (!closed) DebugLog.w("Microphone: ${e.message}")
        } finally {
            record?.let(::release)
            runCatching { Os.close(fd) }
        }
    }

    /**
     * Whether PulseAudio reads the pipe, from what the writes do: [capacity] bytes written since it
     * was last full can only fit if something reads; full for [STOPPED_AFTER_MS] means nothing does.
     */
    private inner class Reading(private val capacity: Int) {
        var now = false
            private set
        private var sinceFull = 0L
        private var fullSince = 0L

        fun wrote(bytes: Int) {
            fullSince = 0L
            sinceFull += bytes
            if (sinceFull > capacity) set(true)
        }

        fun full() {
            sinceFull = 0L
            val time = System.nanoTime()
            if (fullSince == 0L) fullSince = time
            else if (time - fullSince >= STOPPED_AFTER_MS * 1_000_000L) set(false)
        }

        private fun set(value: Boolean) {
            if (value == now) return
            now = value
            onRecording(value)
        }
    }

    /** The pipe, once PulseAudio has made it (it holds the reading end); null when closed or timed out. */
    private fun open(): FileDescriptor? {
        val deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS
        while (!closed && System.currentTimeMillis() < deadline) {
            try {
                // O_CLOEXEC: not handed to the processes Flatdroid starts.
                val fd = Os.open(pipe.path, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK or O_CLOEXEC, 0)
                // Os.fcntlInt is public from Android 11; before, the pipe keeps its default size
                // (more silence waits in it, and recording is noticed a little later).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        // The kernel rounds this up to one page.
                        Os.fcntlInt(fd, F_SETPIPE_SZ, 1)
                    } catch (e: ErrnoException) {
                        DebugLog.w("Microphone: the pipe keeps its size: ${e.message}")
                    }
                }
                return fd
            } catch (e: ErrnoException) {
                // Not made yet (ENOENT), or PulseAudio isn't reading it yet (ENXIO).
                Thread.sleep(OPEN_RETRY_MS)
            }
        }
        if (!closed) DebugLog.w("Microphone: PulseAudio's pipe didn't appear; the app records nothing this session")
        return null
    }

    /** False when the pipe is full: nobody reads it. A chunk is below PIPE_BUF, so it's never split. */
    private fun write(fd: FileDescriptor, bytes: ByteArray, count: Int): Boolean = try {
        Os.write(fd, bytes, 0, count)
        true
    } catch (e: ErrnoException) {
        if (e.errno != OsConstants.EAGAIN) throw e
        false
    }

    /** Waits up to [timeoutMs] for room in the pipe (PulseAudio reading it). */
    private fun waitWritable(fd: FileDescriptor, timeoutMs: Int) {
        val poll = StructPollfd().apply {
            this.fd = fd
            events = OsConstants.POLLOUT.toShort()
        }
        if (Os.poll(arrayOf(poll), timeoutMs) > 0 && poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP) != 0) {
            throw ErrnoException("poll", OsConstants.EPIPE)
        }
    }

    private fun record(chunk: Int): AudioRecord? {
        val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return try {
            @Suppress("MissingPermission") // checked in start()
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, chunk * 4))
                .apply { startRecording() }
        } catch (e: Exception) {
            DebugLog.w("Microphone: ${e.message}")
            null
        }
    }

    private fun release(record: AudioRecord) {
        runCatching { record.stop() }
        record.release()
    }

    companion object {
        private const val CHUNK_MS = 10
        private const val STOP_WAIT_MS = 1000L
        private const val OPEN_TIMEOUT_MS = 30_000L
        private const val OPEN_RETRY_MS = 100L
        private const val IDLE_CHECK_MS = 500
        /** Linux's O_CLOEXEC (the same on arm64 and x86_64); OsConstants has it only from Android 8.1. */
        private const val O_CLOEXEC = 0x80000
        /** Linux's fcntls for a pipe's size, which OsConstants doesn't have. */
        private const val F_SETPIPE_SZ = 1031
        private const val F_GETPIPE_SZ = 1032
        /** A pipe's size when it can't be asked (Linux's default). */
        private const val DEFAULT_PIPE_BYTES = 65536
        /** How long the pipe stays full before the app counts as no longer recording. */
        private const val STOPPED_AFTER_MS = 200L
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
