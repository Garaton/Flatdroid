package com.garathon.flatdroid.viewer

import android.view.KeyEvent

/** X keysyms, which RFB key messages carry, for Android's keys and characters. */
object Keysyms {
    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val RETURN = 0xff0d
    const val PAUSE = 0xff13
    const val SCROLL_LOCK = 0xff14
    const val ESCAPE = 0xff1b
    const val HOME = 0xff50
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val PAGE_UP = 0xff55
    const val PAGE_DOWN = 0xff56
    const val END = 0xff57
    const val PRINT = 0xff61
    const val INSERT = 0xff63
    const val MENU = 0xff67
    const val NUM_LOCK = 0xff7f
    const val F1 = 0xffbe
    const val SHIFT_L = 0xffe1
    const val SHIFT_R = 0xffe2
    const val CONTROL_L = 0xffe3
    const val CONTROL_R = 0xffe4
    const val CAPS_LOCK = 0xffe5
    const val ALT_L = 0xffe9
    const val ALT_R = 0xffea
    const val SUPER_L = 0xffeb
    const val SUPER_R = 0xffec
    const val DELETE = 0xffff

    private val MODIFIERS = setOf(SHIFT_L, SHIFT_R, CONTROL_L, CONTROL_R, ALT_L, ALT_R, SUPER_L, SUPER_R, CAPS_LOCK, NUM_LOCK)

    fun isModifier(keysym: Int) = keysym in MODIFIERS

    /** Keys that aren't characters; the rest are sent as the character they type. */
    private val KEYS: Map<Int, Int> = buildMap {
        put(KeyEvent.KEYCODE_DEL, BACKSPACE)
        put(KeyEvent.KEYCODE_FORWARD_DEL, DELETE)
        put(KeyEvent.KEYCODE_TAB, TAB)
        put(KeyEvent.KEYCODE_ENTER, RETURN)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, RETURN)
        put(KeyEvent.KEYCODE_ESCAPE, ESCAPE)
        put(KeyEvent.KEYCODE_DPAD_LEFT, LEFT)
        put(KeyEvent.KEYCODE_DPAD_UP, UP)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, RIGHT)
        put(KeyEvent.KEYCODE_DPAD_DOWN, DOWN)
        put(KeyEvent.KEYCODE_MOVE_HOME, HOME)
        put(KeyEvent.KEYCODE_MOVE_END, END)
        put(KeyEvent.KEYCODE_PAGE_UP, PAGE_UP)
        put(KeyEvent.KEYCODE_PAGE_DOWN, PAGE_DOWN)
        put(KeyEvent.KEYCODE_INSERT, INSERT)
        put(KeyEvent.KEYCODE_SYSRQ, PRINT)
        put(KeyEvent.KEYCODE_BREAK, PAUSE)
        put(KeyEvent.KEYCODE_SCROLL_LOCK, SCROLL_LOCK)
        put(KeyEvent.KEYCODE_MENU, MENU)
        put(KeyEvent.KEYCODE_SHIFT_LEFT, SHIFT_L)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, SHIFT_R)
        put(KeyEvent.KEYCODE_CTRL_LEFT, CONTROL_L)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, CONTROL_R)
        put(KeyEvent.KEYCODE_ALT_LEFT, ALT_L)
        put(KeyEvent.KEYCODE_ALT_RIGHT, ALT_R)
        put(KeyEvent.KEYCODE_META_LEFT, SUPER_L)
        put(KeyEvent.KEYCODE_META_RIGHT, SUPER_R)
        put(KeyEvent.KEYCODE_CAPS_LOCK, CAPS_LOCK)
        put(KeyEvent.KEYCODE_NUM_LOCK, NUM_LOCK)
        for (i in 0 until 12) put(KeyEvent.KEYCODE_F1 + i, F1 + i)
    }

    /**
     * Copy, cut and paste as the keys X apps share for them: Ctrl+Insert, Shift+Delete and
     * Shift+Insert (GTK, Qt and browsers bind them; Ctrl+C would interrupt a terminal's program,
     * and GTK's text views ignore the XF86 Copy keys). Pairs of modifier and key.
     */
    val COPY = CONTROL_L to INSERT
    val CUT = SHIFT_L to DELETE
    val PASTE = SHIFT_L to INSERT

    /** Android's Copy, Cut and Paste keys, as [COPY], [CUT] and [PASTE]. */
    fun editKey(keyCode: Int): Pair<Int, Int>? = when (keyCode) {
        KeyEvent.KEYCODE_COPY -> COPY
        KeyEvent.KEYCODE_CUT -> CUT
        KeyEvent.KEYCODE_PASTE -> PASTE
        else -> null
    }

    /** The keysym of a key that isn't a character (Enter, arrows, modifiers…), or null. */
    fun ofKeyCode(keyCode: Int): Int? = KEYS[keyCode]

    /**
     * The keysym that types [codePoint], or null for control characters with none. Latin-1 keysyms
     * are the code point itself; other characters are 0x01000000 plus it (X's Unicode keysyms).
     */
    fun ofChar(codePoint: Int): Int? = when (codePoint) {
        '\n'.code, '\r'.code -> RETURN
        '\t'.code -> TAB
        '\b'.code -> BACKSPACE
        in 0x20..0x7e, in 0xa0..0xff -> codePoint
        in 0..0x1f, in 0x7f..0x9f -> null
        else -> 0x01000000 or codePoint
    }
}

