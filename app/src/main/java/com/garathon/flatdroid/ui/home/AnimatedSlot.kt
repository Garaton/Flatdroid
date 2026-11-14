package com.garathon.flatdroid.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Space for something that comes and goes (the bottom bar): it grows
 * from [from] and fades in when [value] appears, and shrinks away when it is null again. The
 * last value stays drawn while it shrinks, so the slot doesn't flash empty.
 */
@Composable
fun <T : Any> AnimatedSlot(
    value: T?, from: Alignment.Vertical, modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val last = remember { arrayOfNulls<Any>(1) }
    if (value != null) last[0] = value
    AnimatedVisibility(
        visible = value != null,
        modifier = modifier,
        enter = expandVertically(expandFrom = from) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = from) + fadeOut(),
    ) {
        @Suppress("UNCHECKED_CAST")
        (last[0] as T?)?.let { content(it) }
    }
}
