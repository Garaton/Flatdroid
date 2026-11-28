package com.garathon.flatdroid.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.InstalledApp
import com.garathon.flatdroid.session.SessionState
import com.garathon.flatdroid.ui.common.BusyLine

/**
 * Covers Flatdroid's window while an app a home-screen shortcut started is on its way: its icon,
 * name and what the start is doing, with Stop. The window behind (whatever was open, then Home)
 * isn't seen; Back uncovers it. Questions the start asks are dialogs, shown above.
 */
@Composable
fun LaunchCover(
    appId: String,
    app: InstalledApp?,
    state: SessionState,
    onSkipCheck: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val ours = state.appIdOrNull == appId
    val starting = state as? SessionState.Starting
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(app?.icon, Modifier.size(96.dp))
            Text(
                app?.name ?: appId,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                starting?.takeIf { ours }?.detail?.takeIf { it.isNotBlank() } ?: stringResource(R.string.session_starting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            val fraction = starting?.takeIf { ours }?.fraction
            val progress = Modifier.padding(top = 24.dp).width(240.dp)
            if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = progress)
            else BusyLine(progress)
            if (ours) {
                Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (starting?.skippable == true) TextButton(onClick = onSkipCheck) { Text(stringResource(R.string.session_skip_check)) }
                    OutlinedButton(onClick = onStop) { Text(stringResource(R.string.session_stop)) }
                }
            }
        }
    }
}
