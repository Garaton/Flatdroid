package com.garathon.flatdroid.ui.common

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.size

/*
 * Indeterminate progress. A spinner draws in a layer of its own, which spares re-recording the
 * screen around it on each frame (the window is still redrawn: they show for moments only).
 */

@Composable
fun BusySpinner(
    modifier: Modifier = Modifier,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
    color: Color = ProgressIndicatorDefaults.circularColor,
) = CircularProgressIndicator(modifier.graphicsLayer(), color = color, strokeWidth = strokeWidth)

/**
 * The indeterminate line on a surface of its own ([BusyLineView]): it shows while a task runs, for
 * minutes, and inside the window every frame of it redrew the whole window. [background] is what is
 * behind it (the surface is opaque).
 */
@Composable
fun BusyLine(modifier: Modifier = Modifier, background: Color) {
    val color = ProgressIndicatorDefaults.linearColor.toArgb()
    val track = ProgressIndicatorDefaults.linearTrackColor.toArgb()
    // Material's gap between the line and its track (its LinearIndicatorTrackGapSize, experimental there).
    val gap = with(LocalDensity.current) { 4.dp.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    AndroidView(
        factory = ::BusyLineView,
        update = { it.set(color, track, background.toArgb(), gap, rtl) },
        onRelease = BusyLineView::stop,
        modifier = modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }.size(240.dp, 4.dp),
    )
}
