package com.garathon.flatdroid.launcher

import com.garathon.flatdroid.proot.GuestPaths

/**
 * The environment every Flatpak starts with. One set serves all apps: the toolkit variables are
 * harmless to apps that don't use them. Order: defaults, then the app's metadata `[Environment]`,
 * then LD_LIBRARY_PATH and the GL variables, then [overrides].
 */
object LaunchEnvironment {
    const val DISPLAY = ":1"

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
    ): LinkedHashMap<String, String> {
        val varApp = "${GuestPaths.HOME}/.var/app/$appId"
        val env = linkedMapOf(
            "PATH" to "/app/bin:/usr/bin",
            "HOME" to GuestPaths.HOME,
            "LANG" to "C.UTF-8",
            "XDG_RUNTIME_DIR" to "/tmp",
            "FLATPAK_ID" to appId,
            "XDG_CONFIG_HOME" to "$varApp/config",
            "XDG_DATA_HOME" to "$varApp/data",
            "XDG_CACHE_HOME" to "$varApp/cache",
            "XDG_STATE_HOME" to "$varApp/.local/state",
            // GTK4 apps abort without their GSettings schemas.
            "XDG_DATA_DIRS" to "/app/share:/usr/share:/usr/share/runtime/share",
            "DISPLAY" to DISPLAY,
            "PULSE_SERVER" to "unix:${GuestPaths.PULSE_DIR}/native",
            "GTK_USE_PORTAL" to "0",
            // GTK4 uses portals because of /.flatpak-info, and can't save without them otherwise.
            "GDK_DEBUG" to "no-portals",
            "GDK_BACKEND" to "x11",
            "GSK_RENDERER" to "cairo",
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
            "ALSOFT_DRIVERS" to "pulse",
        )
        for ((key, value) in metadataEnvironment) {
            if (key != "LD_LIBRARY_PATH" && isName(key)) env[key] = value
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
        for ((key, value) in overrides) if (isName(key)) env[key] = value
        return env
    }

    /** A usable variable name; anything else could be read by `env` as an option or the command. */
    fun isName(key: String) = Regex("[A-Za-z_][A-Za-z0-9_]*").matches(key)

    /** GNU triplet of a Flatpak architecture, as runtimes name their library directories. */
    fun triplet(arch: String) = "$arch-linux-gnu"
}
