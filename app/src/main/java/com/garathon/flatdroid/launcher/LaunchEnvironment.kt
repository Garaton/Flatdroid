package com.garathon.flatdroid.launcher

import com.garathon.flatdroid.proot.GuestPaths
import com.garathon.flatdroid.util.ENV_NAME

/**
 * The environment every Flatpak starts with. One set serves all apps: the toolkit variables are
 * harmless to apps that don't use them. Order: defaults, then the metadata `[Environment]`,
 * then LD_LIBRARY_PATH and the GL variables, then [overrides].
 */
object LaunchEnvironment {
    const val DISPLAY = ":1"
    /** Xwayland's display in a Wayland session ([build]'s xwayland): the first, the helpers' X socket folder starts empty. */
    const val XWAYLAND_DISPLAY = ":0"

    /**
     * [triplet] is e.g. `aarch64-linux-gnu`. [glDefault] is where GL.default is bound, under /usr
     * (e.g. `lib/aarch64-linux-gnu/GL/default`), or null. [extensionLdPaths] are the library
     * directories mounted extensions add, as absolute guest paths.
     */
    fun build(
        appId: String,
        triplet: String,
        metadataEnvironment: Map<String, String>,
        glDefault: String?,
        extensionLdPaths: List<String> = emptyList(),
        overrides: Map<String, String> = emptyMap(),
        /** The runtime has KDE's Qt platform theme, which applies Flatdroid's kdeglobals. */
        kdePlatformTheme: Boolean = false,
        /** The app's session bus is bound at [GuestPaths.SESSION_BUS_DIR]. */
        sessionBus: Boolean = false,
        /** The locale ([locale]); the generic C.UTF-8 when the device's language isn't shared. */
        lang: String = GENERIC_LOCALE,
        /** XDG_RUNTIME_DIR, the app's own `/run/user/<uid>` as in Flatpak. */
        runtimeDir: String = "/tmp",
        /** The root looks like Flatpak's (/.flatpak-info), so `container` says so too, as Flatpak sets it. */
        flatpakContainer: Boolean = false,
        /** The session is Wayland: the toolkits are pointed at it instead of X11. */
        wayland: Boolean = false,
        /**
         * The Wayland session also offers X11 through Xwayland: DISPLAY for programs that take only
         * X11. The toolkits stay on Wayland.
         */
        xwayland: Boolean = false,
        /** GTK 4 and Qt Quick draw on the CPU instead of through GL ([Rendering]). */
        softwareRendering: Boolean = true,
        /** Whether the runtime has a folder at a `/usr` path ([multiarchPaths]). */
        usrHas: (String) -> Boolean = { false },
        /** A terminal app's TERM; null for an app that isn't one. Without Wayland it has no display at all. */
        terminal: String? = null,
        /** An Alpine program ([alpineAdjusted]). */
        alpine: Boolean = false,
    ): LinkedHashMap<String, String> {
        val varApp = "${GuestPaths.HOME}/.var/app/$appId"
        val env = linkedMapOf(
            "PATH" to "/app/bin:/usr/bin",
            "HOME" to GuestPaths.HOME,
            "LANG" to lang,
            // Terminal apps start it; Flatpak passes the host's.
            "SHELL" to "/bin/sh",
            "XDG_RUNTIME_DIR" to runtimeDir,
            "FLATPAK_ID" to appId,
            "XDG_CONFIG_HOME" to "$varApp/config",
            "XDG_DATA_HOME" to "$varApp/data",
            "XDG_CACHE_HOME" to "$varApp/cache",
            "XDG_STATE_HOME" to "$varApp/.local/state",
            // GTK4 apps abort without their GSettings schemas.
            "XDG_DATA_DIRS" to "/app/share:/usr/share:/usr/share/runtime/share",
            // Flatpak's, then Flatdroid's desktop settings last, so the runtime's and the app's win.
            "XDG_CONFIG_DIRS" to "/app/etc/xdg:/etc/xdg:${GuestPaths.XDG_CONFIG_DIR}",
            "DISPLAY" to DISPLAY,
            "PULSE_SERVER" to "unix:${GuestPaths.PULSE_DIR}/native",
            "GTK_USE_PORTAL" to "0",
            // GTK4 uses portals because of /.flatpak-info, and can't save without them otherwise.
            "GDK_DEBUG" to "no-portals",
            "GDK_BACKEND" to "x11",
            // GTK4's GL renderer runs in software (Mesa) and keeps a camera preview smooth, where
            // cairo lets frames pile up; it needs nothing from Xvnc (no GLX). Cairo needs less
            // memory and processor time for everything else (see softwareRendering below).
            "GSK_RENDERER" to "gl",
            // New glycin; old glycin is turned off by /.flatpak-info.
            "GLYCIN_DISABLE_SANDBOX" to "i-know-the-risks",
            "WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS" to "1",
            "WEBKIT_DISABLE_COMPOSITING_MODE" to "1",
            "QT_QPA_PLATFORM" to "xcb",
            "QT_X11_NO_MITSHM" to "1",
            "QT_XCB_GL_INTEGRATION" to "xcb_egl",
            "SDL_VIDEODRIVER" to "x11",
            "SDL_AUDIODRIVER" to "pulseaudio",
            "SDL_VIDEO_X11_FORCE_EGL" to "1",
            // Flutter's GTK embedder draws through a GTK3 GL context, which needs GLX (Xvnc has
            // none): without this its window stays black. Only Flutter reads it.
            "FLUTTER_LINUX_RENDERER" to "software",
            "ALSOFT_DRIVERS" to "pulse",
        )
        if (wayland) {
            env.remove("DISPLAY")
            for (key in X11_ONLY) env.remove(key)
            env["WAYLAND_DISPLAY"] = "${GuestPaths.WAYLAND_DIR}/${GuestPaths.WAYLAND_SOCKET}"
            env["XDG_SESSION_TYPE"] = "wayland"
            env["GDK_BACKEND"] = "wayland"
            env["QT_QPA_PLATFORM"] = "wayland"
            env["SDL_VIDEODRIVER"] = "wayland"
            if (xwayland) {
                env["DISPLAY"] = XWAYLAND_DISPLAY
                // Xwayland runs among the helpers, in another proot: no MIT-SHM, as with Xvnc.
                env["QT_X11_NO_MITSHM"] = "1"
                // The compositor's X window manager doesn't reparent windows. Java's AWT assumes one that does
                // when it doesn't know the manager by name, and then never resizes its content to the window's size.
                env["_JAVA_AWT_WM_NONREPARENTING"] = "1"
            }
        }
        if (terminal != null) {
            env["TERM"] = terminal
            env["COLORTERM"] = "truecolor"
            if (!wayland) {
                env.remove("DISPLAY")
                for (key in X11_ONLY) env.remove(key)
            }
        }
        // Qt Quick has the same choice: its software backend leaves out shader effects, Qt Quick 3D
        // and GL items, and costs a fraction of the processor time of Mesa's.
        if (softwareRendering) {
            env["GSK_RENDERER"] = "cairo"
            env["QT_QUICK_BACKEND"] = "software"
        }
        if (flatpakContainer) env["container"] = "flatpak"
        if (kdePlatformTheme) env["QT_QPA_PLATFORMTHEME"] = "kde"
        if (sessionBus) env["DBUS_SESSION_BUS_ADDRESS"] = "unix:path=${GuestPaths.SESSION_BUS_DIR}/${GuestPaths.SESSION_BUS_SOCKET}"
        for ((key, value) in metadataEnvironment) {
            if (key != "LD_LIBRARY_PATH" && isName(key)) env[key] = if (key in PLUGIN_PATHS) multiarchPaths(value, triplet, usrHas) else value
        }
        val gl = glDefault?.let { "/usr/${it.trim('/')}" }
        val ldPath = listOfNotNull(metadataEnvironment["LD_LIBRARY_PATH"]?.takeIf { it.isNotEmpty() }) +
            listOf("/app/lib", "/usr/lib/$triplet") + extensionLdPaths
        env["LD_LIBRARY_PATH"] = ldPath.distinct().joinToString(":")
        if (gl != null) {
            env["LIBGL_ALWAYS_SOFTWARE"] = "1"
            env["LIBGL_DRIVERS_PATH"] = "$gl/lib/dri"
            env["__EGL_VENDOR_LIBRARY_DIRS"] = "$gl/share/glvnd/egl_vendor.d"
        }
        if (alpine) alpineAdjusted(env)
        for ((key, value) in overrides) if (isName(key)) env[key] = value
        return env
    }

