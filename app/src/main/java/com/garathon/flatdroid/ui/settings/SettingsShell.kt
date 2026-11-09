package com.garathon.flatdroid.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import com.garathon.flatdroid.R
import com.garathon.flatdroid.ui.LocalDeveloperOptions
import com.garathon.flatdroid.ui.aboveKeyboard
import com.garathon.flatdroid.ui.theme.Spacing

/** A page of a settings screen: its icon in the rail, and its title (the rail has no text, the page shows it). */
interface SettingsPage {
    val icon: ImageVector
    @get:StringRes val titleRes: Int
}

/**
 * The frame of both settings screens: [topBar], an icon-only rail of [pages] on the left with
 * the current one highlighted, and the selected page's [content] beside it. Pages change with a
 * short vertical shift in the direction of the rail (down the rail, the new page rises), so the
 * motion says where the user went without implying a swipe. [error] shows under the top bar.
 */
@Composable
fun <P : SettingsPage> SettingsShell(
    pages: List<P>,
    selected: P,
    onSelect: (P) -> Unit,
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    /** The changes are being applied: the page is dimmed as a whole and takes no touches. */
    dimmed: Boolean = false,
    /** Developer options are on: the developer settings of the pages show. */
    developerOptions: Boolean = false,
    content: @Composable (P) -> Unit,
) {
    val focus = LocalFocusManager.current
    LaunchedEffect(dimmed) { if (dimmed) focus.clearFocus() }
    val dim by animateFloatAsState(if (dimmed) 0.5f else 1f, label = "dim")
    // The keyboard takes the room under the page, so the page scrolls to what it covers. The navigation bar's
    // share of it is already padded by the screen.
    Column(modifier.fillMaxSize().aboveKeyboard()) {
        topBar()
        error?.let {
            SelectionContainer {
                Text(
                    stringResource(R.string.settings_failed, it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            SettingsRail(pages, selected, onSelect)
            val shift = with(LocalDensity.current) { PAGE_SHIFT.roundToPx() }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AnimatedContent(
                    targetState = selected,
                    modifier = Modifier.fillMaxSize().alpha(dim),
                    transitionSpec = { pageTransition(pages.indexOf(targetState) > pages.indexOf(initialState), shift) },
                    label = "settings page",
                ) { page -> CompositionLocalProvider(LocalDeveloperOptions provides developerOptions) { content(page) } }
                // Everything on the page, whatever it is made of, looks and acts the same while applying.
                if (dimmed) Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } })
            }
        }
    }
}

private val PAGE_SHIFT = 24.dp
private const val PAGE_MILLIS = 180

private fun pageTransition(down: Boolean, shift: Int) =
    (fadeIn(tween(PAGE_MILLIS, delayMillis = 60)) + slideInVertically(tween(PAGE_MILLIS)) { if (down) shift else -shift }) togetherWith
        (fadeOut(tween(90)) + slideOutVertically(tween(PAGE_MILLIS)) { if (down) -shift else shift })

/** Icons only; scrolls when the pages don't fit (a phone held sideways). */
@Composable
private fun <P : SettingsPage> SettingsRail(pages: List<P>, selected: P, onSelect: (P) -> Unit) {
    Column(
        modifier = Modifier.width(RAIL_WIDTH).fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (page in pages) {
            val isSelected = page == selected
            Box(
                modifier = Modifier.size(RAIL_ITEM).clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .selectable(isSelected, role = Role.Tab, onClick = { onSelect(page) }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    page.icon,
                    contentDescription = stringResource(page.titleRes),
                    tint = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(RAIL_ICON),
                )
            }
        }
    }
}

// About three quarters of Material's rail (64 dp wide, 56 dp items, 24 dp icons).
private val RAIL_WIDTH = 48.dp
private val RAIL_ITEM = 42.dp
private val RAIL_ICON = 20.dp

/**
 * The settings top bar: [title] on the left; on the right the check (save, a spinner while
 * [busy]) and the X (discard). Save is off until something changed and every field is valid.
 */
@Composable
fun SettingsTopBar(
    canSave: Boolean,
    busy: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    title: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(TOP_BAR_HEIGHT).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { title() }
        if (busy) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp) }
        } else {
            IconButton(onClick = onSave, enabled = canSave) { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.settings_save)) }
        }
        IconButton(onClick = onDiscard, enabled = !busy) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_discard)) }
    }
}

private val TOP_BAR_HEIGHT = 56.dp

/** A page: its title (none on the Overview pages, which open with their own heading), then [content], scrolling as a whole. */
@Composable
fun SettingsPageColumn(@StringRes title: Int?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.row),
    ) {
        // As the section headers were before pages: `titleLarge` in the primary color.
        title?.let { Text(stringResource(it), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) }
        content()
    }
}
