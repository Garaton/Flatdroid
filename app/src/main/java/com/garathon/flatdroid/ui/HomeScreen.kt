package com.garathon.flatdroid.ui

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.util.LruCache
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import com.caverock.androidsvg.SVG
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.InstalledApp
import com.garathon.flatdroid.ui.theme.FlatdroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

enum class AppSort { NAME, LAST_OPENED }

/**
 * Sorted and filtered for the list: [query] matches names, case-insensitively; the app in
 * [runningAppId] comes first.
 */
fun List<InstalledApp>.forList(query: String, sort: AppSort, runningAppId: String? = null): List<InstalledApp> {
    val q = query.trim()
    val matching = if (q.isEmpty()) this else filter { it.name.contains(q, ignoreCase = true) }
    val byName = compareBy<InstalledApp, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id }
    val order = when (sort) {
        AppSort.NAME -> byName
        // Most recently opened or installed first, so a new app starts at the top; then by name.
        AppSort.LAST_OPENED -> compareByDescending<InstalledApp> { maxOf(it.lastOpened ?: Long.MIN_VALUE, it.installed ?: Long.MIN_VALUE) }
            .then(byName)
    }
    return matching.sortedWith(compareBy<InstalledApp> { it.id != runningAppId }.then(order))
}

/**
 * The home screen: installed apps with search and sort, a top bar (install, update all, import
 * into the shared home, settings), and below the list, the running session and Flatpak task.
 * Tapping an app launches it; its gear opens its settings. A launched or newly installed app
 * moves to the top of the list, which scrolls up to it.
 */
@Composable
fun HomeScreen(
    apps: List<InstalledApp>?,
    runningAppId: String?,
    canLaunch: Boolean,
    canInstall: Boolean,
    canUpdate: Boolean,
    onLaunch: (InstalledApp) -> Unit,
    onAppSettings: (InstalledApp) -> Unit,
    onInstall: () -> Unit,
    onUpdateAll: () -> Unit,
    onSettings: () -> Unit,
    onImportFiles: () -> Unit,
    onImportZip: () -> Unit,
    modifier: Modifier = Modifier,
    bottom: @Composable () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(AppSort.LAST_OPENED) }
    var importMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(runningAppId) {
        if (runningAppId != null) listState.animateScrollToItem(0)
    }
    // A newly installed app starts at the top of the Last opened order: scroll up to show it.
    var knownIds by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(apps) {
        val ids = apps?.map { it.id }?.toSet() ?: return@LaunchedEffect
        val known = knownIds
        knownIds = ids
        if (known != null && (ids - known).isNotEmpty()) listState.animateScrollToItem(0)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlatdroidIcon(Modifier.padding(end = 12.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onInstall, enabled = canInstall) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_install))
            }
            // Also with no apps: it removes runtimes nothing uses any more.
            IconButton(onClick = onUpdateAll, enabled = canUpdate) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.home_update_all))
            }
            Box {
                IconButton(onClick = { importMenu = true }) {
                    Icon(Icons.Filled.FileDownload, contentDescription = stringResource(R.string.home_import))
                }
                DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_import_files)) },
                        onClick = { importMenu = false; onImportFiles() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_import_zip)) },
                        onClick = { importMenu = false; onImportZip() },
                    )
                }
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_open))
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.home_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = sort == AppSort.LAST_OPENED, onClick = { sort = AppSort.LAST_OPENED }, label = { Text(stringResource(R.string.home_sort_last_opened)) })
            FilterChip(selected = sort == AppSort.NAME, onClick = { sort = AppSort.NAME }, label = { Text(stringResource(R.string.home_sort_name)) })
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val shown = remember(apps, query, sort, runningAppId) { apps?.forList(query, sort, runningAppId) }
            when {
                apps == null -> Unit
                apps.isEmpty() -> Text(
                    stringResource(R.string.home_empty),
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    textAlign = TextAlign.Center,
                )
                shown.isNullOrEmpty() -> Text(stringResource(R.string.home_no_match), modifier = Modifier.align(Alignment.Center))
                else -> LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                    items(shown, key = { it.id }) { app ->
                        AppRow(app, running = app.id == runningAppId, enabled = canLaunch, onLaunch = { onLaunch(app) }, onSettings = { onAppSettings(app) })
                        HorizontalDivider()
                    }
                }
            }
        }
        bottom()
    }
}

