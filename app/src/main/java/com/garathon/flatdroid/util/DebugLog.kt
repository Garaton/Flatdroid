package com.garathon.flatdroid.util

import android.util.Log
import com.garathon.flatdroid.BuildConfig
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Logcat output under the tag "Flatdroid", in debug builds only (`adb logcat -s Flatdroid`).
 * Once [init] has run, the same lines also go to `cache/logs/flatdroid.log`, since logcat's
 * shared buffer drops them within minutes: `adb shell run-as com.garathon.flatdroid cat
 * cache/logs/flatdroid.log`. At [MAX_FILE_BYTES] the file becomes `flatdroid.log.1` (replacing
 * the previous one), so the two hold at most twice that.
 */
object DebugLog {
    private const val TAG = "Flatdroid"
    private const val MAX_FILE_BYTES = 2L shl 20

    @Volatile private var file: File? = null
    // One writer thread keeps lines in order and callers off the disk.
    private val writer by lazy { Executors.newSingleThreadExecutor { Thread(it, "debug-log").apply { isDaemon = true } } }
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Starts writing to `<cacheDir>/logs/flatdroid.log` (debug builds only). */
    fun init(cacheDir: File) {
        if (BuildConfig.DEBUG) file = File(cacheDir, "logs/flatdroid.log")
    }

    fun i(message: String) {
        if (BuildConfig.DEBUG) {
            Log.i(TAG, message)
            append("I", message)
        }
    }

    fun w(message: String) {
        if (BuildConfig.DEBUG) {
            Log.w(TAG, message)
            append("W", message)
        }
    }

    private fun append(level: String, message: String) {
        val target = file ?: return
        val line = "${synchronized(time) { time.format(Date()) }} $level $message\n"
        writer.execute {
            try {
                target.parentFile?.mkdirs()
                if (target.length() > MAX_FILE_BYTES) target.renameTo(File(target.path + ".1"))
                FileWriter(target, true).use { it.write(line) }
            } catch (_: Exception) {
                // The cache can be cleared at any time; logcat still has the line.
            }
        }
    }
}
