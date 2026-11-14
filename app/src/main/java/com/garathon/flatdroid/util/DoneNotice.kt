package com.garathon.flatdroid.util

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.garathon.flatdroid.MainActivity
import com.garathon.flatdroid.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * A notification that something Flatdroid did in the background has finished (setup, a Flatpak
 * task or the queue): on its own channel, which pops up on the screen, unlike the quiet progress
 * ones. Not posted
 * while one of Flatdroid's screens is in front, where the bottom bar or setup screen says it already.
 */
object DoneNotice {
    /** High importance: it pops up on the screen. (A channel's importance can't be raised, so not "done", the quieter one before.) */
    private const val CHANNEL_ID = "finished"
    private const val OLD_CHANNEL_ID = "done"
    /** The notification of setup's end (it replaces the earlier quiet one of the same ID). */
    const val SETUP_ID = 2
    /** The notification of the Flatpak tasks that ran until nothing was left. */
    const val TASKS_ID = 21
    private val started = AtomicInteger(0)

    /** Counts Flatdroid's started screens; registered once by the application. */
    fun track(application: Application) = application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) { started.incrementAndGet() }
        override fun onActivityStopped(activity: Activity) { started.updateAndGet { maxOf(0, it - 1) } }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    })

    /** Whether one of Flatdroid's screens is showing. */
    val inFront: Boolean get() = started.get() > 0

    /** Posts [title] (and [text]) as notification [id], replacing an earlier one of that id; tapping it opens Flatdroid. */
    fun post(context: Context, id: Int, title: String, text: String?, failed: Boolean) {
        if (inFront) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.deleteNotificationChannel(OLD_CHANNEL_ID)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.done_channel), NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (failed) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .apply { if (!text.isNullOrEmpty()) setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)) }
            .setContentIntent(open)
            .setAutoCancel(true)
            // Before Android 8, the notification's own priority makes it pop up.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // Notifications not allowed: the bottom bar says it when Flatdroid is opened.
        }
    }

    /** Takes notification [id] away (Flatdroid came to the front, where the result shows anyway). */
    fun cancel(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java)?.cancel(id)
    }
}
