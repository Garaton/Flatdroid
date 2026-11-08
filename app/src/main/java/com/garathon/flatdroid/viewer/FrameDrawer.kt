package com.garathon.flatdroid.viewer

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.SurfaceView

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

    /**
     * Frames go to [surface]'s surface from now on, straight to Android's compositor, instead of
     * [draw] (null: drawn again). True when they will; then [show] places them. Main thread.
     */
    fun useSurface(surface: SurfaceView?): Boolean = false

    /** On the surface: shows the latest frame at [destination] (the surface's pixels). */
    fun show(destination: RectF) {}

    /** [ScrollMeter.hashRows] of the latest frame; false when there is no whole frame to hash now. */
    fun hashRows(fb: Framebuffer, out: IntArray): Boolean
}
