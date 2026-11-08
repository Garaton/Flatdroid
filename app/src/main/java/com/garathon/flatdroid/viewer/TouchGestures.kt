package com.garathon.flatdroid.viewer

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** What touch gestures do to the remote pointer; coordinates are the screen's (the view's). */
interface PointerOutput {
    fun click(button: Int, x: Float, y: Float)
    fun press(button: Int, x: Float, y: Float)
    fun move(x: Float, y: Float)
    fun release(x: Float, y: Float)
    /** Wheel notches at the point: positive [down] scrolls the content down, positive [right] right. */
    fun scroll(x: Float, y: Float, down: Int, right: Int)
    /** The hold was recognised (the moment to vibrate). */
    fun held()
}

/**
 * One finger, phone-style: a tap clicks; a drag scrolls (the content follows the finger); a hold
 * ([holdRecognised], after the system's long-press delay without moving) then a move drags with the
 * left button, a hold then lift is a right-click. A second finger cancels the gesture (it zooms
 * instead). Not thread-safe: called from the UI thread.
 *
 * [slop]: movement (px) that still counts as holding still; [scrollStep]: finger travel (px) per
 * wheel notch, which may change between moves. While [paused] a drag sends no notches; [resume]
 * sends the ones its travel adds up to meanwhile, also after the finger has lifted. A drag let go
 * with speed goes on through [flingTo] (the viewer moves a virtual finger on, slowing down).
 */
class TouchGestures(private val output: PointerOutput, private val slop: Float, scrollStep: Float) {
    /** Changed during a drag, the notches already sent count at the new step: the content stays with the finger. */
    var scrollStep = scrollStep

    /** [SCROLLED]: a drag ended; [flingTo] and [resume] still scroll on from it. */
    private enum class State { IDLE, DOWN, SCROLLING, SCROLLED, HELD, DRAGGING, CANCELLED }

    private var state = State.IDLE
    private var downX = 0f
    private var downY = 0f
    /** Where scrolling started, and the notches sent since. */
    private var scrollX = 0f
    private var scrollY = 0f
    private var sentDown = 0
    private var sentRight = 0
    private var lastX = 0f
    private var lastY = 0f

    /** Holds a drag's notches back (while the viewer measures how far one scrolls). */
    var paused = false
        private set

    fun pause() {
        paused = true
    }

    /** Sends what the drag travelled while paused, at the current [scrollStep]. */
    fun resume() {
        if (!paused) return
        paused = false
        if (state == State.SCROLLING || state == State.SCROLLED) sendNotches(lastX, lastY)
    }

    /** The finger is down and scrolling (a lift now may fling). */
    val scrolling: Boolean get() = state == State.SCROLLING

    /** After a drag, its fling moved the virtual finger to ([x], [y]): the content keeps following. */
    fun flingTo(x: Float, y: Float) {
        if (state != State.SCROLLED) return
        lastX = x; lastY = y
        if (!paused) sendNotches(x, y)
    }

    /** The gesture waits for [holdRecognised] (the view schedules it at the long-press delay). */
    val waitingForHold: Boolean get() = state == State.DOWN

    fun down(x: Float, y: Float) {
        state = State.DOWN
        downX = x; downY = y
        lastX = x; lastY = y
        scrollX = 0f; scrollY = 0f
        sentDown = 0; sentRight = 0
    }

    fun move(x: Float, y: Float) {
        lastX = x; lastY = y
        when (state) {
            State.DOWN -> if (moved(x, y)) {
                state = State.SCROLLING
                // Scrolling starts where the finger crossed the slop: no jump of a slop's worth.
                scrollX = x; scrollY = y
            }
            State.SCROLLING -> if (!paused) sendNotches(x, y)
            State.HELD -> if (moved(x, y)) {
                state = State.DRAGGING
                output.press(BUTTON_LEFT, downX, downY)
                output.move(x, y)
            }
            State.DRAGGING -> output.move(x, y)
            State.SCROLLED, State.IDLE, State.CANCELLED -> Unit
        }
    }

    /**
     * Content follows the finger (moving up scrolls down): as many notches as the travel since
     * scrolling started is worth, to the nearest one, less those already sent.
     */
    private fun sendNotches(x: Float, y: Float) {
        val down = ((scrollY - y) / scrollStep).roundToInt() - sentDown
        val right = ((scrollX - x) / scrollStep).roundToInt() - sentRight
        if (down != 0 || right != 0) {
            output.scroll(downX, downY, down, right)
            sentDown += down
            sentRight += right
        }
    }

    fun holdRecognised() {
        if (state != State.DOWN) return
        state = State.HELD
        output.held()
    }

    fun up(x: Float, y: Float) {
        when (state) {
            State.DOWN -> output.click(BUTTON_LEFT, downX, downY)
            State.HELD -> output.click(BUTTON_RIGHT, downX, downY)
            State.DRAGGING -> output.release(x, y)
            State.SCROLLING -> {
                lastX = x; lastY = y
                if (!paused) sendNotches(x, y)
                state = State.SCROLLED
                return
            }
            State.SCROLLED, State.IDLE, State.CANCELLED -> Unit
        }
        state = State.IDLE
    }

    /** A second finger, or the system took the gesture: a drag is let go where it is, anything else dropped. */
    fun cancel() {
        if (state == State.DRAGGING) output.release(lastX, lastY)
        if (state != State.IDLE) state = State.CANCELLED
    }

    private fun moved(x: Float, y: Float) = hypot(abs(x - downX), abs(y - downY)) > slop

    companion object {
        /** RFB button numbers (bit positions in the pointer message's mask). */
        const val BUTTON_LEFT = 0
        const val BUTTON_MIDDLE = 1
        const val BUTTON_RIGHT = 2
    }
}
