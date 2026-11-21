package com.garathon.flatdroid.shortcuts

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.PersistableBundle
import androidx.annotation.RequiresApi
import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.InstalledApp
import com.garathon.flatdroid.ui.home.appIconBitmap
import com.garathon.flatdroid.util.debug.DebugLog

/**
 * Home-screen shortcuts to installed apps (pinned shortcuts, Android 8 and later). Each is named by
 * its app's ID and opens [ShortcutActivity]. Android lets an app disable its pinned shortcuts but
 * not remove them: one whose app is gone is disabled, and enabled again when the app is back.
 */
object Shortcuts {
    /** The icon's file and its time, so an update that changed the icon redraws it. */
    private const val ICON_KEY = "icon"
    /** The icon's size in pixels when Android doesn't say, and the most it is decoded at. */
    private const val DEFAULT_ICON_PX = 192
    private const val MAX_ICON_PX = 512

    /** Whether the launcher takes pinned shortcuts. */
    fun supported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context.getSystemService(ShortcutManager::class.java)?.isRequestPinShortcutSupported == true

    /** Asks the launcher to add [app]'s shortcut (it confirms with the user); false when it can't. Off the main thread. */
    fun pin(context: Context, app: InstalledApp): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            context.getSystemService(ShortcutManager::class.java)?.requestPinShortcut(info(context, app), null) == true
        } catch (e: Exception) {
            DebugLog.w("Shortcut for ${app.id} refused: $e")
            false
        }
    }

    /**
     * Follows the installed apps: shortcuts to apps that are gone are disabled, the others enabled,
     * and redrawn when their name or icon changed. Off the main thread.
     */
    fun sync(context: Context, installed: List<InstalledApp>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return
            val pinned = manager.pinnedShortcuts
            if (pinned.isEmpty()) return
            val apps = installed.associateBy { it.id }
            val gone = pinned.filter { it.isEnabled && it.id !in apps }.map { it.id }
            if (gone.isNotEmpty()) manager.disableShortcuts(gone, context.getString(R.string.shortcut_not_installed))
            val back = pinned.filter { !it.isEnabled && it.id in apps }.map { it.id }
            if (back.isNotEmpty()) manager.enableShortcuts(back)
            val changed = pinned.mapNotNull { shortcut ->
                val app = apps[shortcut.id] ?: return@mapNotNull null
                app.takeIf { shortcut.shortLabel?.toString() != it.name || shortcut.extras?.getString(ICON_KEY) != iconStamp(it) }
            }
            if (changed.isNotEmpty()) manager.updateShortcuts(changed.map { info(context, it) })
        } catch (e: Exception) {
            DebugLog.w("Shortcuts not updated: $e")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun info(context: Context, app: InstalledApp): ShortcutInfo = ShortcutInfo.Builder(context, app.id)
        .setShortLabel(app.name)
        .setLongLabel(app.name)
        .setIcon(icon(context, app))
        .setIntent(ShortcutActivity.intent(context, app.id))
        .setExtras(PersistableBundle().apply { putString(ICON_KEY, iconStamp(app)) })
        .build()

    private fun iconStamp(app: InstalledApp): String = app.icon?.let { "${it.path}|${it.lastModified()}" }.orEmpty()

    /**
     * The app's icon as a plain picture, not an adaptive icon: Android draws an adaptive icon's
     * transparent background black, while a plain picture is the launcher's to show as it is or
     * on a shape of its own. Flatdroid's icon when the app has none.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun icon(context: Context, app: InstalledApp): Icon {
        val size = context.getSystemService(ShortcutManager::class.java)?.iconMaxWidth?.takeIf { it > 0 } ?: DEFAULT_ICON_PX
        val picture = app.icon?.let { appIconBitmap(it, context.cacheDir, minOf(size, MAX_ICON_PX)) }
        return if (picture != null) Icon.createWithBitmap(picture) else Icon.createWithResource(context, R.mipmap.ic_launcher)
    }
}
