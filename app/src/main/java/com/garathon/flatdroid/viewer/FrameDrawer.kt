package com.garathon.flatdroid.viewer

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect

/**
 * How the framebuffer gets to the screen: [NativeFrames], or [TileFrames] where that can't run.
 * Main thread; [frame], [reveal] and [hashRows] under [RfbClient.frameLock].
 */
interface FrameDrawer {
    /** There are changes out of sight that [reveal] could catch up. */
    val hasHidden: Boolean

    /** The latest update is complete: true when there is something new to draw. [visible] is the part of the framebuffer on screen. */
    fun frame(fb: Framebuffer, visible: Rect): Boolean

    /** More came into sight: catches up what changed there; true when there is something new to draw. */
    fun reveal(fb: Framebuffer, visible: Rect): Boolean

    fun draw(canvas: Canvas, matrix: Matrix, paint: Paint)

    /** [ScrollMeter.hashRows] of the latest frame. */
    fun hashRows(fb: Framebuffer, out: IntArray)
}
