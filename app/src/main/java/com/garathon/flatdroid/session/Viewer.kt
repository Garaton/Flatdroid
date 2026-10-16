package com.garathon.flatdroid.session

import android.content.Intent
import android.net.Uri

/** Opening an external VNC viewer on the session's display. */
object Viewer {
    const val HOST = "127.0.0.1"

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

    fun intent(password: String): Intent =
        Intent(Intent.ACTION_VIEW, uri(password)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
