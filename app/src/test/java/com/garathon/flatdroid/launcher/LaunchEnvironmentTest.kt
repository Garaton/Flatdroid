package com.garathon.flatdroid.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LaunchEnvironmentTest {
    @Test
    fun defaultsMatchTheTestedSet() {
        val env = LaunchEnvironment.build("org.xfce.mousepad", "aarch64-linux-gnu", emptyMap(), glDefault = null)
        assertEquals("/app/bin:/usr/bin", env["PATH"])
        assertEquals("/bin/sh", env["SHELL"])
        assertEquals("/home/user/.var/app/org.xfce.mousepad/config", env["XDG_CONFIG_HOME"])
        assertEquals("/home/user/.var/app/org.xfce.mousepad/.local/state", env["XDG_STATE_HOME"])
        assertEquals("/app/share:/usr/share:/usr/share/runtime/share", env["XDG_DATA_DIRS"])
        assertEquals("/app/etc/xdg:/etc/xdg:/run/flatdroid/xdg", env["XDG_CONFIG_DIRS"])
        assertFalse("ADW_DEBUG_COLOR_SCHEME" in env)
        assertFalse("QT_QPA_PLATFORMTHEME" in env)
        assertEquals("unix:/run/pulse/native", env["PULSE_SERVER"])
        assertEquals("/app/lib:/usr/lib/aarch64-linux-gnu", env["LD_LIBRARY_PATH"])
        assertFalse("LIBGL_ALWAYS_SOFTWARE" in env)
        // Flutter apps draw in software: GTK3 gets no GL context from Xvnc.
        assertEquals("software", env["FLUTTER_LINUX_RENDERER"])
        assertEquals(30, env.size)
    }

    @Test
    fun metadataLdPathComesFirstAndGlAddsItsVariables() {
        val env = LaunchEnvironment.build(
            "org.supertuxproject.SuperTux", "aarch64-linux-gnu",
            mapOf("LD_LIBRARY_PATH" to "/app/lib/audacity", "FOO" to "bar", "bad key" to "x", "-u" to "PATH"),
            glDefault = "lib/aarch64-linux-gnu/GL/default",
            extensionLdPaths = listOf("/usr/lib/aarch64-linux-gnu/GL/default/lib"),
            overrides = mapOf("GSK_RENDERER" to "ngl"),
        )
        assertEquals(
            "/app/lib/audacity:/app/lib:/usr/lib/aarch64-linux-gnu:/usr/lib/aarch64-linux-gnu/GL/default/lib",
            env["LD_LIBRARY_PATH"],
        )
        assertEquals("bar", env["FOO"])
        assertFalse("bad key" in env || "-u" in env)
        assertEquals("/usr/lib/aarch64-linux-gnu/GL/default/lib/dri", env["LIBGL_DRIVERS_PATH"])
        assertEquals("/usr/lib/aarch64-linux-gnu/GL/default/share/glvnd/egl_vendor.d", env["__EGL_VENDOR_LIBRARY_DIRS"])
        assertEquals("ngl", env["GSK_RENDERER"])
    }

    @Test
    fun tripletFollowsTheArchitecture() {
        assertEquals("x86_64-linux-gnu", LaunchEnvironment.triplet("x86_64"))
    }

    @Test
    fun theLocaleFollowsTheDevicesLanguagesAmongTheRuntimes() {
        val available = listOf("C.utf8", "de_DE", "de_DE.utf8", "de_AT.utf8", "en_US.utf8", "es_ES.utf8", "es_MX.utf8", "he_IL.utf8")
        assertEquals("de_AT.UTF-8", LaunchEnvironment.locale(listOf("de-AT", "en-US"), available))
        // A region the runtime lacks: the language's own country, else any of its locales.
        assertEquals("de_DE.UTF-8", LaunchEnvironment.locale(listOf("de-CH"), available))
        assertEquals("es_ES.UTF-8", LaunchEnvironment.locale(listOf("es-419"), available))
        // Android's old code for Hebrew.
        assertEquals("he_IL.UTF-8", LaunchEnvironment.locale(listOf("iw-IL"), available))
        // Nothing fits: the next language, then the generic locale.
        assertEquals("en_US.UTF-8", LaunchEnvironment.locale(listOf("ja-JP", "en-GB"), available))
        assertEquals(LaunchEnvironment.GENERIC_LOCALE, LaunchEnvironment.locale(listOf("ja-JP"), available))
        assertEquals(LaunchEnvironment.GENERIC_LOCALE, LaunchEnvironment.locale(emptyList(), available))
    }
}
