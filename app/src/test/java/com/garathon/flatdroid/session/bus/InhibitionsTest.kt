package com.garathon.flatdroid.session.bus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InhibitionsTest {
    private val states = mutableListOf<Inhibitions.State>()
    private val inhibitions = Inhibitions { states += it }

    @Test
    fun stateChangesOnlyWhenItDiffers() {
        assertTrue(inhibitions.add(":1.1", "a", Inhibitions.IDLE))
        assertTrue(inhibitions.add(":1.1", "b", Inhibitions.IDLE))
        assertTrue(inhibitions.add(":1.2", "c", Inhibitions.SUSPEND))
        inhibitions.remove(":1.1", "a")
        inhibitions.removeOwner(":1.1")
        inhibitions.clear()
        assertEquals(
            listOf(Inhibitions.State(idle = true), Inhibitions.State(idle = true, suspend = true), Inhibitions.State(suspend = true), Inhibitions.State()),
            states,
        )
    }

    @Test
    fun onlyTheOwnerReleases() {
        inhibitions.add(":1.1", "a", Inhibitions.IDLE)
        assertFalse(inhibitions.remove(":1.2", "a"))
        assertFalse(inhibitions.add(":1.2", "a", 0u))
        assertTrue(inhibitions.remove(":1.1", "a"))
        // Logout and user switch hold nothing here.
        inhibitions.add(":1.1", "b", 3u)
        assertEquals(listOf(Inhibitions.State(idle = true), Inhibitions.State()), states)
    }

    @Test
    fun boundedAndCookiesNeverZero() {
        repeat(Inhibitions.MAX) { assertTrue(inhibitions.add(":1.1", "k$it", Inhibitions.IDLE)) }
        assertFalse(inhibitions.add(":1.1", "one more", Inhibitions.IDLE))
        assertNotEquals(0u, inhibitions.nextCookie())
        assertNotEquals(inhibitions.nextCookie(), inhibitions.nextCookie())
    }
}
