package com.garathon.flatdroid.ui.browse

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.garathon.flatdroid.flatpak.catalog.CatalogIndex
import com.garathon.flatdroid.flatpak.catalog.Screenshot
import com.garathon.flatdroid.settings.MobileData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.FlathubApp
import com.garathon.flatdroid.flatpak.FlathubBadAnswerException
import com.garathon.flatdroid.flatpak.FlathubHttpException
import com.garathon.flatdroid.flatpak.FlathubNotFoundException
import com.garathon.flatdroid.flatpak.FlathubStats
import com.garathon.flatdroid.flatpak.FlatpakRef
import com.garathon.flatdroid.flatpak.catalog.Catalog
import com.garathon.flatdroid.flatpak.catalog.CatalogApp
import com.garathon.flatdroid.flatpak.catalog.CatalogDetails
import com.garathon.flatdroid.flatpak.catalog.CatalogImages
import com.garathon.flatdroid.flatpak.catalog.TextBlock
import com.garathon.flatdroid.ui.home.AppIcon
import com.garathon.flatdroid.ui.settings.UninstallDialog
import com.garathon.flatdroid.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What Flathub said about an app's sizes and permissions. */
private sealed interface Summary {
    data object Loading : Summary
    data class Found(val app: FlathubApp, val runtimeInstalled: Boolean) : Summary
    data object NotFound : Summary
    /** Not asked: the connection is metered and downloads on mobile data are off. */
    data object MobileData : Summary
    data class Failed(@StringRes val message: Int, val code: Int? = null) : Summary
}

/** Where the app is: what the Install button can do. */
enum class InstallState { NOT_INSTALLED, QUEUED, INSTALLING, INSTALLED, UNKNOWN }

/**
 * An app's page: its icon, name (with the check when Flathub verified it), developer and ID,
 * Install (queued behind whatever runs), screenshots, the description, what it asks for, its size
 * and the runtime it needs (installed already or its size), its license and links. The catalog
 * ([app], [Catalog]) gives most of it; sizes and permissions come from Flathub when the page opens,
 * not on mobile data when downloads there are off. An app the catalog doesn't have (a link to one
 * with no build for this phone) shows what Flathub says. The top bar shows the name once the
 * header has scrolled away. On mobile data, screenshots wait for the user when downloads there are
 * set to Ask (asked per page; a page asked for once shows them for the rest of this run,
 * [CatalogImages.allowedOnMobileData]) and aren't loaded when they are off. A tapped screenshot opens full size.
 */
