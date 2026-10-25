package com.garathon.flatdroid.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class LineBufferTest {
    private fun withBuffer(maxLines: Int, maxLineLength: Int = 2_000, block: suspend (LineBuffer) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking { block(LineBuffer(maxLines, scope, maxLineLength, intervalMs = 10)) }
        } finally {
            scope.cancel()
        }
    }

    private suspend fun LineBuffer.settled(expected: List<String>) =
        withTimeout(5_000) { lines.first { it == expected } }

    @Test
    fun keepsOnlyTheLastLinesAndCutsLongOnes() = withBuffer(maxLines = 3, maxLineLength = 5) { buf ->
        (1..10).forEach { buf.add("l$it") }
        buf.add("abcdefgh")
        buf.settled(listOf("l9", "l10", "abcde…"))
    }

    @Test
    fun replacesTheLastLineWhenAsked() = withBuffer(maxLines = 10) { buf ->
        buf.add("Installing 1/2… 5%")
        buf.add("Installing 1/2… 50%") { it.startsWith("Installing 1/2") }
        buf.add("Installing 2/2…") { it.startsWith("Installing 2/2") }
        buf.settled(listOf("Installing 1/2… 50%", "Installing 2/2…"))
    }

    @Test
    fun clearEmptiesAtOnce() = withBuffer(maxLines = 10) { buf ->
        buf.add("a")
        buf.settled(listOf("a"))
        buf.clear()
        assertEquals(emptyList<String>(), buf.lines.value)
    }
}
