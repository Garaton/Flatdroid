package com.garathon.flatdroid.session

import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.net.toUri
import com.garathon.flatdroid.R

/** Opening an external VNC viewer on the session's display. */
object Viewer {
    const val HOST = "127.0.0.1"

    /** An installed app that opens `vnc:` links. */
    data class App(val packageName: String, val label: String)

    /**
     * A `vnc://` link with the password as the `VncPassword` parameter (RFC 7869 style), and the
     * [username] as `VncUsername` when there is one, which AVNC and bVNC are expected to read
     * (verify per viewer). Both are shown in the app too, in case a viewer ignores them.
     */
    fun uri(password: String, username: String? = null): Uri = Uri.Builder()
        .scheme("vnc")
        .encodedAuthority("$HOST:${SessionScript.VNC_PORT}")
        .apply { if (username != null) appendQueryParameter("VncUsername", username) }
        .appendQueryParameter("VncPassword", password)
        .build()

    /** Installed viewers (apps that open `vnc:` links), by label. */
    fun installed(context: Context): List<App> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_VIEW, "vnc://$HOST".toUri())
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION") // the only way before Android 13
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return activities.map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Opens the display in [chosen] (a package from Settings), never through Android's chooser or
     * a default app: the link carries the password, so it goes only to the app the user picked.
     * Null when none is picked or it isn't installed any more.
     */
    fun intent(context: Context, password: String, chosen: String?, username: String? = null): Intent? {
        val target = chosen?.takeIf { pkg -> installed(context).any { it.packageName == pkg } } ?: return null
        return Intent(Intent.ACTION_VIEW, uri(password, username)).setPackage(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