    /**
     * An Alpine program's differences: Alpine's PATH (busybox is in /bin), no FLATPAK_ID (it isn't
     * one), Mesa in software as a Flatpak's GL.default runs, settings in a file, and no Python byte
     * code: Python would write it into Alpine's /usr, which Alpine safe launch reports as a change.
     */
    private fun alpineAdjusted(env: LinkedHashMap<String, String>) {
        env["PATH"] = "/usr/bin:/bin:/usr/sbin:/sbin"
        env.remove("FLATPAK_ID")
        env["LIBGL_ALWAYS_SOFTWARE"] = "1"
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        // GLib's keyfile backend in XDG_CONFIG_HOME: there is no dconf service on the session bus.
        env["GSETTINGS_BACKEND"] = "keyfile"
    }

    /**
     * [value] (a colon-separated list of folders) with each `/usr/lib/<name>` the runtime lacks
     * replaced by `/usr/lib/<triplet>/<name>` where the runtime has that instead ([usrHas]): runtimes
     * keep their libraries' plugins in the multiarch folder, and an app naming the other finds none.
     */
    fun multiarchPaths(value: String, triplet: String, usrHas: (String) -> Boolean): String = value.split(':').joinToString(":") { entry ->
        val folder = entry.trimEnd('/')
        val name = folder.removePrefix("/usr/lib/")
        val multiarch = "/usr/lib/$triplet/$name"
        val candidate = name != folder && name.isNotEmpty() && !name.startsWith("$triplet/") && ".." !in name.split('/')
        if (candidate && !usrHas(folder) && usrHas(multiarch)) multiarch else entry
    }

