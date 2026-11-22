package com.garathon.flatdroid.camera

import android.media.projection.MediaProjection

/**
 * A screen share Android granted, on its way from the session's service, which took it after the
 * user's consent, to the session's screen ([ScreenStreamer]), which takes it when the app is
 * allowed the screen. One at a time: a newer one, or the session's end, stops one not taken.
 */
object ScreenShare {
    private var offered: MediaProjection? = null

    @Synchronized
    fun offer(projection: MediaProjection) {
        offered?.takeIf { it != projection }?.stop()
        offered = projection
    }

    @Synchronized
    fun take(): MediaProjection? = offered.also { offered = null }

    @Synchronized
    fun clear() {
        offered?.stop()
        offered = null
    }
}
