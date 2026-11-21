package com.garathon.flatdroid.shortcuts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.garathon.flatdroid.MainActivity
import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.session.SessionController
import com.garathon.flatdroid.session.SessionService
import com.garathon.flatdroid.session.SessionState
import com.garathon.flatdroid.ui.home.LaunchCover
import com.garathon.flatdroid.ui.theme.FlatdroidTheme
import com.garathon.flatdroid.viewer.ViewerActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A shortcut's app on its way, when nothing has to be asked first ([QuietLaunch]): this screen is
 * all that shows from the tap until the app's viewer, without Flatdroid's window (and whatever it
 * last showed) in between. It starts the app and opens the viewer once the app is up. A question
 * the start runs into (Electron, changed files), a failed start, or an external viewer go to
 * Flatdroid's window, which handles them as for a start from its list.
 */
class LaunchActivity : ComponentActivity() {
    private lateinit var appId: String
    /** The session was seen on this app: going idle afterwards means the start ended. */
    private var seenBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appId = intent?.getStringExtra(ShortcutActivity.EXTRA_APP)?.takeIf(InstalledApps::isValidId) ?: return finish()
        // Started once, not again after a rotation recreates the screen.
        if (savedInstanceState == null && SessionController.ui.value.state == SessionState.Idle) SessionService.launch(this, appId)
        // Read before the first frame (its desktop file and icon path), so the cover shows the name from the start.
        val app = InstalledApps(this).brief(appId)
        enableEdgeToEdge()
        setContent {
            FlatdroidTheme {
                val ui by SessionController.ui.collectAsState()
                LaunchCover(
                    appId = appId,
                    app = app,
                    state = ui.state,
                    onSkipCheck = { SessionController.skipFileCheck() },
                    onStop = { SessionService.stop(this); finish() },
                    // The app goes on starting; its notification opens it.
                    onBack = ::finish,
                )
            }
        }
        // Only while shown: Android doesn't let a screen in the background open another.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SessionController.ui.collect { follow(it.state) }
            }
        }
        // A start that never got going (the service refused it): Flatdroid's window says why.
        lifecycleScope.launch {
            delay(NO_START_MS)
            if (!seenBusy && SessionController.ui.value.state == SessionState.Idle) handOver()
        }
    }

    private fun follow(state: SessionState) {
        val ours = state.appIdOrNull == appId
        when {
            ours && state is SessionState.Running -> {
                if (state.socket != null) startActivity(Intent(this, ViewerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                else startActivity(MainActivity.launchIntent(this, appId))
                finish()
            }
            ours && state is SessionState.Question -> handOver()
            ours -> seenBusy = true
            // Ended without a window (its message is on Flatdroid's), or another app took the session.
            seenBusy || state != SessionState.Idle -> handOver()
        }
    }

    private fun handOver() {
        startActivity(MainActivity.windowIntent(this))
        finish()
    }

    companion object {
        private const val NO_START_MS = 5_000L

        fun intent(context: Context, appId: String): Intent =
            Intent(context, LaunchActivity::class.java).putExtra(ShortcutActivity.EXTRA_APP, appId)
    }
}
