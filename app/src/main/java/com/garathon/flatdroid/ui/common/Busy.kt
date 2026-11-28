package com.garathon.flatdroid.ui.common

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/*
 * Indeterminate progress, each in a layer of its own: every frame of the animation then redraws only
 * the indicator, not the whole screen around it (the Home bar's line during a task on a Cortex-A53:
 * 0.93 of a core with it, 1.2 without).
 */

@Composable
fun BusySpinner(
    modifier: Modifier = Modifier,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
    color: Color = ProgressIndicatorDefaults.circularColor,
) = CircularProgressIndicator(modifier.graphicsLayer(), color = color, strokeWidth = strokeWidth)

@Composable
fun BusyLine(modifier: Modifier = Modifier) = LinearProgressIndicator(modifier.graphicsLayer())
