package com.garathon.flatdroid.launcher

/**
 * Desktop settings every app gets from a directory at the end of `XDG_CONFIG_DIRS`, so an app's
 * own settings (in its config directory) still win: no minimise button in title bars that apps
 * draw themselves (there is no taskbar to bring a window back), and Android's dark mode.
 */
object DesktopConfig {
    /** GTK 3 and 4 read it from `gtk-3.0/settings.ini` and `gtk-4.0/settings.ini`. */
    fun gtkSettings(dark: Boolean): String =
        "[Settings]\n" +
            "gtk-decoration-layout=menu:maximize,close\n" +
            "gtk-application-prefer-dark-theme=${if (dark) 1 else 0}\n"

    /**
     * KDE's colors when [dark]: the runtime's Breeze Dark scheme ([darkScheme], its
     * `share/color-schemes/BreezeDark.colors`) is what a desktop's kdeglobals holds for it. Null
     * without one, or in light mode (Breeze, KDE's default).
     */
    fun kdeglobals(dark: Boolean, darkScheme: String?): String? = darkScheme?.takeIf { dark && it.isNotBlank() }

    /** The runtime's Breeze Dark colors, relative to its files. */
    const val BREEZE_DARK = "share/color-schemes/BreezeDark.colors"

    /** KDE's Qt platform theme, which applies kdeglobals; relative to the runtime's files. */
    val KDE_PLATFORM_THEMES = listOf(
        "lib/plugins/platformthemes/KDEPlasmaPlatformTheme6.so",
        "lib/plugins/platformthemes/KDEPlasmaPlatformTheme.so",
    )
}
