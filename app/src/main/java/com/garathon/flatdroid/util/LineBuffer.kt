package com.garathon.flatdroid.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Output lines for the UI: only the last [maxLines] are kept, each cut to [maxLineLength], and
 * [lines] changes at most every [intervalMs]. A program printing thousands of lines a second
 * then costs a list copy and a redraw ten times a second, not per line. Thread-safe.
 */
class LineBuffer(
    private val maxLines: Int,
    scope: CoroutineScope,
    private val maxLineLength: Int = 2_000,
    private val intervalMs: Long = 100,
) {
    private val buffer = ArrayDeque<String>()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()
    private val changed = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (unit in changed) {
                publish()
                delay(intervalMs)
            }
        }
    }

    /** Adds [line], or replaces the last line when [replaces] says so (repeated progress). */
    fun add(line: String, replaces: (last: String) -> Boolean = { false }) {
        val cut = if (line.length > maxLineLength) line.take(maxLineLength) + "…" else line
        synchronized(buffer) {
            val last = buffer.lastOrNull()
            if (last != null && replaces(last)) buffer.removeLast()
            buffer.addLast(cut)
            while (buffer.size > maxLines) buffer.removeFirst()
        }
        changed.trySend(Unit)
    }

    /** Empties the buffer, at once. */
    fun clear() {
        synchronized(buffer) { buffer.clear() }
        _lines.value = emptyList()
    }

    private fun publish() {
        _lines.value = synchronized(buffer) { buffer.toList() }
    }
}
