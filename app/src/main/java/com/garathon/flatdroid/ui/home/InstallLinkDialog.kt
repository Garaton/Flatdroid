package com.garathon.flatdroid.ui.home

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.FlathubApp
import com.garathon.flatdroid.flatpak.FlathubBadAnswerException
import com.garathon.flatdroid.flatpak.FlathubHttpException
import com.garathon.flatdroid.flatpak.FlathubClient
import com.garathon.flatdroid.flatpak.FlathubNotFoundException
import com.garathon.flatdroid.flatpak.FlatpakRef
import com.garathon.flatdroid.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What Flathub said about the app of a link. */
private sealed interface LinkApp {
    data object Loading : LinkApp
    data class Found(val app: FlathubApp, val runtimeInstalled: Boolean) : LinkApp
    data object NotFound : LinkApp
    /** Not asked: the connection is metered and downloads on mobile data are off. */
    data object MobileData : LinkApp
    /** [message] is what the user is told; the details went to the log. */
    data class Failed(@StringRes val message: Int, val code: Int? = null) : LinkApp
}

/**
 * Asks before installing the Flathub app [appId] that a link named: its name (with a check when
 * Flathub verified the developer), ID, summary, size, the runtime it needs (installed already or
 * its size) and what it asks for, from Flathub. Install is offered only when it is built for
 * [arch], isn't installed ([installed]; null while the list of installed apps isn't known yet) and
 * says when it would wait for what runs ([busy]). The answer is fetched here, so the same dialog
 * can open for an app picked in a list later; not on a metered connection when [mobileDataBlocked]
 * says downloads there are off.
 */
@Composable
fun InstallLinkDialog(
    appId: String,
    arch: String,
    installed: Boolean?,
    busy: Boolean,
    mobileDataBlocked: () -> Boolean,
    /** Whether the runtime (a ref in Flathub's architecture) is installed already. Called off the main thread. */
    runtimeInstalled: (FlatpakRef) -> Boolean,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    val result by produceState<LinkApp>(LinkApp.Loading, appId, attempt) {
        value = LinkApp.Loading
        if (mobileDataBlocked()) {
            value = LinkApp.MobileData
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            try {
                val app = FlathubClient.fetch(appId)
                LinkApp.Found(app, app.runtimeRef?.let(runtimeInstalled) == true)
            } catch (_: FlathubNotFoundException) {
                LinkApp.NotFound
            } catch (e: Exception) {
                DebugLog.w("Asking Flathub about $appId failed: $e")
                when (e) {
                    is FlathubHttpException -> LinkApp.Failed(R.string.install_link_server_error, e.code)
                    is FlathubBadAnswerException -> LinkApp.Failed(R.string.install_link_bad_answer)
                    else -> LinkApp.Failed(R.string.install_link_failed)
                }
            }
        }
    }
    val found = (result as? LinkApp.Found)?.app
    // While a task or app runs, Install queues it (FlatpakTaskService.run).
    val canInstall = found != null && found.builtFor(arch) && installed == false
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.install_link_title, found?.name ?: appId), Modifier.weight(1f, fill = false))
                if (found?.verified == true) {
                    Icon(
                        Icons.Filled.Verified, contentDescription = stringResource(R.string.install_link_verified),
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp).size(24.dp),
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val r = result) {
                    LinkApp.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 3.dp)
                        Text(stringResource(R.string.install_link_loading))
                    }
                    LinkApp.NotFound -> Text(stringResource(R.string.install_link_not_found, appId))
                    LinkApp.MobileData -> Text(stringResource(R.string.install_link_mobile_data), color = MaterialTheme.colorScheme.error)
                    is LinkApp.Failed -> Text(
                        if (r.code != null) stringResource(r.message, r.code) else stringResource(r.message),
                        color = MaterialTheme.colorScheme.error,
                    )
                    is LinkApp.Found -> {
                        val app = r.app
                        Text(app.id, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        app.summary?.let { Text(it) }
                        app.installedSize?.let {
                            Text(stringResource(R.string.install_link_app_size, Formatter.formatShortFileSize(context, it)), style = MaterialTheme.typography.bodySmall)
                        }
                        if (app.runtime != null) {
                            // What the runtime takes, or that it is installed already (nothing more to download).
                            val state = if (r.runtimeInstalled) stringResource(R.string.install_link_runtime_installed)
                            else app.runtimeSize?.let { Formatter.formatShortFileSize(context, it) } ?: "?"
                            Text(stringResource(R.string.install_link_runtimes, app.runtime, state), style = MaterialTheme.typography.bodySmall)
                        }
                        val asks = app.permissions.map { stringResource(permissionName(it)) }
                        if (asks.isNotEmpty()) {
                            Text(
                                stringResource(R.string.install_link_asks, asks.joinToString(", ")),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        when {
                            !app.builtFor(arch) -> Text(
                                stringResource(R.string.install_link_no_arch, arch, app.arches.joinToString(", ").ifEmpty { "?" }),
                                color = MaterialTheme.colorScheme.error,
                            )
                            installed == true -> Text(stringResource(R.string.install_link_installed))
                            busy -> Text(stringResource(R.string.install_link_waits))
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (result) {
                is LinkApp.Failed, LinkApp.MobileData -> Button(onClick = { attempt++ }) { Text(stringResource(R.string.install_link_retry)) }
                else -> Button(onClick = onInstall, enabled = canInstall) { Text(stringResource(R.string.install_confirm)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reset_cancel)) } },
    )
}

private fun permissionName(permission: FlathubApp.Permission) = when (permission) {
    FlathubApp.Permission.NETWORK -> R.string.app_settings_network
    FlathubApp.Permission.SOUND -> R.string.app_settings_audio
    FlathubApp.Permission.ALL_DEVICES -> R.string.permission_all_devices
    FlathubApp.Permission.FILES -> R.string.permission_files
}
