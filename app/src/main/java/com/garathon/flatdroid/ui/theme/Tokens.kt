package com.garathon.flatdroid.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shape tokens: cards, banners and notes use [Shapes.small], very slightly rounded; everything
 * else keeps Material's shapes (buttons are pills, dialogs large).
 */
val FlatdroidShapes = Shapes(
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(6.dp),
)

/** Spacing tokens shared by the screens. */
object Spacing {
    /** Space between a screen's edge and its content. */
    val screen = 16.dp

    /** Space between rows, and between the parts of a row. */
    val row = 12.dp

    /** Space inside a card. */
    val card = 12.dp

    /** Small gaps: between icons, buttons in a row, lines of text. */
    val small = 8.dp
}
