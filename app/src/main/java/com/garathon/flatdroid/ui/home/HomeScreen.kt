package com.garathon.flatdroid.ui.home

import android.content.res.Configuration
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.FlatpakTask
import com.garathon.flatdroid.flatpak.FlatpakTaskUi
import com.garathon.flatdroid.flatpak.InstalledApp
import com.garathon.flatdroid.session.SessionState
import com.garathon.flatdroid.session.SessionUi
import com.garathon.flatdroid.ui.FlatdroidIcon
import com.garathon.flatdroid.ui.list.ListQuery
import com.garathon.flatdroid.ui.list.ListSearchBar
import com.garathon.flatdroid.ui.list.ScrollToTopOnNewOrder
import com.garathon.flatdroid.ui.list.rememberListQuery
import com.garathon.flatdroid.ui.settings.durationText
import com.garathon.flatdroid.ui.theme.FlatdroidTheme
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** A row of the list and the pinned one: the same height, the divider not counted. */
internal val ROW_HEIGHT = 64.dp

/** How long the pinned row takes to come or go, and the list to fade in at start-up. */
private const val PIN_MILLIS = 300
private const val LIST_FADE_MILLIS = 700

/**
 * The home list's scroll position and search, kept by the activity so they survive a visit to
 * Settings: the list comes back where it was, with the same search and order.
 */
@Stable
class HomeListState(val list: LazyListState, private val queryState: MutableState<ListQuery>) {
    var query: ListQuery
        get() = queryState.value
        set(value) { queryState.value = value }

    /** The list has been shown once: it fades in only then, not each time Home comes back. */
    var shownOnce = false
}

@Composable
fun rememberHomeListState(): HomeListState {
    val list = rememberLazyListState()
    val query = rememberListQuery(homeListSpec)
    return remember(list, query) { HomeListState(list, query) }
}

/**
 * The home screen: the installed apps with the shared search bar (name or ID; ordered by last
 * opened, name or most used), the app that starts or runs pinned above them, and the bottom bar
 * for Flatpak tasks and how a session ended. Held sideways it is the same list, with the search
 * in the top bar's row.
 *
 * The pinned row is as tall as a list row, and the app's own row stays in the list but shrinks
 * away as the pinned row grows (and back). When the app that starts (or stops) is the list's
 * first and the list is at its top, they swap at once, so nothing moves and the row just looks
 * highlighted (and fades back); any other time the list moves with the pinned row, keeping what
 * it shows (half a row stays half a row).
 * Tapping an app launches it; its gear opens its settings. A newly installed app scrolls the list
 * to the top, and so does a new order (search and filters leave the position).
 */
