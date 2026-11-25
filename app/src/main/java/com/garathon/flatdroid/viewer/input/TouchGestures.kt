package com.garathon.flatdroid.viewer.input

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
    /** While [TouchGestures.steering]: the finger travelled [dx], [dy] (screen px); the pointer moves by as much. */
    fun look(dx: Float, dy: Float) {}
}

/**
 * One finger, phone-style: a tap clicks; a drag scrolls (the content follows the finger); a hold
 * ([holdRecognised], after the system's long-press delay without moving) then a move drags with the
 * left button, a hold then lift is a right-click. A second finger cancels the gesture (it zooms
 * instead). Not thread-safe: called from the UI thread.
 *
 * [slop]: movement (px) that still counts as holding still; [scrollStep]: finger travel (px) per
 * wheel notch, which may change between moves. A drag let go with speed goes on through [flingTo]
 * (the viewer moves a virtual finger on, slowing down).
 *
 * While [steering] (the app has hidden its pointer and moves it back itself, as a game does to
 * turn the view by the mouse), a drag moves the pointer by the finger's travel ([PointerOutput.look])
 * instead of scrolling, also during a held drag; taps and holds stay clicks.
 */
class TouchGestures(private val output: PointerOutput, private val slop: Float, scrollStep: Float) {
    /** Changed during a drag, the notches already sent count at the new step: the content stays with the finger. */
    var scrollStep = scrollStep
    /** As [scrollStep], sideways (apps may scroll a different distance across than down). */
    var scrollStepAcross = scrollStep

    /** The app steers by the pointer: drags move it by their travel instead of scrolling. */
    var steering = false

    /** [SCROLLED]: a drag ended; [flingTo] still scrolls on from it. [STEERING]: a drag while [steering]. */
    private enum class State { IDLE, DOWN, SCROLLING, SCROLLED, STEERING, HELD, DRAGGING, CANCELLED }

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
    /** Where the finger was at the last [PointerOutput.look]. */
    private var lookX = 0f
    private var lookY = 0f

    /** The finger is down and scrolling (a lift now may fling). */
    val scrolling: Boolean get() = state == State.SCROLLING

    /** After a drag, its fling moved the virtual finger to ([x], [y]): the content keeps following. */
    fun flingTo(x: Float, y: Float) {
        if (state != State.SCROLLED) return
        lastX = x; lastY = y
        sendNotches(x, y)
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
                // Either starts where the finger crossed the slop: no jump of a slop's worth.
                if (steering) {
                    state = State.STEERING
                    lookX = x; lookY = y
                } else {
                    state = State.SCROLLING
                    scrollX = x; scrollY = y
                }
            }
            State.SCROLLING -> sendNotches(x, y)
            State.STEERING -> look(x, y)
            State.HELD -> if (moved(x, y)) {
                state = State.DRAGGING
                output.press(BUTTON_LEFT, downX, downY)
                if (steering) {
                    lookX = x; lookY = y
                } else {
                    output.move(x, y)
                }
            }
            State.DRAGGING -> if (steering) look(x, y) else output.move(x, y)
            State.SCROLLED, State.IDLE, State.CANCELLED -> Unit
        }
    }

    private fun look(x: Float, y: Float) {
        output.look(x - lookX, y - lookY)
        lookX = x; lookY = y
    }

    /**
     * Content follows the finger (moving up scrolls down): as many notches as the travel since
     * scrolling started is worth, to the nearest one, less those already sent.
     */
    private fun sendNotches(x: Float, y: Float) {
        val down = ((scrollY - y) / scrollStep).roundToInt() - sentDown
        val right = ((scrollX - x) / scrollStepAcross).roundToInt() - sentRight
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
                sendNotches(x, y)
                state = State.SCROLLED
                return
            }
            State.STEERING, State.SCROLLED, State.IDLE, State.CANCELLED -> Unit
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
        /**
         * [down] and [right] notches (signs give the direction) in the order a diagonal travels them:
         * each as soon as its share of the way is reached, rather than all of one axis first.
         * True for a vertical notch, false for a horizontal one.
         */
        fun interleave(down: Int, right: Int): List<Boolean> {
            val v = abs(down)
            val h = abs(right)
            val order = ArrayList<Boolean>(v + h)
            var sentV = 0
            var sentH = 0
            while (sentV < v || sentH < h) {
                // Each notch sits at the middle of its share of the way; the one that comes first goes first.
                val nextV = if (sentV < v) (sentV + 0.5f) / v else Float.MAX_VALUE
                val nextH = if (sentH < h) (sentH + 0.5f) / h else Float.MAX_VALUE
                if (nextV <= nextH) { order += true; sentV++ } else { order += false; sentH++ }
            }
            return order
        }

        /** RFB button numbers (bit positions in the pointer message's mask). */
        const val BUTTON_LEFT = 0
        const val BUTTON_MIDDLE = 1
        const val BUTTON_RIGHT = 2
    }
}
