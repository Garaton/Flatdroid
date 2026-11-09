package com.garathon.flatdroid.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity

/**
 * Leaves room for the keyboard under a screen, so what scrolls on it can reach what the keyboard
 * covers. The navigation bar's share of the keyboard is left out: the screen is padded for it
 * already.
 */
@Composable
fun Modifier.aboveKeyboard(): Modifier = windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))

/**
 * Keeps a text field in view while it has focus: scrolls to it when it gets focus and again
 * while the keyboard moves, since the keyboard covers the bottom of a scrolling screen.
 */
@Composable
fun Modifier.keepInViewWhileFocused(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        requester.bringIntoView()
        snapshotFlow { ime.getBottom(density) }.collect { requester.bringIntoView() }
    }
    return this.bringIntoViewRequester(requester).onFocusChanged { focused = it.isFocused }
}
