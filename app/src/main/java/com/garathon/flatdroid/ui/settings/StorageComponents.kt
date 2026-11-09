package com.garathon.flatdroid.ui.settings

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.ui.list.ListSearchBar
import com.garathon.flatdroid.ui.list.ScrollToTopOnNewOrder
import com.garathon.flatdroid.ui.list.ListSpec
import com.garathon.flatdroid.ui.list.rememberListQuery

/** One part of the size bar. A null [bytes] isn't measured yet. */
class StorageSegment(@StringRes val label: Int, val bytes: Long?, val color: Color)

/** A bar of the parts of a total, and a legend with each part's size. */
@Composable
fun StorageBar(segments: List<StorageSegment>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val measured = segments.filter { (it.bytes ?: 0) > 0 }
    val total = measured.sumOf { it.bytes!! }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            for (segment in measured) Box(Modifier.weight(segment.bytes!!.toFloat()).height(12.dp).background(segment.color))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (segment in segments) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(segment.color))
                    Text(
                        segment.bytes?.takeIf { total > 0 }?.let {
                            stringResource(R.string.storage_legend_percent, stringResource(segment.label), Formatter.formatShortFileSize(context, it), (it * 100 / total).toInt())
                        } ?: stringResource(R.string.storage_legend, stringResource(segment.label), segment.bytes?.let { Formatter.formatShortFileSize(context, it) } ?: "…"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** The colors of the size bar's parts, in order. */
@Composable
fun storageColors(): List<Color> = with(MaterialTheme.colorScheme) {
    listOf(primary, secondary, tertiary, primaryContainer, tertiaryContainer, outline)
}

/**
 * A row of a storage list: [title] and [subtitle] on the left, the size, then [actions]; a row
 * with several [actions] gives them a line of their own ([actionsBelow]). A [dimmed] row is
 * something nothing uses (a runtime no app needs, files of an app that is gone).
 */
@Composable
fun StorageRow(
    title: String,
    size: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dimmed: Boolean = false,
    actionsBelow: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).alpha(if (dimmed) 0.55f else 1f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            size?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (!actionsBelow) actions()
        }
        if (actionsBelow) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
    }
}

/**
 * A searchable list in a box of fixed height, which the page scrolls around: the shared search
 * bar on top, the rows of [items] that [spec] and the query let through below it.
 */
@Composable
fun <T> StorageList(
    spec: ListSpec<T>,
    items: List<T>,
    key: (T) -> Any,
    emptyText: Int,
    modifier: Modifier = Modifier,
    row: @Composable (T) -> Unit,
) {
    var query by rememberListQuery(spec)
    val shown = remember(items, query) { spec.apply(items, query) }
    val listState = rememberLazyListState()
    ScrollToTopOnNewOrder(query, listState)
    Column(
        modifier.fillMaxWidth().height(LIST_HEIGHT)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(LIST_CORNER)),
    ) {
        ListSearchBar(spec, query, { query = it }, Modifier.padding(8.dp))
        if (shown.isEmpty()) {
            Text(
                stringResource(emptyText), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.weight(1f), state = listState) {
                items(shown, key = key) { item ->
                    row(item)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

private val LIST_HEIGHT = 360.dp
private val LIST_CORNER = 6.dp
