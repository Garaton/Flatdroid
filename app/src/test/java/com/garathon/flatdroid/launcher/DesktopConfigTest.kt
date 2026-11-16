package com.garathon.flatdroid.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopConfigTest {
    @Test
    fun noMinimiseAndDarkModeForGtk() {
        val dark = DesktopConfig.gtkSettings(dark = true)
        assertTrue("gtk-decoration-layout=menu:maximize,close\n" in dark)
        assertTrue("gtk-application-prefer-dark-theme=1\n" in dark)
        assertTrue("gtk-application-prefer-dark-theme=0\n" in DesktopConfig.gtkSettings(dark = false))
    }

    @Test
    fun theCursorStopsBlinkingAfterThreeSeconds() {
        assertTrue("gtk-cursor-blink-timeout=3\n" in DesktopConfig.gtkSettings(dark = false))
    }

    @Test
    fun reducingMotionTurnsGtkAnimationsOff() {
        assertTrue("gtk-enable-animations" !in DesktopConfig.gtkSettings(dark = false))
        assertTrue("gtk-enable-animations=0\n" in DesktopConfig.gtkSettings(dark = false, reduceMotion = true))
    }

    @Test
    fun kdeglobalsOnlyInDarkModeWithTheRuntimesScheme() {
        assertEquals("[General]\nColorScheme=BreezeDark\n", DesktopConfig.kdeglobals(true, "[General]\nColorScheme=BreezeDark\n"))
        assertNull(DesktopConfig.kdeglobals(false, "[General]\n"))
        assertNull(DesktopConfig.kdeglobals(true, null))
    }
}
