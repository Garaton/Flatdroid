package com.garathon.flatdroid.shortcuts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.garathon.flatdroid.alpine.AlpineCommands
import com.garathon.flatdroid.audio.MicStreamer
import com.garathon.flatdroid.camera.AndroidCameras
import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.session.Sensor
import com.garathon.flatdroid.session.SessionService
import com.garathon.flatdroid.settings.HomeMode
import com.garathon.flatdroid.storage.AppData
import com.garathon.flatdroid.storage.SharedHome
import com.garathon.flatdroid.storage.TempHomePrompt
import com.garathon.flatdroid.storage.tasks.StorageTaskService
import com.garathon.flatdroid.storage.tasks.StorageTasks

/**
 * Whether an app can start with nothing to ask first, so a shortcut can start it from its cover
 * ([LaunchActivity]) without Flatdroid's window. Errs towards the window: anything a launch from
 * the window would stop for (the notification permission, a storage task or one left unfinished,
 * the temporary home's question, the storage permission, the shared home's warning, the
 * microphone and camera questions, a terminal command) sends the start there instead.
 */
object QuietLaunch {
    fun possible(context: Context, appId: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (AlpineCommands.terminalRunning || StorageTasks.busy) return false
        if (StorageTaskService.pendingTasks(context).all().isNotEmpty()) return false
        if (TempHomePrompt.needsAsking(context) != null) return false
        val settings = AppData.settings(context, appId).load()
        val shared = settings.homeMode == HomeMode.SHARED
        if ((shared || settings.homeMode == HomeMode.ANDROID || settings.sdcard) && SharedHome.needsLegacyPermission(context)) return false
        if (shared && SharedHome.needsWarning(context)) return false
        return launchQuestions(context, appId).isEmpty()
    }

    /**
     * The app's microphone or camera is on but will ask, and Flatdroid can't post the question when
     * the app uses it (notifications are off): asked before the launch instead, for the session.
     */
    fun launchQuestions(context: Context, appId: String): List<Sensor> {
        if (SessionService.canAskInNotification(context)) return emptyList()
        val settings = AppData.settings(context, appId).load()
        val apps = InstalledApps(context)
        return buildList {
            if (settings.microphoneAllowed(apps.sockets(appId)) && !(settings.alwaysAllowMicrophone && MicStreamer.hasPermission(context))) {
                add(Sensor.MICROPHONE)
            }
            val camera = apps.context(appId)?.let(settings::cameraAllowed) == true && AndroidCameras.present(context)
            if (camera && !(settings.alwaysAllowCamera && AndroidCameras.hasPermission(context))) add(Sensor.CAMERA)
        }
    }
}
