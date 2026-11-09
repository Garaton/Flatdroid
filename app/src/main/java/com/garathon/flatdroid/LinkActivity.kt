package com.garathon.flatdroid

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.garathon.flatdroid.flatpak.FlathubLink

/**
 * Receives Flathub app links (Flathub's install page, or appstream://) and hands the app ID to
 * Flatdroid's window, which asks before installing it. It has no screen of its own.
 *
 * The window is brought forward as it is, not started again: [MainActivity] keeps the standard
 * launch mode, so Flatdroid's icon still returns to whatever was open (the viewer of a running
 * app). The intent looks like the launcher's, so a task a link started is the same task the icon
 * opens later.
 */
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.let(FlathubLink::appId)
        if (id != null) startActivity(MainActivity.installIntent(this, id))
        finish()
    }
}
