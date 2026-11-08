package com.garathon.flatdroid.viewer

import android.view.View
import android.view.inputmethod.BaseInputConnection

/**
 * The soft keyboard's text, typed as key presses. The keyboard is asked for no suggestions (see
 * [ViewerView.onCreateInputConnection]), but one that composes anyway (word by word, or for
 * languages that need it) is followed: each change to the composing text is sent as Backspaces
 * for what changed and the new characters. Key events (Backspace on an empty field, Enter,
 * arrows) reach [ViewerView.onKeyDown] as they would from a hardware keyboard.
 */
class KeyInput(
    view: View,
    private val keys: ViewerKeys,
    /** Text longer than [MAX_TYPED] committed at once (a keyboard pasting): not typed. */
    private val longText: (String) -> Unit = {},
) : BaseInputConnection(view, false) {
    private var composing = ""

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        // Typed, it would take minutes, the screen waiting behind it, and the app may not keep up.
        if (composing.isEmpty() && text.length > MAX_TYPED) {
            longText(text.toString())
            return true
        }
        replace(text.toString())
        composing = ""
        return true
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        replace(text.toString())
        composing = text.toString()
        return true
    }

    override fun finishComposingText(): Boolean {
        composing = ""
        return true
    }

    /**
     * A keyboard's editing commands (Copy, Cut, Paste, Select all), done by the app with its own
     * keys: X's selection isn't something this field holds. A keyboard that instead reads the
     * selected text to copy it gets none.
     */
    override fun performContextMenuAction(id: Int): Boolean {
        when (id) {
            android.R.id.copy -> keys.chord(Keysyms.COPY)
            android.R.id.cut -> keys.chord(Keysyms.CUT)
            android.R.id.paste -> keys.chord(Keysyms.PASTE)
            android.R.id.selectAll -> keys.chord(Keysyms.CONTROL_L to 'a'.code)
            else -> return false
        }
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        repeat(beforeLength) { keys.tap(Keysyms.BACKSPACE) }
        repeat(afterLength) { keys.tap(Keysyms.DELETE) }
        return true
    }

    /** The composing text becomes [text]: Backspace over what differs, then type the rest. */
    private fun replace(text: String) {
        val same = sharedStart(composing, text)
        val removed = composing.substring(same)
        repeat(removed.codePointCount(0, removed.length)) { keys.tap(Keysyms.BACKSPACE) }
        keys.type(text.substring(same))
    }
}

/** The most characters committed at once that are typed as keys (about ten seconds' worth). */
const val MAX_TYPED = 1000

/** How many chars [a] and [b] start with in common, never ending inside a character made of two. */
internal fun sharedStart(a: String, b: String): Int {
    val same = a.commonPrefixWith(b).length
    return if (same > 0 && a[same - 1].isHighSurrogate()) same - 1 else same
}
