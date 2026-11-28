package com.garathon.flatdroid.ui.browse

import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.outlined.AddToHomeScreen
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.graphics.vector.ImageVector
import com.garathon.flatdroid.ui.common.SettingsCard
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.alpha
import java.io.File
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.flathub.FlathubApp
import com.garathon.flatdroid.flatpak.flathub.FlathubBadAnswerException
import com.garathon.flatdroid.flatpak.flathub.FlathubHttpException
import com.garathon.flatdroid.flatpak.flathub.FlathubNotFoundException
import com.garathon.flatdroid.flatpak.flathub.FlathubStats
import com.garathon.flatdroid.flatpak.FlatpakRef
import com.garathon.flatdroid.flatpak.catalog.Catalog
import com.garathon.flatdroid.flatpak.catalog.CatalogApp
import com.garathon.flatdroid.flatpak.catalog.CatalogDetails
import com.garathon.flatdroid.flatpak.catalog.CatalogImages
import com.garathon.flatdroid.flatpak.catalog.TextBlock
import com.garathon.flatdroid.ui.home.AppIcon
import com.garathon.flatdroid.ui.settings.app.UninstallDialog
import com.garathon.flatdroid.util.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.garathon.flatdroid.ui.common.BusySpinner

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
 * Install (queued behind whatever runs), its installs and size, screenshots, and cards for the
 * description, the details (the runtime it needs, installed already or its size; what it asks for;
 * its license) and its links. The catalog
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
    /** Flathub's saved lists say the app is verified: shown until the page's own answer comes. */
    listedVerified: Boolean,
    installState: InstallState,
    mobileDataBlocked: () -> Boolean,
    /** What the mobile data setting says now ([MobileData.ALWAYS] when the connection isn't metered). */
    mobileData: () -> MobileData,
    /** Whether the runtime (a ref in Flathub's architecture) is installed already. Called off the main thread. */
    runtimeInstalled: (FlatpakRef) -> Boolean,
    onInstall: () -> Unit,
    onUnqueue: () -> Unit,
    /** For an installed app: starts it, shows it ([open] says which), opens its settings, uninstalls it (queued behind what runs). */
    open: OpenState,
    onOpen: () -> Unit,
    onAppSettings: () -> Unit,
    onUninstall: (deleteData: Boolean, keepRuntimes: Boolean) -> Unit,
    /** For an installed app: asks the launcher for a home-screen shortcut to it; null when it takes none. */
    onAddShortcut: (() -> Unit)?,
    /** The title the shared top bar shows for this page ([BrowseAppPager]): the name once the header has scrolled away, else null. */
    onTitle: (String?) -> Unit,
    modifier: Modifier = Modifier,
    /** The page is the one on screen, not a neighbor built ahead ([BrowseAppPager]): its screenshots load only then. */
    onScreen: Boolean = true,
) {
    val context = LocalContext.current
    val details by produceState<CatalogDetails?>(null, appId, index) {
        value = index?.let { withContext(Dispatchers.IO) { it.details(appId) } }
    }
    // A file check, so off the main thread (a page is composed for every swipe).
    val iconFile by produceState<File?>(null, app?.icon) { value = withContext(Dispatchers.IO) { Catalog.iconFile(context, app?.icon) } }
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
                DebugLog.w { "Asking Flathub about $appId failed: $e" }
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
    // Flathub's answer, not the catalog's, which an installed app can rewrite: the page's own, else the saved lists'.
    val verified = found?.verified ?: listedVerified

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

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Beside the header in landscape, as tall as suits it.
    val tall = if (landscape) Modifier.height(LANDSCAPE_BUTTON_HEIGHT) else Modifier
    // A pill that tall looks swollen: rounded corners instead.
    val tallShape = if (landscape) RoundedCornerShape(30.dp) else ButtonDefaults.shape
    val installButtons = @Composable { boxModifier: Modifier ->
        Box(boxModifier) {
            when (installState) {
                // Open with its label; settings and uninstall as icons beside it, uninstall in red.
                InstallState.INSTALLED -> {
                    val settingsButton = @Composable {
                        OutlinedIconButton(onClick = onAppSettings, modifier = Modifier.alpha(if (open == OpenState.STARTING || open == OpenState.RUNNING) 0.6f else 1f)) {
                            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_title))
                        }
                    }
                    val uninstallButton = @Composable {
                        OutlinedIconButton(
                            onClick = { confirmUninstall = true },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                            colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.uninstall_button)) }
                    }
                    val icons = @Composable {
                        onAddShortcut?.let { add ->
                            OutlinedIconButton(onClick = add) {
                                Icon(Icons.AutoMirrored.Outlined.AddToHomeScreen, contentDescription = stringResource(R.string.shortcut_add))
                            }
                        }
                        settingsButton()
                        uninstallButton()
                    }
                    // In landscape there is no room beside Open for its label: the icons go under it, and
                    // Open is as wide as their row (or as its label needs, when that is wider).
                    if (landscape) {
                        Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Inset by the icons' touch margin, so its edges line up with the outer circles.
                            OpenButton(open, onOpen, ButtonDefaults.shape, false, Modifier.fillMaxWidth().padding(horizontal = 4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { icons() }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OpenButton(open, onOpen, tallShape, false, Modifier.weight(1f))
                            icons()
                        }
                    }
                }
                // Muted, but as big as Install, with a spinner where its icon was.
                InstallState.INSTALLING -> OutlinedButton(
                    onClick = {}, enabled = false, shape = tallShape, modifier = Modifier.fillMaxWidth().then(tall),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) {
                    BusySpinner(Modifier.size(if (landscape) 24.dp else 16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(if (landscape) 12.dp else 8.dp))
                    Text(stringResource(R.string.browse_installing), style = if (landscape) MaterialTheme.typography.titleLarge else LocalTextStyle.current)
                }
                // In landscape as tall as Install beside the header: the note over a full-width Cancel.
                InstallState.QUEUED -> if (landscape) {
                    Column(Modifier.fillMaxWidth().then(tall), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                        Text(stringResource(R.string.browse_queued_waiting), style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = onUnqueue, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text(stringResource(R.string.cancel), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.browse_queued_waiting), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = onUnqueue) { Text(stringResource(R.string.cancel)) }
                    }
                }
                InstallState.UNKNOWN -> Button(onClick = {}, enabled = false, shape = tallShape, modifier = Modifier.fillMaxWidth().then(tall)) {
                    ButtonContent(Icons.Filled.Download, stringResource(R.string.install), large = landscape)
                }
                InstallState.NOT_INSTALLED -> Button(onClick = onInstall, enabled = builtHere, shape = tallShape, modifier = Modifier.fillMaxWidth().then(tall)) {
                    ButtonContent(Icons.Filled.Download, stringResource(R.string.install), large = landscape)
                }
            }
        }
    }

    val title = name.takeIf { headerGone }
    LaunchedEffect(title) { onTitle(title) }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Header: the icon, the name, who made it, the ID; one line each, so pages swiped side by
            // side keep the rest in place. The name shrinks to fit, then each ends in "…" (the ID in
            // the middle: its start and end tell apps apart).
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AppIcon(iconFile, Modifier.size(80.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val nameStyle = MaterialTheme.typography.headlineSmall
                        Text(
                            name, style = nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            autoSize = TextAutoSize.StepBased(minFontSize = nameStyle.fontSize * NAME_MIN_SCALE, maxFontSize = nameStyle.fontSize, stepSize = 1.sp),
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (verified) {
                            Icon(
                                Icons.Filled.Verified, contentDescription = stringResource(R.string.flathub_verified),
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp).size(22.dp),
                            )
                        }
                    }
                    app?.developer?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Text(appId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
                // Centered in the room beside the name.
                if (landscape) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { installButtons(Modifier.widthIn(max = 320.dp)) }
            }
            (app?.summary?.takeIf { it.isNotEmpty() } ?: found?.summary)?.let {
                // Two lines kept for it, so pages swiped through keep the rest in the same place.
                Text(it, style = MaterialTheme.typography.bodyLarge, minLines = 2, modifier = Modifier.padding(horizontal = 16.dp))
            }

            // Install: under the header, or beside it in landscape.
            if (!landscape) installButtons(Modifier.padding(horizontal = 16.dp))
            if (!builtHere && found != null) {
                Text(
                    stringResource(R.string.install_link_no_arch, arch, found.arches.joinToString(", ").ifEmpty { "?" }),
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // Installs and size at a glance; or why Flathub didn't say.
            when (val s = summary) {
                Summary.Loading -> Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BusySpinner(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.flathub_loading), style = MaterialTheme.typography.bodySmall)
                }
                Summary.NotFound -> Text(stringResource(R.string.flathub_not_found, appId), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                Summary.MobileData -> Column(Modifier.padding(horizontal = 16.dp)) { Retry(stringResource(R.string.install_link_mobile_data)) { attempt++ } }
                is Summary.Failed -> Column(Modifier.padding(horizontal = 16.dp)) {
                    Retry(if (s.code != null) stringResource(s.message, s.code) else stringResource(s.message)) { attempt++ }
                }
                is Summary.Found -> Stats(
                    listOfNotNull(
                        stats?.total?.let { compactCount(it) to stringResource(R.string.browse_stat_installs) },
                        stats?.lastMonth?.let { compactCount(it) to stringResource(R.string.browse_stat_month) },
                        s.app.installedSize?.let { Formatter.formatShortFileSize(context, it) to stringResource(R.string.browse_stat_size) },
                    ),
                )
            }

            val shown = details
            if (shown == null && catalogLoading) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BusySpinner(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.browse_loading), style = MaterialTheme.typography.bodySmall)
                }
            }
            // Screenshots, as wide as they come at a fixed height; on mobile data as its setting says.
            val shots = shown?.screenshots.orEmpty()
            if (shots.isNotEmpty()) {
                val policy = remember(appId) { mobileData() }
                when {
                    // A neighbor built ahead keeps their room, so nothing moves when it comes on screen.
                    !onScreen -> Spacer(Modifier.height(SCREENSHOT_HEIGHT))
                    policy == MobileData.ALWAYS || loadScreenshots -> {
                        val loader = CatalogImages.loader(context)
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(shots, key = { _, shot -> shot.image }) { i, shot ->
                                AsyncImage(
                                    model = shot.image, contentDescription = shot.caption, imageLoader = loader, contentScale = ContentScale.Fit,
                                    modifier = Modifier.height(SCREENSHOT_HEIGHT).widthIn(min = 120.dp, max = 480.dp)
                                        .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
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
                SettingsCard(Modifier.padding(horizontal = 16.dp), title = stringResource(R.string.browse_about)) {
                    for (block in blocks) when (block) {
                        is TextBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
                        is TextBlock.Items -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            block.items.forEachIndexed { i, item ->
                                Row {
                                    Text(
                                        if (block.ordered) "${i + 1}." else "•", style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.widthIn(min = 20.dp),
                                    )
                                    Text(item, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }

            // What it needs and asks for, and its license.
            val info = found
            val license = shown?.license
            if (info?.runtime != null || info?.permissions?.isNotEmpty() == true || license != null) {
                SettingsCard(Modifier.padding(horizontal = 16.dp), title = stringResource(R.string.browse_details)) {
                    info?.runtime?.let { runtime ->
                        val state = if ((summary as? Summary.Found)?.runtimeInstalled == true) stringResource(R.string.browse_runtime_installed)
                        else stringResource(R.string.browse_runtime_size, info.runtimeSize?.let { Formatter.formatShortFileSize(context, it) } ?: "?")
                        InfoRow(Icons.Outlined.Layers, stringResource(R.string.browse_runtime)) {
                            Text(runtime, style = MaterialTheme.typography.bodyMedium)
                            Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    info?.permissions?.takeIf { it.isNotEmpty() }?.let { permissions ->
                        InfoRow(Icons.Outlined.Shield, stringResource(R.string.browse_asks_for)) {
                            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (permission in permissions) PermissionTag(permission)
                            }
                        }
                    }
                    license?.let {
                        // Flathub marks a closed app as `LicenseRef-proprietary=<its terms>`.
                        val licenseName = if (it.startsWith("LicenseRef-proprietary")) stringResource(R.string.browse_license_proprietary) else it
                        InfoRow(Icons.Outlined.Gavel, stringResource(R.string.browse_license_title)) {
                            Text(licenseName, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // Links (opened in the browser).
            val links = shown?.urls.orEmpty().mapNotNull { (kind, link) -> LINKS[kind]?.let { it to link } }.distinctBy { it.first }
            if (links.isNotEmpty()) {
                SettingsCard(Modifier.padding(horizontal = 16.dp), title = stringResource(R.string.browse_links)) {
                    for ((kind, link) in links) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
                                } catch (_: ActivityNotFoundException) {
                                }
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Icon(kind.second, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(kind.first), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Icon(
                                Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    fullScreenshot?.let { start ->
        details?.screenshots?.takeIf { start in it.indices }?.let { shots -> ScreenshotViewer(shots, start) { fullScreenshot = null } }
    }
}

/**
 * App pages side by side in the browser's list order ([ids], as searched, filtered and sorted;
 * null while it is prepared): swiped sideways to the next app (from the right) or the one before.
 * A drag that starts up or down scrolls the page instead. [onSettled] gets the app swiped to and
 * its place in [ids]. An app not in [ids] (opened from a link) has no neighbors. The top bar (Back,
 * and the app's name once its header has scrolled away) stays put while pages slide.
 *
 * [BUILT_AHEAD] pages each side of the one shown are built ahead (text and details, not
 * screenshots: [page]'s `onScreen`), so a swipe doesn't wait for them.
 *
 * Only the apps around the one shown are pages ([WINDOW] each way, moving with it): a pager asked
 * to fill a viewport that has no width (measured while the window is resized) composes page after
 * page, a whole app page each, until memory runs out, however many apps the list has.
 */
@Composable
fun BrowseAppPager(
    ids: List<String>?,
    current: String,
    onSettled: (id: String, index: Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    page: @Composable (id: String, onScreen: Boolean, onTitle: (String?) -> Unit) -> Unit,
) {
    // Each app once: the pager's keys are the IDs, and a repeated one ends Flatdroid.
    val order = remember(ids, current) { ids?.takeIf { current in it }?.distinct() ?: listOf(current) }
    val window = remember(order, current) { pagerWindow(order, current, WINDOW) }
    // Where the pages start in [order]; a page's place in the list is this and its place here.
    val first = window.first
    val pages = window.pages
    val pager = rememberPagerState(initialPage = window.currentAt) { pages.size }
    val shown by rememberUpdatedState(current)
    // The list was prepared or changed (a filter) with the page open: keep the app shown.
    LaunchedEffect(pages) {
        val at = pages.indexOf(shown)
        if (at >= 0 && at != pager.currentPage) pager.scrollToPage(at)
    }
    LaunchedEffect(pager, pages) {
        snapshotFlow { pager.settledPage to pager.isScrollInProgress }
            // Not while it moves (the window follows what is shown, which moves the pages under it), and not for the app already shown.
            .filter { (_, moving) -> !moving }
            .map { (at, _) -> at }
            .distinctUntilChanged()
            .collect { at -> pages.getOrNull(at)?.takeIf { it != shown }?.let { onSettled(it, first + at) } }
    }
    // Each page's title, by app.
    val titles = remember { mutableStateMapOf<String, String?>() }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Crossfade(pages.getOrNull(pager.currentPage)?.let { titles[it] }, label = "title") { title ->
                if (title != null) Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
            }
        }
        HorizontalPager(pager, Modifier.weight(1f), key = { pages[it] }, verticalAlignment = Alignment.Top, beyondViewportPageCount = BUILT_AHEAD) { at ->
            val id = pages[at]
            page(id, at == pager.settledPage) { titles[id] = it }
        }
    }
}

/** The pages around the app shown: [pages] from [first] in the whole list, the app being at [currentAt]. */
class PagerWindow(val first: Int, val pages: List<String>, val currentAt: Int)

/** [radius] apps each side of [current] in [order] (fewer near the ends); [current] not in [order] is the only page. */
fun pagerWindow(order: List<String>, current: String, radius: Int): PagerWindow {
    val centre = order.indexOf(current)
    if (centre < 0) return PagerWindow(0, listOf(current), 0)
    val first = (centre - radius).coerceAtLeast(0)
    return PagerWindow(first, order.subList(first, minOf(order.size, centre + radius + 1)), centre - first)
}

/** The smallest the app's name shrinks to in its header before it is cut, as a share of its size. */
private const val NAME_MIN_SCALE = 0.75f

/**
 * How many apps each side of the one shown the pager holds as pages ([BrowseAppPager]). The window
 * moves only once a swipe settles, so quick swipes in a row run this far before meeting its end.
 */
private const val WINDOW = 6

/** How many pages each side of the one shown are built before they are swiped to. */
private const val BUILT_AHEAD = 2

/**
 * Screenshots full size over everything, starting at [start]; swiped through sideways. Closed by
 * its X, Back, or swiping the picture up or down past [DISMISS_DRAG] (it follows the finger and
 * the black fades; a shorter swipe springs back). A tap does nothing, so looking closely doesn't
 * close it. The original picture loads over the phone-sized one already shown, so something is
 * there at once.
 */
@Composable
private fun ScreenshotViewer(shots: List<Screenshot>, start: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val loader = CatalogImages.loader(context)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start) { shots.size }
        val scope = rememberCoroutineScope()
        val dragY = remember { Animatable(0f) }
        val dismissPx = with(LocalDensity.current) { DISMISS_DRAG.toPx() }
        val swipe = Modifier.pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragEnd = { if (abs(dragY.value) > dismissPx) onClose() else scope.launch { dragY.animateTo(0f) } },
                onDragCancel = { scope.launch { dragY.animateTo(0f) } },
            ) { change, amount ->
                change.consume()
                scope.launch { dragY.snapTo(dragY.value + amount) }
            }
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - (abs(dragY.value) / (dismissPx * 3)).coerceAtMost(0.6f) }.background(Color.Black).then(swipe)) {
            HorizontalPager(pager, Modifier.fillMaxSize().graphicsLayer { translationY = dragY.value }) { page ->
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
    TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.retry)) }
}

/**
 * What the page's main button for an installed app does: [READY] starts it, [STARTING] is it
 * starting, [RUNNING] brings its window forward, [BUSY] is another app or a task in the way.
 */
enum class OpenState { READY, STARTING, RUNNING, BUSY }

/** The four states look different: a filled Open, a spinner while it starts, a tonal Show app once it runs, an outlined Busy. */
@Composable
private fun OpenButton(state: OpenState, onOpen: () -> Unit, shape: Shape, large: Boolean, modifier: Modifier) {
    when (state) {
        OpenState.READY -> Button(onClick = onOpen, shape = shape, modifier = modifier) {
            ButtonContent(Icons.Filled.PlayArrow, stringResource(R.string.browse_open), large = large)
        }
        OpenState.STARTING -> FilledTonalButton(onClick = {}, enabled = false, shape = shape, modifier = modifier) {
            BusySpinner(Modifier.size(if (large) 28.dp else 18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(if (large) 12.dp else 8.dp))
            Text(stringResource(R.string.browse_starting), style = if (large) MaterialTheme.typography.titleLarge else LocalTextStyle.current)
        }
        OpenState.RUNNING -> FilledTonalButton(onClick = onOpen, shape = shape, modifier = modifier) {
            ButtonContent(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.browse_show), large = large)
        }
        OpenState.BUSY -> OutlinedButton(onClick = {}, enabled = false, shape = shape, modifier = modifier) {
            ButtonContent(Icons.Outlined.Block, stringResource(R.string.browse_busy), large = large)
        }
    }
}

/** A button's icon and label; [large] for the tall buttons beside the header in landscape. */
@Composable
private fun ButtonContent(icon: ImageVector, label: String, large: Boolean = false) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(if (large) 28.dp else 18.dp))
    Spacer(Modifier.width(if (large) 12.dp else 8.dp))
    Text(label, style = if (large) MaterialTheme.typography.titleLarge else LocalTextStyle.current)
}

/** Figures side by side, each over what it counts, lines between them. */
@Composable
private fun Stats(stats: List<Pair<String, String>>) {
    if (stats.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(IntrinsicSize.Min)) {
        stats.forEachIndexed { i, (value, label) ->
            if (i > 0) VerticalDivider(Modifier.padding(vertical = 4.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** A row of the details card: an icon, what the row is, and [content] under that. */
@Composable
private fun InfoRow(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

/** What an app asks for, as a small tag with its icon. */
@Composable
private fun PermissionTag(permission: FlathubApp.Permission) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(permissionIcon(permission), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(stringResource(permissionName(permission)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

private fun permissionIcon(permission: FlathubApp.Permission) = when (permission) {
    FlathubApp.Permission.NETWORK -> Icons.Outlined.Wifi
    FlathubApp.Permission.SOUND -> Icons.AutoMirrored.Outlined.VolumeUp
    FlathubApp.Permission.ALL_DEVICES -> Icons.Outlined.Usb
    FlathubApp.Permission.FILES -> Icons.Outlined.Folder
}

private fun permissionName(permission: FlathubApp.Permission) = when (permission) {
    FlathubApp.Permission.NETWORK -> R.string.app_settings_network
    FlathubApp.Permission.SOUND -> R.string.app_settings_audio
    FlathubApp.Permission.ALL_DEVICES -> R.string.permission_all_devices
    FlathubApp.Permission.FILES -> R.string.permission_files
}

private val SCREENSHOT_HEIGHT = 240.dp

/** How far a full-size screenshot is swiped up or down to close it. */
private val DISMISS_DRAG = 120.dp

/** The buttons' height beside the header in landscape: two icon buttons, one over the other. */
private val LANDSCAPE_BUTTON_HEIGHT = 84.dp

/** How far the page scrolls before the top bar shows the app's name: about the header's height. */
private val HEADER_HEIGHT = 72.dp

/** The AppStream link types the page offers, in this order: label and icon. */
private val LINKS = linkedMapOf(
    "homepage" to (R.string.browse_link_homepage to Icons.Outlined.Public),
    "help" to (R.string.browse_link_help to Icons.AutoMirrored.Outlined.HelpOutline),
    "bugtracker" to (R.string.browse_link_bugtracker to Icons.Outlined.BugReport),
    "vcs-browser" to (R.string.browse_link_source to Icons.Outlined.Code),
    "donation" to (R.string.browse_link_donation to Icons.Outlined.VolunteerActivism),
)
