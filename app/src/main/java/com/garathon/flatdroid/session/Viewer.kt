package com.garathon.flatdroid.session

import android.content.Context
import android.content.Intent
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
     * A `vnc://` link with the password as the `VncPassword` parameter (RFC 7869 style), which
     * AVNC and bVNC are expected to read (verify per viewer). The password is shown in the app too,
     * in case a viewer ignores it.
     */
    fun uri(password: String): Uri = Uri.Builder()
        .scheme("vnc")
        .encodedAuthority("$HOST:${SessionScript.VNC_PORT}")
        .appendQueryParameter("VncPassword", password)
        .build()

    /** Installed viewers (apps that open `vnc:` links), by label. */
    fun installed(context: Context): List<App> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val activities = pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, "vnc://$HOST".toUri()), PackageManager.MATCH_DEFAULT_ONLY)
        return activities.map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Opens the display in [chosen] (a package from Settings) if it is still installed, else
     * through a chooser every time: the link carries the password, so it must not go to whichever
     * app made itself the default for `vnc:` links. Null when no viewer is installed, or, without
     * [allowChooser] (the password protection setting), when the chosen one isn't.
     */
    fun intent(context: Context, password: String, chosen: String?, allowChooser: Boolean = true): Intent? {
        val viewers = installed(context)
        if (viewers.isEmpty()) return null
        val view = Intent(Intent.ACTION_VIEW, uri(password))
        val target = chosen?.takeIf { pkg -> viewers.any { it.packageName == pkg } }
        if (target == null && !allowChooser) return null
        val intent = if (target != null) view.setPackage(target) else Intent.createChooser(view, context.getString(R.string.session_open_viewer))
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
