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
        assertEquals(29, env.size)
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
}
