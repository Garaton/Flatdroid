package com.garathon.flatdroid.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RootLayoutTest {
    @Test
    fun linksAsFlatpakLaysOutARoot() {
        val links = RootLayout.links("org.xfce.mousepad", usrLinks = listOf("bin", "lib"), wholeDev = false)
        assertEquals(
            linkedMapOf(
                "bin" to "usr/bin",
                "lib" to "usr/lib",
                "var/run" to "../run",
                "var/config" to "/home/user/.var/app/org.xfce.mousepad/config",
                "var/data" to "/home/user/.var/app/org.xfce.mousepad/data",
                "var/cache" to "/home/user/.var/app/org.xfce.mousepad/cache",
                "var/tmp" to "/home/user/.var/app/org.xfce.mousepad/cache/tmp",
                "dev/fd" to "/proc/self/fd",
                "dev/stdin" to "/proc/self/fd/0",
                "dev/stdout" to "/proc/self/fd/1",
                "dev/stderr" to "/proc/self/fd/2",
            ),
            links,
        )
    }

    @Test
    fun aWholeDevKeepsItsBinds() {
        val links = RootLayout.links("org.xfce.mousepad", usrLinks = emptyList(), wholeDev = true)
        assertFalse(links.keys.any { it.startsWith("dev/") })
    }
}
