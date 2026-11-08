package com.garathon.flatdroid.viewer

import com.garathon.flatdroid.util.DebugLog
import java.io.File
import java.util.Locale
import java.util.Properties

/**
 * Debug builds' measuring of the viewer: a benchmark from `viewer-bench.properties` in the app's
 * cache ([BenchSettings], [ViewerBench]), and the rates of the pixels read, logged every 5 s.
 * Release builds have a version of this object that does nothing.
 */
object ViewerDebug {
    const val FILE = "viewer-bench.properties"

    private var bench: ViewerBench? = null

    // Reading thread: updates and Raw pixels read in the current period.
    private var since = 0L
    private var updates = 0
    private var bytes = 0L
    private var nanos = 0L
    private var copyRects = 0
    private var copyRectPixels = 0L
    private var copyRectNanos = 0L

    fun rawMeter(): (Int, Long) -> Unit = { count, time ->
        bytes += count
        nanos += time
        bench?.pixels(count)
    }

    fun copyRectMeter(): (Int, Long) -> Unit = { pixels, time ->
        copyRects++
        copyRectPixels += pixels
        copyRectNanos += time
    }

    /** `tiles=192`: [TileFrames] of that size, whatever the device could do. */
    fun tileSize(cacheDir: File): Int? = read(cacheDir).getProperty("tiles")?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    // Reading thread: time spent showing updates (posting them to the surface's queue).
    private var posts = 0
    private var postNanos = 0L
    private var postMax = 0L

    fun postMeter(): (Long) -> Unit = { nanos ->
        posts++
        postNanos += nanos
        postMax = maxOf(postMax, nanos)
    }


    /** Starts the benchmark, if the file asks for one; [zoom] zooms the view in for it. */
    fun connected(client: RfbClient, cacheDir: File, zoom: (Float) -> Unit) {
        since = 0L
        bench = BenchSettings.from(read(cacheDir))?.let { settings ->
            if (settings.zoom > 1f) zoom(settings.zoom)
            ViewerBench(settings, client).also { it.start() }
        }
    }

    fun updateDone() {
        bench?.updateDone()
        updates++
        val now = System.currentTimeMillis()
        if (since == 0L) since = now
        val elapsed = now - since
        if (elapsed < 5_000) return
        val mb = bytes / 1e6
        val readMs = nanos / 1e6
        DebugLog.i(
            "Viewer: %d updates/s, %.1f MB/s of pixels, reading them took %.0f ms/s (%.0f MB/s while reading)".format(
                Locale.ROOT, updates * 1000 / elapsed, mb * 1000 / elapsed, readMs * 1000 / elapsed,
                if (readMs > 0) mb * 1000 / readMs else 0.0,
            ),
        )
        if (copyRects > 0) {
            DebugLog.i(
                "Viewer: %d CopyRects/s, %.2f Mpx/s, copying them took %.2f ms/s".format(
                    Locale.ROOT, copyRects * 1000 / elapsed, copyRectPixels / 1e6 * 1000 / elapsed, copyRectNanos / 1e6 * 1000 / elapsed,
                ),
            )
        }
        if (posts > 0) {
            DebugLog.i(
                "Viewer: posting took %.1f ms per update, at most %.1f ms".format(
                    Locale.ROOT, postNanos / 1e6 / posts, postMax / 1e6,
                ),
            )
        }
        posts = 0; postNanos = 0; postMax = 0
        since = now; updates = 0; bytes = 0; nanos = 0
        copyRects = 0; copyRectPixels = 0; copyRectNanos = 0
    }

    private fun read(cacheDir: File): Properties = Properties().apply {
        runCatching { File(cacheDir, FILE).takeIf { it.isFile }?.inputStream()?.use(::load) }
    }
}

/** A benchmark's settings: `bench` (the workload) and the `bench…` keys. */
data class BenchSettings(
    val workload: String,
    val seconds: Int = 30,
    /** Inputs a second. */
    val rate: Double = 4.0,
    /** Wait after connecting, before preparing the workload. */
    val settleMs: Long = 3_000,
    /** Zoom for the benchmark (above 1, only part of the screen is shown). */
    val zoom: Float = 1f,
) {
    companion object {
        fun from(props: Properties): BenchSettings? {
            val workload = props.getProperty("bench")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return BenchSettings(
                workload,
                seconds = props.getProperty("benchSeconds")?.trim()?.toIntOrNull() ?: 30,
                rate = props.getProperty("benchRate")?.trim()?.toDoubleOrNull() ?: 4.0,
                settleMs = props.getProperty("benchSettleMs")?.trim()?.toLongOrNull() ?: 3_000,
                zoom = props.getProperty("benchZoom")?.trim()?.toFloatOrNull() ?: 1f,
            )
        }
    }
}
