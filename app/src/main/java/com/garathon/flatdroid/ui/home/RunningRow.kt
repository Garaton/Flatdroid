package com.garathon.flatdroid.ui.home

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.InstalledApp
import com.garathon.flatdroid.session.SessionState
import com.garathon.flatdroid.ui.IconAction

/**
 * The app that starts or runs, pinned above the list in the shape of a list row: its icon and
 * name, and under the name what it is doing now (its ID once it runs). On the right, instead of
 * the gear and the last opened time: while it starts, a dial (with developer options a tap on it
 * shows the log), skipping the file check while that runs, and stop; once it runs, the log (with
 * developer options), stop, open, and for an external viewer its address and password.
 * [large] is the bigger one of a phone held sideways.
 */
@Composable
fun RunningRow(
    state: SessionState,
    app: InstalledApp?,
    fromRow: Boolean,
    developerOptions: Boolean,
    onOpen: (SessionState.Running) -> Unit,
    onStop: () -> Unit,
    onLog: () -> Unit,
    onSkipCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appId = state.appIdOrNull ?: return
    var viewerInfo by rememberSaveable { mutableStateOf(false) }
    if (viewerInfo && state is SessionState.Running && state.socket == null) {
        ExternalViewerDialog(state, onDismiss = { viewerInfo = false })
    }
    val colors = MaterialTheme.colorScheme
    val background = remember { Animatable(if (fromRow) colors.surface else colors.secondaryContainer) }
    LaunchedEffect(Unit) { if (fromRow) background.animateTo(colors.secondaryContainer, tween(300)) }
    Column(modifier.fillMaxWidth().background(background.value)) {
        Row(
            Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIcon(app?.icon, Modifier.size(48.dp))
            Column(Modifier.weight(1f)) {
                Text(app?.name ?: appId, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (state) {
                        is SessionState.Starting -> state.detail
                        is SessionState.PreparingElectron -> state.detail
                        is SessionState.Stopping -> stringResource(R.string.session_stopping_short)
                        else -> appId
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    // What a file check is on can be long: two lines, which fit the fixed row height.
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            when (state) {
                is SessionState.Starting, is SessionState.PreparingElectron, is SessionState.Stopping -> {
                    Dial(
                        fraction = (state as? SessionState.PreparingElectron)?.fraction ?: (state as? SessionState.Starting)?.fraction,
                        onClick = onLog.takeIf { developerOptions },
                    )
                    if (state is SessionState.Starting && state.skippable) IconAction(Icons.Filled.SkipNext, R.string.session_skip_check, onSkipCheck, size = ICON)
                    if (state !is SessionState.Stopping) IconAction(Icons.Filled.PowerSettingsNew, R.string.session_stop, onStop, size = ICON)
                }
                is SessionState.Running -> {
                    if (developerOptions) IconAction(Icons.Filled.Info, R.string.show_log, onLog, size = ICON)
                    if (state.socket == null) IconAction(Icons.Filled.Link, R.string.session_external_title, { viewerInfo = true }, size = ICON)
                    IconAction(Icons.Filled.PowerSettingsNew, R.string.session_stop, onStop, size = ICON)
                    IconAction(Icons.Filled.OpenInNew, if (state.socket != null) R.string.session_open_app else R.string.session_open_viewer, { onOpen(state) }, size = ICON)
                }
                else -> Unit
            }
        }
        // As a list row has one, so the two are the same height.
        HorizontalDivider()
    }
}

/** The size of the icon buttons of a row (the dial, stop, open, the gear of a list row). */
internal val ICON = 36.dp

/** The spinning dial of something under way (a progress ring when it knows how far it is); tappable when [onClick] is set. */
@Composable
private fun Dial(fraction: Float?, onClick: (() -> Unit)?) {
    Box(
        Modifier.size(ICON).clip(CircleShape).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (fraction != null) CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
    }
}
