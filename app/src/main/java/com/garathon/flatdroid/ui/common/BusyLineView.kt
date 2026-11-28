package com.garathon.flatdroid.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Material's indeterminate progress line, drawn on a surface of its own above the window. Inside
 * Compose, any animation re-records the root view, and Android then redraws the whole window on
 * every frame (on a Cortex-A53 about 13 ms of the render thread per frame, most of a core for as
 * long as a task runs). On its own surface only this line is drawn, and the window stays as it is.
 *
 * The motion follows Material 3's indeterminate line: two lines whose ends travel the width on
 * keyframes over [CYCLE_MS], the track drawn in the gaps between them.
 */
@SuppressLint("ViewConstructor")
class BusyLineView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private var color = 0
    private var trackColor = 0
    private var background = 0
    private var gapPx = 0f
    private var rtl = false
    private var startNanos = 0L
    private var running = false

    init {
        // Above the window and opaque (it paints the bar's background itself): the compositor then
        // needn't blend it with the window under it.
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.OPAQUE)
        holder.addCallback(this)
    }

    fun set(color: Int, trackColor: Int, background: Int, gapPx: Float, rtl: Boolean) {
        this.color = color
        this.trackColor = trackColor
        this.background = background
        this.gapPx = gapPx
        this.rtl = rtl
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        startNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) = stop()

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (startNanos == 0L) startNanos = frameTimeNanos
        val ms = ((frameTimeNanos - startNanos) / 1_000_000L % CYCLE_MS).toFloat()
        // A software canvas: a few short lines on a strip a few pixels high cost microseconds on the
        // CPU, less than a GPU frame's set-up and swap.
        val canvas = try {
            holder.lockCanvas()
        } catch (_: Exception) {
            null
        } ?: return
        try {
            draw(canvas, ms)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun draw(canvas: Canvas, ms: Float) {
        canvas.drawColor(background)
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        if (width <= 0f || height <= 0f) return
        val firstHead = phase(ms, 0f, 1000f)
        val firstTail = phase(ms, 250f, 1000f)
        val secondHead = phase(ms, 650f, 850f)
        val secondTail = phase(ms, 900f, 850f)
        // Round ends reach half a stroke past each end: the gaps leave room for them.
        val gap = (gapPx + height) / width
        paint.strokeWidth = height
        if (firstHead < 1f - gap) line(canvas, if (firstHead > 0f) firstHead + gap else 0f, 1f, trackColor)
        if (firstHead - firstTail > 0f) line(canvas, firstTail, firstHead, color)
        if (firstTail > gap) {
            line(canvas, if (secondHead > 0f) secondHead + gap else 0f, if (firstTail < 1f) firstTail - gap else 1f, trackColor)
        }
        if (secondHead - secondTail > 0f) line(canvas, secondTail, secondHead, color)
        if (secondTail > gap) line(canvas, 0f, if (secondTail < 1f) secondTail - gap else 1f, trackColor)
    }

    /** A line from [from] to [to] (fractions of the width, from the start edge). */
    private fun line(canvas: Canvas, from: Float, to: Float, lineColor: Int) {
        if (to <= from) return
        val width = canvas.width.toFloat()
        val half = canvas.height / 2f
        val start = (if (rtl) 1f - to else from) * width
        val end = (if (rtl) 1f - from else to) * width
        paint.color = lineColor
        canvas.drawLine(start.coerceIn(half, width - half), half, end.coerceIn(half, width - half), half, paint)
    }

    private companion object {
        const val CYCLE_MS = 1750L

        /** One end's position at [ms] into the cycle: still at 0 before [delay], at 1 after [delay] + [duration]. */
        fun phase(ms: Float, delay: Float, duration: Float): Float = when {
            ms <= delay -> 0f
            ms >= delay + duration -> 1f
            else -> easing((ms - delay) / duration)
        }

        /** Material's emphasized-accelerate curve, cubic-bezier(0.3, 0, 0.8, 0.15). */
        fun easing(x: Float): Float {
            // Solve the curve's x for t by bisection (a few steps are plenty for a 4 px line), then its y.
            var lo = 0f
            var hi = 1f
            var t = x
            repeat(16) {
                t = (lo + hi) / 2f
                if (bezier(t, 0.3f, 0.8f) < x) lo = t else hi = t
            }
            return bezier(t, 0f, 0.15f)
        }

        fun bezier(t: Float, p1: Float, p2: Float): Float {
            val u = 1f - t
            return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t
        }
    }
}
