package com.garathon.flatdroid.session

import org.junit.Assert.assertEquals
import org.junit.Test

class UseQuestionTest {
    @Test
    fun usedOnlyWhileAllowedAndInUse() {
        // Not in use: nothing, whatever the answer.
        assertEquals(UseAction.IDLE, UseAction.of(alwaysAllowed = true, answer = null, inUse = false))
        assertEquals(UseAction.IDLE, UseAction.of(alwaysAllowed = false, answer = null, inUse = false))
        // In use, not answered yet this session: ask; allowed: use; denied: silence or black frames, no asking.
        assertEquals(UseAction.ASK, UseAction.of(alwaysAllowed = false, answer = null, inUse = true))
        assertEquals(UseAction.USE, UseAction.of(alwaysAllowed = false, answer = true, inUse = true))
        assertEquals(UseAction.IDLE, UseAction.of(alwaysAllowed = false, answer = false, inUse = true))
        // Always allowed: used without asking.
        assertEquals(UseAction.USE, UseAction.of(alwaysAllowed = true, answer = null, inUse = true))
    }
}
