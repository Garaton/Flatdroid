package com.garathon.flatdroid.session.bus

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.garathon.flatdroid.settings.FlatpakTheme
import com.garathon.flatdroid.settings.UserSettings

/** The [Appearance] Android and Flatdroid's settings ask for now. */
object AndroidAppearance {
    /** Android's contrast levels are 0 (standard), 0.5 (medium) and 1 (high); only high counts as high. */
    private const val HIGH_CONTRAST = 0.75f

    fun of(context: Context, user: UserSettings?, configuration: Configuration = context.resources.configuration): Appearance =
        Appearance(
            dark = (user?.flatpakTheme ?: FlatpakTheme.SYSTEM).isDark(androidDark(configuration)),
            accent = accent(context),
            highContrast = highContrast(context),
            reducedMotion = user?.reduceMotion == true,
        )

    fun androidDark(configuration: Configuration): Boolean =
        configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Android 12 and later: the system's accent, from the wallpaper or the user's pick. */
    private fun accent(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching { context.getColor(android.R.color.system_accent1_500) and 0xFFFFFF }.getOrNull()
    }

    /** Android 14 and later: the contrast setting. */
    private fun highContrast(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val modes = context.getSystemService(UiModeManager::class.java) ?: return false
        return modes.contrast >= HIGH_CONTRAST
    }
}
