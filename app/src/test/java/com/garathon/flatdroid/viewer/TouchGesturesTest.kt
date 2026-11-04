package com.garathon.flatdroid.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchGesturesTest {
    private class Recorder : PointerOutput {
        val events = mutableListOf<String>()
        override fun click(button: Int, x: Float, y: Float) { events += "click $button ${x.toInt()},${y.toInt()}" }
        override fun press(button: Int, x: Float, y: Float) { events += "press $button ${x.toInt()},${y.toInt()}" }
        override fun move(x: Float, y: Float) { events += "move ${x.toInt()},${y.toInt()}" }
        override fun release(x: Float, y: Float) { events += "release ${x.toInt()},${y.toInt()}" }
        override fun scroll(x: Float, y: Float, down: Int, right: Int) { events += "scroll ${x.toInt()},${y.toInt()} down $down right $right" }
        override fun held() { events += "held" }
    }

    private val out = Recorder()
    private val touch = TouchGestures(out, slop = 10f, scrollStep = 50f)

    @Test
    fun aTapClicksWhereTheFingerWentDownEvenWithATremble() {
        touch.down(100f, 200f)
        touch.move(105f, 203f)
        touch.up(105f, 203f)
        assertEquals(listOf("click 0 100,200"), out.events)
    }

    @Test
    fun aDragScrollsOneNotchPerStepAndTheContentFollowsTheFinger() {
        touch.down(100f, 500f)
        touch.move(100f, 480f) // past the slop: scrolling starts here
        touch.move(100f, 460f) // 20 px: under half a notch, none yet
        touch.move(100f, 370f) // 110 px up: two notches down (to the nearest)
        touch.move(160f, 370f) // 60 px right: one notch left
        touch.up(160f, 370f)
        assertEquals(listOf("scroll 100,500 down 2 right 0", "scroll 100,500 down 0 right -1"), out.events)
    }

    @Test
    fun aHoldThenLiftIsARightClick() {
        touch.down(100f, 200f)
        assertTrue(touch.waitingForHold)
        touch.holdRecognised()
        assertFalse(touch.waitingForHold)
        touch.move(104f, 200f)
        touch.up(104f, 200f)
        assertEquals(listOf("held", "click 2 100,200"), out.events)
    }

    @Test
    fun aHoldThenMoveDragsWithTheLeftButton() {
        touch.down(100f, 200f)
        touch.holdRecognised()
        touch.move(130f, 200f)
        touch.move(160f, 250f)
        touch.up(170f, 260f)
        assertEquals(listOf("held", "press 0 100,200", "move 130,200", "move 160,250", "release 170,260"), out.events)
    }

    @Test
    fun aHoldAfterScrollingStartedIsIgnored() {
        touch.down(100f, 200f)
        touch.move(100f, 150f)
        touch.holdRecognised()
        touch.up(100f, 150f)
        assertEquals(emptyList<String>(), out.events)
    }

    @Test
    fun aSecondFingerLetsADragGoAndDropsAnythingElse() {
        touch.down(100f, 200f)
        touch.holdRecognised()
        touch.move(150f, 200f)
        touch.cancel()
        touch.move(300f, 300f)
        touch.up(300f, 300f)
        assertEquals(listOf("held", "press 0 100,200", "move 150,200", "release 150,200"), out.events)

        out.events.clear()
        touch.down(10f, 10f)
        touch.cancel()
        touch.up(10f, 10f)
        assertEquals(emptyList<String>(), out.events)
    }

    @Test
    fun aPausedDragCatchesUpWhenResumedEvenAfterLifting() {
        touch.down(100f, 500f)
        touch.move(100f, 480f) // scrolling starts here
        touch.move(100f, 430f) // one notch
        touch.pause()
        touch.move(100f, 280f) // three more, held back
        touch.up(100f, 280f)
        touch.scrollStep = 75f
        touch.resume()
        assertEquals(listOf("scroll 100,500 down 1 right 0", "scroll 100,500 down 2 right 0"), out.events)
    }

    @Test
    fun aNewStepDuringADragRecountsTheNotchesSent() {
        touch.down(100f, 500f)
        touch.move(100f, 480f)
        touch.move(100f, 430f) // one notch at 50 px
        touch.scrollStep = 100f // it scrolled 100: the finger is half a notch behind
        touch.move(100f, 390f) // 90 px: still one
        touch.move(100f, 320f) // 160 px: two
        assertEquals(listOf("scroll 100,500 down 1 right 0", "scroll 100,500 down 1 right 0"), out.events)
    }

    @Test
    fun aFlingGoesOnFromWhereTheDragWasLetGo() {
        touch.down(100f, 500f)
        touch.move(100f, 480f)
        touch.move(100f, 430f) // one notch
        assertTrue(touch.scrolling)
        touch.up(100f, 420f)
        assertFalse(touch.scrolling)
        touch.flingTo(100f, 330f) // the virtual finger, two notches on
        touch.down(100f, 300f) // a touch ends it
        touch.flingTo(100f, 100f)
        assertEquals(listOf("scroll 100,500 down 1 right 0", "scroll 100,500 down 2 right 0"), out.events)
    }
}
