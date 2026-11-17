package com.garathon.flatdroid.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Output that keeps arriving (a task's log, a command's output), selectable for copying. A
 * read-only text field rather than a SelectionContainer: the text can change while part of it is
 * selected, and the selection is kept within the new text instead of pointing past its end.
 */
@Composable
fun LogText(text: String, modifier: Modifier = Modifier) {
    var selection by remember { mutableStateOf(TextRange.Zero) }
    val clamped = TextRange(selection.start.coerceIn(0, text.length), selection.end.coerceIn(0, text.length))
    BasicTextField(
        value = TextFieldValue(text, clamped),
        onValueChange = { selection = it.selection },
        readOnly = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = LocalContentColor.current),
        modifier = modifier,
    )
}

/**
 * Keeps [scroll] at the end of a log whose text [key] stands for, as it grows, until the reader
 * scrolls up to read something: the log follows again once they are back at the end.
 */
@Composable
fun FollowEnd(scroll: ScrollState, key: Any?, key2: Any? = null) {
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(scroll) {
        // Judged when a scroll ends, not as the text grows: new lines make the view "not at the end" for a moment.
        snapshotFlow { scroll.isScrollInProgress }.collect { moving ->
            if (!moving) following = scroll.value >= scroll.maxValue - END_SLACK_PX
        }
    }
    LaunchedEffect(key, key2) { if (following) scroll.scrollTo(scroll.maxValue) }
}

private const val END_SLACK_PX = 8
