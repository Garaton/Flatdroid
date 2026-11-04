package com.garathon.flatdroid.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.garathon.flatdroid.settings.LiveSettings
import kotlinx.coroutines.launch

/**
 * While the Hide Flatdroid's screen setting is on ([LiveSettings.hideScreen]), the window can't
 * be captured: screenshots and screen recordings get nothing, and Recents shows it blank.
 * Follows the setting as soon as it is saved.
 */
fun ComponentActivity.followHideScreen() {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.CREATED) {
            LiveSettings.hideScreen.collect { hide ->
                if (hide) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}
