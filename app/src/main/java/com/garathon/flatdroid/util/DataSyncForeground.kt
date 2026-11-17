package com.garathon.flatdroid.util

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import com.garathon.flatdroid.util.debug.DebugLog

/**
 * Makes this service a foreground service of the data-sync type, showing [notification] as [id].
 * False when Android refuses it: from Android 15 the daily limit of this kind of service can be used
 * up (not tried on such a phone (verify)), and the caller doesn't go on.
 */
fun Service.startDataSyncForeground(id: Int, notification: Notification): Boolean {
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
    return try {
        ServiceCompat.startForeground(this, id, notification, type)
        true
    } catch (e: IllegalStateException) {
        DebugLog.w("Foreground start refused: $e")
        false
    }
}
