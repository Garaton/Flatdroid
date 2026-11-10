package com.garathon.flatdroid.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import com.garathon.flatdroid.ui.list.FilterChips
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.catalog.Catalog
import com.garathon.flatdroid.flatpak.catalog.CatalogApp
import com.garathon.flatdroid.flatpak.catalog.CatalogState
import com.garathon.flatdroid.flatpak.catalog.FlathubLists
import com.garathon.flatdroid.ui.home.AppIcon
import com.garathon.flatdroid.ui.list.ListQuery
import com.garathon.flatdroid.ui.list.ListSearchBar
import com.garathon.flatdroid.ui.list.ScrollToTopOnNewOrder
import com.garathon.flatdroid.ui.list.rememberListQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import android.icu.text.CompactDecimalFormat
import androidx.compose.ui.platform.LocalDensity

/** The browser's list position, search and category, kept while an app's page is open and through rotation. */
class BrowseListState(val list: LazyListState, private val queryState: androidx.compose.runtime.MutableState<ListQuery>, private val categoryState: androidx.compose.runtime.MutableState<String?>) {
    var query: ListQuery
        get() = queryState.value
        set(value) { queryState.value = value }

    /** The [BrowseCategory] shown, by name; null for all. */
    var category: String?
        get() = categoryState.value
        set(value) { categoryState.value = value }
}

@Composable
fun rememberBrowseListState(): BrowseListState {
    val list = rememberLazyListState()
    val query = rememberListQuery(browseListSpec(FlathubLists()))
    val category = rememberSaveable { mutableStateOf<String?>(null) }
    return remember(list, query, category) { BrowseListState(list, query, category) }
}

/**
 * Flathub's apps for this phone, from Flatpak's own catalog ([Catalog]): the shared search bar
 * (Popular or name order, verified and touch filters), the categories, and a row per app (icon,
 * name with the verified check, summary; installed or waiting to install). The top bar has Back,
 * typing app IDs directly ([onInstallById]) and refreshing the catalog ([onRefresh], a download
 * that follows the mobile data setting). Nothing is downloaded by just opening it.
 */
