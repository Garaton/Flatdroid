package com.garathon.flatdroid.flatpak

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Whether Android opens Flathub install links in Flatdroid. From Android 12 a link whose domain the
 * app can't verify opens in the browser until the user allows it for the app (App info, Open by
 * default); an app can't allow itself, only read the answer. Before Android 12 the system asks
 * each time, so there is nothing to allow.
 */
object FlathubLinkApproval {
    private val HOSTS = setOf("flathub.org", "www.flathub.org")

    /**
     * Opens Android's Open by default screen for Flatdroid, where the user allows the links (Android
     * 12 and later). In its own task: left in Flatdroid's, it could end up under Flatdroid's window,
     * and Back from the home screen would go to it instead of leaving.
     */
    fun openSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** True when Flathub links open Flatdroid, false when they don't yet, null before Android 12 (or when Android can't say). */
    fun allowed(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val manager = context.getSystemService(DomainVerificationManager::class.java) ?: return null
        val state = runCatching { manager.getDomainVerificationUserState(context.packageName) }.getOrNull() ?: return null
        if (!state.isLinkHandlingAllowed) return false
        return state.hostToStateMap.any { (host, value) ->
            host in HOSTS && (value == DomainVerificationUserState.DOMAIN_STATE_SELECTED || value == DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
        }
    }
}