@Composable
private fun AppRow(app: InstalledApp, running: Boolean, enabled: Boolean, onLaunch: () -> Unit, onSettings: () -> Unit) {
    val background = if (running) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier.fillMaxWidth().background(background).clickable(enabled = enabled, onClick = onLaunch)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(app.icon, Modifier.size(48.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                app.id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                stringResource(R.string.home_last_opened),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                app.lastOpened?.let { formatDateTime(it) } ?: stringResource(R.string.home_never_opened),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
            )
        }
        // Its settings would change files the running app uses.
        IconButton(onClick = onSettings, enabled = !running) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.app_settings_open, app.name))
        }
    }
}

/**
 * Decoded icons by path, so a row scrolling back into view isn't decoded again. An app update
 * deploys to a new directory, so its new icon has a new path.
 */
private val iconCache = LruCache<String, ImageBitmap>(64)

/** The app's icon, or a plain tile when it has none Android can draw. */
@Composable
fun AppIcon(file: File?, modifier: Modifier = Modifier) {
    val bitmap by produceState(file?.let { iconCache.get(it.path) }, file) {
        if (value != null || file == null) return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                decodeIcon(file)?.asImageBitmap()?.also { iconCache.put(file.path, it) }
            } catch (_: Exception) {
                null
            }
        }
    }
    val shape = RoundedCornerShape(8.dp)
    val image = bitmap
    if (image != null) {
        Image(image, contentDescription = null, modifier = modifier.clip(shape))
    } else {
        Box(modifier.clip(shape).background(MaterialTheme.colorScheme.primaryContainer))
    }
}

/**
 * Decodes an app's icon at most about [ICON_DECODE_PX] pixels wide: the file comes from the app,
 * and a huge one decoded at full size could exhaust memory. SVGs are rendered at that size.
 */
private fun decodeIcon(file: File): android.graphics.Bitmap? {
    if (file.name.endsWith(".svg")) return renderSvg(file)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ICON_DECODE_PX) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/**
 * Renders an SVG icon (AndroidSVG). Nothing external is loaded: AndroidSVG fetches images or
 * fonts only through a resolver, and none is registered. Oversized files are skipped.
 */
private fun renderSvg(file: File): android.graphics.Bitmap? {
    if (file.length() > MAX_SVG_BYTES) return null
    val svg = file.inputStream().use { SVG.getFromInputStream(it) }
    // Scaled by its viewBox to fill the bitmap (centred if not square). A fixed width and height
    // (`width="128px"`) would otherwise draw it at that size in the top-left corner; an icon with
    // no viewBox gets one from that size first.
    if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) {
        svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
    }
    svg.setDocumentWidth("100%")
    svg.setDocumentHeight("100%")
    val bitmap = createBitmap(ICON_DECODE_PX, ICON_DECODE_PX)
    svg.renderToCanvas(Canvas(bitmap), RectF(0f, 0f, ICON_DECODE_PX.toFloat(), ICON_DECODE_PX.toFloat()))
    return bitmap
}

private const val ICON_DECODE_PX = 192
private const val MAX_SVG_BYTES = 1L shl 20

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
            runningAppId = "org.xfce.mousepad",
            canLaunch = true,
            canInstall = true,
            canUpdate = false,
            onLaunch = {}, onAppSettings = {}, onInstall = {}, onUpdateAll = {}, onSettings = {}, onImportFiles = {}, onImportZip = {},
        )
    }
}
