package com.garathon.flatdroid.shortcuts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.garathon.flatdroid.MainActivity
import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.flatpak.tasks.FlatpakTasks
import com.garathon.flatdroid.session.SessionController
import com.garathon.flatdroid.session.SessionState
import com.garathon.flatdroid.setup.SetupController
import com.garathon.flatdroid.viewer.ViewerActivity

/**
 * A home-screen shortcut's tap ([Shortcuts]): Flatdroid's window starts the app as a tap in its
 * list does (the questions a launch may ask show there, over a cover that hides the window until
 * the viewer opens), or the app's viewer comes forward when it already runs. While
 * another app or a task runs it is refused with a message, and the user stays where they were.
 * Not exported: only the launcher, starting Flatdroid's own shortcuts, reaches it.
 */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getStringExtra(EXTRA_APP)?.takeIf(InstalledApps::isValidId)?.let(::open)
        finish()
    }

    private fun open(appId: String) {
        SetupController.refresh(this)
        // Setup (or a reset) isn't finished: the window shows where it is, and nothing starts.
        if (!SetupController.state.value.isComplete) return startActivity(MainActivity.windowIntent(this))
        val decision = ShortcutLaunch.decide(appId, SessionController.ui.value.state, busy = FlatpakTasks.ui.value.running != null)
        val refusal = refusal(this, decision)
        val running = SessionController.ui.value.state as? SessionState.Running
        when {
            refusal != null -> Toast.makeText(applicationContext, refusal, Toast.LENGTH_LONG).show()
            // The app is up in Flatdroid's own viewer: straight to it.
            running?.appId == appId && running.socket != null -> startActivity(
                Intent(this, ViewerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
            // Nothing to ask first: the cover alone, then the viewer.
            SessionController.ui.value.state == SessionState.Idle && QuietLaunch.possible(this, appId) -> startActivity(
                LaunchActivity.intent(this, appId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            else -> startActivity(MainActivity.launchIntent(this, appId))
        }
    }

    companion object {
        const val EXTRA_APP = "com.garathon.flatdroid.APP"
        /** A shortcut's intent needs an action. */
        private const val ACTION = "com.garathon.flatdroid.OPEN_APP"

        fun intent(context: Context, appId: String): Intent =
            Intent(ACTION).setClass(context, ShortcutActivity::class.java).putExtra(EXTRA_APP, appId)

        /** Why [decision] doesn't start anything, for the user; null when it opens Flatdroid. */
        fun refusal(context: Context, decision: ShortcutLaunch.Decision): String? = when (decision) {
            ShortcutLaunch.Decision.Open -> null
            ShortcutLaunch.Decision.Busy -> context.getString(R.string.shortcut_busy)
            is ShortcutLaunch.Decision.AppBusy -> {
                val name = InstalledApps(context).name(decision.appId) ?: decision.appId
                context.getString(if (decision.asking) R.string.shortcut_app_asking else R.string.shortcut_app_running, name)
            }
        }
    }
}