@Composable
fun HomeScreen(
    apps: List<InstalledApp>?,
    session: SessionUi,
    task: FlatpakTaskUi,
    listState: HomeListState,
    developerOptions: Boolean,
    showTimeUsed: Boolean,
    canLaunch: Boolean,
    canInstall: Boolean,
    canUpdate: Boolean,
    describeTask: (FlatpakTask) -> String,
    onLaunch: (InstalledApp) -> Unit,
    onAppSettings: (InstalledApp) -> Unit,
    onInstall: () -> Unit,
    onUpdateAll: () -> Unit,
    onSettings: () -> Unit,
    onImportFiles: () -> Unit,
    onImportZip: () -> Unit,
    onOpenApp: (SessionState.Running) -> Unit,
    onStopApp: () -> Unit,
    onSessionLog: () -> Unit,
    onSkipCheck: () -> Unit,
    onCancelTask: () -> Unit,
    onDismissTask: () -> Unit,
    onTaskLog: () -> Unit,
    onDismissSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val query = listState.query
    val runningState = session.state.takeIf { it is SessionState.Starting || it is SessionState.PreparingElectron || it is SessionState.Running || it is SessionState.Stopping }
    val runningAppId = runningState?.appIdOrNull

    ScrollToTopOnNewOrder(query, listState.list)
    // A newly installed app starts at the top of the Last opened order: scroll up to show it.
    var knownIds by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(apps) {
        val ids = apps?.map { it.id }?.toSet() ?: return@LaunchedEffect
        val known = knownIds
        knownIds = ids
        if (known != null && (ids - known).isNotEmpty()) listState.list.scrollToItem(0)
    }

    // The pinned row and the app's own row in the list share one value, so they never show at the
    // same time and nothing else moves by itself: [pinned] goes 0 to 1 when the app starts (the
    // pinned row grows above the list, the app's row shrinks away in it) and back when it stops.
    // The app's row stays in the list the whole time, so the rows around it keep their place.
    // When the app is the list's first and the list is at its top, the swap is at once instead,
    // since the two rows are the same size and exactly there.
    val lazy = listState.list
    val isRunning = runningState != null
    val pinned = remember { Animatable(if (isRunning) 1f else 0f) }
    // The last app that started or ran: the pinned row still shows it while it shrinks away after a stop.
    var lastState by remember { mutableStateOf(runningState) }
    LaunchedEffect(runningState) { if (runningState != null) lastState = runningState }
    val pinnedState = runningState ?: lastState
    val pinnedId = pinnedState?.appIdOrNull
    var inPlace by remember { mutableStateOf(false) }
    val sorted = remember(apps, query) { apps?.let { homeListSpec.apply(it, query) } }
    val currentSorted by rememberUpdatedState(sorted)
    var fadeRow by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(isRunning) {
        val target = if (isRunning) 1f else 0f
        // Already there (the first composition): nothing to move.
        if (pinned.value == target && !pinned.isRunning) return@LaunchedEffect
        // Decided before anything moves: the pinned row still has its old height, so the list shows what it showed.
        val id = (runningState ?: lastState)?.appIdOrNull
        val first = currentSorted?.firstOrNull()?.id
        // At the top: nothing above the first visible row but the app's own row, which has no height while pinned.
        val atTop = lazy.firstVisibleItemScrollOffset == 0 &&
            (lazy.firstVisibleItemIndex == 0 || (lazy.firstVisibleItemIndex == 1 && first == id))
        inPlace = atTop && id != null && first == id
        if (inPlace) {
            if (!isRunning) {
                // Its row is the first again: show the list from it, and let it fade from the highlight.
                fadeRow = id
                lazy.scrollToItem(0)
            }
            pinned.snapTo(target)
        } else {
            fadeRow = null
            pinned.animateTo(target, tween(PIN_MILLIS))
        }
    }
    // Shown while it has any height (it also stays drawn, as it was, while it shrinks away).
    val pinnedShown by remember { derivedStateOf { pinned.value > 0f } }

    Column(modifier.fillMaxSize()) {
        if (landscape) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FlatdroidIcon(Modifier.padding(end = 12.dp))
                ListSearchBar(homeListSpec, query, { listState.query = it }, Modifier.weight(1f), placeholder = R.string.home_search)
                TopIcons(canInstall, canUpdate, onInstall, onUpdateAll, onSettings, onImportFiles, onImportZip)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FlatdroidIcon(Modifier.padding(end = 12.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TopIcons(canInstall, canUpdate, onInstall, onUpdateAll, onSettings, onImportFiles, onImportZip)
            }
            ListSearchBar(homeListSpec, query, { listState.query = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), placeholder = R.string.home_search)
        }
        val pinnedNow = pinnedState
        if (pinnedShown && pinnedNow != null) {
            Box(
                // The clip comes first: it is the size the layout below gives, not the full row's.
                Modifier.fillMaxWidth()
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        // A pixel less than its share: the app's own row keeps one (see [collapsing]).
                        val height = (placeable.height * pinned.value - pinned.value).roundToInt().coerceAtLeast(0)
                        // Bottom aligned: it slides down into its place.
                        layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
                    },
            ) {
                RunningRow(
                    pinnedNow, apps?.firstOrNull { it.id == pinnedNow.appIdOrNull }, fromRow = inPlace, developerOptions = developerOptions,
                    onOpen = onOpenApp, onStop = onStopApp, onLog = onSessionLog, onSkipCheck = onSkipCheck,
                )
            }
        }

        // The list fades in the first time it is shown.
        val fadeIn = remember { !listState.shownOnce }
        LaunchedEffect(apps != null) { if (apps != null) listState.shownOnce = true }
        val listAlpha by animateFloatAsState(if (apps != null || !fadeIn) 1f else 0f, tween(LIST_FADE_MILLIS), label = "list fade")
        Box(modifier = Modifier.weight(1f).fillMaxWidth().alpha(listAlpha)) {
            when {
                apps == null -> Unit
                apps.isEmpty() -> Text(
                    stringResource(R.string.home_empty),
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    textAlign = TextAlign.Center,
                )
                sorted.isNullOrEmpty() -> Text(stringResource(R.string.home_no_match), modifier = Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize(), state = lazy) {
                    // Keys: the rows keep their place when the app's row moves or changes size.
                    items(sorted, key = { it.id }) { app ->
                        val pinnedRow = app.id == pinnedId
                        Column(Modifier.collapsing { if (pinnedRow) 1f - pinned.value else 1f }) {
                            AppRow(
                                app, enabled = canLaunch, showTimeUsed = showTimeUsed,
                                fadeFromHighlight = app.id == fadeRow, onLaunch = { onLaunch(app) }, onSettings = { onAppSettings(app) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        StatusBar(
            task, session, describeTask, developerOptions,
            onCancelTask = onCancelTask, onDismissTask = onDismissTask, onTaskLog = onTaskLog,
            onDismissSession = onDismissSession, onSessionLog = onSessionLog,
        )
    }
}

/** Install, update all, add files to the shared home, and Flatdroid's settings (not the gear of an app's own). */
@Composable
private fun TopIcons(
    canInstall: Boolean, canUpdate: Boolean,
    onInstall: () -> Unit, onUpdateAll: () -> Unit, onSettings: () -> Unit,
    onImportFiles: () -> Unit, onImportZip: () -> Unit,
) {
    var importMenu by remember { mutableStateOf(false) }
    IconButton(onClick = onInstall, enabled = canInstall) {
        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_install))
    }
    // Also with no apps: it removes runtimes nothing uses any more.
    IconButton(onClick = onUpdateAll, enabled = canUpdate) {
        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.home_update_all))
    }
    Box {
        IconButton(onClick = { importMenu = true }) {
            Icon(Icons.Filled.UploadFile, contentDescription = stringResource(R.string.home_import))
        }
        DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.home_import_files)) }, onClick = { importMenu = false; onImportFiles() })
            DropdownMenuItem(text = { Text(stringResource(R.string.home_import_zip)) }, onClick = { importMenu = false; onImportZip() })
        }
    }
    IconButton(onClick = onSettings) {
        Icon(Icons.Filled.SettingsApplications, contentDescription = stringResource(R.string.settings_open))
    }
}

