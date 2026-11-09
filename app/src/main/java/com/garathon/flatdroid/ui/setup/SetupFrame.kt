package com.garathon.flatdroid.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.ui.FlatdroidIcon
import com.garathon.flatdroid.ui.aboveKeyboard
import com.garathon.flatdroid.ui.theme.Spacing

/** How many screens setup has before Flatdroid is ready: the choices, then the installation. */
const val SETUP_SCREENS = 2

/**
 * The frame both setup screens share: Flatdroid's icon, [title] and which screen of
 * [SETUP_SCREENS] this is at the top; [content] scrolling under them; [actions] fixed at the
 * bottom, so the main button is always in reach.
 */
@Composable
fun SetupFrame(
    title: String,
    screen: Int,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().aboveKeyboard()) {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FlatdroidIcon(size = 64.dp)
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            Text(
                stringResource(R.string.setup_screen_count, screen, SETUP_SCREENS),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen, vertical = Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.row),
            content = content,
        )
        HorizontalDivider()
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = actions,
        )
    }
}
