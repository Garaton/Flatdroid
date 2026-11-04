package com.garathon.flatdroid.viewer

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerKeysTest {
    private val sent = mutableListOf<String>()
    private val keys = ViewerKeys { down, keysym -> sent += (if (down) "+" else "-") + Integer.toHexString(keysym) }

    @Test
    fun charactersBecomeLatin1OrUnicodeKeysyms() {
        assertEquals('a'.code, Keysyms.ofChar('a'.code))
        assertEquals(0xe9, Keysyms.ofChar('é'.code))
        assertEquals(0x010020ac, Keysyms.ofChar('€'.code))
        assertEquals(0x0101f600, Keysyms.ofChar(0x1f600))
        assertEquals(Keysyms.RETURN, Keysyms.ofChar('\n'.code))
        assertEquals(Keysyms.TAB, Keysyms.ofChar('\t'.code))
        assertNull(Keysyms.ofChar(0x07))
        assertNull(Keysyms.ofChar(0x85))
    }

    @Test
    fun keysThatArentCharacters() {
        assertEquals(Keysyms.BACKSPACE, Keysyms.ofKeyCode(KeyEvent.KEYCODE_DEL))
        assertEquals(Keysyms.DELETE, Keysyms.ofKeyCode(KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals(Keysyms.F1 + 11, Keysyms.ofKeyCode(KeyEvent.KEYCODE_F12))
        assertEquals(Keysyms.CONTROL_L, Keysyms.ofKeyCode(KeyEvent.KEYCODE_CTRL_LEFT))
        assertNull(Keysyms.ofKeyCode(KeyEvent.KEYCODE_A))
    }

    @Test
    fun typingTapsEachCharacterIncludingOnesOutsideTheBasicPlane() {
        keys.type("aé😀\n")
        assertEquals(listOf("+61", "-61", "+e9", "-e9", "+101f600", "-101f600", "+ff0d", "-ff0d"), sent)
    }

    @Test
    fun latchedModifiersHoldForTheNextKeyOnly() {
        var changes = 0
        keys.onLatchChanged = { changes++ }
        keys.toggle(Keysyms.CONTROL_L)
        keys.toggle(Keysyms.SHIFT_L)
        keys.type("tx")
        assertEquals(listOf("+ffe3", "+ffe1", "+74", "-74", "-ffe1", "-ffe3", "+78", "-78"), sent)
        assertFalse(keys.isLatched(Keysyms.CONTROL_L) || keys.isLatched(Keysyms.SHIFT_L))
        assertEquals(3, changes)
    }

    @Test
    fun aLatchWaitsThroughModifierKeys() {
        keys.toggle(Keysyms.CONTROL_L)
        keys.down(Keysyms.SHIFT_L)
        keys.down('a'.code)
        keys.up('a'.code)
        keys.up(Keysyms.SHIFT_L)
        assertEquals(listOf("+ffe1", "+ffe3", "+61", "-61", "-ffe3", "-ffe1"), sent)
    }

    @Test
    fun anUntoggledLatchSendsNothing() {
        keys.toggle(Keysyms.ALT_L)
        keys.toggle(Keysyms.ALT_L)
        keys.tap(Keysyms.ESCAPE)
        assertEquals(listOf("+ff1b", "-ff1b"), sent)
    }

    @Test
    fun aHeldModifierStaysDownForEveryKeyUntilLifted() {
        keys.hold(Keysyms.SHIFT_L)
        keys.tap(Keysyms.LEFT)
        keys.tap(Keysyms.LEFT)
        keys.release(Keysyms.SHIFT_L, tapped = true)
        assertEquals(listOf("+ffe1", "+ff51", "-ff51", "+ff51", "-ff51", "-ffe1"), sent)
        assertFalse(keys.isLatched(Keysyms.SHIFT_L))
    }

    @Test
    fun aModifierHeldAloneIsATapAndSendsNothing() {
        keys.hold(Keysyms.ALT_L)
        keys.release(Keysyms.ALT_L, tapped = true)
        assertEquals(emptyList<String>(), sent)
        assertTrue(keys.isLatched(Keysyms.ALT_L))
        keys.hold(Keysyms.CONTROL_L)
        keys.release(Keysyms.CONTROL_L, tapped = false)
        assertFalse(keys.isLatched(Keysyms.CONTROL_L))
    }

    @Test
    fun aHeldModifierThatIsAlsoLatchedIsPressedOnce() {
        keys.toggle(Keysyms.CONTROL_L)
        keys.toggle(Keysyms.SHIFT_L)
        keys.hold(Keysyms.SHIFT_L)
        keys.tap('a'.code)
        keys.tap('b'.code)
        keys.release(Keysyms.SHIFT_L, tapped = true)
        assertEquals(listOf("+ffe1", "+ffe3", "+61", "-61", "-ffe3", "+62", "-62", "-ffe1"), sent)
        assertFalse(keys.isLatched(Keysyms.SHIFT_L) || keys.isLatched(Keysyms.CONTROL_L))
    }
}
