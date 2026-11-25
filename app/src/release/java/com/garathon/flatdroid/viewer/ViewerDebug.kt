package com.garathon.flatdroid.viewer

import com.garathon.flatdroid.viewer.protocol.RfbClient
import java.io.File

/** Release builds: no measuring (debug builds have the real one). */
object ViewerDebug {
    fun rawMeter(): ((Int, Long) -> Unit)? = null

    fun copyRectMeter(): ((Int, Long) -> Unit)? = null

    fun tileSize(cacheDir: File): Int? = null

    fun clickReleaseMs(cacheDir: File): Long? = null

    fun postMeter(): ((Long) -> Unit)? = null

    fun connected(client: RfbClient, cacheDir: File, zoom: (Float) -> Unit) {}

    fun updateDone() {}
}
