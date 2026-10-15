package com.garathon.flatdroid.util

import android.util.Log
import com.garathon.flatdroid.BuildConfig

/** Logcat output under the tag "Flatdroid", in debug builds only (`adb logcat -s Flatdroid`). */
object DebugLog {
    private const val TAG = "Flatdroid"

    fun i(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    fun w(message: String) {
        if (BuildConfig.DEBUG) Log.w(TAG, message)
    }
}