/**
 * An app of the list; [fadeFromHighlight] (it just stopped, in place) starts with the running row's
 * color and fades to the list's. The right column is the time the app was used or when it was last
 * opened ([showTimeUsed]).
 */
@Composable
private fun AppRow(
    app: InstalledApp, enabled: Boolean, showTimeUsed: Boolean, fadeFromHighlight: Boolean,
    onLaunch: () -> Unit, onSettings: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val background = remember(app.id, fadeFromHighlight) { Animatable(if (fadeFromHighlight) colors.secondaryContainer else colors.surface) }
    LaunchedEffect(app.id, fadeFromHighlight) { if (fadeFromHighlight) background.animateTo(colors.surface, tween(500)) }
    Row(
        modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT).background(background.value).clickable(enabled = enabled, onClick = onLaunch)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(app.icon, Modifier.size(48.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // An app that never ran says so, rather than "0 min".
        val never = if (showTimeUsed) app.launches == 0 && app.usedMillis == 0L else app.lastOpened == null
        Column(horizontalAlignment = Alignment.End) {
            Text(
                stringResource(if (showTimeUsed) R.string.home_time_used else R.string.home_last_opened),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when {
                    never -> stringResource(R.string.home_never_opened)
                    showTimeUsed -> durationText(app.usedMillis)
                    else -> formatDateTime(app.lastOpened!!)
                },
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
            )
        }
        // The same size and spacing as the icons of the running row.
        IconButton(onClick = onSettings, modifier = Modifier.size(ICON)) {
            Icon(
                Icons.Filled.Settings, contentDescription = stringResource(R.string.app_settings_open, app.name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Gives the item a height of [factor] (0 to 1, read as it lays out) times its own (one pixel at
 * least), clipped and fading out as it goes: the app's row, as its pinned row grows.
 */
private fun Modifier.collapsing(factor: () -> Float): Modifier = this
    // Clip and fade come first (outside): they act on the size the layout below gives.
    .clipToBounds()
    .graphicsLayer { alpha = ((factor() - 0.4f) / 0.6f).coerceIn(0f, 1f) }
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        // One pixel stays: a first row with no height would be skipped by the list, which then
        // wouldn't show it when it grows again.
        val height = (placeable.height * factor().coerceIn(0f, 1f)).roundToInt().coerceAtLeast(1)
        layout(placeable.width, height) { placeable.place(0, 0) }
    }

fun formatDateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(Date(millis))

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    FlatdroidTheme {
        HomeScreen(
            apps = listOf(
                InstalledApp("org.xfce.mousepad", "Mousepad", null, 1_760_000_000_000),
                InstalledApp("org.audacityteam.Audacity", "Audacity", null, null),
            ),
            session = SessionUi(SessionState.Starting("org.xfce.mousepad", "Starting the display")),
            task = FlatpakTaskUi(),
            listState = rememberHomeListState(),
            developerOptions = true, showTimeUsed = true, canLaunch = true, canInstall = true, canUpdate = false, describeTask = { "" },
            onLaunch = {}, onAppSettings = {}, onInstall = {}, onUpdateAll = {}, onSettings = {}, onImportFiles = {}, onImportZip = {},
            onOpenApp = {}, onStopApp = {}, onSessionLog = {}, onSkipCheck = {}, onCancelTask = {}, onDismissTask = {}, onTaskLog = {}, onDismissSession = {},
        )
    }
}
