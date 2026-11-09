package com.garathon.flatdroid.ui.home

import android.content.ClipData
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.session.SessionScript
import com.garathon.flatdroid.session.SessionState
import com.garathon.flatdroid.session.Viewer
import com.garathon.flatdroid.ui.IconAction
import kotlinx.coroutines.launch

/**
 * How to reach the running app from an external VNC viewer: its address, the username (for a
 * Wayland session) and the password, which is shown only on request and copied marked as sensitive.
 */
@Composable
fun ExternalViewerDialog(session: SessionState.Running, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var shown by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_external_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.session_address, Viewer.HOST, SessionScript.VNC_PORT))
                session.username?.let { Text(stringResource(R.string.session_username, it)) }
                // The password on a line of its own, bullets or not, so showing it moves nothing.
                Text(stringResource(R.string.session_password_label))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (shown) session.password else "••••••••",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconAction(if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, R.string.session_show_password, { shown = !shown })
                    IconAction(
                        Icons.Filled.ContentCopy, R.string.session_copy_password,
                        { scope.launch { clipboard.setClipEntry(ClipEntry(sensitiveText("VNC password", session.password))) } },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) } },
    )
}

/**
 * Clipboard text marked sensitive, so keyboards and Android's clipboard preview don't show or keep
 * it (Android 13+; older versions ignore the mark).
 */
private fun sensitiveText(label: String, text: String): ClipData = ClipData.newPlainText(label, text).apply {
    description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
}

/** `ClipDescription.EXTRA_IS_SENSITIVE`, which only exists in the API from Android 13. */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
