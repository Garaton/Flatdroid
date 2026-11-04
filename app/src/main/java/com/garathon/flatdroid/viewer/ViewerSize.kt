package com.garathon.flatdroid.viewer

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import kotlin.math.roundToInt

/** The size Flatdroid's viewer draws the session in, known before it opens (for the screen's size at launch). */
object ViewerSize {
    /** The viewer's bar, in dp ([ViewerActivity]). */
    const val BAR_HEIGHT_DP = 40

    /**
     * The viewer's drawing area on the display as it is turned now: the whole display, less the
     * bar and a cutout above it ([bar]; an external viewer has neither). Usable from a service.
     */
    fun expected(context: Context, bar: Boolean = true): Pair<Int, Int> {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        @Suppress("DEPRECATION") // the whole display, whatever window asks: what a full-screen viewer gets
        display.getRealSize(size)
        if (!bar) return size.x to size.y
        val cutoutTop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) display.cutout?.safeInsetTop ?: 0 else 0
        val barHeight = (BAR_HEIGHT_DP * context.resources.displayMetrics.density).roundToInt()
        return size.x to size.y - barHeight - cutoutTop
    }
}