@Composable
fun BrowseScreen(
    state: CatalogState,
    listState: BrowseListState,
    installed: Set<String>,
    queued: Set<String>,
    installing: List<String>,
    canRefresh: Boolean,
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit,
    onRefresh: () -> Unit,
    onInstallById: () -> Unit,
    onRetry: () -> Unit,
    bottomBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(modifier.fillMaxSize()) {
        ScrollToTopOnNewOrder(listState.query, listState.list)
        // A list of thousands: a new search, filter or category starts at its top too (the shared rule keeps the place, which suits short lists).
        var seenSelection by remember { mutableStateOf(Triple(listState.query.text, listState.query.filters, listState.category)) }
        LaunchedEffect(listState.query.text, listState.query.filters, listState.category) {
            val now = Triple(listState.query.text, listState.query.filters, listState.category)
            if (now != seenSelection) {
                seenSelection = now
                listState.list.scrollToItem(0)
            }
        }
        val ready = state as? CatalogState.Ready
        val lists = ready?.lists ?: FlathubLists()
        val spec = remember(lists) { browseListSpec(lists) }
        // Prepared off the main thread, once per index and set of Flathub's lists; new lists (a
        // refresh fetched them) reorder the list at once, the old order shown meanwhile.
        val items by produceState(ready?.let { BrowseItems.ready(it.index, it.lists) }, ready?.index, ready?.lists) {
            val now = ready ?: return@produceState
            value = BrowseItems.ready(now.index, now.lists) ?: withContext(Dispatchers.Default) { BrowseItems.prepare(now.index, now.lists) }
        }
        val back = @Composable { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back)) } }
        val actions = @Composable {
            IconButton(onClick = onInstallById) { Icon(Icons.AutoMirrored.Filled.Input, contentDescription = stringResource(R.string.browse_install_by_id)) }
            IconButton(onClick = onRefresh, enabled = canRefresh) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.browse_refresh)) }
        }
        val search = @Composable { mod: Modifier ->
            ListSearchBar(spec, listState.query, { listState.query = it }, mod, placeholder = R.string.browse_search, showFilters = false)
        }
        // As Home: in landscape one row (back, search, actions); in portrait the title row, then the search.
        if (landscape) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                back()
                search(Modifier.weight(1f).padding(start = 4.dp))
                actions()
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                back()
                Text(stringResource(R.string.browse_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
                actions()
            }
            search(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp))
        }
        // The filters and the categories in one scrolling row, a line between them.
        val shownCategories = items?.categories.orEmpty()
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            item(key = "filters") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChips(spec, listState.query) { listState.query = it } }
            }
            if (shownCategories.isNotEmpty()) item(key = "divider") { VerticalDivider(Modifier.height(24.dp)) }
            items(shownCategories, key = { it.name }) { category ->
                FilterChip(
                    selected = listState.category == category.name,
                    onClick = { listState.category = if (listState.category == category.name) null else category.name },
                    label = { Text(stringResource(category.label)) },
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state) {
                CatalogState.Idle, CatalogState.Loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.browse_loading), modifier = Modifier.padding(top = 12.dp))
                }
                CatalogState.Missing -> Message(stringResource(R.string.browse_missing), stringResource(R.string.browse_refresh), onRefresh, canRefresh)
                is CatalogState.Failed -> Message(stringResource(R.string.browse_failed, state.message), stringResource(R.string.install_link_retry), onRetry, true)
                is CatalogState.Ready -> {
                    val category = BrowseCategory.entries.firstOrNull { it.name == listState.category }
                    val prepared = items
                    if (prepared == null) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                        return@Box
                    }
                    val apps = remember(prepared, spec, listState.query, category) { prepared.select(spec, listState.query, category) }
                    // The icons' folder once; a row's icon is a path in it (a missing file just shows none).
                    val iconsDir = remember(state.index) { Catalog.iconsDir(context) }
                    val iconPx = with(LocalDensity.current) { ROW_ICON_SIZE.roundToPx() }
                    if (apps.isEmpty()) {
                        Text(stringResource(R.string.home_no_match), modifier = Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), state = listState.list) {
                            items(apps, key = { it.id }) { app ->
                                val label = when (app.id) {
                                    in installing -> R.string.browse_installing
                                    in queued -> R.string.browse_queued
                                    in installed -> R.string.browse_installed
                                    else -> null
                                }
                                BrowseRow(app, remember(app.icon) { app.icon?.let { File(iconsDir, it) } }, iconPx, label, state.lists.installs[app.id]) { onOpenApp(app.id) }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
        bottomBar()
    }
}

@Composable
private fun Message(text: String, action: String, onAction: () -> Unit, enabled: Boolean) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, textAlign = TextAlign.Center)
        OutlinedButton(onClick = onAction, enabled = enabled, modifier = Modifier.padding(top = 16.dp)) { Text(action) }
    }
}

/**
 * An app of the list: icon, name (with the verified check and whether it is installed or waiting),
 * summary, and at the right its installs of the last month when Flathub's lists give them.
 */
@Composable
private fun BrowseRow(app: CatalogApp, icon: File?, iconPx: Int, label: Int?, installs: Long?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Decoded at the row's size: the catalog's icons are 128 px, more than a row shows.
        AppIcon(icon, Modifier.size(ROW_ICON_SIZE), decodePx = iconPx)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (app.verified) {
                    Icon(
                        Icons.Filled.Verified, contentDescription = stringResource(R.string.install_link_verified),
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp).size(16.dp),
                    )
                }
                label?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Text(app.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // As Home's right column: what it is, then the value.
        if (installs != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.browse_installs_month), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(compactCount(installs), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private val ROW_ICON_SIZE = 48.dp

/** A count as short as it reads well: 950, 8.9K, 241K, 1.2M, in the user's locale. */
fun compactCount(count: Long): String = synchronized(CompactFormat) { CompactFormat.forLocale(Locale.getDefault()).format(count) }

/**
 * One formatter for [compactCount], made again only when the language changes: making one (the
 * language's number patterns) for every row that scrolls into view would be wasted. ICU's
 * formatters aren't thread-safe, hence the lock.
 */
private object CompactFormat {
    private var locale: Locale? = null
    private var format: CompactDecimalFormat? = null

    fun forLocale(now: Locale): CompactDecimalFormat = format?.takeIf { locale == now }
        ?: CompactDecimalFormat.getInstance(now, CompactDecimalFormat.CompactStyle.SHORT).also { format = it; locale = now }
}
