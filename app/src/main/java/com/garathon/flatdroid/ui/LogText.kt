package com.garathon.flatdroid.ui

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
