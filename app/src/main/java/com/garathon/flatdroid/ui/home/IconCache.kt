package com.garathon.flatdroid.ui.home

import java.io.File
import java.security.MessageDigest

/**
 * Where decoded app icons are kept: `cache/icons/<key>.png`. An SVG, or a picture too big to
 * decode quickly, is turned into a small bitmap once and read from there afterwards; anything
 * else is decoded from the app's own file. The cache can vanish at any time, so a missing file
 * is simply made again.
 */
object IconCache {
    /** A picture at least this big is cached; smaller ones decode faster than they would be read. */
    const val MIN_RASTER_BYTES = 64 * 1024L

    fun dir(cacheDir: File) = File(cacheDir, "icons")

    /** The file's name in the cache: from its path, time and size, so an updated app's icon is a new file. */
    fun key(path: String, modified: Long, length: Long): String =
        MessageDigest.getInstance("SHA-1").digest("$path|$modified|$length".toByteArray()).joinToString("") { "%02x".format(it) }

    fun shouldCache(path: String, length: Long): Boolean = path.endsWith(".svg") || length >= MIN_RASTER_BYTES
}
