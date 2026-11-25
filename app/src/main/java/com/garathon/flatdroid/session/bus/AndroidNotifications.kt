package com.garathon.flatdroid.session.bus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.ui.home.appIconBitmap
import com.garathon.flatdroid.util.debug.DebugLog
import com.garathon.flatdroid.viewer.ViewerActivity
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * One session's notifications ([Notifications]) shown as Android's: Flatdroid's small icon, the
 * picture the app sent or else the app's icon, the app's name as the sub-text. A tap opens the
 * viewer and sends the default action ([open]), a button its action, a swipe tells the app it
 * was dismissed ([NotificationActionReceiver]); [onEvent] hears them. Low urgency goes to a
 * silent channel. Nothing shows without Android's notification permission.
 */
class AndroidNotifications(
    private val context: Context,
    private val appId: String,
    /** The host path behind a path in the app's root, or null. */
    private val hostPath: (String) -> String?,
    private val onEvent: (key: Int, action: String?, dismissed: Boolean) -> Unit,
) : Notifications.Shower {
    private val manager = NotificationManagerCompat.from(context)
    private val session = SESSIONS.incrementAndGet()
    private val app by lazy { runCatching { InstalledApps(context).brief(appId) }.getOrNull() }
    private val appIcon: Bitmap? by lazy { app?.icon?.let { runCatching { appIconBitmap(it, context.cacheDir, LARGE_ICON_PX) }.getOrNull() } }

    init {
        current = this
    }

    override fun show(notification: Notifications.AppNotification) {
        if (!manager.areNotificationsEnabled()) {
            DebugLog.i("Notifications: Android's permission is off; not shown")
            return
        }
        createChannels(context)
        val key = notification.key
        val builder = NotificationCompat.Builder(context, if (notification.quiet) QUIET_CHANNEL else CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(notification.title.ifEmpty { app?.name ?: appId })
            .setContentText(notification.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
            .setSubText(app?.name)
            .setGroup("app:$appId")
            .setAutoCancel(true)
            .setContentIntent(activity(key))
            .setDeleteIntent(broadcast(key, 0, null, dismissed = true))
        (picture(notification.image) ?: appIcon)?.let(builder::setLargeIcon)
        notification.timeoutMillis?.let(builder::setTimeoutAfter)
        for ((i, button) in notification.buttons.withIndex()) builder.addAction(0, button.label, broadcast(key, i + 1, button.action, dismissed = false))
        runCatching { manager.notify(TAG, key, builder.build()) }.onFailure { DebugLog.i { "Notifications: not shown: $it" } }
    }

    override fun remove(key: Int) {
        manager.cancel(TAG, key)
    }

    /** This session's notifications no longer reach it. */
    fun close() {
        if (current === this) current = null
    }

    /** The viewer, carrying [key] so it sends the default action when it opens. */
    private fun activity(key: Int): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, ViewerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .setData(uri(key, 0)).putExtra(EXTRA_SESSION, session).putExtra(EXTRA_KEY, key),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcast(key: Int, index: Int, action: String?, dismissed: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, NotificationActionReceiver::class.java).setData(uri(key, if (dismissed) DISMISS_INDEX else index))
            .putExtra(EXTRA_SESSION, session).putExtra(EXTRA_KEY, key).putExtra(EXTRA_ACTION, action).putExtra(EXTRA_DISMISSED, dismissed),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Each intent its own address, so Android keeps one pending intent per notification and button. */
    private fun uri(key: Int, index: Int) = Uri.parse("flatdroid-notification://$session/$key/$index")

    /** The app's picture, decoded no larger than [LARGE_ICON_PX] (it comes from the app). */
    private fun picture(image: Notifications.Image?): Bitmap? = runCatching {
        when (image) {
            null -> null
            is Notifications.Image.Pixels -> pixels(image)
            is Notifications.Image.Encoded -> decode(BitmapFactory.Options()) { BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, it) }
            is Notifications.Image.Path -> hostPath(image.guestPath)?.let(::File)?.takeIf { it.isFile && it.length() <= MAX_FILE_BYTES }?.let { file ->
                decode(BitmapFactory.Options()) { BitmapFactory.decodeFile(file.path, it) }
            }
        }
    }.getOrNull()

    private fun decode(options: BitmapFactory.Options, read: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        options.inJustDecodeBounds = true
        read(options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / (sample * 2) >= LARGE_ICON_PX) sample *= 2
        return read(BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun pixels(p: Notifications.Image.Pixels): Bitmap {
        val colors = IntArray(p.width * p.height)
        for (y in 0 until p.height) {
            for (x in 0 until p.width) {
                val i = y * p.rowStride + x * p.channels
                val r = p.data[i].toInt() and 0xff
                val g = p.data[i + 1].toInt() and 0xff
                val b = p.data[i + 2].toInt() and 0xff
                val a = if (p.alpha) p.data[i + 3].toInt() and 0xff else 0xff
                colors[y * p.width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(colors, p.width, p.height, Bitmap.Config.ARGB_8888)
    }

    companion object {
        private const val TAG = "app-notification"
        private const val CHANNEL = "app_notifications"
        private const val QUIET_CHANNEL = "app_notifications_quiet"
        private const val EXTRA_SESSION = "com.garathon.flatdroid.notification.SESSION"
        private const val EXTRA_KEY = "com.garathon.flatdroid.notification.KEY"
        private const val EXTRA_ACTION = "com.garathon.flatdroid.notification.ACTION"
        private const val EXTRA_DISMISSED = "com.garathon.flatdroid.notification.DISMISSED"
        private const val DISMISS_INDEX = 9
        private const val LARGE_ICON_PX = 256
        private const val MAX_FILE_BYTES = 8L shl 20
        private val SESSIONS = AtomicInteger()

        /** The running session's; one app runs at a time. */
        @Volatile private var current: AndroidNotifications? = null

        /** A tap, button or dismissal from [intent], for the session that showed it; false when it isn't one. */
        fun deliver(intent: Intent?): Boolean {
            val extras = intent?.extras ?: return false
            if (!extras.containsKey(EXTRA_KEY)) return false
            val target = current?.takeIf { it.session == extras.getInt(EXTRA_SESSION) } ?: return true
            target.onEvent(extras.getInt(EXTRA_KEY), extras.getString(EXTRA_ACTION), extras.getBoolean(EXTRA_DISMISSED))
            return true
        }

        private fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.app_notifications_channel), NotificationManager.IMPORTANCE_DEFAULT))
            }
            if (manager.getNotificationChannel(QUIET_CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(QUIET_CHANNEL, context.getString(R.string.app_notifications_quiet_channel), NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}

/** A notification's button or dismissal ([AndroidNotifications]); not exported, only Flatdroid's pending intents reach it. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AndroidNotifications.deliver(intent)
    }
}