@Composable
fun BrowseAppScreen(
    appId: String,
    app: CatalogApp?,
    /** The index [app] comes from: its page data is read from it, again when it is rebuilt. */
    index: CatalogIndex?,
    /** The index is being read or built (after a change, or Android's Clear cache): the page says so meanwhile. */
    catalogLoading: Boolean,
    arch: String,
    installState: InstallState,
    mobileDataBlocked: () -> Boolean,
    /** What the mobile data setting says now ([MobileData.ALWAYS] when the connection isn't metered). */
    mobileData: () -> MobileData,
    /** Whether the runtime (a ref in Flathub's architecture) is installed already. Called off the main thread. */
    runtimeInstalled: (FlatpakRef) -> Boolean,
    onInstall: () -> Unit,
    onUnqueue: () -> Unit,
    /** For an installed app: starts it (when [canOpen]: nothing else runs), opens its settings, uninstalls it (queued behind what runs). */
    canOpen: Boolean,
    onOpen: () -> Unit,
    onAppSettings: () -> Unit,
    onUninstall: (deleteData: Boolean, keepRuntimes: Boolean) -> Unit,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val details by produceState<CatalogDetails?>(null, appId, index) {
        value = index?.let { withContext(Dispatchers.IO) { it.details(appId) } }
    }
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    val summary by produceState<Summary>(Summary.Loading, appId, attempt) {
        value = Summary.Loading
        if (mobileDataBlocked()) {
            value = Summary.MobileData
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            try {
                val found = Catalog.summary(appId)
                Summary.Found(found, found.runtimeRef?.let(runtimeInstalled) == true)
            } catch (_: FlathubNotFoundException) {
                Summary.NotFound
            } catch (e: Exception) {
                DebugLog.w("Asking Flathub about $appId failed: $e")
                when (e) {
                    is FlathubHttpException -> Summary.Failed(R.string.install_link_server_error, e.code)
                    is FlathubBadAnswerException -> Summary.Failed(R.string.install_link_bad_answer)
                    else -> Summary.Failed(R.string.install_link_failed)
                }
            }
        }
    }
    // Installs, asked with the rest (and kept until the index is rebuilt); nothing shown when Flathub doesn't answer.
    val stats by produceState<FlathubStats?>(null, appId, attempt) {
        if (mobileDataBlocked()) return@produceState
        value = withContext(Dispatchers.IO) { runCatching { Catalog.stats(appId) }.getOrNull() }
    }
    val found = (summary as? Summary.Found)?.app
    // The catalog only has apps built for this phone; one it doesn't have is checked with Flathub's answer.
    val builtHere = app != null || found?.builtFor(arch) == true
    val name = app?.name ?: found?.name ?: appId
    val verified = app?.verified ?: found?.verified ?: false

    val scroll = rememberScrollState()
    // The header's name is in view: the top bar has none, so it isn't shown twice.
    val scrolledPast = with(LocalDensity.current) { HEADER_HEIGHT.toPx() }
    val headerGone by remember(scrolledPast) { derivedStateOf { scroll.value > scrolledPast } }
    var fullScreenshot by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirmUninstall by rememberSaveable { mutableStateOf(false) }
    if (confirmUninstall) {
        UninstallDialog(name, onConfirm = { data, runtimes -> confirmUninstall = false; onUninstall(data, runtimes) }, onDismiss = { confirmUninstall = false })
    }
    // This page's own answer to Load screenshots on mobile data.
    var loadScreenshots by remember(appId) { mutableStateOf(CatalogImages.allowedOnMobileData(appId)) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back)) }
            AnimatedVisibility(headerGone, enter = fadeIn(), exit = fadeOut()) {
                Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header.
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AppIcon(Catalog.iconFile(context, app?.icon), Modifier.size(64.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f, fill = false))
                        if (verified) {
                            Icon(
                                Icons.Filled.Verified, contentDescription = stringResource(R.string.install_link_verified),
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp).size(22.dp),
                            )
                        }
                    }
                    app?.developer?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Text(appId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            (app?.summary?.takeIf { it.isNotEmpty() } ?: found?.summary)?.let { Text(it, Modifier.padding(horizontal = 16.dp)) }

            // Install.
            Box(Modifier.padding(horizontal = 16.dp)) {
                when (installState) {
                    InstallState.INSTALLED -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = onOpen, enabled = canOpen) { Text(stringResource(R.string.browse_open)) }
                        OutlinedButton(onClick = onAppSettings) { Text(stringResource(R.string.settings_title)) }
                        OutlinedButton(onClick = { confirmUninstall = true }) { Text(stringResource(R.string.uninstall_button)) }
                    }
                    InstallState.INSTALLING -> OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.browse_installing)) }
                    InstallState.QUEUED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.browse_queued_waiting), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onUnqueue) { Text(stringResource(R.string.browse_queued_cancel)) }
                    }
                    InstallState.UNKNOWN -> Button(onClick = {}, enabled = false) { Text(stringResource(R.string.install_confirm)) }
                    InstallState.NOT_INSTALLED -> Button(onClick = onInstall, enabled = builtHere) { Text(stringResource(R.string.install_confirm)) }
                }
            }
            if (!builtHere && found != null) {
                Text(
                    stringResource(R.string.install_link_no_arch, arch, found.arches.joinToString(", ").ifEmpty { "?" }),
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // What Flathub says about sizes and permissions.
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (val s = summary) {
                    Summary.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.install_link_loading), style = MaterialTheme.typography.bodySmall)
                    }
                    Summary.NotFound -> Text(stringResource(R.string.install_link_not_found, appId), style = MaterialTheme.typography.bodySmall)
                    Summary.MobileData -> Retry(stringResource(R.string.install_link_mobile_data)) { attempt++ }
                    is Summary.Failed -> Retry(if (s.code != null) stringResource(s.message, s.code) else stringResource(s.message)) { attempt++ }
                    is Summary.Found -> {
                        val info = s.app
                        info.installedSize?.let {
                            Text(stringResource(R.string.install_link_app_size, Formatter.formatShortFileSize(context, it)), style = MaterialTheme.typography.bodySmall)
                        }
                        if (info.runtime != null) {
                            val state = if (s.runtimeInstalled) stringResource(R.string.install_link_runtime_installed)
                            else info.runtimeSize?.let { Formatter.formatShortFileSize(context, it) } ?: "?"
                            Text(stringResource(R.string.install_link_runtimes, info.runtime, state), style = MaterialTheme.typography.bodySmall)
                        }
                        val asks = info.permissions.map { stringResource(permissionName(it)) }
                        if (asks.isNotEmpty()) Text(stringResource(R.string.install_link_asks, asks.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
                        stats?.let { counts ->
                            val total = counts.total
                            val month = counts.lastMonth
                            if (total != null && month != null) {
                                Text(stringResource(R.string.browse_installs, compactCount(total), compactCount(month)), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            val shown = details
            if (shown == null && catalogLoading) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.browse_loading), style = MaterialTheme.typography.bodySmall)
                }
            }
            // Screenshots, as wide as they come at a fixed height; on mobile data as its setting says.
            val shots = shown?.screenshots.orEmpty()
            if (shots.isNotEmpty()) {
                val policy = remember(appId) { mobileData() }
                when {
                    policy == MobileData.ALWAYS || loadScreenshots -> {
                        val loader = CatalogImages.loader(context)
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(shots, key = { _, shot -> shot.image }) { i, shot ->
                                AsyncImage(
                                    model = shot.image, contentDescription = shot.caption, imageLoader = loader, contentScale = ContentScale.Fit,
                                    modifier = Modifier.height(SCREENSHOT_HEIGHT).widthIn(min = 120.dp, max = 480.dp)
                                        .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { fullScreenshot = i },
                                )
                            }
                        }
                    }
                    policy == MobileData.ASK -> OutlinedButton(
                        onClick = {
                            CatalogImages.allowOnMobileData(appId)
                            loadScreenshots = true
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text(stringResource(R.string.browse_load_screenshots))
                    }
                    else -> Text(
                        stringResource(R.string.browse_screenshots_mobile_data), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            // The description.
            shown?.description?.takeIf { it.isNotEmpty() }?.let { blocks ->
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (block in blocks) when (block) {
                        is TextBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
                        is TextBlock.Items -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            block.items.forEachIndexed { i, item ->
                                Text(if (block.ordered) "${i + 1}. $item" else "• $item", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            // License and links (opened in the browser).
            shown?.license?.let { license ->
                // Flathub marks a closed app as `LicenseRef-proprietary=<its terms>`.
                val name = if (license.startsWith("LicenseRef-proprietary")) stringResource(R.string.browse_license_proprietary) else license
                Text(stringResource(R.string.browse_license, name), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
            val links = shown?.urls.orEmpty().mapNotNull { (kind, link) -> LINK_LABELS[kind]?.let { it to link } }.distinctBy { it.first }
            if (links.isNotEmpty()) {
                FlowRow(Modifier.padding(horizontal = 8.dp)) {
                    for ((label, link) in links) {
                        TextButton(onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE))
                            } catch (_: ActivityNotFoundException) {
                            }
                        }) { Text(stringResource(label)) }
                    }
                }
            }
        }
        bottomBar()
    }
    fullScreenshot?.let { start ->
        details?.screenshots?.takeIf { start in it.indices }?.let { shots -> ScreenshotViewer(shots, start) { fullScreenshot = null } }
    }
}

/**
 * Screenshots full size over everything, starting at [start]; swiped through. The original picture
 * loads over the phone-sized one already shown, so something is there at once.
 */
@Composable
private fun ScreenshotViewer(shots: List<Screenshot>, start: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val loader = CatalogImages.loader(context)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start) { shots.size }
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                val shot = shots[page]
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f, fill = false)) {
                        AsyncImage(model = shot.image, contentDescription = null, imageLoader = loader, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                        shot.full?.let { full ->
                            AsyncImage(model = full, contentDescription = shot.caption, imageLoader = loader, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize())
                        }
                    }
                    shot.caption?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dismiss), tint = Color.White)
            }
        }
    }
}

@Composable
private fun Retry(text: String, onRetry: () -> Unit) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.install_link_retry)) }
}

private fun permissionName(permission: FlathubApp.Permission) = when (permission) {
    FlathubApp.Permission.NETWORK -> R.string.app_settings_network
    FlathubApp.Permission.SOUND -> R.string.app_settings_audio
    FlathubApp.Permission.ALL_DEVICES -> R.string.permission_all_devices
    FlathubApp.Permission.FILES -> R.string.permission_files
}

private val SCREENSHOT_HEIGHT = 240.dp

/** How far the page scrolls before the top bar shows the app's name: about the header's height. */
private val HEADER_HEIGHT = 72.dp

/** The AppStream link types the page offers, in this order. */
private val LINK_LABELS = linkedMapOf(
    "homepage" to R.string.browse_link_homepage,
    "help" to R.string.browse_link_help,
    "bugtracker" to R.string.browse_link_bugtracker,
    "vcs-browser" to R.string.browse_link_source,
    "donation" to R.string.browse_link_donation,
)