    /** GStreamer's plugin folders, which [multiarchPaths] corrects. */
    private val PLUGIN_PATHS = setOf("GST_PLUGIN_SYSTEM_PATH", "GST_PLUGIN_SYSTEM_PATH_1_0", "GST_PLUGIN_PATH", "GST_PLUGIN_PATH_1_0")

    /** Variables only X11 sessions use, left out of Wayland ones. */
    private val X11_ONLY = listOf("QT_X11_NO_MITSHM", "QT_XCB_GL_INTEGRATION", "SDL_VIDEO_X11_FORCE_EGL")

    /** The locale apps get when the device's language isn't shared: no language or region. */
    const val GENERIC_LOCALE = "C.UTF-8"

    /**
     * The locale for the device's [languageTags] (BCP 47, most preferred first), among the glibc
     * locales the runtime has ([available], the names in its `/usr/lib/locale`, e.g. `de_DE.utf8`):
     * the first tag's language and region; else that language in its own country (`de_DE` for a
     * `de-AT` the runtime lacks) or in any; then the next tag. [GENERIC_LOCALE] when none fits.
     */
    fun locale(languageTags: List<String>, available: Collection<String>): String {
        val utf8 = available.filter { it.endsWith(".utf8") }.map { it.removeSuffix(".utf8") }.toSet()
        for (tag in languageTags) {
            val locale = java.util.Locale.forLanguageTag(tag)
            // Android still reports a few languages by their old codes; glibc uses the current ones.
            val language = LEGACY_LANGUAGES[locale.language] ?: locale.language
            if (!LANGUAGE.matches(language)) continue
            val country = locale.country.takeIf { COUNTRY.matches(it) }
            val candidates = listOfNotNull(country?.let { "${language}_$it" }, "${language}_${language.uppercase()}") +
                utf8.filter { it.startsWith("${language}_") }.sorted()
            candidates.firstOrNull { it in utf8 }?.let { return "$it.UTF-8" }
        }
        return GENERIC_LOCALE
    }

    private val LANGUAGE = Regex("[a-z]{2,3}")
    private val COUNTRY = Regex("[A-Z]{2}")
    private val LEGACY_LANGUAGES = mapOf("iw" to "he", "in" to "id", "ji" to "yi")

    /** A usable variable name; anything else could be read by `env` as an option or the command. */
    fun isName(key: String) = ENV_NAME.matches(key)

    /** GNU triplet of a Flatpak architecture, as runtimes name their library directories. */
    fun triplet(arch: String) = "$arch-linux-gnu"
}
