package com.garathon.flatdroid.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/** Flatdroid's launcher icon, as the launcher shapes it (adaptive icons can't be drawn from resources). */
@Composable
fun FlatdroidIcon(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon = remember(px) {
        runCatching { context.packageManager.getApplicationIcon(context.packageName).toBitmap(px, px).asImageBitmap() }.getOrNull()
    } ?: return
    Image(icon, contentDescription = null, modifier = modifier.size(size))
}
