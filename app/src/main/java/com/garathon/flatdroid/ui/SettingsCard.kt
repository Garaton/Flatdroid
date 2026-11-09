package com.garathon.flatdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.ui.theme.Spacing

/**
 * Cards come in one of two looks, filled (a tinted surface) or outlined (a thin line, no fill).
 * Outlined to start with; the other stays until the two have been compared on the phone, and then
 * the one that loses is deleted.
 */
private const val CARDS_FILLED = false

/**
 * A group of settings: [content] in a card, with an optional [title] on top. [danger] colors the
 * title and the outline as a warning. Every settings page is built from these.
 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (CARDS_FILLED) colors.surfaceContainer else Color.Transparent,
        border = if (CARDS_FILLED) null else BorderStroke(1.dp, if (danger) colors.error.copy(alpha = 0.6f) else colors.outlineVariant),
    ) {
        Column(Modifier.padding(Spacing.card), verticalArrangement = Arrangement.spacedBy(Spacing.row)) {
            title?.let { Text(it, style = sectionTitleStyle(), color = if (danger) colors.error else colors.primary) }
            content()
        }
    }
}