/**
 * Key presses going to the server, with the extra keys row's Shift, Ctrl and Alt: tapped, each is
 * held for the next key only (pressed before it, released after it); held down, for every key
 * pressed until it is lifted. Not thread-safe: UI thread.
 */
class ViewerKeys(private val send: (down: Boolean, keysym: Int) -> Unit) {
    /** Latched modifiers, in the order they are pressed. */
    private val latched = linkedSetOf<Int>()
    /** A latch was set or released (to redraw the row). */
    var onLatchChanged: (() -> Unit)? = null

    /** The latches went to the key being held: [applied] (pressed for it) are released with it. */
    private var latchTaken = false
    private var applied = emptyList<Int>()

    /** Modifiers held down on the row, and whether a key was pressed meanwhile (then sent down). */
    private val held = linkedMapOf<Int, Boolean>()

    fun isLatched(modifier: Int) = modifier in latched

    /** Latches [modifier] ([Keysyms.SHIFT_L], [Keysyms.CONTROL_L], [Keysyms.ALT_L]) for the next key, or releases the latch. */
    fun toggle(modifier: Int) {
        if (!latched.remove(modifier)) latched += modifier
        onLatchChanged?.invoke()
    }

    /**
     * [modifier] is held down on the row: sent only once another key is pressed (a lone Alt
     * would open some apps' menus), then kept down until [release].
     */
    fun hold(modifier: Int) {
        held[modifier] = false
    }

    /**
     * [modifier] is lifted. Tapped ([tapped], and no key pressed while it was down), it is
     * [toggle]d; a lift after a cancelled press does nothing more.
     */
    fun release(modifier: Int, tapped: Boolean) {
        val used = held.remove(modifier) ?: return
        if (used) send(false, modifier) else if (tapped) toggle(modifier)
    }

    fun down(keysym: Int) {
        if (!Keysyms.isModifier(keysym)) {
            for ((modifier, sent) in held) {
                if (!sent) send(true, modifier)
                held[modifier] = true
            }
            if (!latchTaken && latched.isNotEmpty()) {
                latchTaken = true
                // One held down as well is already pressed.
                applied = latched.filter { it !in held }
                applied.forEach { send(true, it) }
            }
        }
        send(true, keysym)
    }

    fun up(keysym: Int) {
        send(false, keysym)
        if (latchTaken && !Keysyms.isModifier(keysym)) {
            applied.asReversed().forEach { send(false, it) }
            applied = emptyList()
            latchTaken = false
            latched.clear()
            onLatchChanged?.invoke()
        }
    }

    fun tap(keysym: Int) {
        down(keysym)
        up(keysym)
    }

    /** A [modifier] and key pressed together, such as [Keysyms.COPY]. */
    fun chord(modifierAndKey: Pair<Int, Int>) {
        val (modifier, key) = modifierAndKey
        send(true, modifier)
        send(true, key)
        send(false, key)
        send(false, modifier)
    }

    /** Types [text] character by character (what a soft keyboard commits). */
    fun type(text: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            Keysyms.ofChar(cp)?.let(::tap)
            i += Character.charCount(cp)
        }
    }
}
