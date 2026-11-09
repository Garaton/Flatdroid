package com.garathon.flatdroid.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A small icon button for a row's action; dimmed when not [enabled]. */
@Composable
fun IconAction(
    icon: ImageVector, @StringRes description: Int, onClick: () -> Unit, enabled: Boolean = true, danger: Boolean = false,
    size: Dp = 40.dp,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(size)) {
        Icon(
            icon, contentDescription = stringResource(description),
            tint = (if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = if (enabled) 1f else 0.38f),
            modifier = Modifier.size(22.dp),
        )
    }
}
