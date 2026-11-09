package com.garathon.flatdroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.garathon.flatdroid.flatpak.FlathubLinkApproval

/**
 * Whether Android opens Flathub links in Flatdroid ([FlathubLinkApproval.allowed]), read again
 * whenever Flatdroid comes back, since the setting is on Android's own screen.
 */
@Composable
fun rememberFlathubLinksAllowed(): Boolean? {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(FlathubLinkApproval.allowed(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) allowed = FlathubLinkApproval.allowed(context) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return allowed
}
